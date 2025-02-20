package zmaster587.advancedRocketry.integration;

import matteroverdrive.entity.player.MOPlayerCapabilityProvider;
import matteroverdrive.init.OverdriveBioticStats;
import net.minecraft.entity.EntityLivingBase;

public class MatterOvedriveIntegration {
    public static boolean isAndroidNeedNoOxygen(EntityLivingBase player) {
        return (MOPlayerCapabilityProvider.GetAndroidCapability(player) != null
                && MOPlayerCapabilityProvider.GetAndroidCapability(player).isAndroid()
                && MOPlayerCapabilityProvider.GetAndroidCapability(player).isUnlocked(OverdriveBioticStats.oxygen, 1));
    }
}
