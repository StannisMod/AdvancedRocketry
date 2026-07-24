package com.github.stannismod.affs.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.util.EnumHand;

public class ContainerCodeDevice extends Container {

    private final EnumHand hand;
    private final String initialCode;

    public ContainerCodeDevice(EnumHand hand, String initialCode) {
        this.hand = hand;
        this.initialCode = initialCode;
    }

    public EnumHand getHand() {
        return hand;
    }

    public String getInitialCode() {
        return initialCode;
    }

    @Override
    public boolean canInteractWith(EntityPlayer playerIn) {
        return true;
    }
}
