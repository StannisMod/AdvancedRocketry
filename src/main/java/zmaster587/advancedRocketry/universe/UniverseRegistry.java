package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntFunction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.CellFrames;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * The Layer-1 universe registry (universe-model.md &sect;2): the single owner of galactic PLACEMENT.
 *
 * <p>It answers only "what exists and where" — no worlds are loaded. It is an <b>additive bridge</b> over the
 * legacy star catalogue: {@link StellarBody} + {@link DimensionManager}'s int-keyed star list keep their
 * identity and persistence untouched; this registry INDEXES them, attaching a {@link GalacticCoord} to each
 * system and answering coord&harr;system both ways. Systems therefore stay LOCATION-AGNOSTIC — the coordinate
 * lives here, never on the star.</p>
 *
 * <p>Placement is keyed by the ANCHOR cell (one system per anchor; every coord snaps to its
 * {@link GalacticCoord#cellCentre() cell centre} before use). Per amendment A#1a a system is an anchored
 * NEIGHBOURHOOD: the star holds the anchor cell, every planet/belt its own cell — a MEMBER cell attributes
 * back to its system via {@link #anchorForCell} (super-cell partition; derive-don't-store). The persistent
 * override store holds authored (XML anchor) placements, player POIs and {@code pin-on-touch} snapshots of
 * touched procedural systems; untouched procedural space is re-derived on demand from {@code (seed, coord)}
 * through the {@link IGalaxyGenerator} seam (which ships as {@link EmptyGalaxyGenerator} here).</p>
 *
 * <p>A {@link WorldSavedData} on the overworld's global {@code MapStorage} (reachable from any dimension since
 * the overworld is always loaded). Server-side only; the world seed is re-derived on load rather than
 * persisted (it is immutable for a save and is the single source of truth).</p>
 */
public final class UniverseRegistry extends WorldSavedData implements CellFrames {

    /** The persisted identifier == the {@code .dat} filename in the world save. A save-schema constant. */
    public static final String STORAGE_KEY = "advancedrocketry_universe";

    // v3: + durable cell names (derived once, then persisted and never re-derived) and their owning system.
    // v4: + the world-model stamp (schema version + galaxy-config fingerprint).
    private static final int NBT_VERSION = 4;

    /**
      * A save with no world-model stamp: a fresh world, or one written before the stamp existed.
      *
      * <p>Negative on purpose. Version numbers start at ZERO — the alpha — so a sentinel of 0 would have
      * read every alpha world as unstamped and silently re-adopted whatever the build shipped.
      */
    public static final int UNSTAMPED = -1;

    // A self-contained logger rather than AdvancedRocketry.logger: loading the mod class triggers Forge
    // bootstrap (FluidRegistry.enableUniversalBucket), which would break pure unit tests of this registry.
    private static final Logger LOGGER = LogManager.getLogger("AdvancedRocketry|Universe");

    // ─── The override store (persisted) ───────────────────────────────────────
    /** cell key ("sx_sy_sz") -> system star-id. Forward index. */
    private final Map<String, Integer> byCell = new HashMap<>();
    /** system star-id -> its cell-centre coordinate. Reverse index. */
    private final Map<Integer, GalacticCoord> byStar = new HashMap<>();
    /** Authored / player-built POIs (station slots, …) keyed by their OWN cell key. */
    private final Map<String, List<SystemBody>> poiOverrides = new HashMap<>();
    /**
     * Pin-on-touch store (A#1a sub-decision b): a touched PROCEDURAL system's fabricated star + body list,
     * keyed by its anchor cell. A pinned system reads from the save forever after — immune to
     * config/seed/XML edits. Authored systems never pin (they are already in the store).
     */
    private final Map<String, PinnedSystem> pinnedSystems = new HashMap<>();
    /**
     * dimension id -&gt; the body's DURABLE cell name. Written on a body's first derivation and never
     * again, so an address a player wrote down keeps denoting the same thing for the life of the
     * save — through an XML round-trip that quantizes the authored angles, through a spacing change,
     * and through any later edit to the derivation. A cell name is an identifier, like a registry
     * name or an NBT key; it is not a snapshot of where a planet happened to be.
     *
     * <p>The owning system travels with the name because a dimension ID IS RECYCLED
     * ({@code DimensionManager.getNextFreeDim} hands a deleted id straight back), and a name is
     * meaningless outside the system whose neighbourhood it sits in. See {@link #durableName}.</p>
     */
    private final Map<Integer, RecordedName> namesByDim = new HashMap<>();
    /** Latch: authored anchors drain into the store exactly once (unless a config XML reset is forced). */
    private boolean anchorsSeeded = false;
    /**
     * The world model this save was generated under — {@link UniverseSchema#version()}, or
     * {@link #UNSTAMPED} for a world made before the stamp existed.
     *
     * <p>Deliberately SEPARATE from {@link #NBT_VERSION}: that one is the layout of these tags and
     * moves whenever a field is added here, while this one is the identity of the universe those tags
     * describe. A save whose tag layout is a version behind still describes the same sky; a save whose
     * schema is a version behind describes a different one.
     */
    private int schemaVersion = UNSTAMPED;
    /**
     * The fingerprint of the {@code <galaxyGen>} configuration this save was generated under. The
     * schema decides HOW space is derived; these knobs decide the particular universe it derives, and
     * an edit to either one moves every system nobody has touched yet.
     */
    private String configFingerprint = "";
    /**
     * The fingerprint of the LAWS this save was generated under — the metric and the expansion.
     *
     * <p>Kept apart from the configuration's because the two are edited by different people for
     * different reasons: the configuration is the pack author's, the laws are the mod's. Sharing one
     * stamp would let a refusal blame the wrong one, and the remedies are not the same.
     */
    private String lawsFingerprint = "";
    /**
     * Set by an operator's upgrade, consumed by the NEXT load: permission, given once, to accept a
     * {@code <galaxyGen>} that has changed.
     *
     * <p>It exists because the refusal and its remedy cannot both live inside a running server. A
     * fingerprint is one-way, so a save whose configuration has changed cannot be opened under the
     * universe it was made with — the load has to refuse — and a command inside a server that refuses
     * to start cannot be typed. So the acceptance is armed while the world still loads, which is also
     * the only moment crystals can be read and the systems on them frozen, and it is spent at the boot
     * after.
     *
     * <p>Consumed exactly once, and only when the configuration actually differs, so an accidental
     * edit a year later is refused like any other.
     */
    private boolean upgradeArmed = false;

    // ─── Transient, re-derived per load ───────────────────────────────────────
    /** The world seed fed to the generator; set by {@link #bindWorldSeed}, never persisted. */
    private long worldSeed = 0L;
    /**
     * Derived super-cell &rarr; authored/pinned anchor index for member-cell attribution (A#1a). Rebuilt lazily
     * from {@code byStar} — never persisted, so it cannot drift (derive-don't-store).
     */
    private transient Map<String, GalacticCoord> anchorsBySuper = null;
    private transient int anchorsBySuperSpacing = -1;

    // ─── JVM-global seams / staging ───────────────────────────────────────────
    private static volatile IGalaxyGenerator generator = new EmptyGalaxyGenerator();
    // How a stored star-id resolves to its content object. Defaults to the legacy catalogue; overridable so
    // the forward coord->system path is unit-testable without booting DimensionManager, and so an addon can
    // supply fabricated systems.
    private static volatile IntFunction<StellarBody> starLookup = UniverseRegistry::lookupCatalogueStar;
    private static Map<Integer, GalacticAnchor> pendingAnchors = new HashMap<>();
    /**
     * The pack's {@code <galaxyGen>} configuration for this session, staged while dimensions load and
     * paired with the save's schema stamp at {@link #populate}. Null means the pack declares none.
     */
    private static volatile GalaxyGenConfig packGalaxyConfig = null;
    /** The model {@link #populate} put in force for this session; null until it has run. */
    private static volatile UniverseSchema activeSchema = null;
    private static boolean pendingReset = false;

    /**
     * What each authored star was DECLARED as, keyed by star id — the galaxy-local form, kept so the
     * catalogue can be written back in the language it was written in. Never persisted: it is re-read
     * from XML on every load.
     */
    private final Map<Integer, GalacticAnchor> declaredAnchors = new HashMap<>();

    /** How this star was declared, if it was declared at all rather than given a fallback cell. */
    public GalacticAnchor declaredAnchorFor(int starId) {
        return declaredAnchors.get(starId);
    }

    private static StellarBody lookupCatalogueStar(int starId) {
        return DimensionManager.getInstance().getStar(starId);
    }

    public UniverseRegistry() {
        super(STORAGE_KEY);
    }

    public UniverseRegistry(String name) {
        super(name);
    }

    // ─── Accessor (weather idiom: overworld global MapStorage, null-guarded, lazy) ─────────────────────────

    public static UniverseRegistry get(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        WorldServer overworld = net.minecraftforge.common.DimensionManager.getWorld(0);
        if (overworld == null) {
            overworld = server.getWorld(0);
        }
        return get(overworld);
    }

    public static UniverseRegistry get(World world) {
        if (world == null) {
            return null;
        }
        MapStorage storage = world.getMapStorage();
        if (storage == null) {
            return null;
        }
        WorldSavedData existing = storage.getOrLoadData(UniverseRegistry.class, STORAGE_KEY);
        if (existing instanceof UniverseRegistry) {
            return (UniverseRegistry) existing;
        }
        UniverseRegistry fresh = new UniverseRegistry();
        storage.setData(STORAGE_KEY, fresh);
        return fresh;
    }

    // ─── Forward lookups (coord -> system) ─────────────────────────────────────

    /**
     * The system whose NEIGHBOURHOOD contains {@code coord}'s cell (A#1a member semantics): a member cell —
     * a planet's own zone cell, or the void between bodies of one system — resolves to its owning system.
     * Resolution order: pinned &rarr; authored store &rarr; the procedural generator. Empty means void space.
     */
    public Optional<PlanetarySystem> systemForCoord(GalacticCoord coord) {
        Optional<GalacticCoord> anchor = anchorForCell(coord);
        if (!anchor.isPresent()) {
            return Optional.empty();
        }
        return systemAtAnchor(anchor.get());
    }

    /**
     * The ANCHOR cell of the system whose neighbourhood contains {@code coord}'s cell, or empty for void
     * space. This is the member&rarr;anchor attribution every system-semantics read goes through (A#1a
     * sub-decision d): authored/pinned anchors win over the procedural generator inside one super-cell.
     */
    public Optional<GalacticCoord> anchorForCell(GalacticCoord coord) {
        GalacticCoord cell = coord.cellCentre();
        if (byCell.containsKey(cell.cellKey())) {
            return Optional.of(cell);
        }
        GalacticCoord stored = storedAnchorNear(cell);
        if (stored != null) {
            return Optional.of(stored);
        }
        return generator.anchorAt(worldSeed, cell);
    }

    /**
     * The stored anchor whose NEIGHBOURHOOD contains {@code cell}, or {@code null}.
     *
     * <p>A system's neighbourhood is the box CENTRED on its anchor, {@code minSpacing/2} cells to each
     * side — that is where {@code SystemContent} clamps every body, and what {@link IGalaxyGenerator}
     * documents. Attribution has to ask the same question. Looking the cell up in a fixed
     * {@code floorDiv} GRID of super-cells asks a different one, and the two answers differ for every
     * body that sits on the far side of a grid line from its own anchor: the home system's anchor is at
     * sector 0, so the entire negative half of every one of its orbits fell into the neighbouring grid
     * cube and could not be attributed to any system at all. Those bodies then had no
     * {@link #bodiesAt}, no {@link #systemBodiesAt} and no {@link #isSystemKnown} — the console offered
     * addresses with nothing at them, the slot sky drew nothing, and a descent could never fire.</p>
     *
     * <p>The index stays keyed by super-cell for speed; only the QUERY widens, to the 27 super-cells
     * around the cell. It cannot need more: a body is at most {@code minSpacing/2} cells from its
     * anchor, so the anchor is never more than one super-cell away on any axis. Ties (anchors closer
     * together than the spacing guarantee — already warned about when the index is built) go to the
     * nearest, then to the lowest cell key, so attribution is deterministic.</p>
     */
    private GalacticCoord storedAnchorNear(GalacticCoord cell) {
        int s = generator.minSpacingCells();
        long reach = Math.max(1L, s) / 2L;
        Map<String, GalacticCoord> index = anchorsBySuperIndex();
        GalacticCoord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    GalacticCoord candidate = index.get(neighbourSuperKey(cell, s, dx, dy, dz));
                    if (candidate == null || !withinNeighbourhood(cell, candidate, reach)) {
                        continue;
                    }
                    double distance = cell.staticFrameDistanceSqTo(candidate);
                    if (best == null || distance < bestDistance
                            || (distance == bestDistance
                                && candidate.cellKey().compareTo(best.cellKey()) < 0)) {
                        best = candidate;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static boolean withinNeighbourhood(GalacticCoord cell, GalacticCoord anchor, long reach) {
        return Math.abs(cell.sectorX() - anchor.sectorX()) <= reach
                && Math.abs(cell.sectorY() - anchor.sectorY()) <= reach
                && Math.abs(cell.sectorZ() - anchor.sectorZ()) <= reach;
    }

    private static String neighbourSuperKey(GalacticCoord cell, int spacing, int dx, int dy, int dz) {
        long s = Math.max(1, spacing);
        return (Math.floorDiv(cell.sectorX(), s) + dx) + "_"
                + (Math.floorDiv(cell.sectorY(), s) + dy) + "_"
                + (Math.floorDiv(cell.sectorZ(), s) + dz);
    }

    /** The system AT a known anchor cell: pinned content &rarr; catalogued star &rarr; procedural generator. */
    private Optional<PlanetarySystem> systemAtAnchor(GalacticCoord anchor) {
        String key = anchor.cellKey();
        PinnedSystem pinned = pinnedSystems.get(key);
        if (pinned != null) {
            return Optional.of(pinned.toSystem());
        }
        Integer id = byCell.get(key);
        if (id != null) {
            StellarBody star = starLookup.apply(id);
            return star == null ? Optional.<PlanetarySystem>empty()
                    : Optional.of(PlanetarySystem.ofStar(star));
        }
        return generator.systemAt(worldSeed, anchor);
    }

    /** Lazily (re)build the super-cell &rarr; stored-anchor index; invalidated on store change / spacing change. */
    private Map<String, GalacticCoord> anchorsBySuperIndex() {
        int s = generator.minSpacingCells();
        if (anchorsBySuper == null || anchorsBySuperSpacing != s) {
            Map<String, GalacticCoord> index = new HashMap<>();
            List<Integer> ids = new ArrayList<>(byStar.keySet());
            Collections.sort(ids); // deterministic winner on collision
            for (Integer id : ids) {
                GalacticCoord anchor = byStar.get(id);
                String key = superKey(anchor, s);
                GalacticCoord prev = index.get(key);
                if (prev == null) {
                    index.put(key, anchor);
                } else if (!prev.sameCell(anchor)) {
                    LOGGER.warn("authored anchors {} and {} share one {}-cell super-cell — closer than the "
                            + "spacing guarantee; member cells attribute to the first (fix the XML anchors)",
                            prev, anchor, s);
                }
            }
            anchorsBySuper = index;
            anchorsBySuperSpacing = s;
        }
        return anchorsBySuper;
    }

    private static String superKey(GalacticCoord cell, int spacing) {
        long s = Math.max(1, spacing);
        return Math.floorDiv(cell.sectorX(), s) + "_" + Math.floorDiv(cell.sectorY(), s) + "_"
                + Math.floorDiv(cell.sectorZ(), s);
    }

    /** The stored (registered) system's star-id at this cell, or empty. Ignores the procedural generator. */
    /**
     * Every anchor seated in the star TERRITORY {@code cell} falls in — what one look of a survey
     * owes the direction it is pointed in (see {@link IGalaxyGenerator#anchorsInTerritory}).
     *
     * <p>An authored or pinned anchor still wins over the whole territory, exactly as it does in
     * {@link #anchorForCell}: a pack that placed a system there placed THE system there, and a
     * procedural seat in the same cube would be a second answer to a question that has one.</p>
     */
    public List<GalacticCoord> anchorsInTerritory(GalacticCoord cell, int limit) {
        GalacticCoord c = cell.cellCentre();
        if (byCell.containsKey(c.cellKey())) {
            return Collections.singletonList(c);
        }
        GalacticCoord stored = storedAnchorNear(c);
        if (stored != null) {
            return Collections.singletonList(stored);
        }
        return generator.anchorsInTerritory(worldSeed, c, limit);
    }

    public OptionalInt starIdForCoord(GalacticCoord coord) {
        Integer id = byCell.get(coord.cellCentre().cellKey());
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
    }

    public boolean hasSystemAt(GalacticCoord coord) {
        return systemForCoord(coord).isPresent();
    }

    /** {@code true} iff an authored/modified placement (not merely a procedural cell) exists at this cell. */
    public boolean hasOverrideAt(GalacticCoord coord) {
        return byCell.containsKey(coord.cellCentre().cellKey());
    }

    /** Every stored system whose cell falls inside the inclusive sector box, merged over the generator. */
    public Map<GalacticCoord, PlanetarySystem> systemsInRegion(GalacticCoord min, GalacticCoord max) {
        // Normalise the box once (per axis) so the generator and the override scan see the same ordered
        // bounds — a real generator is entitled to assume min <= max.
        GalacticCoord lo = GalacticCoord.ofSectorLocal(
                Math.min(min.sectorX(), max.sectorX()),
                Math.min(min.sectorY(), max.sectorY()),
                Math.min(min.sectorZ(), max.sectorZ()), 0L, 0L, 0L);
        GalacticCoord hi = GalacticCoord.ofSectorLocal(
                Math.max(min.sectorX(), max.sectorX()),
                Math.max(min.sectorY(), max.sectorY()),
                Math.max(min.sectorZ(), max.sectorZ()), 0L, 0L, 0L);
        Map<GalacticCoord, PlanetarySystem> out = new HashMap<>(generator.systemsInRegion(worldSeed, lo, hi));
        for (Map.Entry<Integer, GalacticCoord> e : byStar.entrySet()) {
            GalacticCoord c = e.getValue();
            if (c.sectorX() >= lo.sectorX() && c.sectorX() <= hi.sectorX()
                    && c.sectorY() >= lo.sectorY() && c.sectorY() <= hi.sectorY()
                    && c.sectorZ() >= lo.sectorZ() && c.sectorZ() <= hi.sectorZ()) {
                StellarBody star = starLookup.apply(e.getKey());
                if (star != null) {
                    out.put(c, PlanetarySystem.ofStar(star)); // overrides win over any procedural entry here
                }
            }
        }
        return out;
    }

    // ─── System content (bodies + POIs) ────────────────────────────────────────

    /**
     * The ZONE read (A#1a sub-decision c): the bodies whose own cell IS {@code coord}'s cell — the one
     * body whose orbital zone this cell hosts (or none: an inter-body void cell), any moons sharing the
     * parent's cell, plus the POIs keyed at this cell. Consumers: the descent trigger, the wells query,
     * entry placement. For the whole system, use {@link #systemBodiesAt}.
     */
    public List<SystemBody> bodiesAt(GalacticCoord systemCoord) {
        GalacticCoord cell = systemCoord.cellCentre();
        List<SystemBody> bodies = new ArrayList<>();
        Optional<GalacticCoord> anchor = anchorForCell(cell);
        if (anchor.isPresent()) {
            for (SystemBody b : allSystemBodies(anchor.get())) {
                if (b.name().sameCell(cell)) {
                    bodies.add(b);
                }
            }
        }
        addPoisOf(cell, frameOf(bodies, cell), bodies);
        return bodies;
    }

    /**
     * Append the POIs keyed at {@code cell}, re-bound to {@code frame}.
     *
     * <p>A POI is persisted as a name plus an offset — which frame that cell rides is a property of
     * the CELL and is resolved here. Without the rebinding an orbital station in a planet's cell
     * would keep a static frame while the planet's own cell moved, so the two would drift apart at
     * orbital speed while sharing one address.</p>
     */
    private void addPoisOf(GalacticCoord cell, CellFrame frame, List<SystemBody> out) {
        List<SystemBody> pois = poiOverrides.get(cell.cellCentre().cellKey());
        if (pois == null) {
            return;
        }
        for (SystemBody poi : pois) {
            out.add(poi.withFrame(frame));
        }
    }

    /** The frame the bodies already found at {@code cell} define, or a static one when none does. */
    private static CellFrame frameOf(List<SystemBody> bodiesHere, GalacticCoord cell) {
        for (SystemBody b : bodiesHere) {
            if (b.definesFrame()) {
                return b.frame();
            }
        }
        return CellFrame.staticAt(cell);
    }

    /**
     * The SYSTEM read (A#1a sub-decision c): ALL bodies of the system whose neighbourhood contains
     * {@code coord}'s cell — star, every planet/belt at its own cell, moons — plus the POIs of the member
     * cells that host bodies. Consumers: the nav-GUI body list, the telescope, info tiers. Empty for void
     * space (a void cell's own POIs are readable via {@link #bodiesAt}/{@link #poisAt}).
     */
    public List<SystemBody> systemBodiesAt(GalacticCoord coord) {
        Optional<GalacticCoord> anchor = anchorForCell(coord);
        if (!anchor.isPresent()) {
            return new ArrayList<>();
        }
        List<SystemBody> bodies = allSystemBodies(anchor.get());
        // Aggregate POIs of the anchor + every body cell (deduped) — the member cells that host content.
        List<GalacticCoord> seenCells = new ArrayList<>();
        List<String> seenKeys = new ArrayList<>();
        seenCells.add(anchor.get());
        seenKeys.add(anchor.get().cellKey());
        List<SystemBody> out = new ArrayList<>(bodies);
        for (SystemBody b : bodies) {
            String key = b.name().cellKey();
            if (!seenKeys.contains(key)) {
                seenKeys.add(key);
                seenCells.add(b.name());
            }
        }
        for (GalacticCoord cell : seenCells) {
            addPoisOf(cell, frameOf(bodies, cell), out);
        }
        return out;
    }

    /**
     * What the SKY of a live cell shows — the whole SYSTEM, never just the cell: the system's bodies,
     * unioned with whatever is keyed at the observer's own cell.
     *
     * <p>The union is not tidiness. {@link #systemBodiesAt} answers empty for a cell no anchor
     * attributes, and its POI aggregation covers the member cells that host BODIES only — so a
     * straight swap would erase an orbital station standing in an otherwise-void cell, which is
     * precisely the thing a pilot parked there is looking at. Interstellar void yields the union's
     * empty case, and that emptiness is the point: the space between stars is black.</p>
     */
    public List<SystemBody> skyBodiesAt(GalacticCoord cell) {
        List<SystemBody> out = systemBodiesAt(cell);
        for (SystemBody here : bodiesAt(cell)) {
            if (!out.contains(here)) {
                out.add(here);
            }
        }
        return out;
    }

    /**
     * The full body list of the system anchored at {@code anchor}: pinned &rarr; authored &rarr;
     * generator.
     *
     * <p>No tick: a body carries its own orbital LAW, so the moment is chosen by whoever asks where
     * the body is, not by whoever produced the list. A pinned system is frozen ELEMENTS, never frozen
     * positions — pin-on-touch fires the first time a player builds a station in a system, and a
     * position snapshot would stop that system dead for the rest of the save.</p>
     */
    private List<SystemBody> allSystemBodies(GalacticCoord anchor) {
        String key = anchor.cellKey();
        PinnedSystem pinned = pinnedSystems.get(key);
        if (pinned != null) {
            return new ArrayList<>(pinned.bodies);
        }
        Integer id = byCell.get(key);
        if (id != null) {
            StellarBody star = starLookup.apply(id);
            if (star == null) {
                return new ArrayList<SystemBody>();
            }
            List<SystemBody> authored = SystemContent.bodiesOf(star, anchor,
                    generator.minSpacingCells(), this::durableName);
            return withDerivedRetinue(anchor, star, id, authored);
        }
        return new ArrayList<>(generator.bodiesFor(worldSeed, anchor));
    }

    /**
     * The recorded cell name for a dimension, recording this derivation the first time one is asked
     * for. Once written a name is never re-derived: the whole point is that it stops depending on
     * anything that can change — the world time it used to be derived from, the precision of the
     * authored angles as they round-trip through XML, or a later edit to the derivation itself.
     *
     * <p>Bodies with no dimension of their own — the star proxy, belts, POIs — carry
     * {@link Constants#INVALID_PLANET} and share it, so there is no identity to key a name on; they
     * keep the derivation, which for them is already time-invariant.</p>
     *
     * <p><b>A recorded name has a lifecycle, and both of its ends are load-bearing.</b></p>
     * <ul>
     *   <li>It is only valid for the system it was derived in. A dimension id is RECYCLED — deleting
     *       a planet frees its id and the next generated body is handed it back — so a name kept on
     *       the id alone lets a brand-new world in one system inherit a cell in another. Nothing
     *       downstream can see that: the two bodies belong to different anchors, so no per-system
     *       audit compares them. The owning star id is therefore recorded with the name and checked
     *       here.</li>
     *   <li>It must still lie inside its system's neighbourhood box. Containment is what makes
     *       member&rarr;anchor attribution work ({@link #withinNeighbourhood}); a name outside the
     *       box attributes to nothing, so its body is listed by the console, jumpable, and impossible
     *       to arrive at. Moving a star's anchor or shrinking {@code minSpacing} does exactly that to
     *       every name already recorded under the old layout.</li>
     * </ul>
     * <p>Either failure is REPORTED and the name re-derived, which is the only outcome that leaves
     * the body reachable. A name that cannot be served is not a name.</p>
     */
    private GalacticCoord durableName(int dimId, int starId, GalacticCoord anchor, int minSpacingCells,
                                      GalacticCoord derived) {
        if (dimId == Constants.INVALID_PLANET || derived == null) {
            return derived;
        }
        RecordedName recorded = namesByDim.get(dimId);
        if (recorded != null) {
            if (recorded.starId != starId) {
                if (SystemContent.reportOnce("nameReused:" + dimId + ':' + recorded.starId + "->" + starId)) {
                    LOGGER.error("dimension id {} carries a cell name recorded for system {} but now "
                            + "belongs to system {} - the id was recycled. Re-deriving its name as {} "
                            + "(the stale one would have put this body in another system's "
                            + "neighbourhood, where nothing would ever audit the collision).",
                            dimId, recorded.starId, starId, derived.cellKey());
                }
            } else if (!SystemContent.withinBoxOf(recorded.name, anchor, minSpacingCells)) {
                if (SystemContent.reportOnce("nameEscaped:" + dimId + ':' + recorded.name.cellKey())) {
                    LOGGER.error("recorded cell name {} of dim {} is no longer inside system {}'s "
                            + "neighbourhood (anchor {}, spacing {}) - the anchor or the spacing moved "
                            + "under it. A name outside its own box attributes to no system: the body "
                            + "would stay listed and jumpable but impossible to arrive at. Re-deriving "
                            + "as {}; the address that was written down for it no longer denotes it.",
                            recorded.name.cellKey(), dimId, starId, anchor.cellKey(), minSpacingCells,
                            derived.cellKey());
                }
            } else {
                return recorded.name;
            }
        }
        namesByDim.put(dimId, new RecordedName(derived, starId));
        markDirty();
        return derived;
    }

    /** The recorded cell name for {@code dimId}, or empty when nothing has derived one yet. */
    public Optional<GalacticCoord> recordedName(int dimId) {
        RecordedName recorded = namesByDim.get(dimId);
        return recorded == null ? Optional.<GalacticCoord>empty() : Optional.of(recorded.name);
    }

    /**
     * Drop the recorded name of a dimension that no longer exists. Called when a dimension is
     * deleted, because its id goes straight back into circulation: without this the next body handed
     * that id silently inherits a cell name derived for a world that is gone. Returns whether one was
     * held.
     */
    public boolean forgetName(int dimId) {
        if (namesByDim.remove(dimId) == null) {
            return false;
        }
        markDirty();
        return true;
    }

    /**
     * Server-side convenience for the dimension lifecycle: forget {@code dimId}'s recorded name on
     * whatever registry is reachable. A no-op with no server (a client, a unit test).
     */
    /**
     * The bodies standing in {@code cell}, resolved through the running server's registry — for
     * callers that hold an ADDRESS and no way to reach a registry, which is most of the space layer's
     * entry path. An empty list when there is no server, no registry, or nothing there.
     */
    public static List<SystemBody> bodiesAtOnServer(GalacticCoord cell) {
        if (cell == null) {
            return Collections.emptyList();
        }
        UniverseRegistry reg;
        try {
            reg = get(net.minecraftforge.fml.common.FMLCommonHandler.instance()
                    .getMinecraftServerInstance());
        } catch (Throwable noServer) {
            // No Forge bootstrap at all — a pure unit context. "There is no server, so there is
            // nothing standing in that cell" is the honest answer here and the caller's own fallback
            // (the flat ring) is the right behaviour, so this is not swallowed error handling.
            return Collections.emptyList();
        }
        return (reg == null) ? Collections.<SystemBody>emptyList() : reg.systemBodiesAt(cell);
    }

    public static void forgetNameOnServer(int dimId) {
        UniverseRegistry reg = get(net.minecraftforge.fml.common.FMLCommonHandler.instance()
                .getMinecraftServerInstance());
        if (reg != null) {
            reg.forgetName(dimId);
        }
    }

    // ─── Frames: where a cell IS at a stated tick ──────────────────────────────

    /**
     * The absolute position of the frame origin of the cell NAMED by {@code name}, at {@code tick} —
     * the position of that cell's PRIMARY. A cell with no primary is void and its origin is the
     * static {@code sector * CELL}, which is also the answer when the registry cannot attribute the
     * cell to any system at all.
     */
    @Override
    public AbsolutePos originAt(GalacticCoord name, long tick) {
        if (name == null) {
            return AbsolutePos.ORIGIN;
        }
        for (SystemBody b : bodiesAt(name)) {
            if (b.definesFrame()) {
                return b.frame().originAt(tick);
            }
        }
        return AbsolutePos.ofCellName(name);
    }

    /** A recorded cell name plus the system it was recorded for. See {@link #durableName}. */
    private static final class RecordedName {
        final GalacticCoord name;
        final int starId;

        RecordedName(GalacticCoord name, int starId) {
            this.name = name;
            this.starId = starId;
        }
    }

    /**
     * Add an authored/player POI, keyed by its OWN cell (the sector of its address). A POI is a TOUCH: the
     * owning procedural system (if any) is pinned first, so the POI's surroundings can never drift away
     * from under it (A#1a pin-on-touch).
     */
    public void addPoi(SystemBody poi) {
        pinSystem(poi.name());
        String key = poi.name().cellKey();
        List<SystemBody> list = poiOverrides.get(key);
        if (list == null) {
            list = new ArrayList<>();
            poiOverrides.put(key, list);
        }
        list.add(poi);
        markDirty();
    }

    /**
     * Pin the PROCEDURAL system whose neighbourhood contains {@code coord} into the persisted override
     * store (A#1a sub-decision b, pin-on-touch): its fabricated star + full body list are snapshotted, so a
     * later config/seed/XML change cannot move or reshape a system the player has touched. Authored or
     * already-pinned systems are a no-op. Returns whether a pin was written.
     */
    public boolean pinSystem(GalacticCoord coord) {
        Optional<GalacticCoord> anchorOpt = anchorForCell(coord);
        if (!anchorOpt.isPresent()) {
            return false;
        }
        GalacticCoord anchor = anchorOpt.get();
        String key = anchor.cellKey();
        if (byCell.containsKey(key)) {
            return false; // authored, or pinned already (pin places into byCell below)
        }
        Optional<PlanetarySystem> sys = generator.systemAt(worldSeed, anchor);
        if (!sys.isPresent()) {
            return false;
        }
        List<SystemBody> bodies = new ArrayList<>(generator.bodiesFor(worldSeed, anchor));
        place(anchor, sys.get().systemId());
        // A star's temperature and size are frozen HERE, because they are drawn values that a later
        // seed or config edit would otherwise move under the planets already derived from them. A
        // system with no star has neither, and freezing a zero for each would be inventing two
        // properties it does not have — its primary's physics is derived from the cell like any
        // other body's, and the cell is what the pin is keyed by.
        PlanetarySystem system = sys.get();
        PinnedSystem snapshot = system.star().isPresent()
                ? PinnedSystem.ofStar(system.systemId(), system.star().get(), bodies)
                : PinnedSystem.ofRogue(system.systemId(), system.name(), bodies);
        pinnedSystems.put(key, snapshot);
        markDirty();
        return true;
    }

    /**
     * The star of the system whose neighbourhood contains {@code coord} — pinned snapshot, catalogue
     * entry, or the generator's fabrication, in that order.
     *
     * <p>The pin comes FIRST and that ordering is the point: a touched procedural system's star is
     * frozen in the save, so a later seed or config edit cannot warm it up under the planets that were
     * derived from it. Realization needs this to materialize a body's physics, and the star it uses must
     * be the one the scan already described.</p>
     *
     * <p>Empty means two different things and a caller has to tell them apart: there is no system here
     * at all, or there IS one and its primary is not a star (a rogue world out in the void). Ask
     * {@link #systemForCoord} when the difference matters.</p>
     */
    public Optional<StellarBody> starAt(GalacticCoord coord) {
        Optional<GalacticCoord> anchorOpt = anchorForCell(coord);
        if (!anchorOpt.isPresent()) {
            return Optional.empty();
        }
        GalacticCoord anchor = anchorOpt.get();
        PinnedSystem pinned = pinnedSystems.get(anchor.cellKey());
        if (pinned != null) {
            return pinned.toSystem().star();
        }
        Integer id = byCell.get(anchor.cellKey());
        if (id != null) {
            return Optional.ofNullable(starLookup.apply(id));
        }
        Optional<PlanetarySystem> sys = generator.systemAt(worldSeed, anchor);
        return sys.isPresent() ? sys.get().star() : Optional.<StellarBody>empty();
    }

    /**
     * Attach a realized dimension to the pinned body standing at {@code bodyCell}, and record that
     * cell as the dimension's durable NAME. Returns whether a body was rewritten.
     *
     * <p>Only a PINNED system can be rewritten, and that is not a limitation but the mechanism: a body
     * is pinned the moment anything touches it, so by the time a descent asks for a dimension the
     * snapshot it is being written into already exists. Rewriting a derived body would be writing into
     * a list that is regenerated on the next query.</p>
     *
     * <p>Idempotent by construction — a body that already carries this dimension is left exactly as it
     * is, so a second descent into the same cell reuses the world rather than minting another.</p>
     */
    public boolean realizeBody(GalacticCoord bodyCell, int variant, int dimId) {
        Optional<GalacticCoord> anchorOpt = anchorForCell(bodyCell);
        if (!anchorOpt.isPresent()) {
            return false;
        }
        PinnedSystem pinned = pinnedSystems.get(anchorOpt.get().cellKey());
        if (pinned == null) {
            return false;
        }
        GalacticCoord cell = bodyCell.cellCentre();
        int seen = -1;
        for (int i = 0; i < pinned.bodies.size(); i++) {
            SystemBody body = pinned.bodies.get(i);
            if (!body.name().sameCell(cell) || !isRealizableKind(body)) {
                continue;
            }
            // Counted the same way realizableBodiesAt counts, so a variant means one body and not a
            // family: writing into "the first free one" is what let a moon inherit its planet.
            seen++;
            if (seen != variant) {
                continue;
            }
            if (body.dimId() == dimId) {
                return true;
            }
            if (body.dimId() != Constants.INVALID_PLANET) {
                return false; // this body already holds a different world
            }
            pinned.bodies.set(i, body.withDimId(dimId));
            namesByDim.put(dimId, new RecordedName(cell, pinned.starId));
            markDirty();
            return true;
        }
        return false;
    }

    /**
     * The bodies of {@code bodyCell} that a descent could ever mint a world for, in the order the
     * generator produced them.
     *
     * <p><b>A cell holds more than one world, and this is the list that says which.</b> A moon is
     * built in its PARENT's cell so that a planet and its moons travel as one destination, so
     * "the body at this cell" names a family rather than an object. A body's index in THIS list is
     * its {@code variant} - the same number the derivation is keyed on - and it is the only identity
     * a body has inside its cell.</p>
     *
     * <p>Stars, station slots and belts are not in it: nothing descends onto them, and counting them
     * would shift every variant by one and silently materialize the wrong world.</p>
     */
    public List<SystemBody> realizableBodiesAt(GalacticCoord bodyCell) {
        List<SystemBody> out = new ArrayList<>();
        for (SystemBody body : bodiesAt(bodyCell)) {
            if (isRealizableKind(body)) {
                out.add(body);
            }
        }
        return out;
    }

    /** Whether a descent could mint a world for a body of this kind. See {@link #realizableBodiesAt}. */
    private static boolean isRealizableKind(SystemBody body) {
        return body.kind() != SystemBodyKind.STAR
                && body.kind() != SystemBodyKind.STATION_SLOT
                && body.kind() != SystemBodyKind.ASTEROID_BELT;
    }

    /**
     * Which body of its cell {@code body} is - its {@code variant} - or empty if the cell does not
     * hold it.
     *
     * <p>Matched by ADDRESS, KIND and ORBIT rather than by object identity: a caller holds a body it
     * got from a derived list, while the pinned snapshot holds another instance of the same body,
     * and a realized one differs from both by carrying a dimension.</p>
     */
    public OptionalInt variantOf(SystemBody body) {
        if (body == null) {
            return OptionalInt.empty();
        }
        List<SystemBody> family = realizableBodiesAt(body.name());
        for (int i = 0; i < family.size(); i++) {
            SystemBody candidate = family.get(i);
            if (candidate.kind() == body.kind()
                    && candidate.orbitalDistance() == body.orbitalDistance()
                    && candidate.name().sameCell(body.name())) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * The realized dimension of a PARTICULAR body of {@code bodyCell}, named by its variant.
     *
     * <p>It used to answer for the first realized body of the cell, whatever was asked - so once a
     * planet had a world, every one of its moons answered with the planet's, and a descent aimed at a
     * moon put the ship on the planet instead.</p>
     */
    public OptionalInt realizedDimAt(GalacticCoord bodyCell, int variant) {
        List<SystemBody> family = realizableBodiesAt(bodyCell);
        if (variant < 0 || variant >= family.size()) {
            return OptionalInt.empty();
        }
        SystemBody body = family.get(variant);
        return body.dimId() == Constants.INVALID_PLANET
                ? OptionalInt.empty()
                : OptionalInt.of(body.dimId());
    }

    /** The POIs at a system's cell (a copy), excluding the derived star/planet/moon bodies. */
    public List<SystemBody> poisAt(GalacticCoord systemCoord) {
        List<SystemBody> list = poiOverrides.get(systemCoord.cellCentre().cellKey());
        return list == null ? Collections.<SystemBody>emptyList() : new ArrayList<>(list);
    }

    /** Drop every POI at a system's cell. Returns whether any existed. */
    public boolean removePois(GalacticCoord systemCoord) {
        boolean removed = poiOverrides.remove(systemCoord.cellCentre().cellKey()) != null;
        if (removed) {
            markDirty();
        }
        return removed;
    }

    // ─── Reverse lookups (system / planet -> coord) ────────────────────────────

    public Optional<GalacticCoord> coordForSystem(int starId) {
        return Optional.ofNullable(byStar.get(starId));
    }

    public Optional<GalacticCoord> coordForStar(StellarBody star) {
        return star == null ? Optional.<GalacticCoord>empty() : coordForSystem(star.getId());
    }

    /**
     * The galactic coordinate of a planet/moon/star-proxy dimension. This is the planet&rarr;coord seam the
     * tier-2 entry/descent handlers use. Per A#1a this is the body's OWN cell — a planet resolves to its
     * zone cell (NOT the system anchor), a moon to its parent planet's cell (moons are local), a star-proxy
     * dim to the system's anchor. Falls back to the anchor when the body is not derivable.
     */
    public Optional<GalacticCoord> coordForPlanet(DimensionProperties props) {
        if (props == null) {
            return Optional.empty();
        }
        if (props.isStar()) {
            return coordForSystem(props.getId() - Constants.STAR_ID_OFFSET);
        }
        Optional<GalacticCoord> anchor = coordForSystem(props.getStarId());
        if (!anchor.isPresent() && props.isMoon()) {
            DimensionProperties parent = props.getParentProperties();
            if (parent != null) {
                anchor = coordForSystem(parent.getStarId());
            }
        }
        if (!anchor.isPresent()) {
            return Optional.empty();
        }
        for (SystemBody body : allSystemBodies(anchor.get())) {
            if (body.dimId() == props.getId()) {
                return Optional.of(body.name()); // the body's OWN cell (moon: the parent's)
            }
        }
        return anchor; // body not derivable from content — lenient anchor fallback
    }

    /**
     * The FULL address of a body at world tick {@code atTick} — its cell AND its position inside that
     * cell — or empty when the body is not derivable from its system's content.
     *
     * <p>This is what a jump AIMS at, and it is deliberately not {@link #coordForPlanet}. That answers
     * "which cell is this body in", snapped to the cell centre, which is the right answer for
     * attribution, for the home-cell skip and for anything that compares cell keys. It is the wrong
     * answer for flying: a moon shares its parent's cell but sits tens of thousands of blocks off its
     * centre, so a ship aimed at the cell arrives at the PARENT and is left short of the moon by ~50
     * descent radii — it can never put down on the body the pilot actually chose. A body target aims
     * at the body.</p>
     *
     * <p>Empty rather than the lenient anchor fallback {@link #coordForPlanet} makes: aiming a ship at
     * a system's star because its planet could not be resolved is exactly the silent
     * flown-somewhere-else failure this exists to prevent. The caller surfaces it instead.</p>
     */
    public Optional<GalacticCoord> addressForPlanet(DimensionProperties props, long atTick) {
        if (props == null || props.isStar()) {
            return props == null ? Optional.<GalacticCoord>empty()
                    : coordForSystem(props.getId() - Constants.STAR_ID_OFFSET);
        }
        Optional<GalacticCoord> anchor = coordForSystem(props.getStarId());
        if (!anchor.isPresent() && props.isMoon()) {
            DimensionProperties parent = props.getParentProperties();
            if (parent != null) {
                anchor = coordForSystem(parent.getStarId());
            }
        }
        if (!anchor.isPresent()) {
            return Optional.empty();
        }
        for (SystemBody body : allSystemBodies(anchor.get())) {
            if (body.dimId() == props.getId()) {
                return Optional.of(body.addressAt(atTick));
            }
        }
        return Optional.empty();
    }

    /** Server-side convenience: resolves the dimension via {@link DimensionManager} then delegates. */
    public Optional<GalacticCoord> coordForPlanet(int dimId) {
        if (dimId >= Constants.STAR_ID_OFFSET) {
            return coordForSystem(dimId - Constants.STAR_ID_OFFSET);
        }
        return coordForPlanet(DimensionManager.getInstance().getDimensionProperties(dimId));
    }

    /**
     * How much the diffuse matter between two cells dims what is behind it, in <b>magnitudes of
     * visual extinction</b> — the unit astronomy states dust in.
     *
     * <p>Zero in clear space, and zero for a universe with no clusters. What the number MEANS:
     * ~1 is noticeable dimming, ~5 is where faint things behind a cloud disappear, ~10 is opaque in
     * the visible. The calibration from this model's density to magnitudes lives on
     * {@link Nebula#MAGNITUDES_PER_DENSITY_LIGHT_YEAR} with its anchor written out; what a given
     * mechanic does at a given number of magnitudes is that mechanic's own (tunable) business.</p>
     */
    public double extinctionBetween(GalacticCoord from, GalacticCoord to) {
        return Nebula.magnitudesForColumn(generator.columnDensityBetween(worldSeed, from, to));
    }

    /**
     * Whether the system at {@code coord} is known. DERIVED, never stored: a system is known iff any of its
     * member bodies with a real dimension is in the global known set ({@link DimensionManager#isPlanetKnown}).
     * Non-dimension bodies (the star proxy, belts) carry {@link Constants#INVALID_PLANET} and are excluded.
     * Procedural (synthetic-negative-id) systems have no dimensioned bodies, so they are never known until a
     * body is discovered. Graded-discovery axis-E, universe half.
     */
    public boolean isSystemKnown(GalacticCoord coord) {
        // SYSTEM semantics (A#1a sub-decision d): resolve from ANY member cell — a ship parked in a
        // planet's zone is "in a known system" iff the system is known, not iff its own cell is the anchor.
        for (SystemBody body : systemBodiesAt(coord)) {
            if (body.dimId() != Constants.INVALID_PLANET
                    && DimensionManager.getInstance().isPlanetKnown(body.dimId())) {
                return true;
            }
        }
        return false;
    }

    // ─── Mutators ──────────────────────────────────────────────────────────────

    /**
     * Upsert a placement, snapped to {@code coord}'s cell centre. Enforces one-coord-per-system (a re-place
     * moves the system, freeing its old cell) and one-system-per-cell (a colliding star is displaced with a
     * warning — a duplicate-coord authoring mistake).
     */
    public void place(GalacticCoord coord, int starId) {
        GalacticCoord cell = coord.cellCentre();
        String key = cell.cellKey();

        GalacticCoord prev = byStar.get(starId);
        if (prev != null && !prev.cellKey().equals(key)) {
            byCell.remove(prev.cellKey());
        }
        Integer occupant = byCell.get(key);
        if (occupant != null && occupant.intValue() != starId) {
            LOGGER.warn("cell {} already held system {}, reassigning to {} (duplicate galactic coordinate?)",
                    key, occupant, starId);
            byStar.remove(occupant);
        }
        byCell.put(key, starId);
        byStar.put(starId, cell);
        anchorsBySuper = null; // derived index follows the store
        markDirty();
    }

    /** Remove the placement at a cell (and any pinned content snapshot). Returns whether one existed. */
    public boolean remove(GalacticCoord coord) {
        String key = coord.cellCentre().cellKey();
        Integer id = byCell.remove(key);
        if (id == null) {
            return false;
        }
        byStar.remove(id);
        pinnedSystems.remove(key);
        anchorsBySuper = null;
        markDirty();
        return true;
    }

    // ─── Population lifecycle ──────────────────────────────────────────────────

    /**
     * Drain authored anchors into the store, once. On a fresh world this places every anchor; on a restart
     * (anchors already seeded) it is a no-op so the persisted store — including player edits — wins. A config
     * XML reset ({@code reset == true}) forces re-application.
     *
     * <p>A reset re-places ANCHORS and deliberately leaves the recorded body cell NAMES alone. Those
     * are what makes a written-down coordinate keep denoting its body; clearing them here would mean
     * exactly the guarantee the store exists to give fails in the one case it is needed most.</p>
     */
    public void applyAnchors(Map<Integer, GalacticAnchor> anchors, boolean reset) {
        // Remembered BEFORE the seeded early-return: the declaration is what the catalogue gets
        // written back as, and on a restart the anchors are already placed while the XML still has to
        // round-trip. It is re-read from XML on every load, which is exactly its lifetime.
        declaredAnchors.clear();
        if (anchors != null) {
            declaredAnchors.putAll(anchors);
        }
        if (anchorsSeeded && !reset) {
            return;
        }
        if (anchors != null) {
            List<Integer> ids = new ArrayList<>(anchors.keySet());
            Collections.sort(ids);
            for (Integer id : ids) {
                GalacticAnchor anchor = anchors.get(id);
                if (anchor == null) {
                    continue;
                }
                place(resolveAnchor(anchor, id), id);
            }
        }
        anchorsSeeded = true;
        markDirty();
    }

    /**
     * Turn a galaxy-local declaration into the absolute cell name everything downstream uses. Done
     * ONCE, here, at the reference angle — afterwards the authored system is named by a cell exactly
     * like a procedural one and rotates with its galaxy exactly like one.
     *
     * <p>An anchor reaching past the radius its galaxy is GUARANTEED is a loud error and never a
     * silent clamp: beyond that wall the position is valid on some seeds and intergalactic on others,
     * and a pack author has to learn that from a log line rather than from a player's bug report.</p>
     */
    private GalacticCoord resolveAnchor(GalacticAnchor anchor, int starId) {
        double guaranteed = generator.guaranteedAuthoredReachLy();
        if (guaranteed > 0d && anchor.reachLy() > guaranteed) {
            LOGGER.error("star " + starId + " is authored at " + anchor
                    + ", which is " + (long) anchor.reachLy() + " light years from its galaxy's centre"
                    + " against a guaranteed radius of " + (long) guaranteed + ". On a seed whose"
                    + " galaxy comes out smaller than that, this system will sit in intergalactic"
                    + " space. Move it inside the guaranteed radius.");
        }
        return anchor.resolve(generator.declarationOriginOf(worldSeed, anchor.galaxy()));
    }

    /**
     * Give every catalogued star that still lacks a placement a deterministic fallback cell, so
     * planet&rarr;coord is total over the legacy galaxy. Sol (id 0) defaults to the origin; others take the
     * first free cell walking out along +X from {@code sector(id,0,0)}, so a fallback never displaces an
     * authored anchor.
     */
    public void assignFallbackCoords(Collection<StellarBody> stars) {
        List<Integer> ids = new ArrayList<>();
        for (StellarBody s : stars) {
            ids.add(s.getId());
        }
        Collections.sort(ids);
        for (Integer id : ids) {
            if (byStar.containsKey(id)) {
                continue;
            }
            GalacticCoord c = fallbackCell(id);
            while (byCell.containsKey(c.cellKey())) {
                c = c.plusLocal(GalacticCoord.CELL, 0L, 0L);
            }
            place(c, id);
        }
    }

    private static GalacticCoord fallbackCell(int starId) {
        if (starId == 0) {
            return GalacticCoord.ORIGIN; // Sol
        }
        // Stride fallback anchors one DEFAULT super-cell apart (A#1a): each legacy star's per-body-cell
        // neighbourhood gets its own super-cell, keeping member attribution exact for the fallback galaxy.
        return GalacticCoord.ofSectorLocal((long) starId * GalaxyGenConfig.DEFAULT_MIN_SPACING, 0L, 0L,
                0L, 0L, 0L);
    }

    public void bindWorldSeed(long seed) {
        this.worldSeed = seed;
    }

    public long worldSeed() {
        return worldSeed;
    }

    // ─── The world-model stamp (schema version + config fingerprint) ───────────

    /** The world model this save was generated under, or {@link #UNSTAMPED}. */
    public int schemaVersion() {
        return schemaVersion;
    }

    /**
     * The world model in force, or empty before {@link #populate} has resolved one.
     *
     * <p>What a caller usually wants this for is {@link UniverseSchema#isStable()} — whether the world
     * it is about to touch was generated by an ALPHA model that may be replaced rather than carried
     * forward.
     */
    public static Optional<UniverseSchema> activeSchema() {
        return Optional.ofNullable(activeSchema);
    }

    /** How many procedural systems this save has frozen — what an upgrade would carry over untouched. */
    public int pinnedSystemCount() {
        return pinnedSystems.size();
    }

    /** The galaxy-config fingerprint this save was generated under; empty when unstamped. */
    public String configFingerprint() {
        return configFingerprint;
    }

    /** The laws fingerprint (metric + expansion) this save was generated under; empty when unstamped. */
    public String lawsFingerprint() {
        return lawsFingerprint;
    }

    /**
     * The identity of a set of laws, taken by MEASURING them rather than by listing their constants.
     *
     * <p>Fixed inputs through every conversion, plus the expansion at fixed ticks, hashed. Two reasons
     * it is done this way. It works for any implementation, so a schema version 2 with its own metric
     * needs no fingerprinting code of its own. And it catches what a declaration cannot: an
     * implementation whose internal constant moved while whatever list it publishes stayed the same.
     */
    public static String lawsFingerprintOf(IUniverseLaws laws) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("laws1;");
        double[] lightYears = {0.1d, 1d, 4.23d, 100d, 50_000d};
        for (double ly : lightYears) {
            sb.append(laws.cellsForLightYears(ly)).append(',').append(laws.cellsAt(ly)).append(';');
        }
        long[] cells = {1L, 1_000_000L, 5_002_361L};
        for (long c : cells) {
            sb.append(Fingerprint.bits(laws.lightYearsForCells(c))).append(',')
                    .append(Fingerprint.bits(laws.orbitUnitsForCells(c))).append(',')
                    .append(laws.seatMarginCells(c)).append(';');
        }
        sb.append(Fingerprint.bits(laws.lightYearsPerTick(1d))).append(';');
        sb.append(laws.cellsForOrbitUnits(1d)).append(';');
        sb.append(Fingerprint.bits(laws.retinueReachLy(1d))).append(';');
        for (long tick : new long[]{0L, 24_000L, 24_000_000L}) {
            sb.append(Fingerprint.bits(laws.scaleFactorAt(tick))).append(';');
        }
        sb.append(laws.driftHorizonTicks());
        return Fingerprint.hex16(sb.toString());
    }

    /** What THIS build's newest schema measures with — what a fresh world is stamped against. */
    public static String currentLawsFingerprint() {
        return lawsFingerprintOf(UniverseSchemas.current().laws());
    }

    /**
     * Decide which world model this save must be read under, and stamp it if it has none yet.
     *
     * <p><b>The version comes from the SAVE, never from the pack.</b> That inversion is the whole
     * mechanism: a world generated under schema 1 keeps being derived by schema 1 after the mod ships
     * schema 2, so the mod is free to move — new mechanics, new blocks, new balance — while the sky a
     * player has already charted stays where he charted it. Only {@code upgrade} moves a world.
     *
     * <p><b>Two refusals, and both are recoverable from outside the game.</b> A stamp naming a version
     * this build does not carry (a world from a newer jar, or one whose version was dropped) and a
     * configuration that has been edited since the world was made. Neither can be honoured by
     * substituting something close: continuing would answer a different universe under an unchanged
     * save, and the player would find out by flying somewhere his notes describe.
     *
     * <p>Note that a pack edit which merely ADDS — one more authored anchor naming a new galaxy, one
     * more star archetype — changes the fingerprint like any other, and that is correct rather than
     * strict: a reserved galaxy is a galaxy forced into a cell that had its own contents, and one more
     * weight moves every draw that walks the table.
     *
     * @param config the pack's {@code <galaxyGen>} configuration, or {@code null} for an
     *               authored-anchors-only universe
     * @return the schema to install for this world
     * @throws UniverseSchemaMismatchException when the save cannot be honoured by this build
     */
    public UniverseSchema reconcileSchema(GalaxyGenConfig config) {
        String fingerprint = fingerprintOf(config);
        if (schemaVersion == UNSTAMPED) {
            UniverseSchema schema = UniverseSchemas.current();
            if (!byCell.isEmpty() || !pinnedSystems.isEmpty()) {
                // A world with content but no stamp predates the stamp. Nothing records what generated
                // it, so adopting the current model is the only move available — said out loud, because
                // it is the one case where this class cannot prove the sky is unchanged.
                LOGGER.warn("Universe save carries content but no world-model stamp; adopting schema {} "
                        + "and configuration {}. If this world was generated by a different build, its "
                        + "untouched systems may have moved.", schema.version(), fingerprint);
            }
            stampSchema(schema.version(), fingerprint);
            return schema;
        }
        Optional<UniverseSchema> saved = UniverseSchemas.of(schemaVersion);
        if (!saved.isPresent()) {
            throw new UniverseSchemaMismatchException(
                    "This world was generated under universe schema " + schemaVersion
                            + ", which this build does not carry (it has " + UniverseSchemas.released()
                            + "). Install a build that carries schema " + schemaVersion
                            + " to open this world.");
        }
        // Measured against the laws of the schema THIS SAVE is owed, not the newest ones. A build that
        // ships a new metric ships it as a new schema version, and this world simply keeps using its own
        // — which is why a mismatch here does not mean "the mod moved on". It means schema
        // %d's laws in this jar are not the ones that made this world, i.e. a released version was
        // edited in place. That is a developer error, and there is nothing a player or an operator can
        // do about it, so it is not something an upgrade may accept.
        String laws = lawsFingerprintOf(saved.get().laws());
        if (!lawsFingerprint.isEmpty() && !lawsFingerprint.equals(laws)) {
            throw new UniverseSchemaMismatchException(
                    "Universe schema " + schemaVersion + " in this build does not measure the way it did "
                            + "when this world was generated: the world was made under laws "
                            + lawsFingerprint + " and this build's schema " + schemaVersion + " states "
                            + laws + ". A released schema's metric and expansion may never change — a "
                            + "changed metric ships as a NEW schema version, which old worlds simply do "
                            + "not use. This build is broken; install one whose schema " + schemaVersion
                            + " is intact.");
        }
        if (!configFingerprint.equals(fingerprint)) {
            if (upgradeArmed) {
                // Permission was given last session, by an operator, on a world that was still loading
                // — which is when the crystals could be read and their systems frozen. Spend it.
                LOGGER.warn("Accepting the changed <galaxyGen> for this world: {} -> {}. This was armed "
                        + "by an operator's upgrade. Systems already frozen keep exactly what they held; "
                        + "everything else is re-derived from here.", configFingerprint, fingerprint);
                upgradeArmed = false;
                stampSchema(UniverseSchemas.CURRENT, fingerprint);
                return UniverseSchemas.current();
            }
            throw new UniverseSchemaMismatchException(
                    "The <galaxyGen> configuration has changed since this world was generated: it was "
                            + "made under " + configFingerprint + " and this pack states " + fingerprint
                            + ". Every system nobody has visited yet would move, so this world will not "
                            + "open under it. "
                            + "To go back: restore the previous <galaxyGen> and start again. "
                            + "To accept the change: restore the previous <galaxyGen>, start, run "
                            + "\"/stellurgy universe upgrade confirm\" (that freezes every system anyone "
                            + "has seen, including the addresses on the memory crystals of players who "
                            + "are online), stop, put the new configuration back, and start again.");
        }
        return saved.get();
    }

    /**
     * Accept {@code config} (and the current schema) as this world's model from now on — the write half
     * of the upgrade, after everything already seen has been pinned.
     *
     * @return the schema now in force
     */
    /**
     * Whether this world is holding an operator's one-shot permission to accept a changed
     * {@code <galaxyGen>} at its next load.
     */
    public boolean isUpgradeArmed() {
        return upgradeArmed;
    }

    /**
     * Give that permission — the half of an upgrade that a running server can perform for a change it
     * cannot see yet. It is spent by the next load, and only if the configuration has actually moved.
     */
    public void armUpgrade() {
        if (!upgradeArmed) {
            upgradeArmed = true;
            markDirty();
        }
    }

    public UniverseSchema adoptSchema(GalaxyGenConfig config) {
        UniverseSchema schema = UniverseSchemas.current();
        stampSchema(schema.version(), fingerprintOf(config));
        return schema;
    }

    /** The fingerprint a {@code null} (authored-anchors-only) configuration has its own name for. */
    public static String fingerprintOf(GalaxyGenConfig config) {
        return (config == null) ? GalaxyGenConfig.noGeneratorFingerprint() : config.fingerprint();
    }

    private void stampSchema(int version, String fingerprint) {
        String laws = currentLawsFingerprint();
        if (schemaVersion == version && configFingerprint.equals(fingerprint)
                && lawsFingerprint.equals(laws)) {
            return;
        }
        schemaVersion = version;
        configFingerprint = fingerprint;
        lawsFingerprint = laws;
        markDirty();
    }

    // ─── Static staging + population (server lifecycle) ────────────────────────

    /**
     * Buffer XML-authored anchor coords parsed during {@code createAndLoadDimensions} (before worlds load, so
     * the registry is not yet reachable). Drained by {@link #populate} once worlds are up.
     */
    public static void stageAnchors(Map<Integer, GalacticAnchor> anchors, boolean reset) {
        pendingAnchors = (anchors == null) ? new HashMap<Integer, GalacticAnchor>() : new HashMap<>(anchors);
        pendingReset = reset;
    }

    /**
     * Hand over the pack's {@code <galaxyGen>} configuration, read while dimensions load — before the
     * save is reachable, so before anything can know which model this world is owed.
     *
     * <p>The pack states the KNOBS; the save states the VERSION. {@link #populate} puts the two together
     * and installs the generator, which is why the generator is no longer built at the XML site: doing
     * it there would make the pack the authority on a question that belongs to the world.
     *
     * <p>It is kept for the session rather than drained, because an upgrade run later needs the same
     * configuration to stamp.
     */
    public static void stageGalaxyConfig(GalaxyGenConfig config) {
        packGalaxyConfig = config;
    }

    /** The pack's {@code <galaxyGen>} configuration for this session, or {@code null} if it declares none. */
    public static GalaxyGenConfig packGalaxyConfig() {
        return packGalaxyConfig;
    }

    /**
     * Server-start hook (call once worlds are loaded): bind the world seed, drain staged anchors, and give
     * every remaining catalogued star a fallback coord. Idempotent across restarts.
     */
    public static void populate(MinecraftServer server) {
        UniverseRegistry reg = get(server);
        if (reg == null) {
            return;
        }
        WorldServer overworld = net.minecraftforge.common.DimensionManager.getWorld(0);
        if (overworld == null) {
            overworld = server.getWorld(0);
        }
        if (overworld != null) {
            reg.bindWorldSeed(overworld.getSeed());
        }
        // Raise the world model from the SAVE and install its generator, BEFORE anything derives.
        // applyAnchors resolves declared positions through the generator, so an anchor placed under the
        // wrong model would be placed wrongly and then persisted.
        UniverseSchema schema = reg.reconcileSchema(packGalaxyConfig);
        activeSchema = schema;
        setGenerator(schema.generator(packGalaxyConfig));
        LOGGER.info("Universe schema {} ({}) in force, configuration {}", schema.version(),
                schema.label(), reg.configFingerprint());
        if (!schema.isStable()) {
            // Loud, and at WARN, because it is a statement about the FUTURE of this save rather than
            // about anything wrong with it now: an alpha model may be replaced outright, and a world
            // built on one is not promised a way forward.
            LOGGER.warn("Universe generator {} is an ALPHA. Its leading zero means the world model may "
                    + "be REPLACED in a later release rather than extended: worlds generated under it "
                    + "are not guaranteed to be carried forward, and only what has already been seen is "
                    + "frozen. Do not start a world you intend to keep for years on it.", schema.label());
        }
        reg.applyAnchors(pendingAnchors, pendingReset);
        reg.assignFallbackCoords(DimensionManager.getInstance().getStars());
        pendingAnchors = new HashMap<>();
        pendingReset = false;
    }

    // ─── Generator seam ────────────────────────────────────────────────────────

    public static IGalaxyGenerator getGenerator() {
        return generator;
    }

    public static void setGenerator(IGalaxyGenerator g) {
        generator = (g == null) ? new EmptyGalaxyGenerator() : g;
    }

    /**
     * Override how a stored star-id resolves to its content object (defaults to the legacy catalogue).
     * Passing {@code null} restores the default. Used by tests and by addons supplying fabricated systems.
     */
    public static void setStarLookup(IntFunction<StellarBody> lookup) {
        starLookup = (lookup == null) ? UniverseRegistry::lookupCatalogueStar : lookup;
    }

    // ─── XML authoring format helpers (sector triple; anchors sit at cell centre) ──────────────────────────

    /**
     * Parse {@code "sx,sy,sz"} into a cell-centre coord. A blank/absent value defaults silently to the
     * origin (the Sol default); a NON-blank value that fails to parse is a config mistake — it is warned
     * and defaults to the origin rather than silently misplacing the system.
     */
    public static GalacticCoord parseAnchor(String attr) {
        if (attr == null || attr.trim().isEmpty()) {
            return GalacticCoord.ORIGIN;
        }
        String[] parts = attr.split(",");
        if (parts.length == 3) {
            try {
                long sx = Long.parseLong(parts[0].trim());
                long sy = Long.parseLong(parts[1].trim());
                long sz = Long.parseLong(parts[2].trim());
                return GalacticCoord.ofSectorLocal(sx, sy, sz, 0L, 0L, 0L);
            } catch (NumberFormatException e) {
                // fall through to the warn + origin default
            }
        }
        LOGGER.warn("malformed galactic anchor \"{}\" (expected \"sectorX,sectorY,sectorZ\"); defaulting to origin",
                attr);
        return GalacticCoord.ORIGIN;
    }

    /** Format a coord's cell as {@code "sx,sy,sz"} for the {@code <star galacticCoord>} attribute. */
    public static String formatAnchor(GalacticCoord coord) {
        GalacticCoord cell = coord.cellCentre();
        return cell.sectorX() + "," + cell.sectorY() + "," + cell.sectorZ();
    }

    // ─── Persistence ───────────────────────────────────────────────────────────

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        byCell.clear();
        byStar.clear();
        poiOverrides.clear();
        pinnedSystems.clear();
        namesByDim.clear();
        anchorsBySuper = null;
        anchorsSeeded = nbt.getBoolean("anchorsSeeded");
        // Read through hasKey, NEVER through the value alone. NBT answers 0 for an absent integer, and
        // 0 is a real version number — the alpha — so taking the default would report every stampless
        // save as "generated by the alpha" and quietly skip the adoption that a fresh world is owed.
        // This is also why UNSTAMPED is negative: no version is.
        schemaVersion = nbt.hasKey("schemaVersion") ? nbt.getInteger("schemaVersion") : UNSTAMPED;
        configFingerprint = nbt.getString("galaxyConfigFingerprint");
        lawsFingerprint = nbt.getString("universeLawsFingerprint");
        upgradeArmed = nbt.getBoolean("universeUpgradeArmed");
        NBTTagList names = nbt.getTagList("cellNames", 10 /* NBTTagCompound */);
        for (int i = 0; i < names.tagCount(); i++) {
            NBTTagCompound e = names.getCompoundTagAt(i);
            namesByDim.put(e.getInteger("dimId"),
                    new RecordedName(GalacticCoord.readFromNBT(e).cellCentre(), e.getInteger("starId")));
        }
        NBTTagList list = nbt.getTagList("placements", 10 /* NBTTagCompound */);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound e = list.getCompoundTagAt(i);
            int id = e.getInteger("starId");
            GalacticCoord cell = GalacticCoord.readFromNBT(e).cellCentre();
            byCell.put(cell.cellKey(), id);
            byStar.put(id, cell);
        }
        NBTTagList pois = nbt.getTagList("pois", 10);
        for (int i = 0; i < pois.tagCount(); i++) {
            SystemBody poi = SystemBody.readFromNBT(pois.getCompoundTagAt(i));
            String key = poi.name().cellKey();
            List<SystemBody> l = poiOverrides.get(key);
            if (l == null) {
                l = new ArrayList<>();
                poiOverrides.put(key, l);
            }
            l.add(poi);
        }
        NBTTagList pinned = nbt.getTagList("pinnedSystems", 10);
        for (int i = 0; i < pinned.tagCount(); i++) {
            NBTTagCompound e = pinned.getCompoundTagAt(i);
            GalacticCoord anchor = GalacticCoord.readFromNBT(e).cellCentre();
            List<SystemBody> bodies = new ArrayList<>();
            NBTTagList bodyList = e.getTagList("bodies", 10);
            for (int j = 0; j < bodyList.tagCount(); j++) {
                bodies.add(SystemBody.readFromNBT(bodyList.getCompoundTagAt(j)));
            }
            SystemBodyKind primaryKind = SystemBodyKind.STAR;
            if (e.hasKey("primaryKind")) {
                try {
                    primaryKind = SystemBodyKind.valueOf(e.getString("primaryKind"));
                } catch (IllegalArgumentException ex) {
                    primaryKind = SystemBodyKind.STAR; // a kind this build does not know: read it as a star
                }
            }
            pinnedSystems.put(anchor.cellKey(), PinnedSystem.read(e.getInteger("starId"), primaryKind,
                    e.getInteger("temperature"), e.getFloat("size"), e.getString("name"), bodies));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("version", NBT_VERSION);
        nbt.setBoolean("anchorsSeeded", anchorsSeeded);
        nbt.setInteger("schemaVersion", schemaVersion);
        nbt.setString("galaxyConfigFingerprint", configFingerprint);
        nbt.setString("universeLawsFingerprint", lawsFingerprint);
        nbt.setBoolean("universeUpgradeArmed", upgradeArmed);
        NBTTagList list = new NBTTagList();
        for (Map.Entry<Integer, GalacticCoord> e : byStar.entrySet()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("starId", e.getKey());
            e.getValue().writeToNBT(entry); // nested sub-tag "galacticCoord"
            list.appendTag(entry);
        }
        nbt.setTag("placements", list);
        NBTTagList names = new NBTTagList();
        for (Map.Entry<Integer, RecordedName> e : namesByDim.entrySet()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("dimId", e.getKey());
            entry.setInteger("starId", e.getValue().starId);
            e.getValue().name.writeToNBT(entry); // nested sub-tag "galacticCoord"
            names.appendTag(entry);
        }
        nbt.setTag("cellNames", names);
        NBTTagList pois = new NBTTagList();
        for (List<SystemBody> cellPois : poiOverrides.values()) {
            for (SystemBody poi : cellPois) {
                NBTTagCompound entry = new NBTTagCompound();
                poi.writeToNBT(entry);
                pois.appendTag(entry);
            }
        }
        nbt.setTag("pois", pois);
        NBTTagList pinned = new NBTTagList();
        for (Map.Entry<String, PinnedSystem> e : pinnedSystems.entrySet()) {
            PinnedSystem p = e.getValue();
            GalacticCoord anchor = byStar.get(p.starId);
            if (anchor == null) {
                continue; // placement removed — the snapshot is orphaned, drop it
            }
            NBTTagCompound entry = new NBTTagCompound();
            anchor.writeToNBT(entry);
            entry.setInteger("starId", p.starId);
            // Written only when it is not a star, so a stellar system's snapshot is byte-identical to
            // what it was before starless systems existed.
            if (p.primaryKind != SystemBodyKind.STAR) {
                entry.setString("primaryKind", p.primaryKind.name());
            }
            entry.setInteger("temperature", p.temperature);
            entry.setFloat("size", p.size);
            entry.setString("name", p.name == null ? "" : p.name);
            NBTTagList bodyList = new NBTTagList();
            for (SystemBody b : p.bodies) {
                NBTTagCompound bodyTag = new NBTTagCompound();
                b.writeToNBT(bodyTag);
                bodyList.appendTag(bodyTag);
            }
            entry.setTag("bodies", bodyList);
            pinned.appendTag(entry);
        }
        nbt.setTag("pinnedSystems", pinned);
        return nbt;
    }

    /**
     * A pinned procedural system's content snapshot (A#1a pin-on-touch): its primary's drawn
     * properties plus its full body list.
     *
     * <p>{@code primaryKind} is what a re-read reconstructs the system FROM, and it is stored rather
     * than inferred from a zero temperature: a star that happens to be cold and a system that has no
     * star are different facts, and telling them apart by their arithmetic is exactly the confusion
     * the kind exists to end.</p>
     */
    private static final class PinnedSystem {
        final int starId;
        final SystemBodyKind primaryKind;
        final int temperature;
        final float size;
        final String name;
        final List<SystemBody> bodies;

        private PinnedSystem(int starId, SystemBodyKind primaryKind, int temperature, float size,
                             String name, List<SystemBody> bodies) {
            this.starId = starId;
            this.primaryKind = primaryKind;
            this.temperature = temperature;
            this.size = size;
            this.name = name;
            this.bodies = bodies;
        }

        static PinnedSystem ofStar(int starId, StellarBody star, List<SystemBody> bodies) {
            return new PinnedSystem(starId, SystemBodyKind.STAR, star.getTemperature(), star.getSize(),
                    star.getName(), bodies);
        }

        /** A system anchored on a starless world: an id, a name, and nothing a star would have had. */
        static PinnedSystem ofRogue(int starId, String name, List<SystemBody> bodies) {
            return new PinnedSystem(starId, SystemBodyKind.ROGUE_PLANET, 0, 0f, name, bodies);
        }

        static PinnedSystem read(int starId, SystemBodyKind primaryKind, int temperature, float size,
                                 String name, List<SystemBody> bodies) {
            return new PinnedSystem(starId, primaryKind, temperature, size, name, bodies);
        }

        PlanetarySystem toSystem() {
            if (primaryKind != SystemBodyKind.STAR) {
                return PlanetarySystem.ofRogue(starId, name);
            }
            StellarBody star = new StellarBody();
            star.setId(starId);
            star.setTemperature(temperature);
            star.setSize(size);
            star.setName(name);
            return PlanetarySystem.ofStar(star);
        }
    }

    /**
     * An authored system's bodies, plus the DERIVED worlds its pack asked for.
     *
     * <p>An authored system used to be filled by a second world-making model: a random generator seeded
     * on {@code System.currentTimeMillis()} that registered Forge dimensions up front at world
     * creation. It meant two saves of one seed differed, and every defect in this family had to be
     * found and fixed twice in two models that answered the same question differently. The pack-facing
     * knob survives as {@link StellarBody#getMaxRetinueBodies()}; the second model does not, and the
     * worlds it used to mint are now derived from {@code (seed, cell)} and realized on arrival like
     * every other world in the game.</p>
     *
     * <p>The authored bodies always win: their cells are handed to the derivation as already taken, so
     * nothing derived can land on one. A system that asks for none is untouched.</p>
     */
    private List<SystemBody> withDerivedRetinue(GalacticCoord anchor, StellarBody star, int starId,
                                                List<SystemBody> authored) {
        int asked = star.getMaxRetinueBodies();
        if (asked <= 0 || generator == null) {
            return authored;
        }
        Set<String> taken = new HashSet<>();
        for (SystemBody b : authored) {
            taken.add(b.name().cellKey());
        }
        List<SystemBody> all = new ArrayList<>(authored);
        all.addAll(generator.authoredRetinueFor(worldSeed, anchor, star, starId, asked, taken));
        return all;
    }
}
