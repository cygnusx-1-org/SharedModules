package com.liskovsoft.sharedutils.okhttp;

import androidx.annotation.NonNull;
import com.liskovsoft.sharedutils.okhttp.OkHttpDNSSelector.IPvMode;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import okhttp3.Dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * The DNS chosen in the settings (see GlobalPreferences.getPreferredDnsType), read at each lookup: a switch applies to the next
 * connection, without a restart. A client keeps the Dns it was built with, and OkHttpManager's client is built once a run (the
 * player's are copies of it), so a DNS picked at build time stays till the app restarts.
 */
final class PreferredDns implements Dns {
    private volatile Dns mIpv4Dns;
    private volatile Dns mGoogleDns;

    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        return current().lookup(hostname);
    }

    /**
     * The one the setting names now
     */
    @NonNull
    Dns current() {
        switch (getDnsType()) {
            case GlobalPreferences.DNS_TYPE_IPV4:
                // Not a filter of the system's (IPv4 only): it caused hangs and crashes (especially on Android 8 devices or Dune HD)
                if (mIpv4Dns == null) {
                    mIpv4Dns = new OkHttpDNSSelector(IPvMode.IPV4_FIRST);
                }
                return mIpv4Dns;
            case GlobalPreferences.DNS_TYPE_GOOGLE:
                // May help with 'java.net.ProtocolException: Too many follow-up requests: 21'
                if (mGoogleDns == null) {
                    mGoogleDns = PublicDnsResolver.google();
                }
                return mGoogleDns;
            default:
                return Dns.SYSTEM;
        }
    }

    private static int getDnsType() {
        GlobalPreferences prefs = GlobalPreferences.sInstance;

        return prefs != null ? prefs.getPreferredDnsType() : GlobalPreferences.DNS_TYPE_SYSTEM;
    }
}
