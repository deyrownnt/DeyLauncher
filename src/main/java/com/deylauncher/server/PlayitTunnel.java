package com.deylauncher.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages a playit.ggs agent so a locally-running Minecraft server becomes reachable from the
 * public Internet for free -- no router port-forwarding and no per-user cost (playit's free
 * manual plan). The agent binary is downloaded once into a tools dir and run per-server with its
 * own config dir so every server can have a separate tunnel + pairing.
 *
 * The honest, free caveat: playit still needs (a) a free playit.ggs account and (b) a one-time
 * pairing -- an agent token (or the one-time claim device flow) copied from the playit dashboard.
 * Once paired and a "TCP tunnel" pointing at 127.0.0.1:&lt;port&gt; is added in that same dashboard,
 * the agent connects to playit's network and forwards the port to a public *.playit.ggs / *.ply.gg
 * address. We try to parse that address straight out of the agent's stdout; if the agent doesn't
 * print it (it can be silent in headless/non-TTY mode), the UI lets the user paste the address the
 * dashboard shows. Either way that same address is what gets auto-shared to friends.
 */
public class PlayitTunnel {

    /** Callback the UI listens to while the agent runs. */
    public interface Listener {
        /** Every line the agent prints (debug / console echo). */
        void onLine(String line);
        /** A public address was parsed from agent output; also re-fires for later lines. */
        void onAddress(String address);
        /** The agent printed something that suggests it isn't paired/logged in yet. */
        void onPairingNeeded(String hint);
    }

    /** A *.playit.ggs or legacy *.ply.gg host, optionally with a :port suffix. */
    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
            "([A-Za-z0-9][A-Za-z0-9.-]*\\.(?:playit\\.ggs|ply\\.gg))(:\\d{1,5})?");

    private static final Pattern PAIRING_HINT = Pattern.compile(
            "(?i)(claim|pair|login|sign in|agent token|device code)");

    private final Path toolsDir;
    private final Path configDir;
    private final HttpClient http = HttpClient.newHttpClient();

    private volatile Process process;
    private volatile Thread readerThread;
    private volatile String publicAddress;
    private volatile String manualAddress;
    private volatile boolean running;

    public PlayitTunnel(Path toolsDir, Path configDir) {
        this.toolsDir = toolsDir;
        this.configDir = configDir;
    }

    /** The newest public address detected so far (or null if none yet). */
    public String publicAddress() {
        return manualAddress != null ? manualAddress : publicAddress;
    }

    /** Lets the user paste the address their playit dashboard assigned (in case the agent doesn't print it). */
    public void setManualAddress(String address) {
        this.manualAddress = (address == null || address.isBlank()) ? null : address.trim();
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** Path to a per-server agent config directory (created as needed by start/pair). */
    public Path configDir() {
        return configDir;
    }
/**
     * Ensures a playit agent binary exists for this OS+arch, downloading it on first use. Free and
     * the binary is the official open-source release. Throws IOException if the download fails.
     */
    public Path agentBinary() throws Exception {
        Files.createDirectories(toolsDir);
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String asset;
        String outName;
        if (os.contains("win")) {
            asset = "playit-windows-x86_64.exe";
            outName = "playit.exe";
        } else if (os.contains("mac")) {
            asset = arch.contains("aarch64") || arch.contains("arm64")
                    ? "playit-darwin-arm64" : "playit-darwin-amd64";
            outName = "playit";
        } else {
            asset = arch.contains("aarch64") || arch.contains("arm64")
                    ? "playit-linux-arm64" : "playit-linux-amd64";
            outName = "playit";
        }
        Path bin = toolsDir.resolve(outName);
        if (Files.exists(bin)) return bin;

        String url = "https://github.com/playit-cloud/playit-agent/releases/latest/download/" + asset;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET()
                .header("User-Agent", "DeyLauncher/0.8").build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(bin));
        if (resp.statusCode() / 100 != 2) {
            Files.deleteIfExists(bin);
            throw new IOException("playit download failed: HTTP " + resp.statusCode());
        }
        bin.toFile().setExecutable(true, true);
        return bin;
    }

    /**
     * One-time pairing: runs the agent with `--claim <code>` (device-flow login using a code from
     * the playit dashboard), bounded to ~45s so it doesn't hang the launcher. On success the agent
     * writes its credentials into configDir and we kill it; a normal start() afterwards uses them.
     * Returns the collected output lines and whether the agent looked like it completed pairing.
     */
    public PairResult pair(String claimCode) throws Exception {
        Path bin = agentBinary();
        Files.createDirectories(configDir);
        ProcessBuilder pb = new ProcessBuilder(bin.toString(), "--claim", claimCode.trim());
        pb.directory(configDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            long deadline = System.currentTimeMillis() + 45_000;
            while (System.currentTimeMillis() < deadline && (line = r.readLine()) != null) {
                lines.add(line);
            }
        }
        boolean exited = p.waitFor(5, TimeUnit.SECONDS);
        if (!exited) p.destroyForcibly();
        boolean paired = lines.stream().anyMatch(l ->
                l.matches("(?i).*(success|paired|claim .*done|you're all set).*"));
        return new PairResult(lines, paired);
    }

    public record PairResult(List<String> output, boolean paired) {}
/** Starts the agent for this server's config dir and streams output to the listener. */
    public void start(Listener listener) throws Exception {
        Path bin = agentBinary();
        Files.createDirectories(configDir);
        ProcessBuilder pb = new ProcessBuilder(bin.toString());
        pb.directory(configDir.toFile());
        pb.redirectErrorStream(true);
        process = pb.start();
        running = true;
        readerThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while (running && process != null && process.isAlive() && (line = r.readLine()) != null) {
                    if (listener != null) {
                        listener.onLine(line);
                        if (PAIRING_HINT.matcher(line).find()
                                && !line.toLowerCase().contains("success")
                                && !line.toLowerCase().contains("paired")) {
                            listener.onPairingNeeded(line);
                        }
                    }
                    String parsed = extractAddress(line);
                    if (parsed != null) {
                        publicAddress = parsed;
                        if (listener != null) listener.onAddress(parsed);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                running = false;
            }
        }, "playit-agent-reader-" + configDir.getFileName());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** Stops the agent process (best-effort; playit is fine being killed). */
    public void stop() {
        running = false;
        Process p = process;
        if (p != null) {
            boolean exited = false;
            try {
                p.destroy();
                exited = p.waitFor(8, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            if (!exited) p.destroyForcibly();
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        process = null;
        // Leave publicAddress in place so the UI can keep showing it after a stop.
    }

    private static String extractAddress(String line) {
        if (line == null) return null;
        Matcher m = ADDRESS_PATTERN.matcher(line);
        if (!m.find()) return null;
        String host = m.group(1);
        String port = m.group(2) != null ? m.group(2) : "";
        return trimPunctuation(host) + port;
    }

    private static String trimPunctuation(String s) {
        int end = s.length();
        while (end > 0 && !Character.isLetterOrDigit(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }
}