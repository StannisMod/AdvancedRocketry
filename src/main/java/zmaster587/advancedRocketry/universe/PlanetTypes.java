package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleToIntFunction;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;

/**
 * The catalogue of {@link PlanetTypePreset planet types} and the two draws that use it: which type a
 * derived world IS, and which of that type's terrain generators it gets.
 *
 * <p>The stock set ships in CODE and is overridden wholesale by the {@code <planetType>} elements of
 * {@code planetDefs.xml}. XML is authoritative when present; the code answers when it is not, so a
 * trimmed or broken config degrades to stock worlds instead of producing worlds with no type at all.
 * Same shape as {@code <galaxyGen>}.</p>
 *
 * <h3>Two rules that are not obvious from the signatures</h3>
 * <ul>
 *   <li><b>Overlap is resolved by a WEIGHTED DRAW among every admitting preset</b>, never by first
 *       match. First match would make the XML's document ORDER load-bearing — a silent dependency an
 *       author cannot see — and an explicit priority attribute would be a second ordering language for
 *       something weights already express. The consequence, which is tuning and not design: a preset
 *       with wide ranges soaks probability from narrow ones, so the stock ranges are authored tight.</li>
 *   <li><b>The availability filter runs BEFORE the terrain draw, never after.</b> An entry naming a
 *       {@code WorldType} this modset does not have is dropped and the remaining weights renormalize by
 *       themselves. Filtering after the draw would silently convert that entry's whole share into the
 *       fallback — so removing one mod would not merely remove its worlds, it would make some other
 *       kind of world commoner in exact proportion.</li>
 * </ul>
 *
 * <p>Static state, server-side authored config — the same lifetime and the same reset points as the
 * star catalogue it is loaded beside.</p>
 */
public final class PlanetTypes {

    // A self-contained logger rather than AdvancedRocketry.logger: loading the mod class triggers Forge
    // bootstrap, which would break pure unit tests of the derivation this class feeds.
    private static final Logger LOGGER = LogManager.getLogger("AdvancedRocketry|Universe");

    /**
     * The name reported for a world no preset admits. It is never drawn — it exists so that a hole in
     * the authored coverage produces a world that is still landable and still describable, rather than
     * a null type nothing downstream can render. Seeing it in a log means the preset table has a gap.
     */
    public static final String UNCLASSIFIED = "unclassified";

    /**
     * Whether a foreign {@code WorldType} of this name exists in the running modset. A seam, so the
     * filter is unit-testable without a Minecraft registry; production resolves it against
     * {@code WorldType.byName}.
     */
    private static volatile Predicate<String> worldTypeAvailable = PlanetTypes::worldTypeIsRegistered;

    private static volatile List<PlanetTypePreset> presets = stockPresets();

    private PlanetTypes() {
    }

    // ─── The catalogue ─────────────────────────────────────────────────────────

    /** Every preset currently in force, in authored order. Never empty. */
    public static List<PlanetTypePreset> presets() {
        return presets;
    }

    /** Install an authored table (the {@code <planetType>} elements). An empty list restores stock. */
    public static void setPresets(List<PlanetTypePreset> authored) {
        if (authored == null || authored.isEmpty()) {
            presets = stockPresets();
            return;
        }
        presets = Collections.unmodifiableList(new ArrayList<>(authored));
    }

    /** Restore the code-shipped table — the world-unload / config-reset path. */
    public static void resetToStock() {
        presets = stockPresets();
    }

    /** The preset of that name, or {@code null}. */
    public static PlanetTypePreset byName(String name) {
        if (name == null) {
            return null;
        }
        for (PlanetTypePreset p : presets) {
            if (p.name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    /** Override the {@code WorldType}-availability probe (tests, or an addon with its own registry). */
    public static void setWorldTypeAvailability(Predicate<String> probe) {
        worldTypeAvailable = probe == null ? PlanetTypes::worldTypeIsRegistered : probe;
    }

    // ─── The draws ─────────────────────────────────────────────────────────────

    /**
     * Every preset whose declared region admits this world. May be empty (an authoring gap).
     *
     * <p><b>Each candidate is tested at the temperature the world would have IF IT WERE THAT TYPE.</b>
     * A preset states its surface, a surface has an albedo, and the albedo is part of what sets the
     * temperature — so admitting every candidate at one temperature and then applying the winner's
     * albedo produced worlds outside their own declared band: an {@code ocean} preset admitting
     * 255&ndash;380 K would be handed to a world that its own albedo of 0.10 then warms to 393 K.</p>
     *
     * <p>It is not circular and it does not iterate: the caller hands in a FUNCTION from albedo to
     * temperature, so each candidate is evaluated once, against its own number. That also keeps the
     * LAW out of this class — it stays a table matcher and never learns what a star is or how one
     * warms a world.</p>
     *
     * @param temperatureForAlbedo what this world's surface temperature would be at a given albedo
     */
    public static List<PlanetTypePreset> candidates(int pressure,
                                                    DoubleToIntFunction temperatureForAlbedo,
                                                    int gravityPercent, boolean gasGiant) {
        List<PlanetTypePreset> out = new ArrayList<>();
        for (PlanetTypePreset p : presets) {
            if (p.admits(pressure, temperatureForAlbedo.applyAsInt(p.albedo()), gravityPercent,
                    gasGiant)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * The type of a world at these parameters, drawn by weight among everything that admits it.
     * {@code hash} is the derivation's own draw — the same {@code (seed, cell)} always lands on the
     * same type.
     *
     * <p>When nothing admits the world, the WIDEST admitting-by-temperature stock shape is not
     * substituted and no preset is invented: the answer is {@code null}, and the caller reports the
     * world as {@link #UNCLASSIFIED}. A silent substitution would hide the authoring gap forever.</p>
     */
    public static PlanetTypePreset drawType(int pressure,
                                            DoubleToIntFunction temperatureForAlbedo,
                                            int gravityPercent, boolean gasGiant, long hash) {
        List<PlanetTypePreset> admitting = candidates(pressure, temperatureForAlbedo, gravityPercent,
                gasGiant);
        if (admitting.isEmpty()) {
            // Reported at the NEUTRAL reading, which is the one number that describes the world rather
            // than one of the types that declined it — an author widening a range needs to know where
            // the world actually sits, not where the last candidate would have put it.
            int neutral = temperatureForAlbedo.applyAsInt(AstronomicalBodyHelper.EARTH_ALBEDO);
            if (SystemContent.reportOnce("noPlanetType:" + gasGiant + ':' + pressure / 50 + ':'
                    + neutral / 25 + ':' + gravityPercent / 25)) {
                LOGGER.warn("no planet type admits a world at pressure {}, {} K, gravity {}% (gasGiant={})"
                        + " - it will be reported as '{}'. Widen a <planetType> range to cover it.",
                        pressure, neutral, gravityPercent, gasGiant, UNCLASSIFIED);
            }
            return null;
        }
        long total = 0L;
        for (PlanetTypePreset p : admitting) {
            total += p.weight();
        }
        long r = Math.floorMod(hash, Math.max(1L, total));
        for (PlanetTypePreset p : admitting) {
            if (r < p.weight()) {
                return p;
            }
            r -= p.weight();
        }
        return admitting.get(admitting.size() - 1);
    }

    /**
     * The terrain generator a world of type {@code preset} gets, drawn by weight over the entries this
     * modset can actually run. Never {@code null}: a preset whose every entry names a missing mod falls
     * back to Advanced Rocketry's own generator, which is the one thing always present.
     */
    public static TerrainOption drawTerrain(PlanetTypePreset preset, long hash) {
        if (preset == null) {
            return TerrainOption.ofNative(0, 1);
        }
        // D6: drop the unavailable entries FIRST, then draw over what is left.
        List<TerrainOption> available = new ArrayList<>();
        for (TerrainOption option : preset.terrain()) {
            if (!option.needsForeignWorldType() || worldTypeAvailable.test(option.worldType())) {
                available.add(option);
            }
        }
        if (available.isEmpty()) {
            if (SystemContent.reportOnce("noTerrain:" + preset.name())) {
                LOGGER.warn("planet type '{}' has no runnable terrain source in this modset (every "
                        + "<gen> entry names a WorldType that is not registered) - falling back to the "
                        + "native generator.", preset.name());
            }
            return TerrainOption.ofNative(0, 1);
        }
        long total = 0L;
        for (TerrainOption option : available) {
            total += option.weight();
        }
        long r = Math.floorMod(hash, Math.max(1L, total));
        for (TerrainOption option : available) {
            if (r < option.weight()) {
                return option;
            }
            r -= option.weight();
        }
        return available.get(available.size() - 1);
    }

    // ─── The stock table ───────────────────────────────────────────────────────

    /**
     * The code-shipped presets. Ranges are authored TIGHT and made to TOUCH rather than overlap
     * broadly: a wide preset soaks probability from every narrow one it contains, so an "everything
     * else" catch-all would quietly become the commonest world in the galaxy.
     *
     * <p>Astronomy on the left of each comment, the Advanced Rocketry lever it is expressed through on
     * the right. Every number here is a balance knob and none of them is a contract.</p>
     */
    public static List<PlanetTypePreset> stockPresets() {
        List<PlanetTypePreset> l = new ArrayList<>();

        // The commonest body class of all — every airless moon, Mercury. Defined by having no air at
        // all, which is why its pressure band is the tight one and its temperature band is not: an
        // airless rock is as plausible baking beside its star as frozen far from it.
        l.add(PlanetTypePreset.builder("barren").albedo(0.12d).weight(30)
                .pressure(0, 25).temperature(0, 1500).gravity(1, 90)
                .biomes("advancedrocketry:moon;30,advancedrocketry:moondark;20")
                .terrain(TerrainOption.ofNative(0, 1))
                .build());

        // Everything past the snow line, thin-aired or thick: Europa and Titan are the same class of
        // world, and which of the two you get is how much nitrogen the gravity managed to keep.
        l.add(PlanetTypePreset.builder("ice").albedo(0.60d).weight(22)
                .pressure(0, 1600).temperature(0, 200).gravity(1, 400)
                .biomes("advancedrocketry:moondark;10,minecraft:ice_flats;30,minecraft:ice_mountains;20")
                .terrain(TerrainOption.ofNative(0, 1))
                .build());

        // Tight inner orbits, common around M dwarfs. A molten surface under whatever the rock itself
        // boiled off, which can be a great deal — hence no pressure ceiling.
        l.add(PlanetTypePreset.builder("lava").albedo(0.10d).weight(12)
                .pressure(0, 1600).temperature(700, 6000).gravity(5, 400)
                .biomes("advancedrocketry:volcanic;30,advancedrocketry:volcanicbarren;20,"
                        + "advancedrocketry:hotdryrock;10")
                .terrain(TerrainOption.ofNative(0, 1))
                .build());

        // Venus-like, and likely common in the hot zone: a thick atmosphere doing the warming, which is
        // why the band is keyed on the PRESSURE floor rather than on where the world orbits.
        l.add(PlanetTypePreset.builder("greenhouse").albedo(0.75d).weight(14)
                .pressure(150, 1600).temperature(275, 1000).gravity(20, 400)
                .biomes("advancedrocketry:hotdryrock;30,advancedrocketry:volcanicbarren;10")
                .terrain(TerrainOption.ofNative(0, 1))
                .build());

        // The commonest planet class in the galaxy, and absent from the Solar System entirely. Defined
        // by MASS, not by climate: a super-Earth is one whether it is frozen or baked.
        l.add(PlanetTypePreset.builder("superearth").albedo(0.30d).weight(16)
                .pressure(0, 1600).temperature(0, 900).gravity(160, 400)
                .biomes("advancedrocketry:stormland;30,advancedrocketry:hotdryrock;10")
                .terrain(TerrainOption.ofNative(0, 1))
                .build());

        // A common end state of water loss: warm, dry, and holding just enough air to blow it around.
        l.add(PlanetTypePreset.builder("desert").albedo(0.30d).weight(16)
                .pressure(0, 200).temperature(200, 700).gravity(10, 200)
                .biomes("advancedrocketry:hotdryrock;30,minecraft:desert;20,minecraft:mesa;10")
                .terrain(TerrainOption.ofNative(0, 1))
                .build());

        // Hypothesised but plausible: no exposed continent worth the name, and a deep global sea.
        l.add(PlanetTypePreset.builder("ocean").albedo(0.10d).weight(7).allowsOxygen(true)
                .pressure(60, 400).temperature(255, 380).gravity(50, 190)
                .seaLevel(96)
                .biomes("advancedrocketry:oceanspires;30,minecraft:deep_ocean;30,minecraft:ocean;20")
                .terrain(TerrainOption.ofNative(0, 1))
                .build());

        // Life without oxygen — the crystal / stormland / alien-forest biomes, all written and nearly
        // unused today. Deliberately narrow: a find, not a background.
        l.add(PlanetTypePreset.builder("exotic").albedo(0.30d).weight(5)
                .pressure(40, 1600).temperature(200, 430).gravity(10, 220)
                .biomes("advancedrocketry:crystalchasms;30,advancedrocketry:stormland;20,"
                        + "advancedrocketry:alien_forest;10")
                .terrain(TerrainOption.ofNative(0, 1))
                .build());

        // Very rare, and rare on purpose: the conjunction is physics, the oxygen on top is biology.
        l.add(PlanetTypePreset.builder("earthlike").albedo(0.30d).weight(3).allowsOxygen(true)
                .pressure(50, 220).temperature(255, 325).gravity(60, 145)
                .biomes("minecraft:plains;30,minecraft:forest;25,minecraft:extreme_hills;15,"
                        + "minecraft:ocean;15,advancedrocketry:marsh;10")
                .terrain(TerrainOption.ofNative(0, 1))
                .build());

        // ~10-20% of stars. A real destination — fuel skimming and moons — but never a landing.
        // Its bands are deliberately the widest in the table: a giant is a giant, and nothing else in
        // this list will ever admit one, so a gap here would leave a whole body class untyped.
        l.add(PlanetTypePreset.builder("gasgiant").albedo(0.50d).weight(14).gasGiant(true).tidallyLockable(false)
                .pressure(0, 1600).temperature(0, 1500).gravity(1, 400)
                .terrain(TerrainOption.ofNative(0, 1))
                .build());

        // Neptune and Uranus: the same, further out and colder.
        l.add(PlanetTypePreset.builder("icegiant").albedo(0.50d).weight(9).gasGiant(true).tidallyLockable(false)
                .pressure(0, 1600).temperature(0, 250).gravity(1, 300)
                .terrain(TerrainOption.ofNative(0, 1))
                .build());

        return Collections.unmodifiableList(l);
    }

    /**
     * Production availability probe. Kept out of the field initialiser so that a unit test which never
     * declares a foreign generator never loads a Minecraft registry class.
     */
    private static boolean worldTypeIsRegistered(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        try {
            // The SAME resolver TerrainResolution uses when it actually installs the generator — a
            // filter that admitted a name the installer then rejects would be worse than no filter.
            return net.minecraft.world.WorldType.parseWorldType(name.trim()) != null;
        } catch (Throwable t) {
            // No registry in this context (a headless derivation) — treat the generator as absent
            // rather than pretending it is there and handing a realized world a name nothing answers.
            return false;
        }
    }
}
