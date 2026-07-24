package com.github.stannismod.affs.world.shield;

public interface IShieldNetworkController extends IShieldNetworkNode {

    String getNetworkCode();

    double getShieldEnergyResistanceBias();

    void applyNetworkState(ShieldNetworkState state);
}
