package com.liskovsoft.sharedutils.okhttp;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import okhttp3.Dns;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Gives the AAAA addresses first, then the A ones, when IPv6 answers — avoids redirect loops on some CDNs. Both are asked at once:
 * one after the other, each new connection waited for two round trips to the server (V8RCD1SA: 274-923 ms of each first connect, against
 * 99-157 ms to connect). Not AAAA while IPv6 doesn't connect (see Ipv6CheckDns): its addresses would be dropped.
 */
public class PublicDnsResolver implements Dns {
    private static final int PROBE_TIMEOUT_MS = 500;
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
    private final Resolver primaryResolver;
    private final Resolver fallbackResolver;

    private PublicDnsResolver(String primaryDns, String fallbackDns, int timeoutSec) {
        try {
            primaryResolver = new SimpleResolver(primaryDns);
            primaryResolver.setTimeout(timeoutSec);

            fallbackResolver = new SimpleResolver(fallbackDns);
            fallbackResolver.setTimeout(timeoutSec);
        } catch (UnknownHostException e) {
            throw new RuntimeException("Cannot create DNS resolvers", e);
        }
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
        List<InetAddress> addresses = query(hostname, type, primaryResolver);
        if (addresses.isEmpty()) {
            addresses = query(hostname, type, fallbackResolver);
        }
        return addresses;
    }

    private List<InetAddress> query(String hostname, int type, Resolver resolver) {
        try {
            Lookup lookup = new Lookup(hostname, type);
            lookup.setResolver(resolver);

            // Qualified: the Record of okhttp3.Dns (5.5+) hides an imported one in this class
            org.xbill.DNS.Record[] records = lookup.run();
            List<InetAddress> addresses = new ArrayList<>();

            if (records != null) {
                for (org.xbill.DNS.Record record : records) {
                    if (record instanceof ARecord && type == Type.A) {
                        addresses.add(((ARecord) record).getAddress());
                    } else if (record instanceof AAAARecord && type == Type.AAAA) {
                        addresses.add(((AAAARecord) record).getAddress());
                    }
                }
            }

            return addresses;
        } catch (Throwable ignored) {
            // ExceptionInInitializerError
            return new ArrayList<>();
        }
    }

    public static Dns google() {
        return new PublicDnsResolver("8.8.8.8", "8.8.4.4", 5);
    }

    public static Dns cloudflare() {
        return new PublicDnsResolver("1.1.1.1", "1.0.0.1", 5);
    }
}

