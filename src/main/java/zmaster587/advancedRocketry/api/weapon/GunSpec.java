package zmaster587.advancedRocketry.api.weapon;

import zmaster587.advancedRocketry.api.damage.ImpactKind;

/**
 * What a built gun IS, once its parts have been counted: everything the firing code needs and
 * nothing about which blocks produced it.
 *
 * <h3>Derived, never authored per block</h3>
 * <p>A gun's numbers come from its build — how long the barrel is, how much feed and cooling it was
 * given. So this object is produced by walking an assembly, not read off the controller: two guns
 * with the same parts have the same spec wherever they stand, and adding a barrel section is a
 * change a player can measure rather than a change to a config file.</p>
 *
 * <h3>Units are the substrate's units</h3>
 * <p>{@link #getMuzzleSpeed()} is blocks per <b>tick</b> and {@link #getImpactEnergy()} is in the
 * same unit a shield spends and a damage budget carries, because those are the units the shot layer
 * speaks. Nothing downstream converts, so nothing downstream can convert wrongly.</p>
 *
 * <h3>A spec is not permission to fire</h3>
 * <p>It says what a shot would look like; whether one happens is decided by the gun's own energy,
 * heat and drive state. A spec with a zero fire interval would still not fire a gun that is jammed.</p>
 */
public final class GunSpec {

    /** What an assembly with no parts at all is worth: nothing, and it says so rather than firing blanks. */
    public static final GunSpec EMPTY = new Builder().build();

    private final double muzzleSpeed;
    private final int impactEnergy;
    private final int fireIntervalTicks;
    private final int energyPerShot;
    private final int heatPerShot;
    private final int heatCapacity;
    private final int coolingPerTick;
    private final double spreadDegrees;
    private final double traverseDegreesPerTick;
    private final int lifetimeTicks;
    private final double projectileRadius;
    private final double projectileMass;
    private final ImpactKind kind;
    private final int partCount;

    private GunSpec(Builder builder) {
        this.muzzleSpeed = builder.muzzleSpeed;
        this.impactEnergy = builder.impactEnergy;
        this.fireIntervalTicks = builder.fireIntervalTicks;
        this.energyPerShot = builder.energyPerShot;
        this.heatPerShot = builder.heatPerShot;
        this.heatCapacity = builder.heatCapacity;
        this.coolingPerTick = builder.coolingPerTick;
        this.spreadDegrees = builder.spreadDegrees;
        this.traverseDegreesPerTick = builder.traverseDegreesPerTick;
        this.lifetimeTicks = builder.lifetimeTicks;
        this.projectileRadius = builder.projectileRadius;
        this.projectileMass = builder.projectileMass;
        this.kind = builder.kind;
        this.partCount = builder.partCount;
    }

    /**
     * Whether this assembly can fire at all. A build missing the one part that makes it a gun — a
     * barrel — has no muzzle speed and no round worth firing, and saying so here means every call
     * site asks one question instead of each inventing its own idea of "complete".
     */
    public boolean isOperable() {
        return muzzleSpeed > 0.0D && impactEnergy > 0 && partCount > 0;
    }

    /** Blocks per TICK, world frame once the mount has rotated it. */
    public double getMuzzleSpeed() {
        return muzzleSpeed;
    }

    /** What one round is worth on arrival, in shield-energy-equivalent units. */
    public int getImpactEnergy() {
        return impactEnergy;
    }

    /** Ticks between two rounds. Never below one: a gun cannot fire twice in one tick. */
    public int getFireIntervalTicks() {
        return fireIntervalTicks;
    }

    /** Forge Energy burned per round. Paid from the gun's own buffer, network or no network. */
    public int getEnergyPerShot() {
        return energyPerShot;
    }

    public int getHeatPerShot() {
        return heatPerShot;
    }

    /** Heat the gun may hold before it must stop firing and let the coolers work. */
    public int getHeatCapacity() {
        return heatCapacity;
    }

    public int getCoolingPerTick() {
        return coolingPerTick;
    }

    /** Half-angle of the cone a round may leave in, in degrees. Zero is a perfectly true barrel. */
    public double getSpreadDegrees() {
        return spreadDegrees;
    }

    /** How fast the mount may swing, in degrees per tick. A hard capability, never exceeded. */
    public double getTraverseDegreesPerTick() {
        return traverseDegreesPerTick;
    }

    /** How long a round lives before it expires — this gun's reach, expressed in the shot's own unit. */
    public int getLifetimeTicks() {
        return lifetimeTicks;
    }

    public double getProjectileRadius() {
        return projectileRadius;
    }

    public double getProjectileMass() {
        return projectileMass;
    }

    public ImpactKind getKind() {
        return kind;
    }

    /** How many parts were counted. Diagnostics, and the "is this thing built" test's raw material. */
    public int getPartCount() {
        return partCount;
    }

    /**
     * Accumulates part contributions into a spec.
     *
     * <p>Parts <b>add</b> rather than set, which is what keeps the contract open: a part shipped by
     * an addon contributes on the same terms as one of ours, and no part has to know what else the
     * build contains. The one exception is spread, where more barrel makes a gun truer — a part may
     * subtract there, and the result is floored at zero rather than allowed to go negative and
     * become an aim bonus nobody declared.</p>
     */
    public static final class Builder {

        private double muzzleSpeed;
        private int impactEnergy;
        private int fireIntervalTicks = 20;
        private int energyPerShot;
        private int heatPerShot;
        private int heatCapacity = 100;
        private int coolingPerTick = 1;
        private double spreadDegrees = 6.0D;
        private double traverseDegreesPerTick = 2.0D;
        private int lifetimeTicks = 200;
        private double projectileRadius = 0.25D;
        private double projectileMass = 1.0D;
        private ImpactKind kind = ImpactKind.KINETIC;
        private int partCount;

        public Builder addMuzzleSpeed(double blocksPerTick) {
            this.muzzleSpeed += Math.max(0.0D, blocksPerTick);
            return this;
        }

        public Builder addImpactEnergy(int energy) {
            this.impactEnergy += Math.max(0, energy);
            return this;
        }

        /** Faster feed = shorter interval. Floored at one tick, which is the physical limit. */
        public Builder speedUpFireIntervalBy(int ticks) {
            this.fireIntervalTicks = Math.max(1, this.fireIntervalTicks - Math.max(0, ticks));
            return this;
        }

        public Builder addEnergyPerShot(int fe) {
            this.energyPerShot += Math.max(0, fe);
            return this;
        }

        public Builder addHeatPerShot(int heat) {
            this.heatPerShot += Math.max(0, heat);
            return this;
        }

        public Builder addHeatCapacity(int heat) {
            this.heatCapacity += Math.max(0, heat);
            return this;
        }

        public Builder addCoolingPerTick(int heat) {
            this.coolingPerTick += Math.max(0, heat);
            return this;
        }

        /** Negative tightens the cone; the result never goes below a true barrel. */
        public Builder addSpreadDegrees(double degrees) {
            this.spreadDegrees = Math.max(0.0D, this.spreadDegrees + degrees);
            return this;
        }

        public Builder addTraverseDegreesPerTick(double degrees) {
            this.traverseDegreesPerTick = Math.max(0.0D, this.traverseDegreesPerTick + degrees);
            return this;
        }

        public Builder addLifetimeTicks(int ticks) {
            this.lifetimeTicks = Math.max(1, this.lifetimeTicks + ticks);
            return this;
        }

        public Builder setProjectileBody(double radius, double mass) {
            this.projectileRadius = Math.max(0.0D, radius);
            this.projectileMass = Math.max(0.0D, mass);
            return this;
        }

        /**
         * The last part to state a kind decides it. A build mixing a kinetic feed and a plasma one
         * is a build whose last-placed part wins, which is a rule a player can see the result of.
         */
        public Builder setKind(ImpactKind kind) {
            if (kind != null) {
                this.kind = kind;
            }
            return this;
        }

        /** Called once per part counted, by the assembly walk rather than by the parts themselves. */
        public Builder countPart() {
            this.partCount++;
            return this;
        }

        public GunSpec build() {
            return new GunSpec(this);
        }
    }
}
