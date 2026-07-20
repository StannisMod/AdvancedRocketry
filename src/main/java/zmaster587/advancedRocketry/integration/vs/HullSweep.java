package zmaster587.advancedRocketry.integration.vs;

import java.util.List;

/**
 * Collision sweep of a WORLD-axis-aligned body box against a ship's blocks in their TRUE world
 * orientation. The hull-stand mode holds a world-upright body against ship geometry; sweeping a
 * subspace-aligned box instead displaces every contact by {@code h*sin(tilt/2)} — the player then
 * walks "about a block beside the blocks he sees". This class collides the body's REAL volume.
 *
 * <p>Pure math, deliberately free of Minecraft and physics-mod types: obstacles arrive as boxes
 * axis-aligned in the SHIP frame — a world center, half-extents, and the ship's three world-frame
 * axis vectors shared by all of them — and the body as a world AABB. Resolution mirrors vanilla
 * {@code Entity.move}: three sequential single-axis passes (Y, X, Z on WORLD axes), each pass
 * clipping its displacement at the first separating-axis time of impact (15 SAT axes: 3 world,
 * 3 ship, 9 crosses). An obstacle already overlapping the body at the start of a pass does not
 * clip it — vanilla's permissiveness; the bounded start-lift handles shallow embeds instead.</p>
 *
 * <p>"Up" (for the start-lift and step assist) is an INPUT — the opposite of the local gravity —
 * per the stand-vs-slide ruling: the stand/slide threshold must emerge from geometry, step assist
 * and gravity, and in zero gravity ({@code up == null}) neither lift nor step applies.</p>
 */
public final class HullSweep {

    private HullSweep() {}

    /** Sliver of clearance left at every computed contact so the next pass does not start
     *  re-penetrated by float error; the same order of magnitude as vanilla's own move slop. */
    private static final double CONTACT_SLOP = 1.0E-7;
    /** Axes shorter than this (near-parallel cross products) carry no separation information. */
    private static final double DEGENERATE_AXIS = 1.0E-8;
    /** The deepest start overlap the up-lift resolves; anything deeper is a real embed and is
     *  left to the sweep's permissive handling, exactly like the subspace sweep before it. */
    private static final double MAX_START_LIFT = 0.1;

    /** One resolved sweep: the achieved displacement and which world axes were clipped. */
    public static final class Result {
        public double dx, dy, dz;
        public boolean collidedX, collidedY, collidedZ;
        /** Upward start-of-sweep de-penetration applied before the passes (world frame). */
        public double liftX, liftY, liftZ;
        /** Unit contact normal of the Y pass's clipping contact (pointing OUT of the surface,
         *  toward the body); all zero when the Y pass was not clipped. This is the face the
         *  stand-vs-slide decision reasons about. */
        public double normalX, normalY, normalZ;
    }

    /** cos(45°): a contact face whose normal is within 45° of `up` holds a body statically —
     *  friction coefficient 1, so the threshold is tan(angle) <= 1, not a magic angle check. */
    private static final double STATIC_HOLD_COS = 0.70710678118;

    /**
     * The slide of a blocked gravity move: the component of {@code (bx,by,bz)} (the part of the
     * pass the contact clipped off) tangential to the contact face {@code normal}. Returns
     * {@code null} — a static hold — when the face is within 45° of {@code up} (μ = 1: the
     * tangential pull does not exceed the normal hold). In zero gravity ({@code up == null})
     * there is no slide at all.
     */
    public static double[] slideOfBlocked(double bx, double by, double bz,
                                          double[] normal, double[] up) {
        if (up == null) {
            return null;
        }
        double nUp = normal[0] * up[0] + normal[1] * up[1] + normal[2] * up[2];
        if (nUp >= STATIC_HOLD_COS - 1.0E-9) {
            return null; // standable face: static friction holds
        }
        double along = bx * normal[0] + by * normal[1] + bz * normal[2];
        double sx = bx - along * normal[0];
        double sy = by - along * normal[1];
        double sz = bz - along * normal[2];
        if (sx * sx + sy * sy + sz * sz < 1.0E-14) {
            return null;
        }
        return new double[]{sx, sy, sz};
    }

    /**
     * Sweep the body box {@code bounds} (world {minX,minY,minZ,maxX,maxY,maxZ}) by
     * {@code (wantX,wantY,wantZ)} against {@code obstacles} ({cx,cy,cz,hx,hy,hz} — world center
     * + half-extents of a box axis-aligned in the ship frame), all oriented by {@code shipAxes}
     * (three world-frame unit vectors, the ship's X/Y/Z).
     *
     * @param up world-frame unit vector opposite the local gravity, or {@code null} in zero
     *           gravity (disables the start-lift and step assist)
     * @param stepHeight vanilla step assist height, applied along {@code up} when a grounded
     *                   horizontal move is clipped
     * @param grounded whether the body starts the tick standing (enables step assist, as
     *                 vanilla's {@code onGround} does)
     */
    public static Result sweep(double[] bounds, double wantX, double wantY, double wantZ,
                               List<double[]> obstacles, double[][] shipAxes,
                               double[] up, double stepHeight, boolean grounded) {
        double[] box = bounds.clone();
        Result out = new Result();

        // Start de-penetration: lift the box along `up` out of the shallowest overlaps, so a
        // subspace round-trip's ~1e-8 float noise (or a settle slightly into a face) does not
        // read as an embed the permissive pass would tunnel through.
        if (up != null) {
            double lift = 0.0;
            for (double[] o : obstacles) {
                double pen = startPenetrationAlong(box, o, shipAxes, up);
                if (pen > lift && pen <= MAX_START_LIFT) {
                    lift = pen;
                }
            }
            if (lift > 0.0) {
                // Over-resolve by the contact slop: a lift to EXACT touch would read as a start
                // overlap on the next pass (permissive) and gravity would fall straight through.
                lift += CONTACT_SLOP;
                offset(box, up[0] * lift, up[1] * lift, up[2] * lift);
                out.liftX = up[0] * lift;
                out.liftY = up[1] * lift;
                out.liftZ = up[2] * lift;
            }
        }

        // Vanilla's pass order: Y, then X, then Z. The Y pass records its contact normal — the
        // face the stand-vs-slide decision is about.
        double[] yNormal = new double[3];
        double gotY = sweepAxis(box, 1, wantY, obstacles, shipAxes, yNormal);
        offset(box, 0.0, gotY, 0.0);
        out.normalX = yNormal[0];
        out.normalY = yNormal[1];
        out.normalZ = yNormal[2];
        double gotX = sweepAxis(box, 0, wantX, obstacles, shipAxes, null);
        offset(box, gotX, 0.0, 0.0);
        double gotZ = sweepAxis(box, 2, wantZ, obstacles, shipAxes, null);
        offset(box, 0.0, 0.0, gotZ);

        boolean clippedHorizontally = gotX != wantX || gotZ != wantZ;
        if (up != null && stepHeight > 0.0 && grounded && clippedHorizontally) {
            // Step assist, along `up`: retry the horizontal move from the original (lifted)
            // start, raised by stepHeight, then settle back; keep whichever went further.
            double[] stepped = bounds.clone();
            offset(stepped, out.liftX, out.liftY, out.liftZ);
            double rise = sweepAlong(stepped, up, stepHeight, obstacles, shipAxes, null);
            offset(stepped, up[0] * rise, up[1] * rise, up[2] * rise);
            double stepX = sweepAxis(stepped, 0, wantX, obstacles, shipAxes, null);
            offset(stepped, stepX, 0.0, 0.0);
            double stepZ = sweepAxis(stepped, 2, wantZ, obstacles, shipAxes, null);
            offset(stepped, 0.0, 0.0, stepZ);
            // Settle back down onto whatever was stepped onto.
            double down = sweepAlong(stepped, new double[]{-up[0], -up[1], -up[2]}, rise,
                    obstacles, shipAxes, null);
            offset(stepped, -up[0] * down, -up[1] * down, -up[2] * down);

            if (stepX * stepX + stepZ * stepZ > gotX * gotX + gotZ * gotZ) {
                // The achieved displacement, read straight off the boxes (start after lift).
                out.dx = stepped[0] - (bounds[0] + out.liftX);
                out.dy = stepped[1] - (bounds[1] + out.liftY);
                out.dz = stepped[2] - (bounds[2] + out.liftZ);
                out.collidedX = stepX != wantX;
                out.collidedY = gotY != wantY;
                out.collidedZ = stepZ != wantZ;
                return out;
            }
        }

        out.dx = gotX;
        out.dy = gotY;
        out.dz = gotZ;
        out.collidedX = gotX != wantX;
        out.collidedY = gotY != wantY;
        out.collidedZ = gotZ != wantZ;
        return out;
    }

    /** Clip a displacement of {@code amount} along the canonical world axis {@code axis};
     *  {@code outNormal} (nullable, length 3) receives the clipping contact's normal. */
    static double sweepAxis(double[] box, int axis, double amount,
                            List<double[]> obstacles, double[][] shipAxes, double[] outNormal) {
        if (amount == 0.0) {
            return 0.0;
        }
        double[] dir = {0.0, 0.0, 0.0};
        dir[axis] = amount > 0.0 ? 1.0 : -1.0;
        double got = sweepAlong(box, dir, Math.abs(amount), obstacles, shipAxes, outNormal);
        return dir[axis] * got;
    }

    /** Clip a displacement of {@code distance >= 0} along the arbitrary unit direction
     *  {@code dir}: the smallest time of impact over all obstacles, less the contact slop.
     *  {@code outNormal} (nullable) receives the normal of the FINAL clipping contact. */
    static double sweepAlong(double[] box, double[] dir, double distance,
                             List<double[]> obstacles, double[][] shipAxes, double[] outNormal) {
        if (distance <= 0.0) {
            return 0.0;
        }
        double allowed = distance;
        double[] candidate = outNormal == null ? null : new double[3];
        for (double[] o : obstacles) {
            double toi = timeOfImpact(box, o, shipAxes, dir, allowed, candidate);
            if (toi >= 0.0 && toi < allowed) {
                allowed = Math.max(0.0, toi - CONTACT_SLOP);
                if (outNormal != null) {
                    outNormal[0] = candidate[0];
                    outNormal[1] = candidate[1];
                    outNormal[2] = candidate[2];
                }
            }
        }
        return allowed;
    }

    /**
     * SAT time of impact of the moving body AABB against one ship-oriented box, motion
     * {@code t * dir}, {@code t} in {@code [0, maxT]}. Returns {@code -1} when there is no
     * contact in range OR the boxes already overlap at {@code t = 0} (vanilla permissiveness:
     * a pass never clips an obstacle it starts inside). {@code outNormal} (nullable) receives
     * the entry axis oriented OUT of the obstacle, toward the body — the contact normal.
     */
    static double timeOfImpact(double[] box, double[] obstacle, double[][] shipAxes,
                               double[] dir, double maxT, double[] outNormal) {
        double bcx = (box[0] + box[3]) * 0.5, bcy = (box[1] + box[4]) * 0.5,
                bcz = (box[2] + box[5]) * 0.5;
        double bhx = (box[3] - box[0]) * 0.5, bhy = (box[4] - box[1]) * 0.5,
                bhz = (box[5] - box[2]) * 0.5;

        double enter = 0.0;   // latest entry over all axes
        double exit = maxT;   // earliest exit over all axes
        boolean startOverlap = true;
        double enX = 0.0, enY = 0.0, enZ = 0.0; // entry axis, oriented toward the body

        for (int i = 0; i < 15; i++) {
            double lx, ly, lz;
            if (i < 3) {                       // world axes (the AABB's own)
                lx = i == 0 ? 1 : 0;
                ly = i == 1 ? 1 : 0;
                lz = i == 2 ? 1 : 0;
            } else if (i < 6) {                // ship axes (the obstacle's own)
                double[] a = shipAxes[i - 3];
                lx = a[0];
                ly = a[1];
                lz = a[2];
            } else {                           // 9 cross products
                int wi = (i - 6) / 3;
                double[] a = shipAxes[(i - 6) % 3];
                double wx = wi == 0 ? 1 : 0, wy = wi == 1 ? 1 : 0, wz = wi == 2 ? 1 : 0;
                lx = wy * a[2] - wz * a[1];
                ly = wz * a[0] - wx * a[2];
                lz = wx * a[1] - wy * a[0];
            }
            double len2 = lx * lx + ly * ly + lz * lz;
            if (len2 < DEGENERATE_AXIS) {
                continue; // parallel edges — no separation information on this axis
            }
            double inv = 1.0 / Math.sqrt(len2);
            lx *= inv;
            ly *= inv;
            lz *= inv;

            double rBody = bhx * Math.abs(lx) + bhy * Math.abs(ly) + bhz * Math.abs(lz);
            double rObs = obstacle[3] * Math.abs(lx * shipAxes[0][0] + ly * shipAxes[0][1] + lz * shipAxes[0][2])
                    + obstacle[4] * Math.abs(lx * shipAxes[1][0] + ly * shipAxes[1][1] + lz * shipAxes[1][2])
                    + obstacle[5] * Math.abs(lx * shipAxes[2][0] + ly * shipAxes[2][1] + lz * shipAxes[2][2]);
            double r = rBody + rObs;
            double d0 = (bcx - obstacle[0]) * lx + (bcy - obstacle[1]) * ly + (bcz - obstacle[2]) * lz;
            double v = dir[0] * lx + dir[1] * ly + dir[2] * lz;

            if (Math.abs(v) < 1.0E-12) {
                if (Math.abs(d0) > r) {
                    return -1.0; // separated on a static axis for the whole sweep
                }
                continue;        // overlapping on this axis throughout; no constraint
            }
            double t1 = (-r - d0) / v;
            double t2 = (r - d0) / v;
            double tEnter = Math.min(t1, t2);
            double tExit = Math.max(t1, t2);
            if (tEnter > 0.0) {
                startOverlap = false;
            }
            if (tEnter > enter) {
                enter = tEnter;
                double sign = d0 >= 0.0 ? 1.0 : -1.0; // orient out of the obstacle, toward the body
                enX = lx * sign;
                enY = ly * sign;
                enZ = lz * sign;
            }
            if (tExit < exit) {
                exit = tExit;
            }
            if (enter > exit) {
                return -1.0; // axes never overlap simultaneously in range
            }
        }
        if (startOverlap) {
            return -1.0; // already penetrating: never clip (the start-lift handles shallow embeds)
        }
        if (enter > maxT) {
            return -1.0;
        }
        if (outNormal != null) {
            outNormal[0] = enX;
            outNormal[1] = enY;
            outNormal[2] = enZ;
        }
        return enter;
    }

    /**
     * Penetration depth along {@code up} at the start of the sweep, for the up-lift: how far the
     * body must move along {@code up} to clear this obstacle, or {@code 0} when they do not
     * overlap. Measured on the SAT axes: the smallest per-axis overlap is a lower bound of the
     * true separation; projected onto {@code up} it errs shallow, which only makes the bounded
     * lift conservative.
     */
    static double startPenetrationAlong(double[] box, double[] obstacle, double[][] shipAxes,
                                        double[] up) {
        // Overlap test first (15 axes); track the overlap along `up` itself for the depth.
        double upOverlap = -1.0;
        double bcx = (box[0] + box[3]) * 0.5, bcy = (box[1] + box[4]) * 0.5,
                bcz = (box[2] + box[5]) * 0.5;
        double bhx = (box[3] - box[0]) * 0.5, bhy = (box[4] - box[1]) * 0.5,
                bhz = (box[5] - box[2]) * 0.5;
        for (int i = 0; i < 15; i++) {
            double lx, ly, lz;
            if (i < 3) {
                lx = i == 0 ? 1 : 0;
                ly = i == 1 ? 1 : 0;
                lz = i == 2 ? 1 : 0;
            } else if (i < 6) {
                double[] a = shipAxes[i - 3];
                lx = a[0];
                ly = a[1];
                lz = a[2];
            } else {
                int wi = (i - 6) / 3;
                double[] a = shipAxes[(i - 6) % 3];
                double wx = wi == 0 ? 1 : 0, wy = wi == 1 ? 1 : 0, wz = wi == 2 ? 1 : 0;
                lx = wy * a[2] - wz * a[1];
                ly = wz * a[0] - wx * a[2];
                lz = wx * a[1] - wy * a[0];
            }
            double len2 = lx * lx + ly * ly + lz * lz;
            if (len2 < DEGENERATE_AXIS) {
                continue;
            }
            double inv = 1.0 / Math.sqrt(len2);
            lx *= inv;
            ly *= inv;
            lz *= inv;
            double rBody = bhx * Math.abs(lx) + bhy * Math.abs(ly) + bhz * Math.abs(lz);
            double rObs = obstacle[3] * Math.abs(lx * shipAxes[0][0] + ly * shipAxes[0][1] + lz * shipAxes[0][2])
                    + obstacle[4] * Math.abs(lx * shipAxes[1][0] + ly * shipAxes[1][1] + lz * shipAxes[1][2])
                    + obstacle[5] * Math.abs(lx * shipAxes[2][0] + ly * shipAxes[2][1] + lz * shipAxes[2][2]);
            double d0 = (bcx - obstacle[0]) * lx + (bcy - obstacle[1]) * ly
                    + (bcz - obstacle[2]) * lz;
            double overlap = rBody + rObs - Math.abs(d0);
            if (overlap <= 0.0) {
                return 0.0; // separated — no penetration at all
            }
            double alongUp = lx * up[0] + ly * up[1] + lz * up[2];
            if (Math.abs(alongUp) > 1.0E-6) {
                double depth = overlap / Math.abs(alongUp);
                if (upOverlap < 0.0 || depth < upOverlap) {
                    upOverlap = depth;
                }
            }
        }
        return upOverlap < 0.0 ? 0.0 : upOverlap;
    }

    /** Whether the (already grown/expanded) body box statically overlaps ANY obstacle — the
     *  contact predicate twin of the sweep, on the same true geometry. */
    public static boolean touchesAny(double[] box, List<double[]> obstacles, double[][] shipAxes) {
        for (double[] o : obstacles) {
            if (overlaps(box, o, shipAxes)) {
                return true;
            }
        }
        return false;
    }

    /** Static 15-axis SAT overlap of the body AABB and one ship-oriented box. */
    static boolean overlaps(double[] box, double[] obstacle, double[][] shipAxes) {
        double bcx = (box[0] + box[3]) * 0.5, bcy = (box[1] + box[4]) * 0.5,
                bcz = (box[2] + box[5]) * 0.5;
        double bhx = (box[3] - box[0]) * 0.5, bhy = (box[4] - box[1]) * 0.5,
                bhz = (box[5] - box[2]) * 0.5;
        for (int i = 0; i < 15; i++) {
            double lx, ly, lz;
            if (i < 3) {
                lx = i == 0 ? 1 : 0;
                ly = i == 1 ? 1 : 0;
                lz = i == 2 ? 1 : 0;
            } else if (i < 6) {
                double[] a = shipAxes[i - 3];
                lx = a[0];
                ly = a[1];
                lz = a[2];
            } else {
                int wi = (i - 6) / 3;
                double[] a = shipAxes[(i - 6) % 3];
                double wx = wi == 0 ? 1 : 0, wy = wi == 1 ? 1 : 0, wz = wi == 2 ? 1 : 0;
                lx = wy * a[2] - wz * a[1];
                ly = wz * a[0] - wx * a[2];
                lz = wx * a[1] - wy * a[0];
            }
            double len2 = lx * lx + ly * ly + lz * lz;
            if (len2 < DEGENERATE_AXIS) {
                continue;
            }
            double inv = 1.0 / Math.sqrt(len2);
            lx *= inv;
            ly *= inv;
            lz *= inv;
            double rBody = bhx * Math.abs(lx) + bhy * Math.abs(ly) + bhz * Math.abs(lz);
            double rObs = obstacle[3] * Math.abs(lx * shipAxes[0][0] + ly * shipAxes[0][1] + lz * shipAxes[0][2])
                    + obstacle[4] * Math.abs(lx * shipAxes[1][0] + ly * shipAxes[1][1] + lz * shipAxes[1][2])
                    + obstacle[5] * Math.abs(lx * shipAxes[2][0] + ly * shipAxes[2][1] + lz * shipAxes[2][2]);
            double d0 = (bcx - obstacle[0]) * lx + (bcy - obstacle[1]) * ly
                    + (bcz - obstacle[2]) * lz;
            if (Math.abs(d0) > rBody + rObs) {
                return false;
            }
        }
        return true;
    }

    private static void offset(double[] box, double dx, double dy, double dz) {
        box[0] += dx;
        box[3] += dx;
        box[1] += dy;
        box[4] += dy;
        box[2] += dz;
        box[5] += dz;
    }
}
