package com.liskovsoft.sharedutils.okhttp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.liskovsoft.sharedutils.okhttp.OkHttpDNSSelector.IPvMode;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import okhttp3.Dns;
import org.xbill.DNS.Resolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The DNS chosen in the settings (see GlobalPreferences.getPreferredDnsType, getPreferredDnsProtocol), read at each lookup: a switch
 * applies to the next connection, without a restart. A client keeps the Dns it was built with, and OkHttpManager's client is built
 * once a run (the player's are copies of it), so a DNS picked at build time stays till the app restarts.<br/>
 * Public for its servers' addresses (see getAddresses, isIpv6Working): built by OkHttpCommons only.
 */
public final class PreferredDns implements Dns {
    /**
     * Each provider's servers: two IPv4 then two IPv6 ones, asked over UDP in this order (an IPv6-only network has no route to the
     * IPv4 ones: they fail at once), and its DNS-over-HTTPS URL, its host at the same addresses
     */
    private static final Provider GOOGLE = new Provider("https://dns.google/dns-query",
            "8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844");
    private static final Provider CLOUDFLARE = new Provider("https://cloudflare-dns.com/dns-query",
            "1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001");
    private static final Provider QUAD9 = new Provider("https://dns.quad9.net/dns-query",
            "9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9");
    // The non-filtering servers: the default ones block ads, a part of the app's requests among them
    private static final Provider ADGUARD = new Provider("https://unfiltered.adguard-dns.com/dns-query",
            "94.140.14.140", "94.140.14.141", "2a10:50c0::1:ff", "2a10:50c0::2:ff");
    private volatile Dns mIpv4Dns;
    /**
     * By the servers asked and how (see getKey): each keeps its answers (see PublicDnsResolver), so the error fixing's switch to
     * Google's and back doesn't ask again
     */
    private final Map<String, Dns> mPublicDns = new ConcurrentHashMap<>();

    PreferredDns() {
    }

    private static final class Provider {
        final String dohUrl;
        final List<String> addresses;

        Provider(String dohUrl, String... addresses) {
            this.dohUrl = dohUrl;
            this.addresses = Collections.unmodifiableList(Arrays.asList(addresses));
        }
    }

    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        return current().lookup(hostname);
    }

    /**
     * The one the settings name now
     */
    @NonNull
    Dns current() {
        GlobalPreferences prefs = GlobalPreferences.sInstance;
        int type = prefs != null ? prefs.getPreferredDnsType() : GlobalPreferences.DNS_TYPE_SYSTEM;

        if (type == GlobalPreferences.DNS_TYPE_IPV4) {
            // Not a filter of the system's (IPv4 only): it caused hangs and crashes (especially on Android 8 devices or Dune HD)
            if (mIpv4Dns == null) {
                mIpv4Dns = new OkHttpDNSSelector(IPvMode.IPV4_FIRST);
            }
            return mIpv4Dns;
        }

        boolean isDoh = prefs != null && prefs.getPreferredDnsProtocol() == GlobalPreferences.DNS_PROTOCOL_DOH;
        Provider provider = prefs != null ? getProvider(prefs, type, isDoh) : null;

        if (provider == null) {
            return Dns.SYSTEM;
        }

        String key = getKey(provider, isDoh);
        Dns dns = mPublicDns.get(key);

        if (dns == null) {
            dns = isDoh ? PublicDnsResolver.doh(provider.dohUrl, provider.addresses) : PublicDnsResolver.plain(provider.addresses);

            if (dns == null) {
                // The custom server isn't set for this protocol (or isn't valid)
                return Dns.SYSTEM;
            }

            Dns existing = mPublicDns.putIfAbsent(key, dns);
            dns = existing != null ? existing : dns;
        }

        return dns;
    }

    /**
     * The servers the settings name now, as current() asks them, by their address or DNS-over-HTTPS URL, in the order asked: none
     * for the system's DNS (System, System IPv4-first, the custom server not set). New ones: no answer of the lookups' is kept in them.
     */
    @NonNull
    static Map<String, Resolver> getServers(@Nullable GlobalPreferences prefs) {
        int type = prefs != null ? prefs.getPreferredDnsType() : GlobalPreferences.DNS_TYPE_SYSTEM;
        boolean isDoh = prefs != null && prefs.getPreferredDnsProtocol() == GlobalPreferences.DNS_PROTOCOL_DOH;
        Provider provider = prefs != null && type != GlobalPreferences.DNS_TYPE_IPV4 ? getProvider(prefs, type, isDoh) : null;
        Map<String, Resolver> result = new LinkedHashMap<>();

        if (provider == null) {
            return result;
        }

        if (!isDoh) {
            return PublicDnsResolver.plainServers(provider.addresses);
        }

        Resolver server = PublicDnsResolver.dohServer(provider.dohUrl, provider.addresses);

        if (server != null) {
            result.put(provider.dohUrl.trim(), server);
        }

        return result;
    }

    /**
     * A public DNS's servers (see GlobalPreferences.DNS_TYPE_*) in the order they're asked over UDP: its IPv4 ones, then its IPv6 ones
     * (when asked for). Over HTTPS, where its host is. None for the system's DNS, nor for the custom one (see
     * GlobalPreferences.getCustomDnsPrimary).
     *
     * @param withIpv6 its IPv6 ones too, e.g. while IPv6 works (see isIpv6Working)
     */
    @NonNull
    public static List<String> getAddresses(int dnsType, boolean withIpv6) {
        Provider provider = getPublicProvider(dnsType);
        List<String> result = new ArrayList<>();

        if (provider != null) {
            for (String address : provider.addresses) {
                if (withIpv6 || PublicDnsResolver.isIpv4Address(address)) {
                    result.add(address);
                }
            }
        }

        return result;
    }

    /**
     * A public DNS's DNS-over-HTTPS URL (see GlobalPreferences.DNS_TYPE_*): null for the system's DNS, and for the custom one (see
     * GlobalPreferences.getCustomDohUrl)
     */
    @Nullable
    public static String getDohUrl(int dnsType) {
        Provider provider = getPublicProvider(dnsType);
        return provider != null ? provider.dohUrl : null;
    }

    /**
     * Whether IPv6 connects, as the app's lookups found it (see Ipv6CheckDns): null till they tested it, and while the test is off
     */
    @Nullable
    public static Boolean isIpv6Working() {
        return Ipv6CheckDns.getIpv6Working();
    }

    /**
     * Its servers, null for the system's DNS (e.g. the type of a newer version)
     */
    @Nullable
    private static Provider getProvider(@NonNull GlobalPreferences prefs, int type, boolean isDoh) {
        return type == GlobalPreferences.DNS_TYPE_CUSTOM ? getCustomProvider(prefs, isDoh) : getPublicProvider(type);
    }

    @Nullable
    private static Provider getPublicProvider(int type) {
        switch (type) {
            case GlobalPreferences.DNS_TYPE_GOOGLE:
                // May help with 'java.net.ProtocolException: Too many follow-up requests: 21'
                return GOOGLE;
            case GlobalPreferences.DNS_TYPE_CLOUDFLARE:
                return CLOUDFLARE;
            case GlobalPreferences.DNS_TYPE_QUAD9:
                return QUAD9;
            case GlobalPreferences.DNS_TYPE_ADGUARD:
                return ADGUARD;
            default:
                return null;
        }
    }

    /**
     * The servers of the protocol: IPv4 addresses only for plain DNS. A DNS-over-HTTPS server's host is looked up with the system's
     * DNS (no address is given for it).
     */
    @NonNull
    private static Provider getCustomProvider(@NonNull GlobalPreferences prefs, boolean isDoh) {
        if (isDoh) {
            String dohUrl = prefs.getCustomDohUrl();
            return new Provider(dohUrl != null ? dohUrl.trim() : null);
        }

        List<String> addresses = new ArrayList<>();

        for (String address : new String[] {prefs.getCustomDnsPrimary(), prefs.getCustomDnsSecondary()}) {
            if (PublicDnsResolver.isIpv4Address(address)) {
                addresses.add(address.trim());
            }
        }

        return new Provider(null, addresses.toArray(new String[0]));
    }

    private static String getKey(@NonNull Provider provider, boolean isDoh) {
        return isDoh ? "doh " + provider.dohUrl : "plain " + provider.addresses;
    }
}
