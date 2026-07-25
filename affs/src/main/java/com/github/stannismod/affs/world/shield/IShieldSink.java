package com.github.stannismod.affs.world.shield;

public interface IShieldSink extends IShieldNetworkNode {

    int getRequestedShieldEnergy();

    int getFreeShieldCapacity();

    int receiveShieldEnergy(int amount);

    /**
     * Redistribution priority (D134-5): under an energy deficit the network satisfies higher-priority
     * sinks first, so a player can pour a starved supply into the emitters that matter ("all power to
     * the rear shields"). Equal priority shares what is left. Default 0 = normal; a bulk store keeps the
     * default so real emitters, when raised, out-rank it.
     */
    default int getShieldPriority() {
        return 0;
    }
}
