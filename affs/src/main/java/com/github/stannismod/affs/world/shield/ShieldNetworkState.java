package com.github.stannismod.affs.world.shield;

import com.github.stannismod.affs.config.ModConfig;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkState;

/**
 * The shared network state plus the one thing that is shield-specific: how much of an impact's
 * energy the network answers with, which a console edits and the whole network then obeys.
 */
public final class ShieldNetworkState extends SubsystemNetworkState {

    private double shieldEnergyResistanceBias = ModConfig.shieldEnergyResistanceBias;

    @Override
    public ShieldNetworkState copy() {
        ShieldNetworkState copy = new ShieldNetworkState();
        copyInto(copy);
        copy.shieldEnergyResistanceBias = shieldEnergyResistanceBias;
        return copy;
    }

    public double getShieldEnergyResistanceBias() {
        return shieldEnergyResistanceBias;
    }

    public void setShieldEnergyResistanceBias(double shieldEnergyResistanceBias) {
        this.shieldEnergyResistanceBias = clamp01(shieldEnergyResistanceBias);
    }

    private static double clamp01(double value) {
        if (value < 0.0D) {
            return 0.0D;
        }
        if (value > 1.0D) {
            return 1.0D;
        }
        return value;
    }
}
