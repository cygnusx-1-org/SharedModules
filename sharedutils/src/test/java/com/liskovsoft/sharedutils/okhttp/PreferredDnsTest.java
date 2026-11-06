package com.liskovsoft.sharedutils.okhttp;

import com.liskovsoft.sharedutils.prefs.GlobalPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Field;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class PreferredDnsTest {
    private int mSavedDns;

    @Before
    public void setUp() {
        mSavedDns = getPrefs().getPreferredDnsType();
    }

    @After
    public void tearDown() {
        getPrefs().setPreferredDnsType(mSavedDns);
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
