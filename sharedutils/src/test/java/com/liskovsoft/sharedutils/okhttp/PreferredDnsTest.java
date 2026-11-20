package com.liskovsoft.sharedutils.okhttp;

import com.liskovsoft.sharedutils.prefs.GlobalPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class PreferredDnsTest {
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

    /**
     * The player's error fixing switches the DNS and restarts the engine, not the app: the same instance follows the setting
     */
    @Test
    public void aSwitchOfTheSettingAppliesToTheNextLookup() {
        PreferredDns dns = new PreferredDns();

        getPrefs().setPreferredDnsType(GlobalPreferences.DNS_TYPE_SYSTEM);
        assertSame(Dns.SYSTEM, dns.current());

        getPrefs().setPreferredDnsType(GlobalPreferences.DNS_TYPE_GOOGLE);
        assertTrue(dns.current() instanceof PublicDnsResolver);

        getPrefs().setPreferredDnsType(GlobalPreferences.DNS_TYPE_IPV4);
        assertTrue(dns.current() instanceof OkHttpDNSSelector);

        getPrefs().setPreferredDnsType(GlobalPreferences.DNS_TYPE_SYSTEM);
        assertSame(Dns.SYSTEM, dns.current());
    }

    /**
     * A resolver is made once, not at each lookup
     */
    @Test
    public void theResolverOfATypeIsKept() {
        PreferredDns dns = new PreferredDns();
        getPrefs().setPreferredDnsType(GlobalPreferences.DNS_TYPE_GOOGLE);

        assertSame(dns.current(), dns.current());
    }

    /**
     * Each provider is asked over the protocol chosen: a resolver for each, kept, so a switch back keeps its answers
     */
    @Test
    public void eachProviderAndProtocolHasItsResolver() {
        PreferredDns dns = new PreferredDns();
        int[] providers = {GlobalPreferences.DNS_TYPE_GOOGLE, GlobalPreferences.DNS_TYPE_CLOUDFLARE, GlobalPreferences.DNS_TYPE_QUAD9,
                GlobalPreferences.DNS_TYPE_ADGUARD};
        List<Dns> resolvers = new ArrayList<>();

        for (int protocol : new int[] {GlobalPreferences.DNS_PROTOCOL_PLAIN, GlobalPreferences.DNS_PROTOCOL_DOH}) {
            getPrefs().setPreferredDnsProtocol(protocol);

            for (int provider : providers) {
                getPrefs().setPreferredDnsType(provider);
                Dns current = dns.current();

                assertTrue(current instanceof PublicDnsResolver);
                assertFalse("Two providers or protocols share a resolver", resolvers.contains(current));
                resolvers.add(current);
            }
        }

        getPrefs().setPreferredDnsProtocol(GlobalPreferences.DNS_PROTOCOL_PLAIN);
        getPrefs().setPreferredDnsType(GlobalPreferences.DNS_TYPE_GOOGLE);
        assertSame(resolvers.get(0), dns.current());
    }

    /**
     * Custom servers not set (or not IPv4 addresses) for the protocol chosen: the system's DNS, not a resolver that can't ask anything
     */
    @Test
    public void customServersNotSetAreTheSystemsDns() {
        PreferredDns dns = new PreferredDns();
        getPrefs().setPreferredDnsType(GlobalPreferences.DNS_TYPE_CUSTOM);
        getPrefs().setCustomDnsPrimary("2001:db8::53");
        getPrefs().setCustomDnsSecondary("dns.example");
        getPrefs().setCustomDohUrl("https://dns.example/dns-query");

        getPrefs().setPreferredDnsProtocol(GlobalPreferences.DNS_PROTOCOL_PLAIN);
        assertSame(Dns.SYSTEM, dns.current());

        getPrefs().setPreferredDnsProtocol(GlobalPreferences.DNS_PROTOCOL_DOH);
        getPrefs().setCustomDohUrl(null);
        assertSame(Dns.SYSTEM, dns.current());

        getPrefs().setCustomDohUrl("http://dns.example/dns-query");
        assertSame(Dns.SYSTEM, dns.current());
    }

    /**
     * A change of the custom servers applies to the next lookup, as a switch of the setting does
     */
    @Test
    public void aChangeOfTheCustomServersAppliesToTheNextLookup() {
        PreferredDns dns = new PreferredDns();
        getPrefs().setPreferredDnsType(GlobalPreferences.DNS_TYPE_CUSTOM);
        getPrefs().setPreferredDnsProtocol(GlobalPreferences.DNS_PROTOCOL_PLAIN);
        getPrefs().setCustomDnsPrimary("192.0.2.1");
        getPrefs().setCustomDnsSecondary(null);

        Dns first = dns.current();
        assertTrue(first instanceof PublicDnsResolver);
        assertSame(first, dns.current());

        getPrefs().setCustomDnsSecondary("192.0.2.2");
        Dns second = dns.current();
        assertTrue(second instanceof PublicDnsResolver);
        assertNotSame(first, second);

        getPrefs().setPreferredDnsProtocol(GlobalPreferences.DNS_PROTOCOL_DOH);
        getPrefs().setCustomDohUrl("https://dns.example/dns-query");
        assertTrue(dns.current() instanceof PublicDnsResolver);
    }

    /**
     * What the settings show for a public DNS: its IPv4 servers, the primary first. None for the system's and the custom one.
     */
    @Test
    public void aPublicDnsHasItsIpv4Servers() {
        assertEquals(Arrays.asList("8.8.8.8", "8.8.4.4"), PreferredDns.getAddresses(GlobalPreferences.DNS_TYPE_GOOGLE, false));
        assertEquals(Arrays.asList("1.1.1.1", "1.0.0.1"), PreferredDns.getAddresses(GlobalPreferences.DNS_TYPE_CLOUDFLARE, false));
        assertEquals(Arrays.asList("9.9.9.9", "149.112.112.112"), PreferredDns.getAddresses(GlobalPreferences.DNS_TYPE_QUAD9, false));
        assertEquals(Arrays.asList("94.140.14.140", "94.140.14.141"), PreferredDns.getAddresses(GlobalPreferences.DNS_TYPE_ADGUARD, false));

        assertTrue(PreferredDns.getAddresses(GlobalPreferences.DNS_TYPE_SYSTEM, false).isEmpty());
        assertTrue(PreferredDns.getAddresses(GlobalPreferences.DNS_TYPE_IPV4, false).isEmpty());
        assertTrue(PreferredDns.getAddresses(GlobalPreferences.DNS_TYPE_CUSTOM, false).isEmpty());
    }

    /**
     * A DNS type of a newer version, after a downgrade: the system's
     */
    @Test
    public void anUnknownTypeIsTheSystemsDns() {
        getPrefs().setPreferredDnsType(99);

        assertSame(Dns.SYSTEM, new PreferredDns().current());
    }

    /**
     * OkHttpManager's client is built once a run, so the DNS picked when it's built would stay till the app restarts
     */
    @Test
    public void theAppsClientReadsTheSettingAtEachLookup() throws Exception {
        getPrefs().setPreferredDnsType(GlobalPreferences.DNS_TYPE_SYSTEM);
        OkHttpClient client = OkHttpCommons.setupBuilder(new OkHttpClient.Builder()).build();

        assertTrue(client.dns() instanceof Ipv6CheckDns);

        Field delegate = Ipv6CheckDns.class.getDeclaredField("mDelegate");
        delegate.setAccessible(true);
        Object dns = delegate.get(client.dns());

        assertTrue(dns instanceof PreferredDns);

        getPrefs().setPreferredDnsType(GlobalPreferences.DNS_TYPE_GOOGLE);
        assertTrue(((PreferredDns) dns).current() instanceof PublicDnsResolver);
    }

    private static GlobalPreferences getPrefs() {
        return GlobalPreferences.instance(RuntimeEnvironment.getApplication());
    }
}
