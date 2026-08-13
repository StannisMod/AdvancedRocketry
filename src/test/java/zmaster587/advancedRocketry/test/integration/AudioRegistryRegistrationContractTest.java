package zmaster587.advancedRocketry.test.integration;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.registries.IForgeRegistry;
import org.junit.BeforeClass;
import org.junit.Test;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;
import zmaster587.advancedRocketry.util.AudioRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * C035 — declared-vs-registered contract of {@link AudioRegistry}.
 *
 * <p><b>Contract</b>: every {@code SoundEvent} declared as a static field on
 * {@code AudioRegistry} (each backed by a {@code sounds.json} entry) must be
 * handed to Forge's sound-event registry by
 * {@code RegistrationHandler.registerSoundEvents} — an unregistered event
 * resolves to registry id -1 on the wire ({@code SPacketSoundEffect}) and the
 * client can never play it, silencing the sound everywhere it is used
 * (rocket engines, railgun, laser drill, machine loops, ...).</p>
 *
 * <p>The expected set is derived by reflecting over the declared fields — no
 * hard-coded count — so adding a 16th sound auto-tightens this guard.</p>
 *
 * <p>Repro history: pre-fix this test pinned the wrong behaviour (only
 * {@code electricShockSmall} registered, 14 declared events silently
 * dropped); flipped to the corrected contract with the C035 fix
 * (Path B - the caller drops it).</p>
 */
public class AudioRegistryRegistrationContractTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Test
    public void registerSoundEventsRegistersEveryDeclaredSoundEvent() throws Exception {
        Set<ResourceLocation> declared = declaredSoundEventNames();
        assertTrue("sanity: AudioRegistry should declare more than one SoundEvent, found "
                + declared.size(), declared.size() > 1);

        CapturingSoundRegistry captured = new CapturingSoundRegistry();
        AudioRegistry.RegistrationHandler.registerSoundEvents(
                new RegistryEvent.Register<>(new ResourceLocation("minecraft", "soundevents"), captured));

        Set<ResourceLocation> dropped = new LinkedHashSet<>(declared);
        dropped.removeAll(captured.names);
        assertTrue("every declared SoundEvent must be handed to the Forge registry — dropped: "
                + dropped, dropped.isEmpty());
        assertEquals("registration must be exactly the declared set (no strays)",
                declared, captured.names);
    }

    private static Set<ResourceLocation> declaredSoundEventNames() throws IllegalAccessException {
        Set<ResourceLocation> names = new LinkedHashSet<>();
        for (Field field : AudioRegistry.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && SoundEvent.class.isAssignableFrom(field.getType())) {
                SoundEvent event = (SoundEvent) field.get(null);
                // A declared-but-nameless field would silently fall out of the
                // contract set — fail loudly instead of skipping.
                assertNotNull("declared SoundEvent field " + field.getName()
                        + " must be initialized", event);
                assertNotNull("declared SoundEvent field " + field.getName()
                        + " must carry a registry name", event.getRegistryName());
                names.add(event.getRegistryName());
            }
        }
        return names;
    }

    /**
     * Minimal capturing stand-in for Forge's sound registry — records what the
     * registration handler hands over, nothing more. Only the members the
     * handler (and the Register event ctor) touch are functional.
     */
    private static final class CapturingSoundRegistry implements IForgeRegistry<SoundEvent> {

        final Set<ResourceLocation> names = new LinkedHashSet<>();

        @Override
        public Class<SoundEvent> getRegistrySuperType() {
            return SoundEvent.class;
        }

        @Override
        public void register(SoundEvent value) {
            names.add(value.getRegistryName());
        }

        @SafeVarargs
        @Override
        public final void registerAll(SoundEvent... values) {
            for (SoundEvent value : values) {
                register(value);
            }
        }

        @Override
        public boolean containsKey(ResourceLocation key) {
            return names.contains(key);
        }

        @Override
        public boolean containsValue(SoundEvent value) {
            return value != null && names.contains(value.getRegistryName());
        }

        @Override
        public SoundEvent getValue(ResourceLocation key) {
            throw new UnsupportedOperationException("capturing stub");
        }

        @Override
        public ResourceLocation getKey(SoundEvent value) {
            throw new UnsupportedOperationException("capturing stub");
        }

        @Override
        public Set<ResourceLocation> getKeys() {
            return names;
        }

        @Override
        public List<SoundEvent> getValues() {
            throw new UnsupportedOperationException("capturing stub");
        }

        @Override
        public Set<Map.Entry<ResourceLocation, SoundEvent>> getEntries() {
            throw new UnsupportedOperationException("capturing stub");
        }

        @Override
        public <T> T getSlaveMap(ResourceLocation slaveMapName, Class<T> type) {
            throw new UnsupportedOperationException("capturing stub");
        }

        @Override
        public java.util.Iterator<SoundEvent> iterator() {
            throw new UnsupportedOperationException("capturing stub");
        }
    }
}
