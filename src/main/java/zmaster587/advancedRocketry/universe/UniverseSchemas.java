package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Every released world model this build can still speak, keyed by version.
 *
 * <p><b>The jar carries all of them, and that is the point.</b> A save stamped with version <i>n</i> is
 * opened under version <i>n</i> forever, whatever the mod has moved on to — so the mod can be updated
 * freely (mechanics, blocks, balance, whole new machines) without the sky changing under a world that
 * has already been explored. Only the player's explicit upgrade moves a world onto a newer model.
 *
 * <p>A RELEASED version is added here and never removed: dropping one makes every save carrying its
 * stamp unopenable. A version that has not shipped is a different matter — it may be edited in place
 * and even replaced outright, because no world outside the branch was ever generated under it and it
 * therefore owes nobody compatibility. <b>"Shipped" means merged to the release branch</b>, not landed
 * on a feature branch; the freeze begins at the merge, and that is the moment a version stops being
 * editable and starts being history.
 *
 * <p>Registering a supplier rather than an instance keeps construction lazy and makes it explicit that
 * a schema is cheap to build and holds no world state.
 */
public final class UniverseSchemas {

    private static final Map<Integer, Supplier<UniverseSchema>> REGISTRY =
            new LinkedHashMap<Integer, Supplier<UniverseSchema>>();

    static {
        register(UniverseSchemaV0.VERSION, new Supplier<UniverseSchema>() {
            @Override
            public UniverseSchema get() {
                return new UniverseSchemaV0();
            }
        });
    }

    /** The newest released version — what a fresh world is stamped with. */
    public static final int CURRENT = UniverseSchemaV0.VERSION;

    private UniverseSchemas() {
    }

    private static void register(int version, Supplier<UniverseSchema> supplier) {
        REGISTRY.put(version, supplier);
    }

    /** The schema for {@code version}, or empty when this build does not carry it. */
    public static Optional<UniverseSchema> of(int version) {
        Supplier<UniverseSchema> supplier = REGISTRY.get(version);
        return (supplier == null) ? Optional.<UniverseSchema>empty() : Optional.of(supplier.get());
    }

    /** The newest released schema — what a world with no stamp of its own is generated under. */
    public static UniverseSchema current() {
        Optional<UniverseSchema> schema = of(CURRENT);
        if (!schema.isPresent()) {
            throw new IllegalStateException("the current universe schema " + CURRENT
                    + " is not registered");
        }
        return schema.get();
    }

    /** Every version this build carries, ascending — for diagnostics and for the refusal message. */
    public static List<Integer> released() {
        List<Integer> versions = new ArrayList<>(REGISTRY.keySet());
        Collections.sort(versions);
        return Collections.unmodifiableList(versions);
    }
}
