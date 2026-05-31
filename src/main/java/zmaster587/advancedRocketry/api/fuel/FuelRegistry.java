package zmaster587.advancedRocketry.api.fuel;

import java.util.HashSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;

public class FuelRegistry {

    public static final FuelRegistry instance = new FuelRegistry();

    /**
     * @param type       {@link FuelType} to register with
     * @param fluid      fluid to register
     * @param multiplier amount of fuel points 1mb is worth
     * @return true if successfully added to the registry, false if it already exists
     */
    public boolean registerFuel(@Nonnull FuelType type, Fluid fluid, float multiplier) {
        FuelEntry entry = new FuelEntry(fluid, multiplier);

        return type.addFuel(entry);
    }

    /**
     * Registers an ItemStack to be used as fuel for engines that use the given type of fuel
     *
     * @param type       {@link FuelType} to register with
     * @param item       item to register
     * @param multiplier amount of fuel points one item is worth
     * @return true if successfully added to the registry, false if it already exists
     */
    public boolean registerFuel(@Nonnull FuelType type, @Nonnull ItemStack item, float multiplier) {
        FuelEntry entry = new FuelEntry(item, multiplier);
        return type.addFuel(entry);
    }

    /**
     * @param type  {@link FuelType} registry to check in
     * @param stack ItemStack to check
     * @return true if the itemStack has been registered as {@link FuelType} fuel
     */
    public boolean isFuel(@Nullable FuelType type, @Nonnull ItemStack stack) {
        return isFuel(type, (Object) stack);
    }

    /**
     * @param type  {@link FuelType} registry to check in
     * @param fluid Fluid to check
     * @return true if the fluid has been registered as {@link FuelType} fuel
     */
    public boolean isFuel(@Nullable FuelType type, Fluid fluid) {
        return isFuel(type, (Object) fluid);
    }

    private boolean isFuel(@Nullable FuelType type, Object obj) {
        if (type == null)
            return false;

        return type.isFuel(obj);
    }

    /**
     * @param type  {@link FuelType} registry to check in
     * @param stack itemStack to check against
     * @return the amount of fuel points one item of this stack is worth
     */
    public float getMultiplier(@Nullable FuelType type, @Nonnull ItemStack stack) {
        return getMultiplier(type, (Object) stack);
    }

    /**
     * @param type  {@link FuelType} registry to check in
     * @param fluid itemStack to check against
     * @return the amount of fuel points one millibucket of this fluid is worth
     */
    public float getMultiplier(@Nullable FuelType type, Fluid fluid) {
        return getMultiplier(type, (Object) fluid);
    }

    private float getMultiplier(@Nullable FuelType type, Object obj) {
        if (type == null)
            return 0;

        FuelEntry fuel = type.getFuel(obj);

        if (fuel == null)
            return 0;

        return fuel.multiplier;
    }

    public enum FuelType {
        LIQUID_MONOPROPELLANT(0),        //Used in ground to space rockets
        LIQUID_BIPROPELLANT(1),
        LIQUID_OXIDIZER(2),
        NUCLEAR_WORKING_FLUID(3),
        ION(4),        //Used in satellites
        WARP(5),        //Used in interstellar missions
        IMPULSE(6);    //Used in interplanetary missions

        //Stores a fuel entry for each type of fuel
        final HashSet<FuelEntry> fuels;
        public final int id;

        FuelType(int id) {
            fuels = new HashSet<>();
            this.id = id;
        }

        public static FuelType getById(int id) {
            return FuelType.values()[id];
        }

        /**
         * @param entry FuelEntry to add
         * @return true if successfully added, false if already exists
         */
        public boolean addFuel(@Nonnull FuelEntry entry) {
            entry.type = this;
            return fuels.add(entry);
        }

        /**
         * @param stack
         * @return true if the itemStack is a fuel Source
         */
        public boolean isFuel(@Nonnull ItemStack stack) {
            return isFuel((Object) stack);
        }

        /**
         * @param stack
         * @return true if the liquid is a fuelsource
         */
        public boolean isFuel(Fluid stack) {
            return isFuel((Object) stack);
        }

        /**
         * Called by helper functions and to avoid confusion
         *
         * @param obj
         * @return true if the passed Itemstack or fluidstack is a fuel
         */
        private boolean isFuel(@Nullable Object obj) {
            if (obj == null)
                return false;

            for (FuelEntry fuel : fuels) {
                if (fuel.fuelMatches(obj))
                    return true;
            }

            return false;
        }

        //Returns the fuel if it exists otherwise null (Helper)

        public FuelEntry getFuel(@Nonnull ItemStack stack) {
            return getFuel((Object) stack);
        }

        //Returns the fuel if it exists otherwise null (Helper)
        public FuelEntry getFuel(Fluid fluid) {
            return getFuel((Object) fluid);
        }

        /**
         * @param obj
         * @return Fuel entry for the itemStack if it exists, null otherwise
         */
        private FuelEntry getFuel(Object obj) {

            for (FuelEntry fuel : fuels) {
                FuelEntry entry;

                if ((entry = fuel).fuelMatches(obj))
                    return entry;
            }
            return null;
        }
    }

    private static class FuelEntry {

        //Fuel: itemstack or liquid
        private Object fuel;
        //Type: as defined above
        private FuelType type;

        //Multiplier: basically how good is the fuel relative to the base value set in the config
        private float multiplier;

        /**
         * @param fuel       ItemStack or Fluid to register as fuel
         * @param multiplier how many fuel points one unit of this object is worth
         */
        public FuelEntry(@Nonnull Object fuel, float multiplier) {
            this.fuel = fuel;
            this.multiplier = multiplier;
        }

        //Returns

        /**
         * @param obj object to check against
         * @return true if the passed object is indeed the same fuel
         */
        public boolean fuelMatches(@Nullable Object obj) {
            if (obj == null)
                return false;

            if (fuel instanceof ItemStack && obj instanceof ItemStack) {
                return ItemStack.areItemStacksEqual((ItemStack) fuel, (ItemStack) obj);
            } else if (fuel instanceof Fluid && obj instanceof Fluid) {
                Fluid a = (Fluid) fuel;
                Fluid b = (Fluid) obj;
                String an = a.getName();
                String bn = b.getName();
                return a == b || (an != null && an.equals(bn));
            } else {
                return false;
            }
        }

        //Override equals(Object), each the itemstack or fluid determines the entry
        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj != null) {
                if (obj instanceof FuelEntry) {
                    FuelEntry cmp = ((FuelEntry) obj);
                    return fuelMatches(cmp.fuel) && cmp.type == type;
                }
                return super.equals(obj);
            }
            return false;
        }
        @Override
        public int hashCode() {
            int result = (type != null ? type.hashCode() : 0);

            if (fuel instanceof ItemStack) {
                ItemStack stack = (ItemStack) fuel;
                result = 31 * result + stack.getItem().hashCode();
                result = 31 * result + stack.getMetadata();
                result = 31 * result + (stack.hasTagCompound() ? stack.getTagCompound().hashCode() : 0);
            } else if (fuel instanceof Fluid) {
                Fluid fluid = (Fluid) fuel;
                String name = fluid.getName();
                result = 31 * result + (name != null ? name.hashCode() : 0);
            }

            return result;
        }
    }
}