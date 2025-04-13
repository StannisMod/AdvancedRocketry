package zmaster587.advancedRocketry.integration;

import matteroverdrive.entity.*;
import matteroverdrive.entity.monster.EntityMeleeRougeAndroidMob;
import matteroverdrive.entity.monster.EntityRangedRogueAndroidMob;
import matteroverdrive.entity.player.MOPlayerCapabilityProvider;
import matteroverdrive.init.OverdriveBioticStats;
import net.minecraft.entity.EntityLivingBase;
import zmaster587.advancedRocketry.api.ARConfiguration;

public class MatterOvedriveIntegration {
    public static boolean isAndroidNeedNoOxygen(EntityLivingBase player) {
        return (MOPlayerCapabilityProvider.GetAndroidCapability(player) != null
                && MOPlayerCapabilityProvider.GetAndroidCapability(player).isAndroid()
                && MOPlayerCapabilityProvider.GetAndroidCapability(player).isUnlocked(OverdriveBioticStats.oxygen, 1));
    }

    public static void addAndroidsToBypassList(ARConfiguration arConfig) {
        // for some reason AR cant register MO entities to bypass, so we just make this a dummy method

        arConfig.bypassEntity.add(EntityDrone.class);
        arConfig.bypassEntity.add(EntityRangedRogueAndroidMob.class);
        arConfig.bypassEntity.add(EntityMeleeRougeAndroidMob.class);
    }
}
