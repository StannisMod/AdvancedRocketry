package zmaster587.advancedRocketry.subsystem.heat;

/**
 * What a material is worth thermally: how much heat a lump of it can carry before it stops being a
 * lump.
 *
 * <p>Three real properties and nothing else - density, specific heat, and the temperature at which
 * the solid gives up. Everything the thermal system needs from a material is DERIVED from these,
 * which is the whole point: a slug's capacity is a consequence of what it is made of rather than a
 * number somebody typed next to an item, and the same three figures decide when a block of it melts.
 * One table, two consumers.</p>
 *
 * <p><b>The ceiling is not always a melting point, and calling it one would be wrong.</b> Metals
 * melt; water boils away at 373 K and never melts at all; graphite does not melt at ordinary pressure
 * but SUBLIMES near 3 900 K. What they have in common is the temperature past which you no longer
 * have a solid object to throw, so that is what is stored and what the name says.</p>
 *
 * <p>The numbers are ordinary physics and are quoted in SI (kg/m3, J/kg.K, K), not in game units.
 * Converting to the heat unit happens once, at the point of use, so a reader can check any row of
 * this table against a handbook.</p>
 */
public final class ThermalMaterial {

    private final String name;
    private final int densityKgPerCubicMetre;
    private final int specificHeatJoulesPerKgKelvin;
    private final int ceilingKelvin;

    public ThermalMaterial(String name, int densityKgPerCubicMetre,
                           int specificHeatJoulesPerKgKelvin, int ceilingKelvin) {
        this.name = name;
        this.densityKgPerCubicMetre = Math.max(0, densityKgPerCubicMetre);
        this.specificHeatJoulesPerKgKelvin = Math.max(0, specificHeatJoulesPerKgKelvin);
        this.ceilingKelvin = Math.max(0, ceilingKelvin);
    }

    public String name() {
        return name;
    }

    public int densityKgPerCubicMetre() {
        return densityKgPerCubicMetre;
    }

    public int specificHeatJoulesPerKgKelvin() {
        return specificHeatJoulesPerKgKelvin;
    }

    /**
     * The temperature past which there is no solid object any more: melting for a metal, boiling for
     * water, sublimation for graphite.
     */
    public int ceilingKelvin() {
        return ceilingKelvin;
    }

    /**
     * How much energy one cubic metre of this material absorbs on the way from {@code startKelvin} to
     * {@code marginKelvin} below its ceiling, in joules.
     *
     * <p>{@code rho * c * dT}, and the margin is what keeps the object intact: a slug charged all the
     * way to its ceiling is a puddle, so the usable span stops short of it. A material already at or
     * above that point carries nothing rather than a negative amount - a lead slug in a room hotter
     * than lead melts is not a heat sink, it is a problem.</p>
     */
    public long joulesPerCubicMetre(int startKelvin, int marginKelvin) {
        long usable = (long) ceilingKelvin - Math.max(0, marginKelvin) - startKelvin;
        if (usable <= 0L) {
            return 0L;
        }
        return (long) densityKgPerCubicMetre * specificHeatJoulesPerKgKelvin * usable;
    }

    @Override
    public String toString() {
        return "ThermalMaterial[" + name + " rho=" + densityKgPerCubicMetre
                + " c=" + specificHeatJoulesPerKgKelvin + " ceiling=" + ceilingKelvin + "K]";
    }
}
