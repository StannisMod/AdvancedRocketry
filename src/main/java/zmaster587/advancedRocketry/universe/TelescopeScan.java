package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

import net.minecraft.item.ItemStack;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.item.ItemMemoryCrystal;
import zmaster587.advancedRocketry.navigation.CrystalEntry;
import zmaster587.advancedRocketry.navigation.CrystalMemory;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * Turns a pointing into addresses a ship can navigate by.
 *
 * <p>This is the discovery instrument, so it asks the registry what is THERE rather than what is
 * already known: an instrument that only reported what the player had already found could never find
 * anything.</p>
 *
 * <p><b>Two stages, because they are two different questions and only one of them is expensive.</b></p>
 * <ol>
 *   <li><b>Detection</b> ({@link #detect}) — is anything in this direction, and is it bright enough
 *       to register? An anchor lookup and a magnitude, both O(1), with no bodies built and no
 *       retinue derived. This is what a survey spends its looks on.</li>
 *   <li><b>Characterisation</b> ({@link #characterise}) — what IS it? The system's bodies, one
 *       address each. Paid only where the first stage found something.</li>
 * </ol>
 *
 * <p>They used to be one call, so the cheap question could never be asked without paying for the
 * expensive one. {@link InfoTier} already distinguished the two grades of knowledge; what was missing
 * was an instrument that could hold one without the other.</p>
 *
 * <p><b>What a look sees is bounded by BRIGHTNESS, never by distance.</b> A star registers when its
 * apparent magnitude from the observatory — its own luminosity, dimmed by distance and by whatever
 * dust lies between — is above the aperture's limit. So the same instrument reaches a blue giant
 * eighty times farther than a red dwarf, and a starless world it never reaches at all: a rogue
 * planet emits nothing, and finding one is a thing you do by going there.</p>
 *
 * <p>What a cell yields once characterised is its system's <b>bodies</b>, one address each, at the
 * coarsest detail an observation can carry. That grade is not a formality — it is what the navigation
 * console reads to decide which of a body's fields it may show, and at telescope grade that is
 * already the whole global set: name, mass, stellar class, rings, sky colour, topology, atmosphere
 * and its density, temperature, water.</p>
 */
public final class TelescopeScan {

    private TelescopeScan() {
    }

    /**
     * The most seats one look will enumerate inside its own territory before it goes back to
     * sampling — see {@link IGalaxyGenerator#anchorsInTerritory}.
     *
     * <p>Sized by what a UNIFORMLY divided field can hold, not by a feel for a good batch: a lattice
     * divided {@code k} ways per axis puts {@code k³} seats in a territory, and 64 covers every
     * division up to four. Past that the divider is a star cluster, where a survey samples rather
     * than counts and always has.</p>
     */
    public static final int MAX_SEATS_PER_LOOK = 64;

    /** How production names a body: by its dimension, the way every other GUI does. */
    public static IntFunction<String> dimensionNames() {
        return dimId -> {
            DimensionProperties props = zmaster587.advancedRocketry.dimension.DimensionManager
                    .getInstance().getDimensionProperties(dimId);
            return props == null || props.getName() == null ? "" : props.getName();
        };
    }

    /**
     * One point the instrument registered: where it is, and how it looked from where the instrument
     * stands.
     *
     * <p>The magnitude and the dust are carried rather than recomputed because the second stage needs
     * them to decide how much it can make out — and because a detection is a fact about a LOOK, not
     * about a system: the same star is a different detection from somewhere else.</p>
     */
    public static final class Detection {

        private final GalacticCoord anchor;
        private final double apparentMagnitude;
        private final double distanceLightYears;
        private final double extinctionMagnitudes;

        public Detection(GalacticCoord anchor, double apparentMagnitude, double distanceLightYears,
                         double extinctionMagnitudes) {
            this.anchor = anchor;
            this.apparentMagnitude = apparentMagnitude;
            this.distanceLightYears = distanceLightYears;
            this.extinctionMagnitudes = extinctionMagnitudes;
        }

        /** The anchor cell of the system that was registered. */
        public GalacticCoord anchor() {
            return anchor;
        }

        /** How bright it looked from the instrument. Magnitudes: smaller is brighter. */
        public double apparentMagnitude() {
            return apparentMagnitude;
        }

        /** How far away it stands, in light years. */
        public double distanceLightYears() {
            return distanceLightYears;
        }

        /** How much dust lies between, in magnitudes of extinction. */
        public double extinctionMagnitudes() {
            return extinctionMagnitudes;
        }

        @Override
        public String toString() {
            return "Detection[" + anchor.cellKey() + ", m=" + String.format("%.2f", apparentMagnitude)
                    + ", " + String.format("%.1f", distanceLightYears) + " ly]";
        }
    }

    /**
     * STAGE ONE. Everything in {@code look}'s star territory that is bright enough to register from
     * {@code observer}.
     *
     * <p><b>The territory and not the point.</b> A survey strides by the star territory, so a look
     * that resolved only the point it landed on would report one seat in however many the generator
     * divides that cube into — a fraction of the sky, presented as the sky. Asking for the
     * territory's anchors makes the answer independent of how finely the field happens to be
     * divided, which is the property a survey needs and a stride cannot give it.</p>
     *
     * <p><b>A null observer means the look is free of geometry</b>: no distance, no dust, and
     * everything present registers. That is what a caller with no position can honestly claim, and
     * what every look was before an instrument had somewhere to stand.</p>
     */
    public static List<Detection> detect(UniverseRegistry registry, GalacticCoord look,
                                         GalacticCoord observer, double limitMagnitude) {
        if (registry == null || look == null) {
            return Collections.emptyList();
        }
        List<GalacticCoord> anchors = registry.anchorsInTerritory(look, MAX_SEATS_PER_LOOK);
        if (anchors.isEmpty()) {
            return Collections.emptyList();
        }
        List<Detection> hits = new ArrayList<>(anchors.size());
        for (GalacticCoord anchor : anchors) {
            if (observer == null) {
                hits.add(new Detection(anchor, Double.NEGATIVE_INFINITY, 0d, 0d));
                continue;
            }
            // The STATIC-frame separation, which is the right one here and not an approximation: an
            // anchor's frame really does sit at sector*CELL forever, and a survey looks at anchors.
            double cells = observer.cellCentre().staticFrameDistanceTo(anchor.cellCentre())
                    / (double) GalacticCoord.CELL;
            double lightYears = UniverseScale.lightYearsForCells(cells);
            StellarBody star = registry.starAt(anchor).orElse(null);
            // CLEAR SKY FIRST, and this ordering is not a micro-optimisation — it is the difference
            // between a survey that runs and one that does not. Measuring the dust on a sight line
            // means integrating a cloud field along the whole of it, which is by far the dearest
            // thing on this path, and extinction can only ever make a star DIMMER. So anything
            // already too faint in a clear sky is rejected without asking about the dust, and a
            // full pointing pays for the integral a dozen times instead of half a million.
            double clearSky = StellarMagnitude.apparentMagnitudeOf(star, lightYears, 0d);
            if (clearSky > limitMagnitude) {
                continue;
            }
            double extinction = registry.extinctionBetween(observer, anchor);
            double magnitude = clearSky + extinction;
            if (magnitude <= limitMagnitude) {
                hits.add(new Detection(anchor, magnitude, lightYears, extinction));
            }
        }
        return hits;
    }

    /**
     * STAGE TWO. Write down what {@code hit} turns out to be.
     *
     * <p><b>A look is a touch.</b> Everything here hands the operator something durable — an address
     * he can fly to, a body he can name — out of a derivation that a later seed, config or generator
     * edit would answer differently. Pinning first freezes the system into the save before a word of
     * it is written down, so what the crystal holds and what the sky holds cannot come apart. The
     * unit is the whole SYSTEM and not the bodies enumerated, because a system is what a pin can key.
     * Idempotent and free for anything already authored or pinned.</p>
     *
     * <p><b>An unresolvable look still yields an address.</b> Whether the dust was too thick or the
     * operator has the instrument set to record positions only, the bare coordinate is written: the
     * operator learns that something is there and has to go and see what. That is the whole
     * mechanic — a reason to FLY somewhere rather than survey it from home — and it is why
     * concealment costs detail and never the look itself.</p>
     *
     * @param wholeSystem whether to enumerate the system's bodies, or record the address alone. The
     *                    operator's own choice: a full characterisation is the instrument's dear
     *                    setting and fills a crystal far faster
     * @return how many entries the memory gained or refreshed
     */
    public static int characterise(UniverseRegistry registry, Detection hit, CrystalMemory memory,
                                   long observedTick, IntFunction<String> nameOf,
                                   boolean wholeSystem) {
        if (registry == null || hit == null || memory == null) {
            return 0;
        }
        GalacticCoord anchor = hit.anchor();
        registry.pinSystem(anchor);
        int written = 0;
        boolean namedSomething = false;
        if (wholeSystem && !isObscuredAt(hit.extinctionMagnitudes())) {
            for (SystemBody body : registry.systemBodiesAt(anchor)) {
                namedSomething = true;
                if (memory.record(entryFor(body, observedTick, nameOf))) {
                    written++;
                }
            }
        }
        if (!namedSomething) {
            PlanetarySystem system = registry.systemForCoord(anchor).orElse(null);
            if (memory.record(entryForSystem(anchor, system, observedTick))) {
                written++;
            }
        }
        return written;
    }

    /**
     * Resolve the next {@code count} looks of {@code scan} onto {@code crystal}.
     *
     * @return how many entries the crystal gained or refreshed
     */
    public static int resolveBatch(UniverseRegistry registry, RegionScan scan, int from, int count,
                                   ItemStack crystal, long observedTick, IntFunction<String> nameOf) {
        return resolveBatch(registry, scan, from, count, crystal, observedTick, nameOf, null, true);
    }

    /** The same, resolved from a stated observer, so distance and dust decide what registers. */
    public static int resolveBatch(UniverseRegistry registry, RegionScan scan, int from, int count,
                                   ItemStack crystal, long observedTick, IntFunction<String> nameOf,
                                   GalacticCoord observer, boolean wholeSystem) {
        if (!ItemMemoryCrystal.isCrystal(crystal)) {
            return 0;
        }
        CrystalMemory memory = ItemMemoryCrystal.memoryOf(crystal);
        int written = resolveBatch(registry, scan, from, count, memory, observedTick, nameOf,
                observer, wholeSystem);
        if (written > 0) {
            ItemMemoryCrystal.writeMemory(crystal, memory);
        }
        return written;
    }

    /** The same, onto an already-opened memory. This is where the discovery actually happens. */
    public static int resolveBatch(UniverseRegistry registry, RegionScan scan, int from, int count,
                                   CrystalMemory memory, long observedTick, IntFunction<String> nameOf) {
        return resolveBatch(registry, scan, from, count, memory, observedTick, nameOf, null, true);
    }

    /**
     * The same, resolved from a stated OBSERVER — the form that can see how far away and how dim
     * something is.
     */
    public static int resolveBatch(UniverseRegistry registry, RegionScan scan, int from, int count,
                                   CrystalMemory memory, long observedTick, IntFunction<String> nameOf,
                                   GalacticCoord observer, boolean wholeSystem) {
        if (registry == null || scan == null || memory == null) {
            return 0;
        }
        double limit = limitMagnitude();
        int written = 0;
        for (int index = from; index < from + count && index < scan.totalCells(); index++) {
            written += resolveLook(registry, scan.cellAt(index), memory, observedTick, nameOf,
                    observer, limit, wholeSystem);
        }
        return written;
    }

    /**
     * ONE look, both stages: what is in this direction's territory, and what those things are.
     *
     * <p>The question a look asks is <b>which systems this territory holds</b>, never "is a star
     * seated exactly at this point". A system is a neighbourhood: its star holds the anchor cell and
     * every planet holds one of its own, so a cell that is a system's planet — or simply the space
     * between its bodies — is a cell that resolves to that system. Asking whether the cell IS the
     * seat means a survey discovers a system only by landing on its star's own address, which for a
     * lattice thousands of cells wide is a thing that never happens.</p>
     */
    public static int resolveLook(UniverseRegistry registry, GalacticCoord look, CrystalMemory memory,
                                  long observedTick, IntFunction<String> nameOf,
                                  GalacticCoord observer, double limitMagnitude,
                                  boolean wholeSystem) {
        int written = 0;
        for (Detection hit : detect(registry, look, observer, limitMagnitude)) {
            written += characterise(registry, hit, memory, observedTick, nameOf, wholeSystem);
        }
        return written;
    }

    /** The aperture the running game is configured with. Magnitudes: larger is fainter. */
    public static double limitMagnitude() {
        return ARConfiguration.getCurrentConfig().telescopeLimitingMagnitude;
    }

    /**
     * Whether a look from {@code observer} to {@code target} is OBSCURED — a cloud between them thick
     * enough that a survey can no longer make out what is there, only that something is.
     *
     * <p>The threshold is read in magnitudes of extinction, the unit the sky is measured in, and its
     * shipped default is the astronomical boundary at which faint objects behind a cloud disappear.
     * Zero or less turns the whole mechanic off, which is what "disable the flag" has to mean.</p>
     *
     * <p>It COMPOSES with the aperture rather than duplicating it, on the same currency: the same
     * dust is added to the star's apparent magnitude, so a thick enough cloud takes the system below
     * the limit and it is never detected at all. Between the two lies the interesting band — bright
     * enough to see, dim enough that nothing about it can be made out.</p>
     */
    public static boolean isObscured(UniverseRegistry registry, GalacticCoord observer,
                                     GalacticCoord target) {
        if (registry == null || observer == null || target == null) {
            return false;
        }
        return isObscuredAt(registry.extinctionBetween(observer, target));
    }

    /** The same decision against an extinction already measured — what a detection carries. */
    public static boolean isObscuredAt(double extinctionMagnitudes) {
        double threshold = ARConfiguration.getCurrentConfig().telescopeObscuredAtMagnitudes;
        return threshold > 0d && extinctionMagnitudes >= threshold;
    }

    /** One body's address, at the coarsest grade, dated by when it was seen. */
    public static CrystalEntry entryFor(SystemBody body, long observedTick, IntFunction<String> nameOf) {
        String name = "";
        if (body.dimId() != Constants.INVALID_PLANET && nameOf != null) {
            name = nameOf.apply(body.dimId());
        }
        return new CrystalEntry(body.name(), name, body.kind(), InfoTier.TELESCOPE, observedTick,
                body.dimId());
    }

    /**
     * A system nothing has been resolved of: the address alone, so a pilot can still aim at the light
     * and go look. It names no body, because none has been resolved.
     */
    public static CrystalEntry entryForSystem(GalacticCoord coord, PlanetarySystem system, long observedTick) {
        // The system's own name and its own PRIMARY KIND: a starless system recorded as a STAR would
        // send a pilot out expecting a sun, and the address is the whole content of this entry.
        String name = system == null ? "" : system.name();
        SystemBodyKind kind = system == null ? SystemBodyKind.STAR : system.primaryKind();
        return new CrystalEntry(coord.cellCentre(), name, kind, InfoTier.TELESCOPE, observedTick);
    }

    /**
     * The bodies of the system owning {@code cell}, written down without any photometry — the form
     * an instrument standing INSIDE a system uses to report what it is standing in.
     *
     * <p>Kept as its own entry point rather than folded into a look, because it answers a different
     * question: not "what can I see from here" but "what is here". Nothing about brightness applies
     * to a system you are inside.</p>
     */
    public static int resolveCell(UniverseRegistry registry, GalacticCoord cell, CrystalMemory memory,
                                  long observedTick, IntFunction<String> nameOf) {
        if (registry == null || cell == null || memory == null) {
            return 0;
        }
        Optional<GalacticCoord> anchor = registry.anchorForCell(cell);
        if (!anchor.isPresent()) {
            return 0;
        }
        return characterise(registry, new Detection(anchor.get(), Double.NEGATIVE_INFINITY, 0d, 0d),
                memory, observedTick, nameOf, true);
    }
}
