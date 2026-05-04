package zmaster587.advancedRocketry.asm.compat.plustic;

import net.minecraft.entity.Entity;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import zmaster587.advancedRocketry.api.ARConfiguration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class PlusTiCPacketReleaseEntityTransformer implements IClassTransformer {

    private static final String TARGET_CLASS = "landmaster.plustic.net.PacketReleaseEntity";

    private static final String ENTITY_OWNER = "net/minecraft/entity/Entity";
    private static final String SET_LOCATION_AND_ANGLES = "setLocationAndAngles";
    private static final String SET_LOCATION_AND_ANGLES_SRG = "func_70012_b";
    private static final String SET_LOCATION_AND_ANGLES_DESC = "(DDDFF)V";

    private static final String HOOK_OWNER =
            "zmaster587/advancedRocketry/asm/compat/plustic/PlusTiCPacketReleaseEntityTransformer";
    private static final String HOOK_NAME =
            "advancedRocketry$setLocationAndAnglesPreserveRocketYaw";
    private static final String HOOK_DESC =
            "(Lnet/minecraft/entity/Entity;DDDFF)V";

    private static volatile Method advancedRocketry$setLocationAndAnglesMethod;
    private static volatile boolean advancedRocketry$setLocationAndAnglesMethodLookupFailed;
    private static volatile Field advancedRocketry$entityRotationYawField;
    private static volatile boolean advancedRocketry$entityRotationYawFieldLookupFailed;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }

        if (!TARGET_CLASS.equals(transformedName) && !TARGET_CLASS.equals(name)) {
            return basicClass;
        }

        if (!advancedRocketry$isCompatEnabled()) {
            System.out.println("[AdvancedRocketry/PlusTiC] PlusTiC Portly rocket compatibility transformer disabled. Leaving " + transformedName + " untouched.");
            return basicClass;
        }

        System.out.println("[AdvancedRocketry/PlusTiC] Transforming " + transformedName);

        try {
            ClassNode classNode = new ClassNode();
            ClassReader classReader = new ClassReader(basicClass);
            classReader.accept(classNode, 0);

            int patchedCalls = 0;

            for (MethodNode method : classNode.methods) {
                patchedCalls += patchMethod(method);
            }

            if (patchedCalls <= 0) {
                System.out.println("[AdvancedRocketry/PlusTiC] No Entity#setLocationAndAngles calls found in " + transformedName);
                return basicClass;
            }

            if (patchedCalls > 1) {
                System.out.println("[AdvancedRocketry/PlusTiC] Warning: patched more than one Entity#setLocationAndAngles call in " + transformedName);
            }

            ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(classWriter);

            System.out.println("[AdvancedRocketry/PlusTiC] Patched " + patchedCalls + " Entity#setLocationAndAngles call(s) in " + transformedName);

            return classWriter.toByteArray();
        } catch (Throwable throwable) {
            System.out.println("[AdvancedRocketry/PlusTiC] Failed to transform " + transformedName);
            throwable.printStackTrace();
            return basicClass;
        }
    }

    private static int patchMethod(MethodNode method) {
        int patchedCalls = 0;

        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null;
             instruction = instruction.getNext()) {

            if (!(instruction instanceof MethodInsnNode)) {
                continue;
            }

            MethodInsnNode methodInsn = (MethodInsnNode) instruction;

            if (methodInsn.getOpcode() != Opcodes.INVOKEVIRTUAL) {
                continue;
            }

            if (!ENTITY_OWNER.equals(methodInsn.owner)) {
                continue;
            }

            if (!SET_LOCATION_AND_ANGLES.equals(methodInsn.name)
                    && !SET_LOCATION_AND_ANGLES_SRG.equals(methodInsn.name)) {
                continue;
            }

            if (!SET_LOCATION_AND_ANGLES_DESC.equals(methodInsn.desc)) {
                continue;
            }

            methodInsn.setOpcode(Opcodes.INVOKESTATIC);
            methodInsn.owner = HOOK_OWNER;
            methodInsn.name = HOOK_NAME;
            methodInsn.desc = HOOK_DESC;

            patchedCalls++;
        }

        return patchedCalls;
    }

    public static void advancedRocketry$setLocationAndAnglesPreserveRocketYaw(
            Entity entity,
            double x,
            double y,
            double z,
            float yaw,
            float pitch) {

        if (entity == null) {
            return;
        }

        if (advancedRocketry$isRocketEntity(entity)) {
            yaw = advancedRocketry$getEntityRotationYaw(entity, yaw);
        }

        advancedRocketry$setLocationAndAngles(entity, x, y, z, yaw, pitch);
    }

    private static boolean advancedRocketry$isRocketEntity(Entity entity) {
        return entity != null
                && "zmaster587.advancedRocketry.entity.EntityRocket".equals(entity.getClass().getName());
    }

    private static float advancedRocketry$getEntityRotationYaw(Entity entity, float fallbackYaw) {
        Field field = advancedRocketry$entityRotationYawField;

        if (field == null && !advancedRocketry$entityRotationYawFieldLookupFailed) {
            field = advancedRocketry$findEntityRotationYawField();
        }

        if (field == null) {
            return fallbackYaw;
        }

        try {
            return field.getFloat(entity);
        } catch (Throwable throwable) {
            return fallbackYaw;
        }
    }

    private static void advancedRocketry$setLocationAndAngles(
            Entity entity,
            double x,
            double y,
            double z,
            float yaw,
            float pitch) {

        Method method = advancedRocketry$setLocationAndAnglesMethod;

        if (method == null && !advancedRocketry$setLocationAndAnglesMethodLookupFailed) {
            method = advancedRocketry$findSetLocationAndAnglesMethod();
        }

        if (method == null) {
            return;
        }

        try {
            method.invoke(entity, x, y, z, yaw, pitch);
        } catch (Throwable throwable) {
            System.out.println("[AdvancedRocketry/PlusTiC] Failed to call Entity#setLocationAndAngles reflectively.");
            throwable.printStackTrace();
        }
    }

    private static Method advancedRocketry$findSetLocationAndAnglesMethod() {
        try {
            Method method = ReflectionHelper.findMethod(
                    Entity.class,
                    "setLocationAndAngles",
                    "func_70012_b",
                    double.class,
                    double.class,
                    double.class,
                    float.class,
                    float.class
            );

            method.setAccessible(true);
            advancedRocketry$setLocationAndAnglesMethod = method;
            return method;
        } catch (Throwable throwable) {
            advancedRocketry$setLocationAndAnglesMethodLookupFailed = true;
            System.out.println("[AdvancedRocketry/PlusTiC] Failed to find Entity#setLocationAndAngles. Entity release will be skipped.");
            throwable.printStackTrace();
            return null;
        }
    }

    private static Field advancedRocketry$findEntityRotationYawField() {
        try {
            Field field = ReflectionHelper.findField(Entity.class, "rotationYaw", "field_70177_z");
            field.setAccessible(true);
            advancedRocketry$entityRotationYawField = field;
            return field;
        } catch (Throwable throwable) {
            advancedRocketry$entityRotationYawFieldLookupFailed = true;
            System.out.println("[AdvancedRocketry/PlusTiC] Failed to find Entity#rotationYaw field. Falling back to PacketReleaseEntity yaw.");
            throwable.printStackTrace();
            return null;
        }
    }

    private static boolean advancedRocketry$isCompatEnabled() {
        if (Boolean.getBoolean("advancedrocketry.disablePlusTiCPortlyRocketCompat")) {
            return false;
        }

        try {
            return ARConfiguration.getCurrentConfig().enablePlusTiCPortlyRocketCompat;
        } catch (Throwable throwable) {
            System.out.println("[AdvancedRocketry/PlusTiC] Could not read compat config. Enabling PlusTiC Portly rocket compatibility by default. " +
                    "If problems occur, use JVM argument '-Dadvancedrocketry.disablePlusTiCPortlyRocketCompat=true' and report issue on GitHub.");
            throwable.printStackTrace();
            return true;
        }
    }
}