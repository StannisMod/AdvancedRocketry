package zmaster587.advancedRocketry.satellite;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import zmaster587.advancedRocketry.api.AdvancedRocketryBiomes;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.api.satellite.SatelliteProperties;
import zmaster587.advancedRocketry.item.ItemBiomeChanger;
import zmaster587.advancedRocketry.util.BiomeHandler;
import zmaster587.libVulpes.api.IUniversalEnergy;
import zmaster587.libVulpes.util.HashedBlockPosition;

import javax.annotation.Nonnull;
import java.util.*;

public class SatelliteBiomeChanger extends SatelliteBase {

    private static int MAX_SIZE = 1024;
    private Biome biomeId;
    private int radius;
    //Stores blocks to be updated
    //Note: we really don't care about order, in fact, lack of order is better
    private List<HashedBlockPosition> toChangeList;
    private Set<Byte> discoveredBiomes;
private int noise_val;
    public SatelliteBiomeChanger() {
        super();
        radius = 4;
        toChangeList = new LinkedList<>();
        discoveredBiomes = new HashSet<>();
        biomeId =  Biome.getBiome(0);
        noise_val = 6;
    }

    public Biome getBiome() {
        return biomeId;
    }

    public void setBiome(Biome biomeId) {
        this.biomeId = biomeId;
    }

    public Set<Byte> discoveredBiomes() {
        return discoveredBiomes;
    }

    public void addBiome(Biome biome) {
        byte byteBiome = (byte) Biome.getIdForBiome(biome);

        if (!AdvancedRocketryBiomes.instance.getBlackListedBiomes().contains(byteBiome))
            discoveredBiomes.add(byteBiome);
    }

    @Override
    public String getInfo(World world) {
        return "Ready";
    }

    @Override
    public String getName() {
        return "Biome Changer";
    }

    @Override
    @Nonnull
    public ItemStack getControllerItemStack(@Nonnull ItemStack satIdChip,
                                            SatelliteProperties properties) {

        ItemBiomeChanger idChipItem = (ItemBiomeChanger) satIdChip.getItem();
        idChipItem.setSatellite(satIdChip, properties);
        return satIdChip;
    }

    @Override
    public boolean isAcceptableControllerItemStack(@Nonnull ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ItemBiomeChanger;
    }


    @Override
    public void tickEntity() {
        //This is hacky..
        World world = net.minecraftforge.common.DimensionManager.getWorld(getDimensionId());
        int powerrequired = 120;
        if (world != null) {

            for (int i = 0; i < 10; i++) {
                //TODO: Better imp

                if (world.getTotalWorldTime() % 1 == 0 && !toChangeList.isEmpty()) {
                    if (battery.getUniversalEnergyStored() > powerrequired) {
                        if (battery.extractEnergy(powerrequired, false) == powerrequired) {
                            HashedBlockPosition pos = toChangeList.remove(world.rand.nextInt(toChangeList.size()));
                            //HashedBlockPosition pos = toChangeList.remove(toChangeList.size()-1);

                            BiomeHandler.terraform(world, biomeId, pos.getBlockPos(), true, world.provider.getDimension());
                        }
                    } else
                        break;
                } else
                    break;
            }
        }
        super.tickEntity();
    }

    public void addBlockToList(HashedBlockPosition pos) {
        if (toChangeList.size() < MAX_SIZE)
            toChangeList.add(pos);
    }

    @Override
    public boolean performAction(EntityPlayer player, World world, BlockPos pos) {
        if (world.isRemote)
            return false;

        radius = 12;
        noise_val = 12;
        MAX_SIZE = 8000;

        // make it less square by adding noise to the edges

        for (int xx = -radius-noise_val; xx < radius+noise_val; xx++) {
            for (int zz = -radius-noise_val; zz < radius+noise_val; zz++) {

                int nx = xx + pos.getX();
                int nz = zz + pos.getZ();

                if (isAdd(xx, zz)) {
                    addBlockToList(new HashedBlockPosition(nx, 0, nz));
                }
            }
        }

        return false;
    }

    private boolean isAdd(int xx, int zz) {

        if (xx > radius || xx < -radius || zz > radius || zz < -radius) {
            Random r = new Random();
            // less probability if it gets further away from max radius
            int dx = Math.abs(xx) - radius;
            int dz = Math.abs(zz) - radius;
            int d = Math.max(dx, dz);
            d = Math.max((d + 1)/2, 1);
            if (r.nextInt(d) == 0)
                return true;
            return false;

        } else return true;
    }

    @Override
    public double failureChance() {
        return 0;
    }

    public IUniversalEnergy getBattery() {
        return battery;
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setInteger("biomeId", Biome.getIdForBiome(biomeId));

        int[] array = new int[toChangeList.size() * 3];
        Iterator<HashedBlockPosition> itr = toChangeList.iterator();
        for (int i = 0; i < toChangeList.size()*3; i += 3) {
            HashedBlockPosition pos = itr.next();
            array[i] = pos.x;
            array[i + 1] = pos.y;
            array[i + 2] = pos.z;
        }
        nbt.setTag("posList", new NBTTagIntArray(array));

        array = new int[discoveredBiomes.size()];

        int i = 0;
        for (byte biome : discoveredBiomes) {
            array[i] = biome;
            i++;
        }

        nbt.setTag("biomeList", new NBTTagIntArray(array));
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        biomeId = Biome.getBiome(nbt.getInteger("biomeId"));

        int[] array = nbt.getIntArray("posList");

        toChangeList.clear();
        for (int i = 0; i < array.length; i += 3) {
            toChangeList.add(new HashedBlockPosition(array[i], array[i + 1], array[i + 2]));
        }

        array = nbt.getIntArray("biomeList");
        discoveredBiomes.clear();
        for (int value : array) {
            discoveredBiomes.add((byte) value);
        }
    }
}

