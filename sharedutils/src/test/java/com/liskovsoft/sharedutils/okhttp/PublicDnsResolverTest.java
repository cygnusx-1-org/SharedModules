package com.liskovsoft.sharedutils.okhttp;

import org.junit.After;
import org.junit.Test;
import org.xbill.DNS.EDNSOption;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.TSIG;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

    /**
     * A server that doesn't answer (e.g. an IPv4 one on an IPv6-only network) is followed by the next one, and the next lookups start
     * with the one that answered: each would wait for its timeout otherwise
     */
    @Test
    public void aServerThatFailsIsFollowedByTheNextFromThenOn() throws Exception {
        resetIpv6Probe(false);
        FakeServer failing = FakeServer.failing();
        FakeServer answering = FakeServer.answering("203.0.113.7");
        PublicDnsResolver resolver = new PublicDnsResolver(Arrays.asList(failing, answering));

        assertEquals(ipv4("203.0.113.7"), resolver.lookup(uniqueHost()));
        assertEquals(1, resolver.getAnsweringServer());
        int failedQueries = failing.queries;

        assertEquals(ipv4("203.0.113.7"), resolver.lookup(uniqueHost()));
        assertEquals(failedQueries, failing.queries);
    }

    /**
     * A name without addresses of a type (e.g. no AAAA) has none on the other server either: asking it cost each such lookup another
     * round trip, one per server
     */
    @Test
    public void anAnswerWithoutAddressesIsntAskedAgain() throws Exception {
        resetIpv6Probe(false);
        FakeServer first = FakeServer.answering("203.0.113.7");
        FakeServer second = FakeServer.answering("203.0.113.8");
        PublicDnsResolver resolver = new PublicDnsResolver(Arrays.asList(first, second));

        assertEquals(ipv4("203.0.113.7"), resolver.lookup(uniqueHost()));
        assertEquals(0, second.queries);
        assertEquals(0, resolver.getAnsweringServer());
    }

    @Test
    public void aCustomServerIsAnIpv4AddressNotANameNorIpv6() {
        assertTrue(PublicDnsResolver.isIpv4Address("1.1.1.1"));
        assertTrue(PublicDnsResolver.isIpv4Address(" 9.9.9.9 "));
        assertFalse(PublicDnsResolver.isIpv4Address("2606:4700:4700::1111"));
        assertFalse(PublicDnsResolver.isIpv4Address("::ffff:1.1.1.1"));
        assertFalse(PublicDnsResolver.isIpv4Address("dns.google"));
        assertFalse(PublicDnsResolver.isIpv4Address("1.1.1"));
        assertFalse(PublicDnsResolver.isIpv4Address(""));
        assertFalse(PublicDnsResolver.isIpv4Address(null));

        assertNull(PublicDnsResolver.plain(Arrays.asList("dns.google", "")));
        assertNotNull(PublicDnsResolver.plain(Arrays.asList("dns.google", "8.8.8.8")));
    }

    /**
     * Unencrypted, it wouldn't be what DNS-over-HTTPS is chosen for
     */
    @Test
    public void aDohServerIsHttps() {
        assertTrue(PublicDnsResolver.isDohUrl("https://dns.example/dns-query"));
        assertFalse(PublicDnsResolver.isDohUrl("http://dns.example/dns-query"));
        assertFalse(PublicDnsResolver.isDohUrl("dns.example"));
        assertFalse(PublicDnsResolver.isDohUrl(null));

        assertNull(PublicDnsResolver.doh("http://dns.example/dns-query", Collections.emptyList()));
        assertNotNull(PublicDnsResolver.doh("https://dns.example/dns-query", Collections.emptyList()));
    }

    private static void resetIpv6Probe(boolean isReachable) {
        PublicDnsResolver.resetIpv6Probe(new CountingProbe(isReachable));
    }

    /**
     * dnsjava keeps the answers of every lookup: a name per test
     */
    private static String uniqueHost() {
        // Absolute: not tried in the host's search domains too
        return "host" + System.nanoTime() + ".example.";
    }

    private static List<InetAddress> ipv4(String literal) throws Exception {
        return Collections.singletonList(InetAddress.getByName(literal));
    }

    /**
     * An A address, and no AAAA one: a negative answer without an SOA, which dnsjava doesn't keep (with one, its cache would answer the
     * next server's lookup in its place)
     */
    private static final class FakeServer implements Resolver {
        private final String mAddress;
        volatile int queries;

        private FakeServer(String address) {
            mAddress = address;
        }

        static FakeServer failing() {
            return new FakeServer(null);
        }

        static FakeServer answering(String address) {
            return new FakeServer(address);
        }

        @Override
        public Message send(Message query) throws IOException {
            queries++;

            if (mAddress == null) {
                throw new SocketTimeoutException("No answer");
            }

            try {
                if (query.getQuestion().getType() == Type.A) {
                    return OkHttpDohResolverTest.answer(query, mAddress, 300);
                }

                Message answer = new Message(query.getHeader().getID());
                answer.getHeader().setFlag(Flags.QR);
                answer.getHeader().setRcode(Rcode.NOERROR);
                answer.addRecord(query.getQuestion(), Section.QUESTION);
                return answer;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        @Override
        public void setPort(int port) {
        }

        @Override
        public void setTCP(boolean flag) {
        }

        @Override
        public void setIgnoreTruncation(boolean flag) {
        }

        @Override
        public void setEDNS(int version, int payloadSize, int flags, List<EDNSOption> options) {
        }

        @Override
        public void setTSIGKey(TSIG key) {
        }

        @Override
        public void setTimeout(Duration timeout) {
        }
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
