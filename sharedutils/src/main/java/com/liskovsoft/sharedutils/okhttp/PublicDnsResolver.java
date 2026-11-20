package com.liskovsoft.sharedutils.okhttp;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import androidx.annotation.Nullable;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.Address;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Asks public DNS servers, over UDP or HTTPS (see GlobalPreferences.DNS_PROTOCOL_*), rather than the system's DNS.<br/>
 * Gives the AAAA addresses first, then the A ones, when IPv6 answers — avoids redirect loops on some CDNs. Both are asked at once:
 * one after the other, each new connection waited for two round trips to the server (V8RCD1SA: 274-923 ms of each first connect, against
 * 99-157 ms to connect). Not AAAA while IPv6 doesn't connect (see Ipv6CheckDns): its addresses would be dropped.
 */
public class PublicDnsResolver implements Dns {
    private static final int PROBE_TIMEOUT_MS = 500;
    private static final long SERVER_TIMEOUT_MS = 5_000;
    /**
     * The servers of a query wait no longer than this together, as Google's two did (5 s each): four that don't answer (e.g. UDP 53
     * blocked) waited 20 s before the system's DNS, each query
     */
    private static final long QUERY_TIMEOUT_MS = 10_000;
    /**
     * The AAAA queries, while the lookup's thread asks for A. Daemons: none keeps the app's process up.
     */
    private static final ExecutorService sAaaaQueries = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "PublicDnsResolver AAAA");
        thread.setDaemon(true);
        return thread;
    });
    private static final Object sLock = new Object();
    static final Probe DEFAULT_PROBE = PublicDnsResolver::isReachable;
    private static volatile Probe sProbe = DEFAULT_PROBE;
    // Null till probed
    private static volatile Boolean sIsIpv6Reachable;
    /**
     * An answer is kept no longer than this, whatever its records' TTL
     */
    private static final long MAX_CACHE_MS = 10 * 60 * 1_000;
    /**
     * Asked in turn, from the one that answered last, while each fails (e.g. the IPv4 ones on a network without IPv4)
     */
    private final List<Resolver> mServers;
    private volatile int mAnswering;
    /**
     * By name and type: the addresses got, kept for their records' TTL, as the system's resolver keeps them. Each new connection of the
     * player asked the server again: 43 of 70 spent 111-436 ms on it (9E8WV5TE). An empty answer isn't kept.
     */
    private final Map<String, Answer> mCache = new ConcurrentHashMap<>();

    private static final class Answer {
        final List<InetAddress> addresses;
        /**
         * No answer came (e.g. a timeout, no route to the server, a server failure): another server may give one. An answer
         * without addresses (the name or its type doesn't exist) is the same on each.
         */
        final boolean isFailed;
        /**
         * The least of its records', in seconds
         */
        final long ttlSec;
        final long expiresAtMs;

        Answer(List<InetAddress> addresses, long ttlSec, long nowMs, boolean isFailed) {
            this.addresses = addresses;
            this.ttlSec = ttlSec;
            this.expiresAtMs = nowMs + Math.min(ttlSec * 1_000, MAX_CACHE_MS);
            this.isFailed = isFailed;
        }

        static Answer failed(long nowMs) {
            return new Answer(new ArrayList<>(), -1, nowMs, true);
        }
    }

    @VisibleForTesting
    PublicDnsResolver(List<Resolver> servers) {
        if (servers.isEmpty()) {
            throw new IllegalArgumentException("No DNS server");
        }

        mServers = new ArrayList<>(servers);
        Duration timeout = Duration.ofMillis(Math.min(SERVER_TIMEOUT_MS, QUERY_TIMEOUT_MS / mServers.size()));

        for (Resolver server : mServers) {
            server.setTimeout(timeout);
        }
    }

    /**
     * Asked over UDP, in this order
     *
     * @param addresses IPv4 or IPv6 addresses: the ones that aren't are left out
     * @return null when none is
     */
    @Nullable
    public static PublicDnsResolver plain(List<String> addresses) {
        Map<String, Resolver> servers = plainServers(addresses);

        return servers.isEmpty() ? null : new PublicDnsResolver(new ArrayList<>(servers.values()));
    }

    /**
     * Each address's server, asked over UDP, by the address as it's given (trimmed), in its order: the ones that aren't addresses are
     * left out
     */
    @NonNull
    static Map<String, Resolver> plainServers(List<String> addresses) {
        Map<String, Resolver> servers = new LinkedHashMap<>();

        for (String address : addresses) {
            InetAddress server = parseAddress(address);

            if (server != null) {
                servers.put(address.trim(), new SimpleResolver(server));
            }
        }

        return servers;
    }

    /**
     * Asked over HTTPS
     *
     * @param url an https URL (see isDohUrl)
     * @param bootstrapAddresses where its host is (IPv4 or IPv6 addresses), or none to look it up with the system's DNS
     * @return null when the URL isn't one
     */
    @Nullable
    public static PublicDnsResolver doh(String url, List<String> bootstrapAddresses) {
        Resolver server = dohServer(url, bootstrapAddresses);

        return server != null ? new PublicDnsResolver(Collections.singletonList(server)) : null;
    }

    /**
     * The server asked over HTTPS: null when the URL isn't one (see doh)
     */
    @Nullable
    static Resolver dohServer(String url, List<String> bootstrapAddresses) {
        HttpUrl serverUrl = parseDohUrl(url);

        if (serverUrl == null) {
            return null;
        }

        List<InetAddress> bootstrap = new ArrayList<>();

        for (String address : bootstrapAddresses) {
            InetAddress server = parseAddress(address);

            if (server != null) {
                bootstrap.add(server);
            }
        }

        return new OkHttpDohResolver(serverUrl, bootstrap);
    }

    /**
     * An IPv4 address: not a host name, nor an IPv6 address (a custom server is IPv4 only)
     */
    public static boolean isIpv4Address(@Nullable String address) {
        if (address == null || address.trim().isEmpty()) {
            return false;
        }

        try {
            // A literal only: never a lookup
            Address.getByAddress(address.trim(), Address.IPv4);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * An https URL, e.g. https://dns.example/dns-query
     */
    public static boolean isDohUrl(@Nullable String url) {
        return parseDohUrl(url) != null;
    }

    @Nullable
    private static InetAddress parseAddress(@Nullable String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }

        try {
            // A literal only: never a lookup
            return Address.getByAddress(address.trim());
        } catch (UnknownHostException e) {
            return null;
        }
    }

    @Nullable
    private static HttpUrl parseDohUrl(@Nullable String url) {
        HttpUrl result = url != null ? HttpUrl.parse(url.trim()) : null;

        return result != null && result.isHttps() ? result : null;
    }

    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        List<InetAddress> result = new ArrayList<>();
        List<InetAddress> ipv6Addresses;
        List<InetAddress> ipv4Addresses;

        if (Ipv6CheckDns.isIpv6Disabled()) {
            ipv4Addresses = queryWithFallback(hostname, Type.A);
            // Only when there's nothing else, as before (see Ipv6CheckDns#lookup)
            ipv6Addresses = ipv4Addresses.isEmpty() ? queryWithFallback(hostname, Type.AAAA) : new ArrayList<>();
        } else {
            Future<List<InetAddress>> ipv6Query = sAaaaQueries.submit(() -> queryWithFallback(hostname, Type.AAAA));
            ipv4Addresses = queryWithFallback(hostname, Type.A);
            ipv6Addresses = getAddresses(ipv6Query);
        }

        // IPv6 first (an essential part)
        if (isIpv6Reachable(ipv6Addresses)) {
            result.addAll(ipv6Addresses);
            result.addAll(ipv4Addresses);
        } else {
            result.addAll(ipv4Addresses);
            result.addAll(ipv6Addresses);
        }

        // Fallback to system DNS only if nothing was resolved
        if (result.isEmpty()) {
            return Dns.SYSTEM.lookup(hostname);
        }

        return result;
    }

    /**
     * What the query found: none when it failed, or the lookup was cancelled meanwhile (its thread interrupted, e.g. by OkHttp)
     */
    private static List<InetAddress> getAddresses(Future<List<InetAddress>> query) {
        try {
            return query.get();
        } catch (InterruptedException e) {
            query.cancel(true);
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        } catch (ExecutionException e) {
            return new ArrayList<>();
        }
    }

    /**
     * IPv6 answers here: probed once an app run, with the first lookup that has IPv6 addresses (as Ipv6CheckDns tests it). A probe
     * at each lookup cost each new connection the probe's timeout where IPv6 goes nowhere: on the emulator, 0.50-0.54 s of each
     * lookup (2026-11-15), so a seek's new connections played again ~0.5 s later. A run's lookups also keep one order now, rather
     * than an order per probe.
     */
    @VisibleForTesting
    static boolean isIpv6Reachable(List<InetAddress> ipv6Addresses) {
        if (ipv6Addresses.isEmpty()) {
            return false;
        }

        Boolean isReachable = sIsIpv6Reachable;

        if (isReachable != null) {
            return isReachable;
        }

        // The other lookups wait for the probe, rather than probe too
        synchronized (sLock) {
            if (sIsIpv6Reachable == null) {
                sIsIpv6Reachable = sProbe.isReachable(ipv6Addresses.get(0));
            }

            return sIsIpv6Reachable;
        }
    }

    private static boolean isReachable(InetAddress address) {
        try {
            return address.isReachable(PROBE_TIMEOUT_MS);
        } catch (IOException e) {
            return false;
        }
    }

    @VisibleForTesting
    static void resetIpv6Probe(Probe probe) {
        synchronized (sLock) {
            sProbe = probe;
            sIsIpv6Reachable = null;
        }
    }

    @VisibleForTesting
    interface Probe {
        boolean isReachable(InetAddress address);
    }

    private List<InetAddress> getReachable(List<InetAddress> addresses) {
        List<InetAddress> reachable = new ArrayList<>();
        for (InetAddress address : addresses) {
            try {
                if (address.isReachable(500)) {
                    reachable.add(address);
                }
            } catch (IOException ignored) {}
        }
        return reachable;
    }

    private List<InetAddress> queryWithFallback(String hostname, int type) {
        String key = type + " " + hostname;
        long nowMs = System.nanoTime() / 1_000_000;
        Answer cached = mCache.get(key);

        if (cached != null && nowMs < cached.expiresAtMs) {
            return new ArrayList<>(cached.addresses);
        }

        Answer answer = Answer.failed(nowMs);
        int first = mAnswering;

        for (int i = 0; i < mServers.size(); i++) {
            int index = (first + i) % mServers.size();
            answer = query(hostname, type, mServers.get(index), nowMs);

            if (!answer.isFailed) {
                mAnswering = index;
                break;
            }
        }

        if (!answer.addresses.isEmpty() && answer.ttlSec > 0) {
            mCache.put(key, answer);
        } else {
            mCache.remove(key);
        }

        return new ArrayList<>(answer.addresses);
    }

    private Answer query(String hostname, int type, Resolver resolver, long nowMs) {
        try {
            Lookup lookup = new Lookup(hostname, type);
            lookup.setResolver(resolver);

            // Qualified: the Record of okhttp3.Dns (5.5+) hides an imported one in this class
            org.xbill.DNS.Record[] records = lookup.run();
            List<InetAddress> addresses = new ArrayList<>();
            long ttlSec = -1;

            if (records != null) {
                for (org.xbill.DNS.Record record : records) {
                    if (record instanceof ARecord && type == Type.A) {
                        addresses.add(((ARecord) record).getAddress());
                        ttlSec = ttlSec < 0 ? record.getTTL() : Math.min(ttlSec, record.getTTL());
                    } else if (record instanceof AAAARecord && type == Type.AAAA) {
                        addresses.add(((AAAARecord) record).getAddress());
                        ttlSec = ttlSec < 0 ? record.getTTL() : Math.min(ttlSec, record.getTTL());
                    }
                }
            }

            // A timeout, a network error or a server failure (e.g. SERVFAIL): TRY_AGAIN. A bad query: UNRECOVERABLE.
            int result = lookup.getResult();
            boolean isFailed = result == Lookup.TRY_AGAIN || result == Lookup.UNRECOVERABLE;

            return new Answer(addresses, ttlSec, nowMs, isFailed && addresses.isEmpty());
        } catch (Throwable ignored) {
            // ExceptionInInitializerError
            return Answer.failed(nowMs);
        }
    }

    @VisibleForTesting
    int getAnsweringServer() {
        return mAnswering;
    }
}

