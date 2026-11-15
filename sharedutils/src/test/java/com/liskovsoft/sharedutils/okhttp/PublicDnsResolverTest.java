package com.liskovsoft.sharedutils.okhttp;

import org.junit.After;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The IPv6 probe ran at each lookup: where IPv6 goes nowhere, each new connection waited its 500 ms timeout (0.50-0.54 s of each lookup
 * on the emulator, 2026-11-15)
 */
public class PublicDnsResolverTest {
    @After
    public void tearDown() {
        PublicDnsResolver.resetIpv6Probe(PublicDnsResolver.DEFAULT_PROBE);
    }

    @Test
    public void aRunProbesIpv6Once() throws Exception {
        CountingProbe probe = new CountingProbe(true);
        PublicDnsResolver.resetIpv6Probe(probe);

        assertTrue(PublicDnsResolver.isIpv6Reachable(ipv6("2001:db8::1")));
        assertTrue(PublicDnsResolver.isIpv6Reachable(ipv6("2001:db8::2")));
        assertTrue(PublicDnsResolver.isIpv6Reachable(ipv6("2001:db8::3")));

        assertEquals(1, probe.count.get());
    }

    @Test
    public void anUnreachableIpv6IsntProbedAgain() throws Exception {
        CountingProbe probe = new CountingProbe(false);
        PublicDnsResolver.resetIpv6Probe(probe);

        assertFalse(PublicDnsResolver.isIpv6Reachable(ipv6("2001:db8::1")));
        assertFalse(PublicDnsResolver.isIpv6Reachable(ipv6("2001:db8::2")));

        assertEquals(1, probe.count.get());
    }

    /**
     * A host without IPv6 addresses says nothing of IPv6: the probe waits for one that has
     */
    @Test
    public void aLookupWithoutIpv6DoesntProbe() throws Exception {
        CountingProbe probe = new CountingProbe(true);
        PublicDnsResolver.resetIpv6Probe(probe);

        assertFalse(PublicDnsResolver.isIpv6Reachable(Collections.emptyList()));
        assertEquals(0, probe.count.get());

        assertTrue(PublicDnsResolver.isIpv6Reachable(ipv6("2001:db8::1")));
        assertEquals(1, probe.count.get());
    }

    /**
     * A seek's audio and video connections look up at once: one probe, the other waits for it
     */
    @Test
    public void lookupsAtOnceShareOneProbe() throws Exception {
        CountDownLatch probing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        PublicDnsResolver.resetIpv6Probe(address -> {
            count.incrementAndGet();
            probing.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        });
        List<InetAddress> addresses = ipv6("2001:db8::1");
        AtomicBoolean first = new AtomicBoolean();
        AtomicBoolean second = new AtomicBoolean();
        Thread thread1 = new Thread(() -> first.set(PublicDnsResolver.isIpv6Reachable(addresses)));
        Thread thread2 = new Thread(() -> second.set(PublicDnsResolver.isIpv6Reachable(addresses)));

        thread1.start();
        assertTrue(probing.await(2, TimeUnit.SECONDS));
        thread2.start();
        release.countDown();
        thread1.join(2_000);
        thread2.join(2_000);

        assertEquals(1, count.get());
        assertTrue(first.get());
        assertTrue(second.get());
    }

    private static List<InetAddress> ipv6(String literal) throws Exception {
        List<InetAddress> result = new ArrayList<>();
        result.add(InetAddress.getByName(literal));
        return result;
    }

    private static final class CountingProbe implements PublicDnsResolver.Probe {
        private final boolean mResult;
        final AtomicInteger count = new AtomicInteger();

        CountingProbe(boolean result) {
            mResult = result;
        }

        @Override
        public boolean isReachable(InetAddress address) {
            count.incrementAndGet();
            return mResult;
        }
    }
}
