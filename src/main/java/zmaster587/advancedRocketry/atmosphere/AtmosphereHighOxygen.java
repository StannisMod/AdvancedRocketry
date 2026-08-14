package zmaster587.advancedRocketry.atmosphere;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import zmaster587.advancedRocketry.network.PacketOxygenState;
import zmaster587.libVulpes.LibVulpes;

/**
 * Too much oxygen rather than too little: the other end of the safe band a life-support governor
 * has to hold. Breathing it is not immediately fatal the way vacuum is, so this is a slow toxicity
 * damage rather than suffocation, and it deliberately still allows combustion — an oxygen-rich
 * room being more flammable is the point of the hazard, not a side effect.
 */
public class AtmosphereHighOxygen extends AtmosphereNeedsSuit {

    public AtmosphereHighOxygen(boolean canTick, boolean isBreathable, boolean allowsCombustion,
                                String name) {
        super(canTick, isBreathable, allowsCombustion, name);
    }

    @Override
    public String getDisplayMessage() {
        return LibVulpes.proxy.getLocalizedString("msg.highOxygen");
    }

    @Override
    public void onTick(EntityLivingBase player) {
        if (player.world.getTotalWorldTime() % 40 == 0 && !isImmune(player)) {
            player.attackEntityFrom(AtmosphereHandler.oxygenToxicityDamage, 1);
            if (player instanceof EntityPlayer)
                AtmosphereType.sendToRealPlayer(new PacketOxygenState(), (EntityPlayer) player);
        }
    }

    // A sealed helmet is enough: the hazard is what you breathe, not pressure or heat.
    @Override
    protected boolean onlyNeedsMask() {
        return true;
    }
}
