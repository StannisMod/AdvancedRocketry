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
 * <p>Placement is keyed by CELL (one system per {@link GalacticCoord#CELL 4&nbsp;M} cell): every coord is
 * snapped to its {@link GalacticCoord#cellCentre() cell centre} before use, so two positions in the same cell
 * resolve to the same system. The persistent override store holds only authored (XML anchor) and
 * player-modified placements; procedural cells are re-derived on demand from {@code (seed, coord)} through the
 * {@link IGalaxyGenerator} seam (which ships as {@link EmptyGalaxyGenerator} here).</p>
 *
 * <p>A {@link WorldSavedData} on the overworld's global {@code MapStorage} (reachable from any dimension since
 * the overworld is always loaded). Server-side only; the world seed is re-derived on load rather than
 * persisted (it is immutable for a save and is the single source of truth).</p>
 */
public final class UniverseRegistry extends WorldSavedData {

    /** The persisted identifier == the {@code .dat} filename in the world save. A save-schema constant. */
    public static final String STORAGE_KEY = "advancedrocketry_universe";

    private static final int NBT_VERSION = 1;

    // A self-contained logger rather than AdvancedRocketry.logger: loading the mod class triggers Forge
    // bootstrap (FluidRegistry.enableUniversalBucket), which would break pure unit tests of this registry.
    private static final Logger LOGGER = LogManager.getLogger("AdvancedRocketry|Universe");

    // ─── The override store (persisted) ───────────────────────────────────────
    /** cell key ("sx_sy_sz") -> system star-id. Forward index. */
    private final Map<String, Integer> byCell = new HashMap<>();
    /** system star-id -> its cell-centre coordinate. Reverse index. */
    private final Map<Integer, GalacticCoord> byStar = new HashMap<>();
    /** Latch: authored anchors drain into the store exactly once (unless a config XML reset is forced). */
    private boolean anchorsSeeded = false;

    // ─── Transient, re-derived per load ───────────────────────────────────────
    /** The world seed fed to the generator; set by {@link #bindWorldSeed}, never persisted. */
    private long worldSeed = 0L;

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
     * The system occupying {@code coord}'s cell: the override store first, else the procedural generator.
     * Empty means void space.
     */
    public Optional<StarSystem> systemForCoord(GalacticCoord coord) {
        GalacticCoord cell = coord.cellCentre();
        Integer id = byCell.get(cell.cellKey());
        if (id != null) {
            StellarBody star = starLookup.apply(id);
            return star == null ? Optional.<StarSystem>empty() : Optional.of(new StarSystem(star));
        }
        return generator.systemAt(worldSeed, cell);
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

    // ─── Reverse lookups (system / planet -> coord) ────────────────────────────

    public Optional<GalacticCoord> coordForSystem(int starId) {
        return Optional.ofNullable(byStar.get(starId));
    }

    public Optional<GalacticCoord> coordForStar(StellarBody star) {
        return star == null ? Optional.<GalacticCoord>empty() : coordForSystem(star.getId());
    }

    /**
     * The galactic coordinate of the system a planet/moon/star-proxy dimension belongs to. This is the
     * planet&rarr;coord seam the tier-2 entry/descent handlers use. A moon resolves through its mirrored
     * {@code starId}; a star-proxy dim id ({@code >= STAR_ID_OFFSET}) resolves to its own system.
     */
    public Optional<GalacticCoord> coordForPlanet(DimensionProperties props) {
        if (props == null) {
            return Optional.empty();
        }
        if (props.isStar()) {
            return coordForSystem(props.getId() - Constants.STAR_ID_OFFSET);
        }
        Optional<GalacticCoord> byStarId = coordForSystem(props.getStarId());
        if (byStarId.isPresent()) {
            return byStarId;
        }
        if (props.isMoon()) {
            DimensionProperties parent = props.getParentProperties();
            if (parent != null) {
                return coordForSystem(parent.getStarId());
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
        markDirty();
    }

    /** Remove the placement at a cell. Returns whether one existed. */
    public boolean remove(GalacticCoord coord) {
        String key = coord.cellCentre().cellKey();
        Integer id = byCell.remove(key);
        if (id == null) {
            return false;
        }
        byStar.remove(id);
        markDirty();
        return true;
    }

    // ─── Population lifecycle ──────────────────────────────────────────────────

    /**
     * Drain authored anchors into the store, once. On a fresh world this places every anchor; on a restart
     * (anchors already seeded) it is a no-op so the persisted store — including player edits — wins. A config
     * XML reset ({@code reset == true}) forces re-application.
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
        return GalacticCoord.ofSectorLocal(starId, 0L, 0L, 0L, 0L, 0L);
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
        anchorsSeeded = nbt.getBoolean("anchorsSeeded");
        NBTTagList list = nbt.getTagList("placements", 10 /* NBTTagCompound */);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound e = list.getCompoundTagAt(i);
            int id = e.getInteger("starId");
            GalacticCoord cell = GalacticCoord.readFromNBT(e).cellCentre();
            byCell.put(cell.cellKey(), id);
            byStar.put(id, cell);
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
        return nbt;
    }
}
