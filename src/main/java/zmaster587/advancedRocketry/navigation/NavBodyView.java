package zmaster587.advancedRocketry.navigation;

import java.util.LinkedHashMap;
import java.util.Map;

import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.PlanetInfoField;
import zmaster587.advancedRocketry.universe.SystemBody;

/**
 * Everything the server knows about a body, as the nav computer would phrase it — before
 * {@link NavInfoRedaction} decides how much of it the pilot has earned.
 *
 * <p>Only fields the universe layer can actually answer are filled. A field nobody can supply yet is
 * simply absent: an empty line in a navigation readout is worse than no line, and inventing one would
 * make the redaction look like it revealed something it did not.</p>
 */
public final class NavBodyView {

    private NavBodyView() {
    }

    /** The unredacted view of {@code body}, keyed by field in reading order. */
    public static Map<PlanetInfoField, String> of(SystemBody body, CrystalEntry recorded) {
        Map<PlanetInfoField, String> view = new LinkedHashMap<>();
        if (body == null) {
            return view;
        }
        // The coordinate a player reads off this panel and writes down. It is the body's durable
        // NAME and nothing else: a name is fixed for the life of the save, so it still denotes the
        // same body at every later tick, whereas an in-cell offset would be a number that means
        // something different an hour later — exactly what a written-down coordinate must not be.
        GalacticCoord name = body.name();
        view.put(PlanetInfoField.COORDINATE, name == null ? "?" : name.cellKey());
        view.put(PlanetInfoField.NAME, nameOf(body, recorded));
        view.put(PlanetInfoField.TOPOLOGY, body.kind().name());

        DimensionProperties props = propsOf(body);
        if (props != null) {
            view.put(PlanetInfoField.ATMOSPHERE_DENSITY, Integer.toString(props.getAtmosphereDensity()));
            view.put(PlanetInfoField.ATMOSPHERE_PRESENCE,
                    Boolean.toString(props.getAtmosphereDensity() > 0));
            view.put(PlanetInfoField.TEMPERATURE, Integer.toString(props.getAverageTemp()));
            view.put(PlanetInfoField.MASS, Float.toString(props.getGravitationalMultiplier()));
            view.put(PlanetInfoField.BIOMES, Integer.toString(props.getBiomes().size()));
        }
        return view;
    }

    private static String nameOf(SystemBody body, CrystalEntry recorded) {
        if (recorded != null && !recorded.name().isEmpty()) {
            return recorded.name();
        }
        DimensionProperties props = propsOf(body);
        if (props != null && props.getName() != null && !props.getName().isEmpty()) {
            return props.getName();
        }
        return body.kind().name();
    }

    private static DimensionProperties propsOf(SystemBody body) {
        if (body.dimId() == zmaster587.advancedRocketry.api.Constants.INVALID_PLANET) {
            return null; // a star, a belt or a station slot has no dimension to read
        }
        // Having a dimension is the question, NOT being landable: a gas giant is a body a survey can
        // report an atmosphere and a temperature for, and is exactly the kind of thing a pilot wants
        // the numbers on before he decides he cannot put down there.
        return DimensionManager.getInstance().getDimensionPropertiesOrNull(body.dimId());
    }
}
