package com.deylauncher.launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps the JVM's own crash dumps ({@code hs_err_pid<pid>.log}) from leaking the account's Minecraft
 * access token.
 *
 * <p>Why this class has to exist: when the JVM dies natively it writes an {@code hs_err} file whose
 * {@code Command Line:} and {@code java_command:} lines repeat the WHOLE launch command -- verbatim,
 * including {@code --accessToken <eyJ...>}. That is a live Microsoft session token, and the file is
 * created world-readable in the instance folder. The launcher already asks the JVM to put the dump in
 * the instance's {@code crash-reports/} folder ({@link GameLauncher#errorFileArg}), which is what makes a
 * deterministic cleanup possible -- but two things have to actually happen afterwards:
 *
 * <ol>
 *   <li><b>Redaction</b>: replace the token (and the {@code token:<uuid>} form older versions put in
 *       {@code auth_session}) with a marker, so the file is safe to keep, attach to a bug report or
 *       share. Only the secrets the caller knows about are removed, plus the well-known flag forms --
 *       nothing else in the dump is altered, because that dump is the evidence used to diagnose the crash
 *       (see {@link LaunchDiagnostics}).</li>
 *   <li><b>Owner-only permissions</b>: even redacted, a crash dump is a private file.</li>
 * </ol>
 *
 * <p>Also performs the scrub across files left by EARLIER runs at startup, so a token that already leaked
 * before this fix existed gets cleaned up the next time the launcher runs.
 *
 * <p>Text dumps are read and written as ISO-8859-1 on purpose: that is a 1:1 byte&lt;-&gt;char mapping, so
 * every byte that isn't part of a redacted secret is written back unchanged even though an hs_err dump
 * mixes ASCII headers with raw memory bytes. Nothing here ever fails a launch -- a dump we can't rewrite
 * is reported and left alone.
 */
public final class CrashDumps {

    private CrashDumps() {}

    /** What the launcher puts in place of a secret. ASCII-only, so it can't corrupt a dump's encoding. */
    static final String REDACTED = "<redacted-by-DeyLauncher>";

    /** Reading/writing must not depend on the platform's default charset. */
    private static final java.nio.charset.Charset DUMP_CHARSET = StandardCharsets.ISO_8859_1;

    /** Flag forms the JVM quotes back in a dump, whichever launcher wrote the command line. */
    private static final List<Pattern> SECRET_FLAGS = List.of(
            Pattern.compile("(?i)(--accessToken\\s+)\\S+"),
            Pattern.compile("(?i)(--access_token\\s+)\\S+"),
            Pattern.compile("(?i)(accessToken=)\\S+"),
            Pattern.compile("(?i)(access_token=)\\S+"),
            // the pre-1.13 ${auth_session} placeholder: "token:<access token>:<uuid>"
            Pattern.compile("(?i)(token:)[0-9A-Za-z._-]+"));

    /** What one scrub pass did. */
    public record Result(int scanned, int redacted, int locked, List<String> files) {

        public boolean changedAnything() {
            return redacted > 0;
        }

        /** One line for the launcher log. */
        public String summary() {
            if (scanned == 0) return "no JVM crash dumps to check";
            StringBuilder sb = new StringBuilder("checked ").append(scanned).append(" JVM crash dump(s)");
            if (redacted > 0) sb.append(", redacted account secrets in ").append(redacted);
            if (locked > 0) sb.append(", restricted ").append(locked).append(" to your user account");
            if (redacted == 0 && locked == 0) sb.append(" -- nothing to clean up");
            return sb.toString();
        }
    }

    /** The folder the launch tells the JVM to write its dump into ({@code -XX:ErrorFile}). */
    public static Path dumpsDir(Path gameDirectory) {
        return gameDirectory.resolve("crash-reports");
    }

    /** True for the JVM's own dump files (and its replay dumps), which are the ones that carry secrets. */
    static boolean isCrashDump(String fileName) {
        if (fileName == null) return false;
        String n = fileName.toLowerCase(Locale.ROOT);
        return (n.startsWith("hs_err_pid") || n.startsWith("replay_pid")) && n.endsWith(".log");
    }

    /**
     * Makes sure {@code -XX:ErrorFile=<instance>/crash-reports/hs_err_pid%p.log} can actually be honoured.
     *
     * <p>The JVM does NOT create the parent directory of {@code ErrorFile}: when it doesn't exist the dump
     * is silently written to the process working directory (the instance folder) instead -- which is
     * exactly what was observed on this machine, where two crashes left {@code hs_err_pid30192.log} and
     * {@code hs_err_pid30512.log} in the instance root rather than in {@code crash-reports/}. Creating the
     * folder up front is what keeps dumps predictable, and a predictable location is what lets a later
     * launch find and redact them.
     */
    public static void prepareDumpLocation(Path gameDirectory) {
        if (gameDirectory == null) return;
        try {
            Files.createDirectories(dumpsDir(gameDirectory));
        } catch (IOException ignored) {
            // Best effort: if we can't create it, the JVM falls back to the instance folder, and
            // scrubInstance() scans there too.
        }
    }

    /**
     * Redacts and locks down every crash dump belonging to one instance, wherever the JVM left it.
     *
     * <p>Both locations are scanned: {@code crash-reports/} (where the current launch points the JVM) and
     * the instance folder itself (where the JVM falls back to when that folder is missing, and where
     * dumps written by older launcher builds landed).
     *
     * @param secrets the values to redact -- typically the session access token. Blank/null entries are
     *                ignored, and so are very short ones, so a scrub can never mangle a whole file over a
     *                one-character "secret".
     */
    public static Result scrubInstance(Path gameDirectory, List<String> secrets) {
        List<String> names = new ArrayList<>();
        int scanned = 0, redacted = 0, locked = 0;
        if (gameDirectory == null || !Files.isDirectory(gameDirectory)) {
            return new Result(0, 0, 0, List.of());
        }
        List<Path> dumps = new ArrayList<>();
        collectDumps(dumpsDir(gameDirectory), dumps);
        collectDumps(gameDirectory, dumps); // the JVM's fallback location
        for (Path dump : dumps) {
            scanned++;
            names.add(dump.getFileName().toString());
            if (scrubFile(dump, secrets)) redacted++;
            if (lockDown(dump)) locked++;
        }
        return new Result(scanned, redacted, locked, names);
    }

    /** Adds every crash dump directly inside {@code dir} (never recursive) to {@code out}. */
    private static void collectDumps(Path dir, List<Path> out) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                if (!isCrashDump(p.getFileName().toString())) continue;
                if (!out.contains(p)) out.add(p);
            }
        } catch (IOException ignored) {
            // Unreadable folder: nothing to do, the launch is unaffected.
        }
    }

    /**
     * Redacts one dump in place. Returns true when the file actually changed. Never throws: a dump that
     * can't be rewritten is left as it is (the caller still locks it down).
     */
    static boolean scrubFile(Path dump, List<String> secrets) {
        try {
            String text = new String(Files.readAllBytes(dump), DUMP_CHARSET);
            String cleaned = redact(text, secrets);
            if (cleaned.equals(text)) return false;
            Files.write(dump, cleaned.getBytes(DUMP_CHARSET));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The redaction itself, split out so it can be tested on a string. Removes the caller's known secrets
     * plus the well-known command-line forms a dump quotes back.
     */
    static String redact(String text, List<String> secrets) {
        if (text == null) return null;
        String out = text;
        if (secrets != null) {
            for (String secret : secrets) {
                // A short value is far more likely to be a common substring than a real token, and
                // replacing it would damage the dump's diagnostic value.
                if (secret == null || secret.strip().length() < 8) continue;
                out = out.replace(secret, REDACTED);
            }
        }
        for (Pattern p : SECRET_FLAGS) {
            out = p.matcher(out).replaceAll("$1" + java.util.regex.Matcher.quoteReplacement(REDACTED));
        }
        return out;
    }

    /**
     * Restricts a dump to its owner (0600). Returns true when the file ends up owner-only. On filesystems
     * without POSIX permissions (Windows, FAT) this is a best-effort fallback -- the redaction is what
     * actually protects the secret there.
     */
    static boolean lockDown(Path file) {
        try {
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
                if (Files.getPosixFilePermissions(file).equals(ownerOnly)) return true;
                Files.setPosixFilePermissions(file, ownerOnly);
                return true;
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through to the java.io attempt below
        }
        try {
            java.io.File f = file.toFile();
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setExecutable(false, false);
            boolean owner = f.setReadable(true, true) & f.setWritable(true, true);
            return owner || f.canRead();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
