package zmaster587.advancedRocketry.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.settings.IKeyConflictContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.api.EntityRocketBase;
import zmaster587.advancedRocketry.entity.EntityHoverCraft;
import zmaster587.advancedRocketry.tile.TilePilotSeat;

/**
 * Key-conflict contexts that scope Advanced Rocketry's craft-steering keys to
 * exactly the moment the local player is piloting an AR vehicle.
 *
 * <p>Several steering keys deliberately share their default key with a vanilla
 * binding (A/D strafe, Q drop, E inventory) and one with another AR binding
 * (X = jetpack toggle vs. vertical-down thrust). Forge resolves a key press to
 * a single binding via {@code KeyBindingMap.lookupActive}, which returns the
 * first binding whose {@link #isActive()} is true; the same context also gates
 * {@code KeyBinding.isKeyDown()} / {@code isPressed()}. Because vanilla bindings
 * register before mod bindings, an AR binding can only win the lookup while the
 * vanilla binding it shares a key with is simultaneously inactive.
 *
 * <p>We therefore pair every overridden key with mutually-exclusive contexts:
 * the AR steering binding gets {@link #PILOTING}, the binding it overrides gets
 * {@link #NOT_PILOTING}. Exactly one is active at any time, so:
 * <ul>
 *   <li>on foot, the keys behave 100% vanilla (inventory/drop/strafe, jetpack);</li>
 *   <li>while piloting, the same keys steer the craft and the vanilla actions
 *       are suppressed (no inventory popping open mid-flight);</li>
 *   <li>the Controls screen reports no conflict, since neither context
 *       {@link #conflicts(IKeyConflictContext)} with the other.</li>
 * </ul>
 *
 * <p>"Piloting" spans both classic-launch and Free Flight, and both rockets and
 * hovercraft, because the shared steering bindings (turn/up/down) serve all of
 * those modes — scoping them to Free Flight alone would break classic control.
 */
@SideOnly(Side.CLIENT)
public enum ARKeyConflictContext implements IKeyConflictContext {

    /** Active while the local player rides a steerable AR craft, in any flight phase. */
    PILOTING {
        @Override
        public boolean isActive() {
            return isPilotingARCraft();
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return this == other;
        }
    },

    /**
     * Complement of {@link #PILOTING} — active whenever the player is NOT
     * piloting an AR craft. Worn by the vanilla (and jetpack) bindings the
     * steering keys override, so they keep their normal behaviour everywhere
     * except the cockpit.
     */
    NOT_PILOTING {
        @Override
        public boolean isActive() {
            return !isPilotingARCraft();
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return this == other;
        }
    };

    private static boolean isPilotingARCraft() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null) {
            return false;
        }
        Entity riding = mc.player.getRidingEntity();
        if (riding instanceof EntityRocketBase || riding instanceof EntityHoverCraft) {
            return true;
        }
        // Tier-2 ship: the pilot sits on a seat (a dummy mount), so recognise a linked pilot
        // seat under the mount as "piloting" too — otherwise the shared steering keys stay
        // scoped OFF and fall through to their vanilla actions while flying the ship.
        TilePilotSeat seat = TilePilotSeat.forRider(riding, mc.world);
        return seat != null && seat.isLinked();
    }
}
