package zmaster587.advancedRocketry.test.server;

import net.minecraft.util.SoundEvent;
import org.junit.BeforeClass;
import org.junit.Test;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;
import zmaster587.advancedRocketry.util.AudioRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * C035 (FIND-014) — the FML-wiring half of the sound-registration contract.
 *
 * <p>{@link zmaster587.advancedRocketry.test.integration.AudioRegistryRegistrationContractTest}
 * pins that the handler registers the declared set, but it invokes the handler
 * directly — deleting {@code @Mod.EventBusSubscriber} from
 * {@code AudioRegistry.RegistrationHandler} would leave it green. This test
 * closes that hole: after a REAL dedicated-server mod boot, every declared
 * {@code AudioRegistry} SoundEvent must be present in the LIVE Forge registry
 * (queried via the {@code artest registry sounds advancedrocketry} probe).</p>
 *
 * <p>The expected set is derived by reflecting over the declared fields in the
 * test JVM (same classes, {@link MinecraftBootstrap} only) — no hard-coded
 * count or name list, so a 16th sound auto-tightens this guard too.</p>
 */
public class AudioRegistryServerRegistrationTest extends AbstractSharedServerTest {

    @BeforeClass
    public static void bootstrapLocalRegistries() {
        MinecraftBootstrap.ensure();
    }

    @Test
    public void everyDeclaredSoundEventIsInTheLiveRegistryAfterRealBoot() throws Exception {
        Set<String> declaredPaths = new LinkedHashSet<>();
        for (Field field : AudioRegistry.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && SoundEvent.class.isAssignableFrom(field.getType())) {
                SoundEvent event = (SoundEvent) field.get(null);
                assertNotNull("declared SoundEvent field " + field.getName()
                        + " must be initialized", event);
                assertNotNull("declared SoundEvent field " + field.getName()
                        + " must carry a registry name", event.getRegistryName());
                declaredPaths.add(event.getRegistryName().getResourcePath());
            }
        }
        assertTrue("sanity: AudioRegistry should declare more than one SoundEvent, found "
                + declaredPaths.size(), declaredPaths.size() > 1);

        String resp = join(client().execute("artest registry sounds advancedrocketry"));
        assertTrue("registry sounds probe errored: " + resp, resp.contains("\"ok\":true"));
        for (String path : declaredPaths) {
            assertTrue("declared sound missing from the live Forge registry after a "
                    + "real mod boot (FML wiring broken?): " + path + " — " + resp,
                    resp.contains("\"" + path + "\""));
        }
    }

    private static String join(java.util.List<String> resp) {
        return String.join("\n", resp);
    }
}
