package zmaster587.advancedRocketry.entity;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.api.AdvancedRocketryItems;
import zmaster587.advancedRocketry.entity.EntityRocket.PacketType;
import zmaster587.libVulpes.interfaces.INetworkEntity;
import zmaster587.libVulpes.network.PacketEntity;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.util.EmbeddedInventory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EntityHoverCraft extends Entity implements IInventory, INetworkEntity {

    private static final int MAX_HEIGHT = 250;
    private static final double HORIZONTAL_VMAX = 0.75;
    private static final double VERTICAL_VMAX = 0.1;
    private static final double MAX_ACCELERATION = 0.05;
    public boolean cruiseControl;
    protected double speedMultiplier;
    protected float fanLoc;
    protected int freshBurnTime;
    protected int currentBurnTime;
    //Used to calculate rendering stuffs
    protected EmbeddedInventory inv;
    private boolean turningUp, turningDownforWhat;

    // Client only, used for interpolation
    @SideOnly(Side.CLIENT)
    private int lerpSteps;
    @SideOnly(Side.CLIENT)
    private double lerpX, lerpY, lerpZ;
    @SideOnly(Side.CLIENT)
    private float lerpYaw, lerpPitch;

    public EntityHoverCraft(World par1World) {
        super(par1World);
        inv = new EmbeddedInventory(1);
        setSize(2.5f, 1f);
        this.stepHeight = 1.0f;
    }

    public EntityHoverCraft(World par1World, double par2, double par4, double par6) {
        this(par1World);

        //System.out.println(localBoundingBox);

        this.setPosition(par2, par4 + this.getYOffset(), par6);
        this.motionX = 0.0D;
        this.motionY = 0.0D;
        this.motionZ = 0.0D;
        this.prevPosX = par2;
        this.prevPosY = par4;
        this.prevPosZ = par6;
    }
    @Override
    @SideOnly(Side.CLIENT)
    public void setPositionAndRotationDirect(double x, double y, double z, float yaw, float pitch,
                                            int posRotationIncrements, boolean teleport) {

        double dx = x - this.posX;
        double dy = y - this.posY;
        double dz = z - this.posZ;

        // Snap only for large real teleports
        if (teleport && (dx*dx + dy*dy + dz*dz) > (16.0 * 16.0)) {
            this.setPosition(x, y, z);
            this.rotationYaw = this.prevRotationYaw = yaw;
            this.rotationPitch = this.prevRotationPitch = pitch;
            this.lerpSteps = 0;
            return;
        }

        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYaw = yaw;
        this.lerpPitch = pitch;
        this.lerpSteps = Math.max(posRotationIncrements, 3);
    }



    @Override
    public double getYOffset() {
        return 0;
    }

    /**
     * returns if this entity triggers Block.onEntityWalking on the blocks they walk on. used for spiders and wolves to
     * prevent them from trampling crops
     */
    public boolean canTriggerWalking() {
        return false;
    }

    @Override
    public void markDirty() {

    }

    /**
     * Returns the Y offset from the entity's position for any entity riding this one.
     */
    public double getMountedYOffset() {
        return (double) this.height * 0.0D + 0.5D;
    }

    /**
     * Returns true if this entity should push and be pushed by other entities when colliding.
     */
    public boolean canBePushed() {
        return true;
    }

    /**
     * Returns true if other Entities should be prevented from moving through this Entity.
     */
    public boolean canBeCollidedWith() {
        return !this.isDead;
    }

    /**
     * First layer of player interaction
     */
    @Override
    public boolean processInitialInteract(EntityPlayer player, EnumHand hand) {
        if (this.getPassengers().isEmpty()/* || (this.riddenByEntity != null && this.riddenByEntity instanceof EntityPlayer && this.riddenByEntity != player)*/) {
            if (!this.world.isRemote)
                player.startRiding(this);
        }
        return true;
    }

    @Override
    public boolean attackEntityFrom(@Nonnull DamageSource par1DamageSource, float par2) {
        if (!this.world.isRemote && !this.isDead && par1DamageSource.getImmediateSource() instanceof EntityPlayer && !this.getPassengers().contains(par1DamageSource.getImmediateSource())) {
            for (ItemStack stack : getItemsDropOnDeath()) {
                if (!stack.isEmpty())
                    this.entityDropItem(stack, 0.0F);
            }

            this.setDead();
            return true;
        }
        return false;
    }

    public ItemStack[] getItemsDropOnDeath() {
        return new ItemStack[]{inv.getStackInSlot(0), new ItemStack(AdvancedRocketryItems.itemHovercraft)};
    }

    @Override
    public int getSizeInventory() {
        return inv.getSizeInventory();
    }

    @Override
    @Nonnull
    public ItemStack decrStackSize(int slot, int amt) {
        return inv.decrStackSize(slot, amt);
    }

    @Override
    @Nonnull
    public ItemStack getStackInSlot(int i) {
        return inv.getStackInSlot(i);
    }

    @Override
    public void setInventorySlotContents(int slot, @Nonnull ItemStack itemstack) {
        inv.setInventorySlotContents(slot, itemstack);
    }
    @Override
    public Entity getControllingPassenger() {
        return this.getPassengers().isEmpty() ? null : this.getPassengers().get(0);
    }

    @Override
    public boolean canPassengerSteer() {
        return getControllingPassenger() instanceof EntityPlayer;
    }

    public void onUp(boolean state) {
        if (turningUp == state) return;
        turningUp = state;
        PacketHandler.sendToServer(new PacketEntity(this, (byte) PacketType.TURNUPDATE.ordinal()));
    }

    public void onDown(boolean state) {
        if (turningDownforWhat == state) return;
        turningDownforWhat = state;
        PacketHandler.sendToServer(new PacketEntity(this, (byte) PacketType.TURNUPDATE.ordinal()));
    }


    @Override
    public void onUpdate() {
        super.onUpdate();

        if (world.isRemote) {
            if (isLocallyControlled()) {
                // Apply small server correction first (prevents drift/jitter)
                clientLerpDriverCorrection();
                Entity ctrl = getControllingPassenger();
                if (ctrl instanceof EntityPlayer) {
                    tickPhysics((EntityPlayer) ctrl);
                }
            } else {
                clientLerp();
            }
            return;
        }

        // server authoritative
        Entity ctrl = getControllingPassenger();
        if (ctrl instanceof EntityPlayer) {
            tickPhysics((EntityPlayer) ctrl);
            if (Math.abs(motionX) + Math.abs(motionY) + Math.abs(motionZ) > 1e-5) {
                this.velocityChanged = true;
            }
        } else {
            // bleed off motion & still move so state is consistent
            this.motionX *= 0.8;
            this.motionY *= 0.8;
            this.motionZ *= 0.8;
            this.move(MoverType.SELF, this.motionX, this.motionY, this.motionZ);
            if (Math.abs(motionX) + Math.abs(motionY) + Math.abs(motionZ) > 1e-5) {
                this.velocityChanged = true;
            }
        }
    }

    @SideOnly(Side.CLIENT)
    private void clientLerpDriverCorrection() {
        if (lerpSteps > 0) {
            double dx = lerpX - posX;
            double dy = lerpY - posY;
            double dz = lerpZ - posZ;

            if (dx*dx + dy*dy + dz*dz < 1e-4) {
                lerpSteps = 0;
                return;
            }

            this.setPosition(posX + dx * 0.2, posY + dy * 0.2, posZ + dz * 0.2);

            float dyaw = MathHelper.wrapDegrees(lerpYaw - rotationYaw);
            this.rotationYaw += dyaw * 0.2f;
            this.rotationYaw = MathHelper.wrapDegrees(this.rotationYaw);

            this.rotationPitch += (lerpPitch - rotationPitch) * 0.2f;
        }
    }

    @SideOnly(Side.CLIENT)
    private boolean isLocallyControlled() {
        Entity ctrl = getControllingPassenger();
        if (!(ctrl instanceof EntityPlayer)) return false;
        return net.minecraft.client.Minecraft.getMinecraft().player == ctrl;
    }

    @SideOnly(Side.CLIENT)
    private void clientLerp() {
        if (lerpSteps > 0) {
            double nx = posX + (lerpX - posX) / lerpSteps;
            double ny = posY + (lerpY - posY) / lerpSteps;
            double nz = posZ + (lerpZ - posZ) / lerpSteps;

            float dyaw = MathHelper.wrapDegrees(lerpYaw - rotationYaw);
            rotationYaw += dyaw / lerpSteps;
            rotationYaw = MathHelper.wrapDegrees(rotationYaw);

            rotationPitch += (lerpPitch - rotationPitch) / lerpSteps;

            lerpSteps--;
            setPosition(nx, ny, nz);
        }
    }


    private void tickPhysics(EntityPlayer rider) {
        // Boat-like turning + throttle
        float forward = MathHelper.clamp(rider.moveForward, -1f, 1f);    // W/S
        float strafe  = MathHelper.clamp(rider.moveStrafing, -1f, 1f);   // A/D

        this.rotationYaw -= strafe * 4.0f; // turn rate tweak
        this.rotationYaw = MathHelper.wrapDegrees(this.rotationYaw); // keep in -180..180 range

        double acc = forward * MAX_ACCELERATION;
        float yawRad = (float) Math.toRadians(this.rotationYaw);

        this.motionX += (-acc) * MathHelper.sin(yawRad);
        this.motionZ += ( acc) * MathHelper.cos(yawRad);

        // vertical: keep your packet booleans if you want
        this.motionY += (turningUp ? MAX_ACCELERATION : 0) - (turningDownforWhat ? MAX_ACCELERATION : 0);

        // drag
        this.motionX *= 0.9;
        this.motionY *= 0.9;
        this.motionZ *= 0.9;

        // clamps
        double h = Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);
        if (h > HORIZONTAL_VMAX) {
            double s = HORIZONTAL_VMAX / h;
            this.motionX *= s;
            this.motionZ *= s;
        }
        this.motionY = MathHelper.clamp(this.motionY, -VERTICAL_VMAX, VERTICAL_VMAX);

        // height cap
        if (this.posY > MAX_HEIGHT * 1.1) this.motionY = 0;
        else if (this.posY > MAX_HEIGHT) this.motionY *= 0.1;

        this.move(MoverType.SELF, this.motionX, this.motionY, this.motionZ);
    }


    public float getPassengerMovingForward() {

        for (Entity entity : this.getPassengers()) {
            if (entity instanceof EntityPlayer) {
                return ((EntityPlayer) entity).moveForward;
            }
        }
        return 0f;
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) {
        if (packetId == PacketType.TURNUPDATE.ordinal()) {
            nbt.setBoolean("up", in.readBoolean());
            nbt.setBoolean("down", in.readBoolean());
        }
    }


    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == PacketType.TURNUPDATE.ordinal()) {
            out.writeBoolean(turningUp);
            out.writeBoolean(turningDownforWhat);
        }
    }


    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        if (id == PacketType.TURNUPDATE.ordinal()) {
            this.turningUp = nbt.getBoolean("up");
            this.turningDownforWhat = nbt.getBoolean("down");
        }
    }


    @Override
    public boolean isEmpty() {
        return inv.isEmpty();
    }

    @Override
    @Nonnull
    public ItemStack removeStackFromSlot(int index) {
        return inv.removeStackFromSlot(index);
    }

    @Override
    public int getInventoryStackLimit() {
        return inv.getInventoryStackLimit();
    }

    @Override
    public boolean isUsableByPlayer(@Nullable EntityPlayer player) {
        return false;
    }

    @Override
    public void openInventory(EntityPlayer player) {
        inv.openInventory(player);
    }

    @Override
    public void closeInventory(EntityPlayer player) {
        inv.closeInventory(player);
    }

    @Override
    public boolean isItemValidForSlot(int index, @Nonnull ItemStack stack) {
        return inv.isItemValidForSlot(index, stack);
    }

    @Override
    public int getField(int id) {
        return inv.getField(id);
    }

    @Override
    public void setField(int id, int value) {
        inv.setField(id, value);

    }

    @Override
    public int getFieldCount() {
        return inv.getFieldCount();
    }

    @Override
    public void clear() {
        inv.clear();
    }

    @Override
    protected void entityInit() {
    }

    @Override
    protected void readEntityFromNBT(@Nonnull NBTTagCompound compound) {
        inv.readFromNBT(compound);
    }

    @Override
    protected void writeEntityToNBT(@Nonnull NBTTagCompound compound) {
        inv.writeToNBT(compound);
    }

    public enum VehicleType {
        submarine,
        blimp
    }
}
