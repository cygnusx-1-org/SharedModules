package com.liskovsoft.sharedutils.okhttp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.sharedutils.prefs.GlobalPreferences;

import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Whether the DNS chosen in the settings answers (see PreferredDns): each of its servers asked for one name, all at once, over UDP or
 * HTTPS as the app's lookups ask them, none of their answers kept. The system's DNS is asked as the app asks it, as a whole: Android
 * picks its servers (Private DNS's among them).
 */
public final class DnsCheck {
    /**
     * The name asked: the app's first lookup
     */
    public static final String HOSTNAME = "www.youtube.com";
    /**
     * Each server's, asked at once: the lookups give one no longer (see PublicDnsResolver)
     */
    public static final long SERVER_TIMEOUT_MS = 5_000;
    /**
     * The system's lookup has no timeout to set: it's waited for no longer than this
     */
    public static final long SYSTEM_TIMEOUT_MS = 10_000;
    /**
     * Daemons: none keeps the app's process up
     */
    private static final ExecutorService sChecks = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "DnsCheck");
        thread.setDaemon(true);
        return thread;
    });
    private final List<String> mNames;
    private final List<Resolver> mServers;
    private final SystemLookup mSystemLookup;

    public enum Status {
        /**
         * It answered with an address
         */
        WORKS,
        /**
         * It answered that the name has no address, or with a filter's (0.0.0.0, 127.0.0.1): YouTube is blocked
         */
        BLOCKED,
        /**
         * No answer in time
         */
        TIMEOUT,
        /**
         * No route to it from the network, e.g. an IPv6 server on a network without IPv6
         */
        UNREACHABLE,
        /**
         * An error: the server's (e.g. SERVFAIL, REFUSED), or of DNS-over-HTTPS (e.g. TLS, an HTTP status)
         */
        FAILED
    }

    public static final class Result {
        @NonNull
        public final Status status;
        /**
         * The first one that isn't a filter's, or the filter's: null for none
         */
        @Nullable
        public final InetAddress address;
        /**
         * From the question to the answer, or to the error
         */
        public final long timeMs;
        /**
         * What failed, e.g. "SERVFAIL" or an exception's message: null for nothing
         */
        @Nullable
        public final String detail;

        public Result(@NonNull Status status, @Nullable InetAddress address, long timeMs, @Nullable String detail) {
            this.status = status;
            this.address = address;
            this.timeMs = timeMs;
            this.detail = detail;
        }
    }

    public interface Listener {
        /**
         * On a thread of the check's own
         *
         * @param index the server's in getServers()
         */
        void onResult(int index, @NonNull Result result);
    }

    @VisibleForTesting
    interface SystemLookup {
        List<InetAddress> lookup(String hostname) throws IOException;
    }

    /**
     * @param servers by their address or URL, in the order asked: none for the system's DNS
     */
    @VisibleForTesting
    DnsCheck(@NonNull Map<String, Resolver> servers, @NonNull SystemLookup systemLookup) {
        mNames = new ArrayList<>(servers.keySet());
        mServers = new ArrayList<>(servers.values());
        mSystemLookup = systemLookup;

        if (mNames.isEmpty()) {
            mNames.add(null);
        }
    }

    /**
     * Of the DNS the settings name now
     */
    @NonNull
    public static DnsCheck ofSettings() {
        return new DnsCheck(PreferredDns.getServers(GlobalPreferences.sInstance),
                hostname -> Arrays.asList(InetAddress.getAllByName(hostname)));
    }

    /**
     * Of these servers, each asked over UDP: e.g. the network's, the system's DNS's. The ones that aren't addresses are left out:
     * none, the system's DNS asked as a whole.
     */
    @NonNull
    public static DnsCheck ofServers(@NonNull List<String> addresses) {
        return new DnsCheck(PublicDnsResolver.plainServers(addresses), hostname -> Arrays.asList(InetAddress.getAllByName(hostname)));
    }

    /**
     * Each server's address or DNS-over-HTTPS URL, in the order the lookups ask them: one null, for the system's DNS, when it's that
     */
    @NonNull
    public List<String> getServers() {
        return Collections.unmodifiableList(mNames);
    }

    /**
     * Each server asked at once: the listener is called once for each, as it answers or fails, in no order
     */
    public void start(@NonNull Listener listener) {
        if (mServers.isEmpty()) {
            sChecks.execute(() -> listener.onResult(0, checkSystem()));
            return;
        }

        for (int i = 0; i < mServers.size(); i++) {
            int index = i;
            Resolver server = mServers.get(i);
            sChecks.execute(() -> listener.onResult(index, check(server)));
        }
    }

    @VisibleForTesting
    static Result check(@NonNull Resolver server) {
        long startNs = System.nanoTime();

        try {
            server.setTimeout(Duration.ofMillis(SERVER_TIMEOUT_MS));
            Message query = Message.newQuery(org.xbill.DNS.Record.newRecord(Name.fromConstantString(HOSTNAME + "."), Type.A, DClass.IN));
            Message answer = server.send(query);
            int rcode = answer.getRcode();

            if (rcode == Rcode.NXDOMAIN) {
                return new Result(Status.BLOCKED, null, elapsedMs(startNs), Rcode.string(rcode));
            }

            if (rcode != Rcode.NOERROR) {
                return new Result(Status.FAILED, null, elapsedMs(startNs), Rcode.string(rcode));
            }

            List<InetAddress> addresses = new ArrayList<>();

            // After the CNAMEs (www.youtube.com is one)
            for (org.xbill.DNS.Record record : answer.getSection(Section.ANSWER)) {
                if (record instanceof ARecord) {
                    addresses.add(((ARecord) record).getAddress());
                }
            }

            return ofAddresses(addresses, startNs);
        } catch (Throwable e) {
            // An IOException, or a RuntimeException or Error of dnsjava's (see PublicDnsResolver.query)
            return ofError(e, startNs);
        }
    }

    private Result checkSystem() {
        long startNs = System.nanoTime();
        Future<List<InetAddress>> lookup = sChecks.submit(() -> mSystemLookup.lookup(HOSTNAME));

        try {
            return ofAddresses(lookup.get(SYSTEM_TIMEOUT_MS, TimeUnit.MILLISECONDS), startNs);
        } catch (TimeoutException e) {
            lookup.cancel(true);
            return new Result(Status.TIMEOUT, null, elapsedMs(startNs), null);
        } catch (InterruptedException e) {
            lookup.cancel(true);
            Thread.currentThread().interrupt();
            return ofError(e, startNs);
        } catch (ExecutionException e) {
            return ofError(e.getCause() != null ? e.getCause() : e, startNs);
        }
    }

    private static Result ofAddresses(List<InetAddress> addresses, long startNs) {
        for (InetAddress address : addresses) {
            if (!isFilters(address)) {
                return new Result(Status.WORKS, address, elapsedMs(startNs), null);
            }
        }

        return new Result(Status.BLOCKED, addresses.isEmpty() ? null : addresses.get(0), elapsedMs(startNs), null);
    }

    /**
     * Where a filtering DNS sends a name it blocks
     */
    private static boolean isFilters(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress();
    }

    private static Result ofError(@NonNull Throwable error, long startNs) {
        long timeMs = elapsedMs(startNs);

        for (Throwable e = error; e != null; e = e.getCause()) {
            String message = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";

            // SocketTimeoutException is one, and OkHttp's call timeout ("timeout"). dnsjava's own: "Timed out while trying to resolve".
            if (e instanceof InterruptedIOException || e instanceof TimeoutException || message.contains("timed out")) {
                return new Result(Status.TIMEOUT, null, timeMs, null);
            }

            // "Network is unreachable" (ENETUNREACH) is a SocketException only
            if (e instanceof NoRouteToHostException || e instanceof PortUnreachableException || e instanceof ConnectException
                    || (e instanceof SocketException && message.contains("unreachable"))) {
                return new Result(Status.UNREACHABLE, null, timeMs, null);
            }
        }

        return new Result(Status.FAILED, null, timeMs, error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
}
