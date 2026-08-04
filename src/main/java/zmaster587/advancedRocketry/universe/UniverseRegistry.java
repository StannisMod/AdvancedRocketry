package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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
    private static final int NBT_VERSION = 3;

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
    private static Map<Integer, GalacticCoord> pendingAnchors = new HashMap<>();
    private static boolean pendingReset = false;

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
    public Optional<StarSystem> systemForCoord(GalacticCoord coord) {
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
    private Optional<StarSystem> systemAtAnchor(GalacticCoord anchor) {
        String key = anchor.cellKey();
        PinnedSystem pinned = pinnedSystems.get(key);
        if (pinned != null) {
            return Optional.of(new StarSystem(pinned.toStar()));
        }
        Integer id = byCell.get(key);
        if (id != null) {
            StellarBody star = starLookup.apply(id);
            return star == null ? Optional.<StarSystem>empty() : Optional.of(new StarSystem(star));
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
    public Map<GalacticCoord, StarSystem> systemsInRegion(GalacticCoord min, GalacticCoord max) {
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
        Map<GalacticCoord, StarSystem> out = new HashMap<>(generator.systemsInRegion(worldSeed, lo, hi));
        for (Map.Entry<Integer, GalacticCoord> e : byStar.entrySet()) {
            GalacticCoord c = e.getValue();
            if (c.sectorX() >= lo.sectorX() && c.sectorX() <= hi.sectorX()
                    && c.sectorY() >= lo.sectorY() && c.sectorY() <= hi.sectorY()
                    && c.sectorZ() >= lo.sectorZ() && c.sectorZ() <= hi.sectorZ()) {
                StellarBody star = starLookup.apply(e.getKey());
                if (star != null) {
                    out.put(c, new StarSystem(star)); // overrides win over any procedural entry at the same cell
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
            return star == null
                    ? new ArrayList<SystemBody>()
                    : SystemContent.bodiesOf(star, anchor, generator.minSpacingCells(),
                            this::durableName);
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
        Optional<StarSystem> sys = generator.systemAt(worldSeed, anchor);
        if (!sys.isPresent()) {
            return false;
        }
        List<SystemBody> bodies = new ArrayList<>(generator.bodiesFor(worldSeed, anchor));
        place(anchor, sys.get().starId());
        StellarBody star = sys.get().star();
        pinnedSystems.put(key, new PinnedSystem(sys.get().starId(), star.getTemperature(), star.getSize(),
                star.getName(), bodies));
        markDirty();
        return true;
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
    public void applyAnchors(Map<Integer, GalacticCoord> anchors, boolean reset) {
        if (anchorsSeeded && !reset) {
            return;
        }
        if (anchors != null) {
            List<Integer> ids = new ArrayList<>(anchors.keySet());
            Collections.sort(ids);
            for (Integer id : ids) {
                GalacticCoord c = anchors.get(id);
                if (c != null) {
                    place(c, id);
                }
            }
        }
        anchorsSeeded = true;
        markDirty();
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

    // ─── Static staging + population (server lifecycle) ────────────────────────

    /**
     * Buffer XML-authored anchor coords parsed during {@code createAndLoadDimensions} (before worlds load, so
     * the registry is not yet reachable). Drained by {@link #populate} once worlds are up.
     */
    public static void stageAnchors(Map<Integer, GalacticCoord> anchors, boolean reset) {
        pendingAnchors = (anchors == null) ? new HashMap<Integer, GalacticCoord>() : new HashMap<>(anchors);
        pendingReset = reset;
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
            pinnedSystems.put(anchor.cellKey(), new PinnedSystem(e.getInteger("starId"),
                    e.getInteger("temperature"), e.getFloat("size"), e.getString("name"), bodies));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("version", NBT_VERSION);
        nbt.setBoolean("anchorsSeeded", anchorsSeeded);
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

    /** A pinned procedural system's content snapshot (A#1a pin-on-touch): fabricated star + body list. */
    private static final class PinnedSystem {
        final int starId;
        final int temperature;
        final float size;
        final String name;
        final List<SystemBody> bodies;

        PinnedSystem(int starId, int temperature, float size, String name, List<SystemBody> bodies) {
            this.starId = starId;
            this.temperature = temperature;
            this.size = size;
            this.name = name;
            this.bodies = bodies;
        }

        StellarBody toStar() {
            StellarBody star = new StellarBody();
            star.setId(starId);
            star.setTemperature(temperature);
            star.setSize(size);
            star.setName(name);
            return star;
        }
    }
}
