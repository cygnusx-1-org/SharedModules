package com.liskovsoft.sharedutils.okhttp;

import com.liskovsoft.sharedutils.okhttp.DnsCheck.Result;
import com.liskovsoft.sharedutils.okhttp.DnsCheck.Status;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.xbill.DNS.EDNSOption;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.TSIG;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Each server of the DNS chosen asked for www.youtube.com: whether it answers, with what, or why not
 */
@RunWith(RobolectricTestRunner.class)
public class DnsCheckTest {
    private int mSavedDns;
    private int mSavedProtocol;
    private String mSavedPrimary;
    private String mSavedSecondary;
    private String mSavedDohUrl;

    @Before
    public void setUp() {
        mSavedDns = getPrefs().getPreferredDnsType();
        mSavedProtocol = getPrefs().getPreferredDnsProtocol();
        mSavedPrimary = getPrefs().getCustomDnsPrimary();
        mSavedSecondary = getPrefs().getCustomDnsSecondary();
        mSavedDohUrl = getPrefs().getCustomDohUrl();
    }

    @After
    public void tearDown() {
        getPrefs().setPreferredDnsType(mSavedDns);
        getPrefs().setPreferredDnsProtocol(mSavedProtocol);
        getPrefs().setCustomDnsPrimary(mSavedPrimary);
        getPrefs().setCustomDnsSecondary(mSavedSecondary);
        getPrefs().setCustomDohUrl(mSavedDohUrl);
    }

    @Test
    public void aServerThatAnswersAnAddressWorks() throws Exception {
        Result result = DnsCheck.check(new FakeServer(query -> answer(query, Rcode.NOERROR, "142.250.72.14")));

        assertEquals(Status.WORKS, result.status);
        assertEquals(InetAddress.getByName("142.250.72.14"), result.address);
        assertNull(result.detail);
    }

    /**
     * A filtering DNS: YouTube's name has no address, or a filter's
     */
    @Test
    public void aServerThatHasNoAddressForYouTubeBlocksIt() {
        assertEquals(Status.BLOCKED, DnsCheck.check(new FakeServer(query -> answer(query, Rcode.NOERROR, "0.0.0.0"))).status);
        assertEquals(Status.BLOCKED, DnsCheck.check(new FakeServer(query -> answer(query, Rcode.NOERROR, "127.0.0.1"))).status);
        assertEquals(Status.BLOCKED, DnsCheck.check(new FakeServer(query -> answer(query, Rcode.NOERROR))).status);
        assertEquals(Status.BLOCKED, DnsCheck.check(new FakeServer(query -> answer(query, Rcode.NXDOMAIN))).status);
    }

    @Test
    public void aServersErrorFails() {
        Result result = DnsCheck.check(new FakeServer(query -> answer(query, Rcode.SERVFAIL)));

        assertEquals(Status.FAILED, result.status);
        assertEquals("SERVFAIL", result.detail);
    }

    /**
     * dnsjava's UDP client's own timeout, and its send's (an IOException), and OkHttp's call timeout (DNS-over-HTTPS)
     */
    @Test
    public void noAnswerInTimeIsATimeout() {
        assertEquals(Status.TIMEOUT, DnsCheck.check(new FakeServer(query -> {
            throw new SocketTimeoutException("Query timed out");
        })).status);
        assertEquals(Status.TIMEOUT, DnsCheck.check(new FakeServer(query -> {
            throw new IOException("Timed out while trying to resolve www.youtube.com./A, id=1", new TimeoutException());
        })).status);
        assertEquals(Status.TIMEOUT, DnsCheck.check(new FakeServer(query -> {
            throw new java.io.InterruptedIOException("timeout");
        })).status);
    }

    /**
     * E.g. an IPv6 server on a network without IPv6
     */
    @Test
    public void noRouteToTheServerIsUnreachable() {
        assertEquals(Status.UNREACHABLE, DnsCheck.check(new FakeServer(query -> {
            throw new SocketException("Network is unreachable");
        })).status);
        assertEquals(Status.UNREACHABLE, DnsCheck.check(new FakeServer(query -> {
            throw new IOException(new NoRouteToHostException("No route to host"));
        })).status);
    }

    @Test
    public void anyOtherErrorFailsWithItsMessage() {
        Result result = DnsCheck.check(new FakeServer(query -> {
            throw new IOException("DNS-over-HTTPS server answered HTTP 503");
        }));

        assertEquals(Status.FAILED, result.status);
        assertEquals("DNS-over-HTTPS server answered HTTP 503", result.detail);
    }

    /**
     * A server that doesn't answer doesn't hold back the one that does: each is asked on its own thread
     */
    @Test
    public void eachServerIsAskedAtOnce() throws Exception {
        CountDownLatch firstAnswered = new CountDownLatch(1);
        Map<String, Resolver> servers = new LinkedHashMap<>();
        servers.put("192.0.2.1", new FakeServer(query -> {
            try {
                // Till the second's result is in
                if (!firstAnswered.await(5, TimeUnit.SECONDS)) {
                    throw new SocketTimeoutException("Query timed out");
                }
            } catch (InterruptedException e) {
                throw new IOException(e);
            }

            return answer(query, Rcode.NOERROR, "142.250.72.14");
        }));
        servers.put("192.0.2.2", new FakeServer(query -> answer(query, Rcode.NOERROR, "142.250.72.15")));
        Map<Integer, Result> results = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(2);

        new DnsCheck(servers, hostname -> Collections.emptyList()).start((index, result) -> {
            results.put(index, result);

            if (index == 1) {
                firstAnswered.countDown();
            }

            done.countDown();
        });

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(Status.WORKS, results.get(0).status);
        assertEquals(InetAddress.getByName("142.250.72.15"), results.get(1).address);
    }

    /**
     * The system's DNS is asked as the app asks it: one lookup, Android's
     */
    @Test
    public void theSystemsDnsIsAskedAsAWhole() throws Exception {
        DnsCheck check = new DnsCheck(new LinkedHashMap<>(), hostname -> {
            assertEquals(DnsCheck.HOSTNAME, hostname);
            return Collections.singletonList(InetAddress.getByName("142.250.72.14"));
        });

        assertEquals(Collections.singletonList(null), check.getServers());
        assertEquals(Status.WORKS, runOne(check).status);

        DnsCheck failing = new DnsCheck(new LinkedHashMap<>(), hostname -> {
            throw new UnknownHostException("Unable to resolve host \"www.youtube.com\": No address associated with hostname");
        });

        assertEquals(Status.FAILED, runOne(failing).status);
    }

    /**
     * The servers the settings name, as the lookups ask them
     */
    @Test
    public void theServersAreTheSettings() {
        getPrefs().setPreferredDnsType(GlobalPreferences.DNS_TYPE_GOOGLE);
        getPrefs().setPreferredDnsProtocol(GlobalPreferences.DNS_PROTOCOL_PLAIN);
        assertEquals(Arrays.asList("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"),
                DnsCheck.ofSettings().getServers());

        getPrefs().setPreferredDnsProtocol(GlobalPreferences.DNS_PROTOCOL_DOH);
        assertEquals(Collections.singletonList("https://dns.google/dns-query"), DnsCheck.ofSettings().getServers());

        getPrefs().setPreferredDnsType(GlobalPreferences.DNS_TYPE_CUSTOM);
        getPrefs().setCustomDohUrl(" https://dns.example/dns-query ");
        assertEquals(Collections.singletonList("https://dns.example/dns-query"), DnsCheck.ofSettings().getServers());

        getPrefs().setPreferredDnsProtocol(GlobalPreferences.DNS_PROTOCOL_PLAIN);
        getPrefs().setCustomDnsPrimary(null);
        getPrefs().setCustomDnsSecondary("192.0.2.2");
        assertEquals(Collections.singletonList("192.0.2.2"), DnsCheck.ofSettings().getServers());
    }

    /**
     * The system's DNS, and the custom server not set (the system's is asked in its place, see PreferredDns.current)
     */
    @Test
    public void theSystemsDnsIsOneServer() {
        for (int type : new int[] {GlobalPreferences.DNS_TYPE_SYSTEM, GlobalPreferences.DNS_TYPE_IPV4}) {
            getPrefs().setPreferredDnsType(type);
            assertEquals(Collections.singletonList(null), DnsCheck.ofSettings().getServers());
        }

        getPrefs().setPreferredDnsType(GlobalPreferences.DNS_TYPE_CUSTOM);
        getPrefs().setPreferredDnsProtocol(GlobalPreferences.DNS_PROTOCOL_PLAIN);
        getPrefs().setCustomDnsPrimary(null);
        getPrefs().setCustomDnsSecondary(null);
        assertEquals(Collections.singletonList(null), DnsCheck.ofSettings().getServers());

        getPrefs().setPreferredDnsProtocol(GlobalPreferences.DNS_PROTOCOL_DOH);
        getPrefs().setCustomDohUrl(null);
        assertEquals(Collections.singletonList(null), DnsCheck.ofSettings().getServers());
    }

    private static Result runOne(DnsCheck check) throws InterruptedException {
        Result[] result = new Result[1];
        CountDownLatch done = new CountDownLatch(1);

        check.start((index, value) -> {
            result[0] = value;
            done.countDown();
        });

        assertTrue(done.await(10, TimeUnit.SECONDS));
        return result[0];
    }

    private static Message answer(Message query, int rcode, String... addresses) throws IOException {
        Message answer = new Message(query.getHeader().getID());
        answer.getHeader().setFlag(Flags.QR);
        answer.getHeader().setRcode(rcode);
        answer.addRecord(query.getQuestion(), Section.QUESTION);

        for (String address : addresses) {
            answer.addRecord(new org.xbill.DNS.ARecord(query.getQuestion().getName(), org.xbill.DNS.DClass.IN, 300,
                    InetAddress.getByName(address)), Section.ANSWER);
        }

        return answer;
    }

    private static GlobalPreferences getPrefs() {
        return GlobalPreferences.instance(RuntimeEnvironment.getApplication());
    }

    private interface Answering {
        Message answer(Message query) throws IOException;
    }

    private static final class FakeServer implements Resolver {
        private final Answering mAnswering;

        FakeServer(Answering answering) {
            mAnswering = answering;
        }

        @Override
        public Message send(Message query) throws IOException {
            return mAnswering.answer(query);
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
}
