package com.deylauncher.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launch dumps a native JVM crash writes repeat the whole command line, including the account's
 * access token, and land readable by everyone. These tests pin down both halves of the cleanup: the
 * token disappears, and the file ends up owner-only -- without damaging the rest of the dump, which is
 * the evidence used to diagnose the crash.
 */
class CrashDumpsTest {

    /** A fake token shaped like the real one (JWT-ish, long). */
    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.FAKE-TOKEN-VALUE.NOT-REAL-1234567890";

    /** A dump shaped like the real hs_err_pid30512.log from this machine. */
    private static String dumpText(String token) {
        return "#\n"
                + "# A fatal error has been detected by the Java Runtime Environment:\n"
                + "#\n"
                + "#  SIGSEGV (0xb) at pc=0x00007f28a2c2257b, pid=30512, tid=30513\n"
                + "# Problematic frame:\n"
                + "# C  [libglfw.so+0x2257b]\n"
                + "#\n"
                + "Command Line: -Xmx4096M -Djava.library.path=/x/natives --accessToken " + token
                + " --clientId abc --xuid 0 net.minecraft.client.main.Main\n"
                + "java_command: net.fabricmc.loader.impl.launch.knot.KnotClient --accessToken " + token
                + " --userType msa\n"
                + "Stack: [0x00007f28,0x00007f30]\n";
    }

    @Test
    void redactsTheKnownTokenValueEverywhereItAppears() {
        String cleaned = CrashDumps.redact(dumpText(TOKEN), List.of(TOKEN));
        assertFalse(cleaned.contains(TOKEN), "the token must not survive the scrub");
        assertEquals(2, cleaned.split(java.util.regex.Pattern.quote(CrashDumps.REDACTED), -1).length - 1,
                "both the Command Line and the java_command line repeat it");
        // The diagnostics must stay intact: the crash banner is the evidence for the diagnosis.
        assertTrue(cleaned.contains("SIGSEGV"));
        assertTrue(cleaned.contains("libglfw.so"));
        assertTrue(cleaned.contains("--clientId abc"), "non-secret arguments are untouched");
    }

    @Test
    void redactsTheFlagFormWithoutKnowingTheValue() {
        // The startup sweep cannot know a previous session's token -- it relies on the shape the JVM
        // always quotes back. This is what makes cleaning up already-leaked dumps possible.
        String cleaned = CrashDumps.redact(dumpText(TOKEN), List.of());
        assertFalse(cleaned.contains(TOKEN));
        assertTrue(cleaned.contains("--accessToken " + CrashDumps.REDACTED));
    }

    @Test
    void redactsTheLegacyAuthSessionForm() {
        String cleaned = CrashDumps.redact("auth_session=token:" + TOKEN + ":069a79f4-44e9-4726", List.of());
        assertFalse(cleaned.contains(TOKEN));
        assertTrue(cleaned.contains("token:" + CrashDumps.REDACTED), "the token is gone, the uuid may stay");
    }

    @Test
    void leavesVeryShortSecretsAlone() {
        // A one/two-character "secret" is far likelier to be a common substring than a token; replacing
        // it would shred the dump and destroy its diagnostic value.
        String text = "Command Line: -Xmx4096M -Dfoo=bar";
        assertEquals(text, CrashDumps.redact(text, List.of("M", "40", "null")));
    }

    @Test
    void scrubsDumpsInBothLocationsAndLocksThemDownButTouchesNothingElse(@TempDir Path instance)
            throws Exception {
        Path crashReports = CrashDumps.dumpsDir(instance);
        Files.createDirectories(crashReports);
        Path inFolder = crashReports.resolve("hs_err_pid1.log");
        Path inRoot = instance.resolve("hs_err_pid30512.log"); // the JVM's fallback location
        Path notADump = instance.resolve("notes.txt");
        Files.writeString(inFolder, dumpText(TOKEN));
        Files.writeString(inRoot, dumpText(TOKEN));
        Files.writeString(notADump, dumpText(TOKEN));

        CrashDumps.Result result = CrashDumps.scrubInstance(instance, List.of(TOKEN));

        assertEquals(2, result.scanned(), "both dump locations are covered");
        assertEquals(2, result.redacted());
        assertTrue(result.changedAnything());
        assertFalse(Files.readString(inFolder).contains(TOKEN));
        assertFalse(Files.readString(inRoot).contains(TOKEN));
        assertTrue(Files.readString(notADump).contains(TOKEN), "only JVM crash dumps are touched");

        if (Files.getFileStore(inFolder).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            assertEquals(ownerOnly, Files.getPosixFilePermissions(inFolder),
                    "a dump must end up owner-only");
            assertEquals(ownerOnly, Files.getPosixFilePermissions(inRoot));
        }
    }

    @Test
    void scrubIsIdempotentAndSurvivesAnUnreadableInstance(@TempDir Path instance) throws Exception {
        Path dump = instance.resolve("hs_err_pid7.log");
        Files.writeString(dump, dumpText(TOKEN));
        CrashDumps.scrubInstance(instance, List.of(TOKEN));
        CrashDumps.Result second = CrashDumps.scrubInstance(instance, List.of(TOKEN));
        assertEquals(1, second.scanned());
        assertEquals(0, second.redacted(), "an already-clean dump is not rewritten");

        // A folder that doesn't exist must be a no-op, never an exception (callers run this on startup).
        assertEquals(0, CrashDumps.scrubInstance(instance.resolve("nope"), List.of(TOKEN)).scanned());
        assertEquals(0, CrashDumps.scrubInstance(null, List.of(TOKEN)).scanned());
    }

    @Test
    void recognizesOnlyJvmDumpFileNames() {
        assertTrue(CrashDumps.isCrashDump("hs_err_pid30512.log"));
        assertTrue(CrashDumps.isCrashDump("replay_pid123.log"));
        assertFalse(CrashDumps.isCrashDump("latest.log"));
        assertFalse(CrashDumps.isCrashDump("hs_err_pid1.txt"));
        assertFalse(CrashDumps.isCrashDump(null));
    }

    @Test
    void prepareDumpLocationCreatesTheFolderTheErrorFileNeeds(@TempDir Path instance) {
        Path folder = CrashDumps.dumpsDir(instance);
        assertFalse(Files.exists(folder));
        CrashDumps.prepareDumpLocation(instance);
        assertTrue(Files.isDirectory(folder),
                "without this folder the JVM writes the dump to the instance root instead");
    }

    @Test
    void redactionIsBytePreservingForNonAsciiDumpContent() {
        // hs_err dumps mix ASCII with raw memory bytes. Reading/writing must not mangle them, so the
        // scrubber uses a 1:1 byte<->char charset: if it used UTF-8 the raw bytes would be rewritten.
        String raw = "bytes: " + (char) 0xE9 + (char) 0x80 + " --accessToken " + TOKEN + " end";
        String cleaned = CrashDumps.redact(raw, List.of(TOKEN));
        assertFalse(cleaned.contains(TOKEN));
        assertTrue(cleaned.contains("bytes: " + (char) 0xE9 + (char) 0x80));
        assertEquals(cleaned, new String(cleaned.getBytes(StandardCharsets.ISO_8859_1),
                StandardCharsets.ISO_8859_1), "the dump round-trips byte-for-byte outside the secret");
    }
}
