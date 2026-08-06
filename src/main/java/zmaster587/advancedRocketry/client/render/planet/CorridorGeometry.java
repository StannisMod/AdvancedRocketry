package zmaster587.advancedRocketry.client.render.planet;

/**
 * Where the hyperspace corridor's rings sit, and how bright they are.
 *
 * <p>The corridor is the only thing that tells a pilot in transit that he is moving: he has no
 * controls, no bodies in the sky and no number counting down. So two things about it are contract
 * and not polish:</p>
 *
 * <ul>
 *   <li><b>A ring gets CLOSER as time passes.</b> That is what reads as travelling forward. Rings
 *       that recede instead read, unmistakably, as flying backwards.</li>
 *   <li><b>The ring at the viewer's nose is invisible.</b> A ring at full brightness on top of the
 *       camera pops into existence in the middle of the screen.</li>
 * </ul>
 *
 * <p>How many rings there are, how far apart, how wide and how fast they travel are all
 * {@code tunable} and are deliberately not part of that contract.</p>
 *
 * <p>Pure arithmetic — no GL, no client state — so both rules can be checked without a client.
 * {@link HyperspaceTunnel} is then only the drawing.</p>
 */
public final class CorridorGeometry {

    /** Rings drawn, nearest to farthest. {@code tunable}. */
    public static final int RINGS = 24;
    /** Segments per ring. Round enough at this radius and keeps the vertex count trivial. {@code tunable}. */
    public static final int SEGMENTS = 12;
    /** Ring radius in sky units. Wide enough that the hull sits inside it at any sane ship size. {@code tunable}. */
    public static final float RADIUS = 22.0f;
    /** Distance between neighbouring rings, in the same units. {@code tunable}. */
    public static final float SPACING = 7.0f;
    /** How fast the corridor travels, in rings per tick. {@code tunable}. */
    public static final float DRIFT_PER_TICK = 0.12f;

    private CorridorGeometry() {
    }

    /**
     * How far the corridor has travelled between one ring and the next at this instant, in [0,1).
     * The whole corridor is periodic in it: at 1 it is back where it was at 0, one ring along.
     */
    public static float driftAt(double ticks) {
        float drift = (float) ((ticks * DRIFT_PER_TICK) % 1.0);
        return drift < 0f ? drift + 1.0f : drift;
    }

    /**
     * How far AHEAD of the viewer ring {@code ring} sits, in sky units, at travel phase {@code drift}.
     *
     * <p>Every ring's distance FALLS as the phase advances — that is the whole corridor coming at the
     * viewer. At the end of a period the nearest ring has arrived and the one behind it has taken its
     * place, which is why the phase can simply wrap: the set of distances is the same on both sides
     * of the seam.</p>
     */
    public static float ringDistance(int ring, float drift) {
        return (ring + 1.0f - drift) * SPACING;
    }

    /**
     * How bright ring {@code ring} is drawn: rings fade with depth so the far end of the corridor
     * dissolves rather than ending in a hard wall, and the nearest one fades out as it ARRIVES, so
     * nothing ever winks out at full strength on top of the camera.
     */
    public static float ringAlpha(int ring, float drift) {
        float depth = (float) ring / (float) RINGS;
        float alpha = (1.0f - depth) * (1.0f - depth) * 0.55f;
        return ring == 0 ? alpha * (1.0f - drift) : alpha;
    }

    /**
     * The unit direction a Minecraft look pair points at — the axis the corridor runs down. Same
     * convention as vanilla's own look vector, so an attitude that came back out of
     * {@code FreeFlightPhysics.eulerFromQuat} maps straight onto it.
     */
    public static float[] axis(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        return new float[]{
                (float) (-Math.sin(yaw) * Math.cos(pitch)),
                (float) (-Math.sin(pitch)),
                (float) (Math.cos(yaw) * Math.cos(pitch))};
    }

    /** Some unit vector perpendicular to {@code (x,y,z)}, chosen to stay stable near the poles. */
    public static float[] perpendicular(float x, float y, float z) {
        float[] candidate = Math.abs(y) < 0.9f
                ? cross(x, y, z, 0.0f, 1.0f, 0.0f)
                : cross(x, y, z, 1.0f, 0.0f, 0.0f);
        return normalize(candidate);
    }

    public static float[] cross(float ax, float ay, float az, float bx, float by, float bz) {
        return new float[]{ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
    }

    public static float[] normalize(float[] v) {
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1.0e-4f) {
            return new float[]{1.0f, 0.0f, 0.0f};
        }
        return new float[]{v[0] / len, v[1] / len, v[2] / len};
    }
}
