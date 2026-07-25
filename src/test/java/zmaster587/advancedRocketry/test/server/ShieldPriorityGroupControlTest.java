package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P4 control layer (D134-5 / D134-6): priority groups are a domain-level SSOT and consoles are stateless
 * editors onto it. The contracts pinned here are the player-visible promises of the design:
 *
 * <ul>
 *   <li><b>A group pushes its priority into its members</b> — one edit retunes many emitters, so a
 *       20-emitter ship is managed with a handful of groups, not 20 sliders;</li>
 *   <li><b>the setting is emitter-owned</b> — deleting the group leaves the emitters tuned as they were,
 *       so losing the naming layer never silently retunes a shield;</li>
 *   <li><b>consoles are replicated and stateless</b> — a group created from one console is visible and
 *       editable from another, and breaking one console changes nothing;</li>
 *   <li><b>rotating the access code</b> (Layer 3) changes the credential across the domain while leaving
 *       grouping (Layer 2/4) untouched — the layers are genuinely orthogonal.</li>
 * </ul>
 */
public class ShieldPriorityGroupControlTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 64;

    @Test
    public void groupPushesPriorityIntoMemberEmitters() throws Exception {
        int base = 1040, z = 840;
        int e1 = base, e2 = base + 2;
        place("affs:field_generator", e1, z);
        place("affs:field_generator", e2, z);
        int console = base + 4;
        place("affs:shield_console", console, z);

        assertTrue(exec(group(console, z, "create aft 7")).contains("\"ok\":true"));
        assertTrue(exec(group(console, z, "assign aft " + e1 + " " + Y + " " + z)).contains("\"ok\":true"));
        assertTrue(exec(group(console, z, "assign aft " + e2 + " " + Y + " " + z)).contains("\"ok\":true"));

        assertEquals("group priority must be pushed into member emitter 1", 7, readPriority(e1, z));
        assertEquals("group priority must be pushed into member emitter 2", 7, readPriority(e2, z));
        assertEquals("the emitter must report the group that lists it", "aft", readString(read(e1, z), "group"));

        // One edit retunes the whole group — the D134-5 point ("all power to the rear shields").
        assertTrue(exec(group(console, z, "priority aft 3")).contains("\"ok\":true"));
        assertEquals("raising the group must retune member 1", 3, readPriority(e1, z));
        assertEquals("raising the group must retune member 2", 3, readPriority(e2, z));

        // The SETTING is emitter-owned: deleting the naming layer must not retune anything.
        assertTrue(exec(group(console, z, "delete aft")).contains("\"ok\":true"));
        assertEquals("deleting the group silently retuned emitter 1 — the setting is emitter-owned",
                3, readPriority(e1, z));
        assertEquals("deleting the group silently retuned emitter 2 — the setting is emitter-owned",
                3, readPriority(e2, z));
        assertEquals("a deleted group must no longer own the emitter", "", readString(read(e1, z), "group"));
    }

    @Test
    public void anyConsoleEditsTheSameDomainConfig() throws Exception {
        int base = 1040, z = 852;
        int emitter = base;
        place("affs:field_generator", emitter, z);
        int consoleA = base + 3;
        int consoleB = base + 6;
        place("affs:shield_console", consoleA, z);
        place("affs:shield_console", consoleB, z);

        // Created at console A...
        assertTrue(exec(group(consoleA, z, "create bow 5")).contains("\"ok\":true"));
        assertTrue(exec(group(consoleA, z, "assign bow " + emitter + " " + Y + " " + z)).contains("\"ok\":true"));

        // ...visible at console B, because both are views of ONE domain-level config.
        String listedAtB = exec(group(consoleB, z, "list"));
        assertTrue("a group created at one console is not visible at another — the config is not a "
                + "domain-level SSOT:\n" + listedAtB, listedAtB.contains("\"name\":\"bow\""));

        // ...and editable at console B, with the effect landing on the emitter.
        assertTrue(exec(group(consoleB, z, "priority bow 9")).contains("\"ok\":true"));
        assertEquals("an edit made at the second console did not reach the emitter", 9, readPriority(emitter, z));

        // Destroying console A loses nothing: the group still exists and still edits.
        assertTrue(exec("artest place " + DIM + " " + consoleA + " " + Y + " " + z + " minecraft:air")
                .contains("\"placed\":true"));
        String afterBreak = exec(group(consoleB, z, "list"));
        assertTrue("destroying one console lost the domain config — consoles must be stateless:\n"
                + afterBreak, afterBreak.contains("\"name\":\"bow\""));
        assertTrue(exec(group(consoleB, z, "priority bow 2")).contains("\"ok\":true"));
        assertEquals("the surviving console cannot retune after the other was destroyed",
                2, readPriority(emitter, z));
    }

    @Test
    public void rotatingAccessCodeChangesCredentialButNotGrouping() throws Exception {
        int base = 1040, z = 864;
        int emitter = base;
        place("affs:field_generator", emitter, z);
        int console = base + 3;
        place("affs:shield_console", console, z);

        assertTrue(exec(group(console, z, "create hull 4")).contains("\"ok\":true"));
        assertTrue(exec(group(console, z, "assign hull " + emitter + " " + Y + " " + z)).contains("\"ok\":true"));
        String codeBefore = readString(read(emitter, z), "accessCode");

        String rotated = exec("artest shield rotate-code " + DIM + " " + console + " " + Y + " " + z);
        assertTrue("rotation failed:\n" + rotated, rotated.contains("\"ok\":true"));
        String newCode = readString(rotated, "code");
        assertFalse("the rotated code is empty — nothing was regenerated", newCode.isEmpty());
        assertFalse("rotation produced the same code as before (" + newCode + ") — a leaked code would "
                + "still work", newCode.equals(codeBefore));

        String afterRotation = read(emitter, z);
        assertEquals("the new credential was not written to the emitter", newCode,
                readString(afterRotation, "accessCode"));
        // Layer 3 (credential) and Layer 2/4 (grouping) are orthogonal: rotating one must not disturb the other.
        assertEquals("rotating the access code disturbed the emitter's group membership",
                "hull", readString(afterRotation, "group"));
        assertEquals("rotating the access code disturbed the redistribution priority",
                4, readPriority(emitter, z));
    }

    private static String group(int x, int z, String opAndArgs) {
        return "artest shield group " + DIM + " " + x + " " + Y + " " + z + " " + opAndArgs;
    }

    private String read(int x, int z) throws Exception {
        return exec("artest shield read " + DIM + " " + x + " " + Y + " " + z);
    }

    private int readPriority(int x, int z) throws Exception {
        String json = read(x, z);
        Matcher m = Pattern.compile("\"priority\":(-?\\d+)").matcher(json);
        assertTrue("no priority in probe response: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static String readString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        assertTrue("no " + key + " field in: " + json, m.find());
        return m.group(1);
    }

    private void place(String block, int x, int z) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + Y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + Y + "," + z + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private static String exec(String command) throws Exception {
        return join(client().execute(command));
    }

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
