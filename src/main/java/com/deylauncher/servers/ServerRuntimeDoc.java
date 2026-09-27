package com.deylauncher.servers;

import java.util.ArrayList;
import java.util.List;

/**
 * The live "who is hosting this server right now" record -- {@code servers/&lt;id&gt;/runtime.json}.
 *
 * <p>This is what makes one shared server usable from several PCs, and it exists because GitHub has no
 * push channel: the launcher that is hosting writes here ("I am running it, here is the public address,
 * I am still alive") and every other launcher reads it. Three things follow from that, and all three
 * are honest limitations rather than bugs:
 *
 * <ul>
 *   <li><b>One host at a time.</b> Starting a server writes {@code state=RUNNING} while carrying the
 *       file's current blob {@code sha}; if someone else got there first the write conflicts, the
 *       starter re-reads, and sees who holds it. That is "the first one to open it wins", enforced by
 *       GitHub's own compare-and-swap instead of a lock service we would have to run.</li>
 *   <li><b>A lease, not an eternal heartbeat.</b> {@link #leaseRenewedAt} plus {@link #leaseSeconds}
 *       mean a host that dies without stopping (power cut, {@code kill -9}) is detected by expiry
 *       instead of blocking the server forever. Expiry is what lets the next person take over.</li>
 *   <li><b>Force stop is cooperative.</b> The owner can only ask (see
 *       {@link ManagersDoc#forceStopRequestedAt}); a host whose launcher is gone sees nothing, and the
 *       server falls back to lease expiry. The UI says so rather than pretending otherwise.</li>
 * </ul>
 */
public class ServerRuntimeDoc {

    /** {@code state} value while a host is running this server. */
    public static final String RUNNING = "RUNNING";
    /** {@code state} value while nobody is hosting. */
    public static final String STOPPED = "STOPPED";

    /** A clean, voluntary stop by the host. */
    public static final String STOP_CLEAN = "CLEAN";
    /** The owner asked this host to stop (force stop). */
    public static final String STOP_FORCED = "FORCED";
    /** The lease ran out because the host stopped refreshing -- their launcher died. */
    public static final String STOP_EXPIRED = "EXPIRED";
    /** The host's launcher saw the server process die on its own. */
    public static final String STOP_CRASH = "CRASH";

    /** {@link #RUNNING} or {@link #STOPPED}. */
    public String state = STOPPED;

    /** Who is hosting: always the account whose PC runs the server process. */
    public String hostUuid;
    public String hostUsername;
    /** {@code ONLINE} / {@code OFFLINE} -- shown so players know whose PC is doing the work. */
    public String hostAccountType = "OFFLINE";

    /**
     * The address players can actually reach while this server runs -- usually the playit tunnel
     * ({@code something.ply.gg}). This is what a {@code dey|alias} address resolves to, and why the
     * same DEY address keeps working when hosting moves to another PC.
     */
    public String publicAddress;

    /** Where the host is reachable on its own network, e.g. {@code 192.168.88.57:25565} -- LAN joins only. */
    public String lanAddress;

    public int port = 25565;

    public long startedAt;
    /** Last time the host refreshed its claim; the lease is dead once this plus {@link #leaseSeconds} passes. */
    public long leaseRenewedAt;
    /** How long a claim stays valid without a refresh. Short enough to recover, long enough to survive a hiccup. */
    public int leaseSeconds = 180;

    public long lastStoppedAt;
    /** Why it last stopped: {@link #STOP_CLEAN}, {@link #STOP_FORCED}, {@link #STOP_EXPIRED} or {@link #STOP_CRASH}. */
    public String lastStopCause;
    public String lastStoppedBy;

    /** Players the host last reported, so a card can show "3 online" without pinging. */
    public List<String> onlinePlayers = new ArrayList<>();

    public ServerRuntimeDoc() {
    }

    /** True when {@code state} says a host is running -- regardless of whether that claim is still fresh. */
    public boolean claimsRunning() {
        return RUNNING.equalsIgnoreCase(state);
    }

    /**
     * True when someone is hosting and their claim has not expired. {@code graceMillis} is added on top
     * of the lease so two PCs whose clocks differ by a little never fight over a server that is in fact
     * still being hosted.
     */
    public boolean isLive(long now, long graceMillis) {
        if (!claimsRunning()) return false;
        long expiry = leaseRenewedAt + (leaseSeconds * 1000L) + Math.max(0L, graceMillis);
        return now <= expiry;
    }

    /** The millisecond timestamp at which this claim stops counting as live. */
    public long leaseExpiry() {
        return leaseRenewedAt + (leaseSeconds * 1000L);
    }

    /** How much of the lease is left; negative once it has expired. */
    public long leaseRemainingMillis(long now) {
        return leaseExpiry() - now;
    }

    /** Marks this document as a live claim by the given host, refreshing the lease. */
    public ServerRuntimeDoc claimBy(String hostUuid, String hostUsername, String hostAccountType) {
        long now = System.currentTimeMillis();
        this.state = RUNNING;
        this.hostUuid = hostUuid;
        this.hostUsername = hostUsername;
        this.hostAccountType = hostAccountType;
        if (startedAt == 0) startedAt = now;
        this.leaseRenewedAt = now;
        return this;
    }

    /** Refreshes the lease for the current host only (a non-host must never extend someone else's claim). */
    public boolean renewLease(String hostUuid) {
        if (hostUuid == null || !hostUuid.equals(this.hostUuid) || !claimsRunning()) return false;
        this.leaseRenewedAt = System.currentTimeMillis();
        return true;
    }

    /** Marks the server stopped, recording why, and clears the fields that only make sense while live. */
    public ServerRuntimeDoc stopWith(String cause, String stoppedBy) {
        this.state = STOPPED;
        this.lastStopCause = cause;
        this.lastStoppedBy = stoppedBy;
        this.lastStoppedAt = System.currentTimeMillis();
        this.publicAddress = null;
        this.leaseRenewedAt = 0;
        if (onlinePlayers != null) onlinePlayers.clear();
        return this;
    }

    /** Repairs a document read back from the repo so callers never see null fields. */
    public ServerRuntimeDoc normalized() {
        if (state == null || state.isBlank()) state = STOPPED;
        if (onlinePlayers == null) onlinePlayers = new ArrayList<>();
        if (leaseSeconds <= 0) leaseSeconds = 180;
        return this;
    }

    /** A sentence for the UI explaining who holds this server (or null when nobody does). */
    public String hostedByMessage() {
        String who = (hostUsername == null || hostUsername.isBlank()) ? "another moderator" : hostUsername;
        return "Already running on " + who + "'s PC"
                + (publicAddress == null || publicAddress.isBlank() ? "" : " (" + publicAddress + ")")
                + ".";
    }
}
