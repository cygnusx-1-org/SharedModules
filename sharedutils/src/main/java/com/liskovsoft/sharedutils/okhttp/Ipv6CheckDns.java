package com.liskovsoft.sharedutils.okhttp;

import androidx.annotation.NonNull;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import okhttp3.Dns;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests IPv6 once an app run, with the first lookup that has IPv6 addresses: a connect to the first of them.
 * When it doesn't connect, the lookups give IPv4 only till the app restarts.<br/>
 * The setting is read at each lookup (see GlobalPreferences.isIpv6TestEnabled), so switching it applies without a restart.<br/>
 * An IPv6 address that goes nowhere (e.g. the Android emulator's, a broken router's) costs a connect timeout per address otherwise,
 * and OkHttp tries the IPv6 ones first once the IPv4 ones of a host are postponed: a call cancelled on a new connection marks its route failed.
 */
final class Ipv6CheckDns implements Dns {
    private static final String TAG = Ipv6CheckDns.class.getSimpleName();
    private static final int TEST_TIMEOUT_MS = 1_500;
    private static final int TEST_PORT = 443;
    private static final Object sLock = new Object();
    // Null till tested
    private static volatile Boolean sIsIpv6Working;
    private final Dns mDelegate;

    Ipv6CheckDns(Dns delegate) {
        mDelegate = delegate;
    }

    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        List<InetAddress> addresses = mDelegate.lookup(hostname);

        if (!isEnabled()) {
            return addresses;
        }

        InetAddress ipv6Address = null;
        List<InetAddress> ipv4Addresses = new ArrayList<>();

        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address) {
                ipv4Addresses.add(address);
            } else if (ipv6Address == null && address instanceof Inet6Address) {
                ipv6Address = address;
            }
        }

        // Nothing to test, or nothing else to try
        if (ipv6Address == null || ipv4Addresses.isEmpty() || isIpv6Working(ipv6Address)) {
            return addresses;
        }

        return ipv4Addresses;
    }

    private static boolean isEnabled() {
        GlobalPreferences prefs = GlobalPreferences.sInstance;

        return prefs != null && prefs.isIpv6TestEnabled();
    }

    private static boolean isIpv6Working(InetAddress address) {
        Boolean isWorking = sIsIpv6Working;

        if (isWorking != null) {
            return isWorking;
        }

        // The other lookups wait for the test, rather than try IPv6 meanwhile
        synchronized (sLock) {
            if (sIsIpv6Working == null) {
                sIsIpv6Working = canConnect(address);
                Log.d(TAG, sIsIpv6Working ? "IPv6 works" : "IPv6 doesn't connect: IPv4 only till the app restarts");
            }

            return sIsIpv6Working;
        }
    }

    private static boolean canConnect(InetAddress address) {
        Socket socket = new Socket();

        try {
            socket.connect(new InetSocketAddress(address, TEST_PORT), TEST_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // Nothing to do
            }
        }
    }
}
