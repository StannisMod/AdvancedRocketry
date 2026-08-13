package zmaster587.advancedRocketry.space;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;

/**
 * Leaving your ship in hyperspace kills you.
 *
 * <p>Hyperspace is not a place with a floor. It is an all-air void world whose only content is the
 * ships parked in it, 2048 blocks apart, so nothing a crew member could step onto is ever within
 * reach — stepping off the hull means falling through nothing for as long as the flight lasts. The
 * ship is the only place to be, and this is what makes that true rather than merely likely.</p>
 *
 * <p><b>Nothing prevents him from trying.</b> There is no wall and no refusal at the deck edge: the
 * danger is the mechanic. A crew member is free to walk, to look over the side, and to fall off it.</p>
 *
 * <h3>Why this is a countdown and not an instant</h3>
 *
 * "Aboard" is answered by the deck resolver, and that answer is legitimately absent for a while
 * around a crossing: the ship is re-assembled asynchronously at the far end, the departure boarding
 * retries per tick until a seat resolves, and a player carried between worlds spends a moment in a
 * world whose ship has not caught up with him. Killing on the first tick that reads "not aboard"
 * would kill for the instrument's silence rather than for the player's position — the exact shape
 * this codebase has been bitten by before.
 *
 * <p>So the check is a consecutive-tick counter, and its budget is not a fresh magic number: it is
 * the SAME budget the boarding and re-seat retries already spend before they give up
 * ({@code MAX_ARRIVAL_ATTEMPTS} / {@code MAX_SEAT_ATTEMPTS} = 200 ticks). A player who is not aboard
 * anything for longer than the machinery is still willing to try to put him aboard is not waiting on
 * a crossing; he is in the void. Any shorter budget would race a slow arrival, and a slow arrival is
 * exactly the case a rare hang would produce.</p>
 *
 * <h3>What counts as aboard</h3>
 *
 * Either shape of membership: riding a seat's dummy, or resolved on a deck by
 * {@link ShipFrameTravel#aboardShipId}. Those are the two postures the whole subsystem recognises,
 * and this class deliberately asks the same question the aboard record asks rather than inventing a
 * second geometry that could disagree with it.
 *
 * <h3>The suit does not save him</h3>
 *
 * The damage bypasses armour, so a space suit is no protection. A suit answers vacuum, and every
 * player who reaches hyperspace is wearing one — exempting them would leave this rule with no
 * population to apply to. Hyperspace is not thin air; there is nothing out there to be equipped for.
 *
 * <p>Server main thread only.</p>
 */
public final class HyperspaceVoid {

    private static final Logger LOGGER = LogManager.getLogger(HyperspaceVoid.class);

    /**
     * How many CONSECUTIVE ticks a player may be in hyperspace and aboard nothing before the void
     * takes him. Deliberately equal to the crossing machinery's own give-up budget (see the class
     * doc): while anything is still trying to put him aboard, he is not adrift.
     */
    public static final int GRACE_TICKS = 200;

    /**
     * Bypasses armour and magic protection on purpose — see the class doc. Named so the death
     * message is about the void rather than about generic damage.
     */
    public static final DamageSource VOID_OF_HYPERSPACE =
            new DamageSource("arHyperspaceVoid").setDamageBypassesArmor().setDamageIsAbsolute();

    /** Consecutive ticks adrift, per player. An entry exists only while its player is adrift. */
    private final Map<UUID, Integer> adriftTicks = new HashMap<>();

    /** How many players the void has taken, cumulatively. Read by the probe; never reset. */
    public static volatile int killed = 0;

    /** The longest run of adrift ticks seen so far, so a test can tell "nobody was ever adrift"
     *  from "somebody was adrift and the budget did not expire". A count alone cannot. */
    public static volatile int longestAdriftRun = 0;

    /** Owned by {@link SpaceDiagnostics#reset()} — see there for why a diagnostic needs an owner. */
    static void resetDiagnostics() {
        killed = 0;
        longestAdriftRun = 0;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        int hyperDim = HyperspaceWorld.dimId();
        if (hyperDim == Integer.MIN_VALUE) {
            adriftTicks.clear(); // no hyperspace this boot: nobody can be adrift in it
            return;
        }
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            if (player == null || player.world == null
                    || player.world.provider.getDimension() != hyperDim) {
                adriftTicks.remove(player == null ? null : player.getUniqueID());
                continue;
            }
            if (player.isDead || player.isSpectator() || player.isCreative()) {
                // Creative and spectator are the two states in which flying through the void is the
                // point rather than a mistake. Death is already handled.
                adriftTicks.remove(player.getUniqueID());
                continue;
            }
            if (aboardSomething(player)) {
                adriftTicks.remove(player.getUniqueID());
                continue;
            }
            Integer prior = adriftTicks.get(player.getUniqueID());
            int run = (prior == null ? 0 : prior) + 1;
            if (run > longestAdriftRun) {
                longestAdriftRun = run;
            }
            if (run < GRACE_TICKS) {
                adriftTicks.put(player.getUniqueID(), run);
                continue;
            }
            adriftTicks.remove(player.getUniqueID());
            killed++;
            LOGGER.info("[SPACE] the void of hyperspace took {} - adrift for {} ticks at ({}, {}, {})",
                    player.getName(), run, (int) player.posX, (int) player.posY, (int) player.posZ);
            player.attackEntityFrom(VOID_OF_HYPERSPACE, Float.MAX_VALUE);
        }
        pruneDeparted(server);
    }

    /** Either posture of membership, asked exactly as the aboard record asks it. */
    private static boolean aboardSomething(EntityPlayerMP player) {
        Entity riding = player.getRidingEntity();
        if (riding instanceof EntityDummy && ((EntityDummy) riding).getSeatPos() != null) {
            return true;
        }
        return ShipFrameTravel.aboardShipId(player) != null;
    }

    /**
     * Drop counters for players who are no longer online. Without this a disconnect mid-fall leaves
     * an entry that resumes his countdown where it stopped if he ever returns — and a player who
     * logs back in is placed by the login restore, which is a fresh judgement, not a continuation.
     */
    private void pruneDeparted(MinecraftServer server) {
        if (adriftTicks.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Integer>> it = adriftTicks.entrySet().iterator();
        while (it.hasNext()) {
            if (server.getPlayerList().getPlayerByUUID(it.next().getKey()) == null) {
                it.remove();
            }
        }
    }
}
