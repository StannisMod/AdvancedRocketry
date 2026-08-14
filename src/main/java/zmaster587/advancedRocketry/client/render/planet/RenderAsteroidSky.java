package zmaster587.advancedRocketry.client.render.planet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.GlStateManager.DestFactor;
import net.minecraft.client.renderer.GlStateManager.SourceFactor;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.IRenderHandler;
import org.lwjgl.opengl.GL11;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.IPlanetaryProvider;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.inventory.TextureResources;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.stations.SpaceStationObject;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;
import zmaster587.libVulpes.util.Vector3F;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class RenderAsteroidSky extends IRenderHandler {

    // === Textures ===
    public static final ResourceLocation asteroid1 = new ResourceLocation("advancedRocketry:textures/planets/asteroid_a.png");
    public static final ResourceLocation asteroid2 = new ResourceLocation("advancedRocketry:textures/planets/asteroid_b.png");
    public static final ResourceLocation asteroid3 = new ResourceLocation("advancedRocketry:textures/planets/asteroid_c.png");

    // Per-frame texture bind cache (local to this renderer)
    private ResourceLocation boundTex = null;

    // Runtime / state
    private float celestialAngle;
    private final Vector3F<Float> axis;
    private final Minecraft mc = Minecraft.getMinecraft();

    // Display lists
    private final int starGLCallList;
    private final int glSkyList;
    private final int glSkyList2;
    private final int glSkyList3;

    // Reused scratch buffers to reduce GC
    private final List<DimensionProperties> childrenBuf = new ArrayList<>(8);
    private final float[] shadowColorTmp = new float[3];

    // Helpers for ring/black-hole math
    private static float xrotangle = 0;             // for ring rotation (kept exactly as before)
    private static final float[] skycolor = {0,0,0}; // for black hole rendering (same usage as before)
    private static double currentplanetphi = 0;     // ring/disk angle (same)

    // === ctor ===
    public RenderAsteroidSky() {
        axis = new Vector3F<>(1f, 0f, 0f);

        // Build display lists once (same seeds/geometry as original)
        this.starGLCallList = GLAllocation.generateDisplayLists(4);

        GL11.glPushMatrix();
        GL11.glNewList(this.starGLCallList, GL11.GL_COMPILE);
        this.renderStars(); // stars list
        GL11.glEndList();
        GL11.glPopMatrix();

        BufferBuilder buffer = Tessellator.getInstance().getBuffer();

        // Sky dome slice 1
        this.glSkyList = this.starGLCallList + 1;
        GL11.glNewList(this.glSkyList, GL11.GL_COMPILE);
        byte b2 = 64;
        int i = 256 / b2 + 2;
        float f = 16.0F;

        for (int j = -b2 * i; j <= b2 * i; j += b2) {
            for (int k = -b2 * i; k <= b2 * i; k += b2) {
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
                buffer.pos(j, f, k).endVertex();
                buffer.pos(j + b2, f, k).endVertex();
                buffer.pos(j + b2, f, k + b2).endVertex();
                buffer.pos(j, f, k + b2).endVertex();
                Tessellator.getInstance().draw();
            }
        }
        GL11.glEndList();

        // Sky dome slice 2
        this.glSkyList2 = this.starGLCallList + 2;
        GL11.glNewList(this.glSkyList2, GL11.GL_COMPILE);
        f = -16.0F;
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        for (int j = -b2 * i; j <= b2 * i; j += b2) {
            for (int k = -b2 * i; k <= b2 * i; k += b2) {
                buffer.pos(j, f, k).endVertex();
                buffer.pos(j + b2, f, k).endVertex();
                buffer.pos(j + b2, f, k + b2).endVertex();
                buffer.pos(j, f, k + b2).endVertex();
            }
        }
        Tessellator.getInstance().draw();
        GL11.glEndList();

        // Asteroids list
        this.glSkyList3 = this.starGLCallList + 3;
        GL11.glPushMatrix();
        GL11.glNewList(this.glSkyList3, GL11.GL_COMPILE);
        renderAsteroids(); // geometry baked with fixed seed matching original
        GL11.glEndList();
        GL11.glPopMatrix();
    }

    // Efficient texture binder (skip redundant binds per frame)
    private void bind(ResourceLocation tex) {
        if (tex != null && tex != boundTex) {
            mc.renderEngine.bindTexture(tex);
            boundTex = tex;
        }
    }

    private void renderAsteroids() {
        Random random = new Random(10843L); // same seed => identical layout to original
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);

        for (int i = 0; i < 200; ++i) {
            double d0 = random.nextFloat() * 2F - 1F;
            double d1 = random.nextFloat() - .5F;
            double d2 = random.nextFloat() * 2F - 1F;
            double size = 0.15F + random.nextFloat();
            double d4 = d0 * d0 + d1 * d1 + d2 * d2;

            if (d4 < 1.0D && d4 > 0.01D) {
                d4 = 0.5D / Math.sqrt(d4);
                d0 *= d4;
                d1 *= d4;
                d2 *= d4;
                double d5 = d0 * 100.0D;
                double d6 = d1 * 100.0D;
                double d7 = d2 * 100.0D;
                double d8 = Math.atan2(d0, d2);
                double d9 = Math.sin(d8);
                double d10 = Math.cos(d8);
                double d11 = Math.atan2(Math.sqrt(d0 * d0 + d2 * d2), d1);
                double d12 = Math.sin(d11);
                double d13 = Math.cos(d11);
                double d14 = random.nextDouble() * Math.PI * 2.0D;
                double d15 = Math.sin(d14);
                double d16 = Math.cos(d14);

                float r = random.nextFloat() * 0.05f + .95f;
                float g = random.nextFloat() * 0.1f + .9f;
                float b = random.nextFloat() * 0.1f + .9f;

                for (int j = 0; j < 4; ++j) {
                    double d17 = 0.0D;
                    double d18 = (double) ((j & 2) - 1) * size;
                    double d19 = (double) ((j + 1 & 2) - 1) * size;
                    double d20 = d18 * d16 - d19 * d15;
                    double d21 = d19 * d16 + d18 * d15;
                    double d22 = d20 * d12 + d17 * d13;
                    double d23 = d17 * d12 - d20 * d13;
                    double d24 = d23 * d9 - d21 * d10;
                    double d25 = d21 * d9 + d23 * d10;
                    buffer.pos(d5 + d24, d6 + d22, d7 + d25).tex(d18 / (size * 2) + .5, d19 / (size * 2) + .5).color(r, g, b, 1f).endVertex();
                }
            }
        }
        Tessellator.getInstance().draw();
    }

    private void renderStars() {
        Random random = new Random(10842L); // same seed => identical layout to original
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);

        for (int i = 0; i < 2000; ++i) {
            double d0 = random.nextFloat() * 2.0F - 1.0F;
            double d1 = random.nextFloat() * 2.0F - 1.0F;
            double d2 = random.nextFloat() * 2.0F - 1.0F;
            double d3 = 0.15F + random.nextFloat() * 0.1F;
            double d4 = d0 * d0 + d1 * d1 + d2 * d2;

            if (d4 < 1.0D && d4 > 0.01D) {
                d4 = 1.0D / Math.sqrt(d4);
                d0 *= d4;
                d1 *= d4;
                d2 *= d4;
                double d5 = d0 * 100.0D;
                double d6 = d1 * 100.0D;
                double d7 = d2 * 100.0D;
                double d8 = Math.atan2(d0, d2);
                double d9 = Math.sin(d8);
                double d10 = Math.cos(d8);
                double d11 = Math.atan2(Math.sqrt(d0 * d0 + d2 * d2), d1);
                double d12 = Math.sin(d11);
                double d13 = Math.cos(d11);
                double d14 = random.nextDouble() * Math.PI * 2.0D;
                double d15 = Math.sin(d14);
                double d16 = Math.cos(d14);

                for (int j = 0; j < 4; ++j) {
                    double d17 = 0.0D;
                    double d18 = (double) ((j & 2) - 1) * d3;
                    double d19 = (double) ((j + 1 & 2) - 1) * d3;
                    double d20 = d18 * d16 - d19 * d15;
                    double d21 = d19 * d16 + d18 * d15;
                    double d22 = d20 * d12 + d17 * d13;
                    double d23 = d17 * d12 - d20 * d13;
                    double d24 = d23 * d9 - d21 * d10;
                    double d25 = d21 * d9 + d23 * d10;
                    buffer.pos(d5 + d24, d6 + d22, d7 + d25).endVertex();
                }
            }
        }
        Tessellator.getInstance().draw();
    }

    @Override
    public void render(float partialTicks, WorldClient world, Minecraft mc) {
        // per-frame texture bind cache reset
        boundTex = null;

        // === Gather properties (unchanged logic) ===
        float atmosphere;
        int solarOrbitalDistance, planetOrbitalDistance = 0;
        double myPhi = 0, myTheta = 0, myPrevOrbitalTheta = 0, myRotationalPhi = 0;
        boolean isMoon;
        // shadowColorTmp reused; values set below
        float[] parentRingColor = new float[]{1f, 1f, 1f};
        float[] ringColor = new float[]{1f, 1f, 1f};
        float sunSize = 1.0f;
        boolean isWarp = false;
        boolean hasRings = false;
        boolean parentHasRings = false;
        boolean parentHasATM = false;
        DimensionProperties parentProperties = null;
        DimensionProperties properties;
        EnumFacing travelDirection = null;
        List<DimensionProperties> children;
        StellarBody primaryStar;
        celestialAngle = mc.world.getCelestialAngle(partialTicks);

        Vec3d sunColor;

        if (mc.world.provider instanceof IPlanetaryProvider) {
            IPlanetaryProvider planetaryProvider = (IPlanetaryProvider) mc.world.provider;
            properties = (DimensionProperties) planetaryProvider.getDimensionProperties(mc.player.getPosition());

            atmosphere = planetaryProvider.getAtmosphereDensityFromHeight(mc.getRenderViewEntity().posY, mc.player.getPosition());
            EnumFacing dir = getRotationAxis(properties, mc.player.getPosition());
            axis.x = (float) dir.getFrontOffsetX();
            axis.y = (float) dir.getFrontOffsetY();
            axis.z = (float) dir.getFrontOffsetZ();

            myPhi = properties.orbitalPhi;
            myTheta = properties.orbitTheta;
            myRotationalPhi = properties.rotationalPhi;
            myPrevOrbitalTheta = properties.prevOrbitalTheta;
            hasRings = properties.hasRings();
            ringColor = properties.ringColor;

            childrenBuf.clear();
            for (Integer i : properties.getChildPlanets()) {
                childrenBuf.add(DimensionManager.getInstance().getDimensionProperties(i));
            }
            children = childrenBuf;

            solarOrbitalDistance = properties.getSolarOrbitalDistance();

            isMoon = properties.isMoon();
            if (isMoon) {
                parentProperties = properties.getParentProperties();
                planetOrbitalDistance = properties.getParentOrbitalDistance();
                parentHasRings = parentProperties.hasRings;
                parentRingColor = parentProperties.ringColor;
            }

            sunColor = planetaryProvider.getSunColor(mc.player.getPosition());
            primaryStar = properties.getStar();
            if (primaryStar != null) {
                sunSize = primaryStar.getSize();
            } else {
                primaryStar = DimensionManager.getInstance().getStar(0);
            }

            if (world.provider.getDimension() == ARConfiguration.getCurrentConfig().spaceDimId) {
                isWarp = properties.getParentPlanet() == SpaceObjectManager.WARPDIMID;
                if (isWarp) {
                    SpaceStationObject station = (SpaceStationObject) SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(mc.player.getPosition());
                    if (station != null) travelDirection = station.getForwardDirection();
                }
            }
        } else if (DimensionManager.getInstance().isDimensionCreated(mc.world.provider.getDimension())) {
            properties = DimensionManager.getInstance().getDimensionProperties(mc.world.provider.getDimension());

            atmosphere = properties.getAtmosphereDensityAtHeight(mc.getRenderViewEntity().posY);
            EnumFacing dir = getRotationAxis(properties, mc.player.getPosition());
            axis.x = (float) dir.getFrontOffsetX();
            axis.y = (float) dir.getFrontOffsetY();
            axis.z = (float) dir.getFrontOffsetZ();

            myPhi = properties.orbitalPhi;
            myTheta = properties.orbitTheta;
            myRotationalPhi = properties.rotationalPhi;
            myPrevOrbitalTheta = properties.prevOrbitalTheta;
            hasRings = properties.hasRings();
            ringColor = properties.ringColor;

            childrenBuf.clear();
            for (Integer i : properties.getChildPlanets()) {
                childrenBuf.add(DimensionManager.getInstance().getDimensionProperties(i));
            }
            children = childrenBuf;

            solarOrbitalDistance = properties.getSolarOrbitalDistance();

            isMoon = properties.isMoon();
            if (isMoon) {
                parentProperties = properties.getParentProperties();
                planetOrbitalDistance = properties.getParentOrbitalDistance();
                parentHasRings = parentProperties.hasRings;
                parentHasATM = parentProperties.hasAtmosphere();
                parentRingColor = parentProperties.ringColor;
            }

            float[] sunColorFloat = properties.getSunColor();
            sunColor = new Vec3d(sunColorFloat[0], sunColorFloat[1], sunColorFloat[2]);
            primaryStar = properties.getStar();
            if (primaryStar != null) {
                sunSize = primaryStar.getSize();
            } else {
                primaryStar = DimensionManager.getInstance().getStar(0);
            }

            if (world.provider.getDimension() == ARConfiguration.getCurrentConfig().spaceDimId) {
                isWarp = properties.getParentPlanet() == SpaceObjectManager.WARPDIMID;
                if (isWarp) {
                    SpaceStationObject station = (SpaceStationObject) SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(mc.player.getPosition());
                    if (station != null) travelDirection = station.getForwardDirection();
                }
            }
        } else {
            // No planet provider and dimension not registered: fall back to overworld props (exactly as before)
            childrenBuf.clear();
            children = childrenBuf;
            isMoon = false;
            atmosphere = DimensionManager.overworldProperties.getAtmosphereDensityAtHeight(mc.getRenderViewEntity().posY);
            solarOrbitalDistance = DimensionManager.overworldProperties.orbitalDist;
            sunColor = new Vec3d(1, 1, 1);
            primaryStar = DimensionManager.overworldProperties.getStar();
            properties = DimensionManager.overworldProperties;
        }

        currentplanetphi = myPhi;

        // === Sky color & base dome ===
        GlStateManager.disableTexture2D();
        Vec3d vec3 = Minecraft.getMinecraft().world.getSkyColor(this.mc.getRenderViewEntity(), partialTicks);
        float f1 = (float) vec3.x;
        float f2 = (float) vec3.y;
        float f3 = (float) vec3.z;
        float f6;

        if (this.mc.gameSettings.anaglyph) {
            float f4 = (f1 * 30.0F + f2 * 59.0F + f3 * 11.0F) / 100.0F;
            float f5 = (f1 * 30.0F + f2 * 70.0F) / 100.0F;
            f6 = (f1 * 30.0F + f3 * 70.0F) / 100.0F;
            f1 = f4; f2 = f5; f3 = f6;
        }

        // Atmospheric brightness shaping (unchanged)
        int atmosphereInt = properties.getAtmosphereDensity();
        f1 = atmosphereInt < 1 ? 0 : (float) Math.pow(f1, Math.sqrt(Math.max(atmosphere, 0.0001)));
        f2 = atmosphereInt < 1 ? 0 : (float) Math.pow(f2, Math.sqrt(Math.max(atmosphere, 0.0001)));
        f3 = atmosphereInt < 1 ? 0 : (float) Math.pow(f3, Math.sqrt(Math.max(atmosphere, 0.0001)));
        f1 *= Math.min(1, atmosphere);
        f2 *= Math.min(1, atmosphere);
        f3 *= Math.min(1, atmosphere);

        skycolor[0] = f1;
        skycolor[1] = f2;
        skycolor[2] = f3;

        BufferBuilder buffer = Tessellator.getInstance().getBuffer();

        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GlStateManager.enableFog();
        GlStateManager.color(f1, f2, f3);
        GL11.glCallList(this.glSkyList);
        GlStateManager.disableFog();
        GlStateManager.disableAlpha();
        net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();

        float[] afloat = mc.world.provider.calcSunriseSunsetColors(celestialAngle, partialTicks);
        float f7, f8, f9, f10;

        if (afloat != null) {
            GlStateManager.disableTexture2D();
            GlStateManager.shadeModel(GL11.GL_SMOOTH);
            GL11.glPushMatrix();
            GL11.glRotatef(90.0F, 1.0F, 0.0F, 0.0F);
            GL11.glRotatef(MathHelper.sin(mc.world.getCelestialAngleRadians(partialTicks)) < 0.0F ? 180.0F : 0.0F, 0.0F, 0.0F, 1.0F);
            GL11.glRotated(90.0F - myRotationalPhi, 0.0F, 0.0F, 1.0F);

            f6 = afloat[0];
            f7 = afloat[1];
            f8 = afloat[2];
            float f11;

            if (this.mc.gameSettings.anaglyph) {
                f9 = (f6 * 30.0F + f7 * 59.0F + f8 * 11.0F) / 100.0F;
                f10 = (f6 * 30.0F + f7 * 70.0F) / 100.0F;
                f11 = (f6 * 30.0F + f8 * 70.0F) / 100.0F;
                f6 = f9; f7 = f10; f8 = f11;
            }

            buffer.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
            buffer.pos(0.0D, 100.0D, 0.0D).color(f6, f7, f8, afloat[3] * atmosphere).endVertex();
            byte b0 = 16;

            for (int j = 0; j <= b0; ++j) {
                f11 = (float) j * (float) Math.PI * 2.0F / (float) b0;
                float sx = MathHelper.sin(f11);
                float cx = MathHelper.cos(f11);
                buffer.pos(sx * 120.0F, cx * 120.0F, -cx * 40.0F * afloat[3]).color(afloat[0], afloat[1], afloat[2], 0.0F).endVertex();
            }
            Tessellator.getInstance().draw();
            GL11.glPopMatrix();
            GlStateManager.shadeModel(GL11.GL_FLAT);
        }

        // shadow color multiplier (reused array)
        shadowColorTmp[0] = f1;
        shadowColorTmp[1] = f2;
        shadowColorTmp[2] = f3;

        GlStateManager.enableTexture2D();
        GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE);

        GL11.glPushMatrix();

        // rain alpha handling
        if (atmosphere > 0) f6 = 1.0F - (mc.world.getRainStrength(partialTicks) * (atmosphere / 100f));
        else f6 = 1f;

        f7 = 0.0F; f8 = 0.0F; f9 = 0.0F;
        GlStateManager.color(1.0F, 1.0F, 1.0F, f6);
        GL11.glTranslatef(f7, f8, f9);
        GL11.glRotatef(-90.0F, 0.0F, 1.0F, 0.0F);

        float multiplier = (2 - atmosphere) / 2f;
        if (mc.world.isRainingAt(mc.player.getPosition().add(0, 199, 0))) {
            multiplier *= 1 - mc.world.getRainStrength(partialTicks);
        }

        GL11.glRotatef((float) myRotationalPhi, 0f, 1f, 0f);

        // Rings (unchanged visuals)
        if (hasRings) {
            GL11.glPushMatrix();
            GL11.glRotatef(90f, 0f, 1f, 0f);

            f10 = 100;
            double ringDist = 0;
            bind(DimensionProperties.planetRingsNew);

            GL11.glRotated(70, 1, 0, 0);
            GL11.glTranslated(0, -10, 0);

            GlStateManager.color(ringColor[0], ringColor[1], ringColor[2], multiplier);
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            buffer.pos(f10, ringDist, -f10).tex(1.0D, 0.0D).endVertex();
            buffer.pos(-f10, ringDist, -f10).tex(0.0D, 0.0D).endVertex();
            buffer.pos(-f10, ringDist, f10).tex(0.0D, 1.0D).endVertex();
            buffer.pos(f10, ringDist, f10).tex(1.0D, 1.0D).endVertex();
            Tessellator.getInstance().draw();
            GL11.glPopMatrix();

            // (Shadowed ring quad code left as in original)
            GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE);
        }

        GlStateManager.disableTexture2D();

        // Stars
        float f18 = mc.world.getStarBrightness(partialTicks) * f6;
        float starAlpha = 1 - ((1 - f18) * atmosphere);

        GlStateManager.disableDepth(); // stars always on top of sky Keep? makes asteroids glow :D

        GlStateManager.color(1, 1, 1, 1);
        GL11.glPushMatrix();
        if (isWarp && travelDirection != null) {
            for (int n = -3; n < 5; n++) {
                GL11.glPushMatrix();
                double magnitude = n * -100 + (((System.currentTimeMillis()) + 50) % 2000) / 20f;
                GL11.glTranslated(-travelDirection.getFrontOffsetZ() * magnitude, 0, travelDirection.getFrontOffsetX() * magnitude);
                GL11.glCallList(this.starGLCallList);
                GL11.glPopMatrix();
            }
        } else {
            GL11.glColor4f(1, 1, 1, starAlpha);
            GL11.glCallList(this.starGLCallList);

            if (atmosphere < 0.5f) {
                GL11.glColor4f(1, 1, 1, starAlpha / 2f);
                GL11.glPushMatrix();
                GL11.glRotatef(-90, 0, 1, 0);
                GL11.glCallList(this.starGLCallList);
                GL11.glPopMatrix();
            }
            if (atmosphere < 0.25f) {
                GL11.glColor4f(1, 1, 1, starAlpha / 4f);
                GL11.glPushMatrix();
                GL11.glRotatef(90, 0, 1, 0);
                GL11.glCallList(this.starGLCallList);
                GL11.glPopMatrix();
            }
            GlStateManager.color(1, 1, 1, 1);
        }
        GL11.glPopMatrix();

        GlStateManager.enableTexture2D();

        GlStateManager.enableDepth();    // keep?

        // Sun & sub-stars
        bind(TextureResources.locationSunPng);

        if (!isWarp) {
            if (parentProperties == null || !parentProperties.isStar()) {
                xrotangle = ((float) (properties.getSolarTheta() * 180f / Math.PI) % 360f); // used in black hole path
                drawStarAndSubStars(buffer, primaryStar, properties, solarOrbitalDistance, sunSize, sunColor, multiplier);
                xrotangle = 0;
            }
        }

        // Moons/parent planets (unchanged logic)
        if (DimensionProperties.AtmosphereTypes.SUPERHIGHPRESSURE.denserThan(
                DimensionProperties.AtmosphereTypes.getAtmosphereTypeFromValue((int) (100 * atmosphere)))) {

            if (isMoon && parentProperties != null) {
                GL11.glPushMatrix();

                float planetPositionTheta = AstronomicalBodyHelper.getParentPlanetThetaFromMoon(
                        properties.rotationalPeriod, properties.orbitalDist, (float) parentProperties.getOrbitalMass(),
                        myTheta, properties.baseOrbitTheta);

                GL11.glRotatef((float) myPhi, 0f, 0f, 1f);
                GL11.glRotatef(planetPositionTheta, 1f, 0f, 0f);

                float phiAngle = (float) (myPhi * Math.PI / 180f);
                double x = MathHelper.sin(phiAngle) * MathHelper.cos((float) myTheta);
                double y = -MathHelper.sin((float) myTheta);
                double rotation = -Math.PI / 2f + Math.atan2(x, y) - (myTheta - Math.PI) * MathHelper.sin(phiAngle);

                if (parentHasRings) {
                    xrotangle = -planetPositionTheta + ((float) (myTheta * 180f / Math.PI) % 360f);
                }

                shadowColorTmp[0] = f1;
                shadowColorTmp[1] = f2;
                shadowColorTmp[2] = f3;

                renderPlanet(buffer, parentProperties, planetOrbitalDistance, multiplier, rotation, false, parentHasRings,
                        (float) Math.pow(parentProperties.getGravitationalMultiplier(), 0.4), shadowColorTmp, 1);
                xrotangle = 0;
                GL11.glPopMatrix();
            }

            // init quirk kept as-is
            shadowColorTmp[0] = 1.000001f * shadowColorTmp[0];

            for (DimensionProperties moons : children) {
                GL11.glPushMatrix();

                float planetPositionTheta = (float) ((partialTicks * moons.orbitTheta + ((1 - partialTicks) * moons.prevOrbitalTheta)) * 180F / Math.PI);

                GL11.glRotatef((float) moons.orbitalPhi, 0f, 0f, 1f);
                GL11.glRotated(planetPositionTheta, 1f, 0f, 0f);

                float phiAngle = (float) (moons.orbitalPhi * Math.PI / 180f);
                double x = -MathHelper.sin(phiAngle) * MathHelper.cos((float) moons.orbitTheta);
                double y = MathHelper.sin((float) moons.orbitTheta);
                double rotation = (-Math.PI / 2f + Math.atan2(x, y) - (moons.orbitTheta - Math.PI) * MathHelper.sin(phiAngle)) + Math.PI;

                shadowColorTmp[0] = f1;
                shadowColorTmp[1] = f2;
                shadowColorTmp[2] = f3;

                renderPlanet(buffer, moons, moons.getParentOrbitalDistance(), multiplier, rotation, moons.hasAtmosphere(),
                        moons.hasRings, (float) Math.pow(moons.gravitationalMultiplier, 0.4), shadowColorTmp, 1);
                GL11.glPopMatrix();
            }
        }

        GlStateManager.enableFog();
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();

        GL11.glPopMatrix(); // matching the big push before rings/stars/sun

        // === Asteroid billboards ===
        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1);
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend(); // additive star style keeps them in "sky"
        GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE);

        GlStateManager.disableDepth(); // keep?

        bind(asteroid1);
        GL11.glCallList(this.glSkyList3);

        GL11.glPushMatrix();
        GL11.glRotatef(90, 0.2f, 0.8f, 0);
        bind(asteroid2);
        GL11.glCallList(this.glSkyList3);
        GL11.glRotatef(90, 0.2f, 0.8f, 0);
        bind(asteroid3);
        GL11.glCallList(this.glSkyList3);
        GL11.glPopMatrix();

        GlStateManager.enableDepth(); // keep?

        // === PROPER GL STATE RESET ===
        // Keep depth mask on, but DO NOT clear depth here
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 0, 0);
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableCull();
    }

    protected void drawStarAndSubStars(BufferBuilder buffer, StellarBody sun, DimensionProperties properties, int solarOrbitalDistance, float sunSize, Vec3d sunColor, float multiplier) {
        drawStar(buffer, sun, properties, solarOrbitalDistance, sunSize, sunColor, multiplier);

        List<StellarBody> subStars = sun.getSubStars();
        if (subStars != null && !subStars.isEmpty()) {
            GL11.glPushMatrix();
            float phaseInc = 360f / subStars.size();

            for (StellarBody subStar : subStars) {
                GL11.glRotatef(phaseInc, 0, 1, 0);
                GL11.glPushMatrix();

                GL11.glRotatef(subStar.apparentSeparationDegrees(solarOrbitalDistance), 1, 0, 0);
                float[] color = subStar.getColor();
                drawStar(buffer, subStar, properties, solarOrbitalDistance, subStar.getSize(),
                        new Vec3d(color[0], color[1], color[2]), multiplier);
                GL11.glPopMatrix();
            }
            GL11.glPopMatrix();
        }
    }

    protected ResourceLocation getTextureForPlanet(DimensionProperties properties) {
        return properties.getPlanetIcon();
    }

    protected ResourceLocation getTextureForPlanetLEO(DimensionProperties properties) {
        return properties.getPlanetIcon();
    }

    protected EnumFacing getRotationAxis(DimensionProperties properties, BlockPos pos) {
        return EnumFacing.EAST;
    }

    protected void renderPlanet(BufferBuilder buffer, DimensionProperties properties, float planetOrbitalDistance, float alphaMultiplier, double shadowAngle, boolean hasAtmosphere, boolean hasRing, float gravitationalMultiplier, float[] shadowColorMultiplier, float alphaMultiplier2) {
        renderPlanet2(buffer, properties, 20f * AstronomicalBodyHelper.getBodySizeMultiplier(planetOrbitalDistance) * gravitationalMultiplier, alphaMultiplier, shadowAngle, hasRing, shadowColorMultiplier, alphaMultiplier2);
    }

    protected void renderPlanet2(BufferBuilder buffer, DimensionProperties properties, float size, float alphaMultiplier, double shadowAngle, boolean hasRing, float[] shadowColorMultiplier, float alphaMultiplier2) {
        ResourceLocation icon = getTextureForPlanet(properties);
        boolean hasAtmosphere = properties.hasAtmosphere();
        boolean hasDecorators = properties.hasDecorators();
        boolean gasGiant = properties.isGasGiant();
        float[] skyColor = properties.skyColor;
        float[] ringColor = properties.ringColor;

        // Keep external call identical
        RenderPlanetarySky.renderPlanetPubHelper(
                buffer, icon, 0, 0, -20,
                size * 0.2f, alphaMultiplier, shadowAngle,
                hasAtmosphere, skyColor, ringColor, gasGiant, hasRing, properties.ringAngle,
                hasDecorators, shadowColorMultiplier, alphaMultiplier2
        );
    }

    protected Vector3F<Float> getRotateAxis() {
        return axis;
    }

    public void renderSphere(double x, double y, double z, float radius, int slices, int stacks) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder bufferBuilder = tessellator.getBuffer();

        bufferBuilder.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);

        for (int i = 0; i < slices; i++) {
            for (int j = 0; j < stacks; j++) {
                double firstLong = 2 * Math.PI * (i / (double) slices);
                double secondLong = 2 * Math.PI * ((i + 1) / (double) slices);
                double firstLat = Math.PI * (j / (double) stacks) - Math.PI / 2;
                double secondLat = Math.PI * ((j + 1) / (double) stacks) - Math.PI / 2;

                bufferBuilder.pos(x + radius * Math.cos(firstLat) * Math.cos(firstLong), y + radius * Math.sin(firstLat), z + radius * Math.cos(firstLat) * Math.sin(firstLong)).tex(0.0D, 0.0D).endVertex();
                bufferBuilder.pos(x + radius * Math.cos(secondLat) * Math.cos(firstLong), y + radius * Math.sin(secondLat), z + radius * Math.cos(secondLat) * Math.sin(firstLong)).tex(1.0D, 0.0D).endVertex();
                bufferBuilder.pos(x + radius * Math.cos(secondLat) * Math.cos(secondLong), y + radius * Math.sin(secondLat), z + radius * Math.cos(secondLat) * Math.sin(secondLong)).tex(1.0D, 1.0D).endVertex();
                bufferBuilder.pos(x + radius * Math.cos(firstLat) * Math.cos(secondLong), y + radius * Math.sin(firstLat), z + radius * Math.cos(firstLat) * Math.sin(secondLong)).tex(0.0D, 1.0D).endVertex();
            }
        }
        tessellator.draw();
    }

    protected void drawStar(BufferBuilder buffer, StellarBody sun, DimensionProperties properties, int solarOrbitalDistance, float sunSize, Vec3d sunColor, float multiplier) {
        if (sun != null && sun.isBlackHole()) {
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.01f);
            float f10;
            GL11.glPushMatrix();
            GL11.glTranslatef(0, 30, 0);

            GL11.glDisable(GL11.GL_BLEND);
            GlStateManager.depthMask(true);

            // Black hole sphere
            GL11.glPushMatrix();
            GL11.glTranslatef(0, 100, 0);
            f10 = sunSize * 2f * AstronomicalBodyHelper.getBodySizeMultiplier(solarOrbitalDistance);
            bind(TextureResources.locationWhitePng);
            GlStateManager.disableCull();
            GlStateManager.color(skycolor[0], skycolor[1], skycolor[2]);
            renderSphere(0, 0, 0, f10, 16, 16);
            GlStateManager.enableCull();
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glDepthMask(false);
            GL11.glPopMatrix();

            float diskangle = sun.diskAngle;
            float m = -xrotangle;
            while (m > 360) m -= 360;
            while (m < 0)   m += 360;

            // Dense inner disk - ORIGINAL ROTATIONS
            bind(TextureResources.locationAccretionDiskDense);
            GlStateManager.depthMask(false);
            GlStateManager.disableCull();

            GL11.glPushMatrix();
            GL11.glTranslatef(0, 100, 0);
            GL11.glRotatef(90, 0f, 1f, 0f);
            // Original rotation with speedMult = 5
            GL11.glRotatef((System.currentTimeMillis() % (int) (360 * 360 * 5)) / (360f * 5), 0, 1, 0);
            GlStateManager.color(1f, .7f, .55f, 1f);
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            f10 = sunSize * 6.5f * AstronomicalBodyHelper.getBodySizeMultiplier(solarOrbitalDistance);
            buffer.pos(-f10, 0.0D, -f10).tex(0.0D, 0.0D).endVertex();
            buffer.pos(f10, 0.0D, -f10).tex(1.0D, 0.0D).endVertex();
            buffer.pos(f10, 0.0D, f10).tex(1.0D, 1.0D).endVertex();
            buffer.pos(-f10, 0.0D, f10).tex(0.0D, 1.0D).endVertex();
            Tessellator.getInstance().draw();
            GL11.glPopMatrix();

            // Outer translucent disks - COMPLEX ORIGINAL LOGIC
            bind(TextureResources.locationAccretionDisk);
            for (int i = 0; i < 3; i++) {
                float speedMult = 10.0f; // ORIGINAL CALCULATION: ((0) * 1.01f + 1)/0.1F

                // First layer - 100.01f
                GL11.glPushMatrix();
                GL11.glTranslatef(0, 100.01f, 0);
                // RESTORE ALL ORIGINAL ROTATIONS:
                GL11.glRotatef((float) currentplanetphi, 0f, 1f, 0f);
                GL11.glRotatef(m, 1f, 0f, 0f);
                GL11.glRotatef(diskangle, 0, 0, 1);
                GL11.glRotatef((System.currentTimeMillis() % (int) (speedMult * 36000)) / (100f * speedMult), 0, 1, 0);
                GL11.glRotatef(120 * i, 0, 1, 0);
                GL11.glRotatef(0.5f, 1, 0, 0);

                GlStateManager.color(1f, .5f, .4f, 0.3f);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                f10 = sunSize * 40f * AstronomicalBodyHelper.getBodySizeMultiplier(solarOrbitalDistance);
                buffer.pos(-f10, 0.0D, -f10).tex(0.0D, 0.0D).endVertex();
                buffer.pos(f10, 0.0D, -f10).tex(1.0D, 0.0D).endVertex();
                buffer.pos(f10, 0.0D, f10).tex(1.0D, 1.0D).endVertex();
                buffer.pos(-f10, 0.0D, f10).tex(0.0D, 1.0D).endVertex();
                Tessellator.getInstance().draw();
                GL11.glPopMatrix();

                // Second layer - 100f
                GL11.glPushMatrix();
                GL11.glTranslatef(0, 100f, 0);
                GL11.glRotatef((float) currentplanetphi, 0f, 1f, 0f);
                GL11.glRotatef(m, 1f, 0f, 0f);
                GL11.glRotatef(diskangle, 0, 0, 1);
                GL11.glRotatef((System.currentTimeMillis() % (int) (speedMult * 360 * 50)) / (50f * speedMult), 0, 1, 0);
                GL11.glRotatef(120 * i, 0, 1, 0);
                GL11.glRotatef(0.5f, 1, 0, 0);

                GlStateManager.color(0.8f, .7f, .4f, 0.3f);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                f10 = sunSize * 30f * AstronomicalBodyHelper.getBodySizeMultiplier(solarOrbitalDistance);
                buffer.pos(-f10, 0.0D, -f10).tex(0.0D, 0.0D).endVertex();
                buffer.pos(f10, 0.0D, -f10).tex(1.0D, 0.0D).endVertex();
                buffer.pos(f10, 0.0D, f10).tex(1.0D, 1.0D).endVertex();
                buffer.pos(-f10, 0.0D, f10).tex(0.0D, 1.0D).endVertex();
                Tessellator.getInstance().draw();
                GL11.glPopMatrix();

                // Third layer - 99.99f  
                GL11.glPushMatrix();
                GL11.glTranslatef(0, 99.99f, 0);
                GL11.glRotatef((float) currentplanetphi, 0f, 1f, 0f);
                GL11.glRotatef(m, 1f, 0f, 0f);
                GL11.glRotatef(diskangle, 0, 0, 1);
                GL11.glRotatef((System.currentTimeMillis() % (int) (speedMult * 360 * 25)) / (25f * speedMult), 0, 1, 0);
                GL11.glRotatef(120 * i, 0, 1, 0);
                GL11.glRotatef(0.5f, 1, 0, 0);

                GlStateManager.color(0.2f, .4f, 1f, 0.3f);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                f10 = sunSize * 15f * AstronomicalBodyHelper.getBodySizeMultiplier(solarOrbitalDistance);
                buffer.pos(-f10, 0.0D, -f10).tex(0.0D, 0.0D).endVertex();
                buffer.pos(f10, 0.0D, -f10).tex(1.0D, 0.0D).endVertex();
                buffer.pos(f10, 0.0D, f10).tex(1.0D, 1.0D).endVertex();
                buffer.pos(-f10, 0.0D, f10).tex(0.0D, 1.0D).endVertex();
                Tessellator.getInstance().draw();
                GL11.glPopMatrix();
            }

            // ORIGINAL DEPTH MANAGEMENT
            GlStateManager.depthMask(true);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GlStateManager.depthMask(false);
            
            GL11.glPopMatrix();
            GlStateManager.enableCull();
            //GlStateManager.depthMask(true); // keep ?
        } else {
            // Regular star (quad) path
            bind(TextureResources.locationSunPng);
            GlStateManager.color((float) sunColor.x, (float) sunColor.y, (float) sunColor.z, Math.min((multiplier) * 2f, 1f));
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            float f10 = sunSize * 15f * AstronomicalBodyHelper.getBodySizeMultiplier(solarOrbitalDistance);
            buffer.pos(-f10, 120.0D, -f10).tex(0.0D, 0.0D).endVertex();
            buffer.pos(f10, 120.0D, -f10).tex(1.0D, 0.0D).endVertex();
            buffer.pos(f10, 120.0D, f10).tex(1.0D, 1.0D).endVertex();
            buffer.pos(-f10, 120.0D, f10).tex(0.0D, 1.0D).endVertex();
            Tessellator.getInstance().draw();
        }
    }
}
