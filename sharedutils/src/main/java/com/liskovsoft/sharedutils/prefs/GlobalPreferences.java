package com.liskovsoft.sharedutils.prefs;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.DateHelper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final public class GlobalPreferences extends SharedPreferencesBase {
    private static final String PREFS_DIR = "global_prefs";
    public static final int DNS_TYPE_SYSTEM = 0;
    public static final int DNS_TYPE_IPV4 = 1;
    public static final int DNS_TYPE_GOOGLE = 2;
    public static final int DNS_TYPE_CLOUDFLARE = 3;
    public static final int DNS_TYPE_QUAD9 = 4;
    public static final int DNS_TYPE_ADGUARD = 5;
    /**
     * The servers set in the settings (see getCustomDnsPrimary, getCustomDnsSecondary, getCustomDohUrl)
     */
    public static final int DNS_TYPE_CUSTOM = 6;
    /**
     * Unencrypted, over UDP
     */
    public static final int DNS_PROTOCOL_PLAIN = 0;
    /**
     * DNS-over-HTTPS (RFC 8484)
     */
    public static final int DNS_PROTOCOL_DOH = 1;
    public static final String PLAYLIST_TYPE_RECOMMENDATIONS = "playlist_type_recommendations";
    public static final String PLAYLIST_TYPE_SUBSCRIPTIONS = "playlist_type_subscriptions";
    public static final String PLAYLIST_TYPE_HISTORY = "playlist_type_history";
    @SuppressLint("StaticFieldLeak")
    public static GlobalPreferences sInstance;
    private static final String SHARED_PREFERENCES_NAME = GlobalPreferences.class.getName();
    private final static String MEDIA_SERVICE_ACCOUNT_DATA = "media_service_account_data";
    private final static String OAUTH2_ACCOUNT_DATA = "oauth2_account_data";
    private final static String MEDIA_SERVICE_DATA = "media_service_data";
    private static final String RECOMMENDED_PLAYLIST_TYPE = "recommended_playlist_type";
    private static final String PREFERRED_LANGUAGE_DATA = "preferred_language_data";
    private static final String PREFERRED_COUNTRY_DATA = "preferred_country_data";
    private static final String ENABLE_CHANNELS_SERVICE = "enable_channels_service";
    private static final String PREFERRED_DNS_TYPE = "preferred_dns_type";
    private static final String PREFERRED_DNS_PROTOCOL = "preferred_dns_protocol";
    private static final String CUSTOM_DNS_PRIMARY = "custom_dns_primary";
    private static final String CUSTOM_DNS_SECONDARY = "custom_dns_secondary";
    private static final String CUSTOM_DOH_URL = "custom_doh_url";
    private static final String DNS_FIX_ENABLED = "dns_fix_enabled";
    private static final String IPV6_TEST_ENABLED = "ipv6_test_enabled";
    private static final String CONTENT_BLOCK_ALT_SERVER = "content_block_alt_server";
    private static final String IS_24_HOUR_LOCALE_ENABLED = "is_24_hour_locale_enabled";
    private static final List<Runnable> sCallbacks = new CopyOnWriteArrayList<>(); // fix ConcurrentModificationException

    private GlobalPreferences(Context context) {
        super(context, SHARED_PREFERENCES_NAME);
    }

    public static GlobalPreferences instance(Context context) {
        if (sInstance == null) {
            sInstance = new GlobalPreferences(context.getApplicationContext());

            for (Runnable callback : sCallbacks) {
                new Thread(callback).start(); // fix network on main thread exception
            }

            // make callbacks garbage collected
            sCallbacks.clear();
        }

        return sInstance;
    }

    public static void setOnInit(Runnable callback) {
        // Fix lost account after reboot bug
        if (sInstance == null) {
            sCallbacks.add(callback);
        } else {
            new Thread(callback).start(); // fix network on main thread exception
        }
    }

    public static boolean isInitialized() {
        return sInstance != null && sInstance.getContext() != null;
    }

    @Nullable
    public static Context context() {
        return sInstance != null ? sInstance.getContext() : null;
    }

    public String getRecommendedPlaylistType() {
        return getString(RECOMMENDED_PLAYLIST_TYPE, PLAYLIST_TYPE_RECOMMENDATIONS);
    }

    public void setRecommendedPlaylistType(String type) {
        putString(RECOMMENDED_PLAYLIST_TYPE, type);
    }

    //public String getMediaServiceAccountData() {
    //    return getString(MEDIA_SERVICE_ACCOUNT_DATA, null);
    //}
    //
    //public void setMediaServiceAccountData(String data) {
    //    putString(MEDIA_SERVICE_ACCOUNT_DATA, data);
    //}

    public String getMediaServiceAccountData() {
        return getData(MEDIA_SERVICE_ACCOUNT_DATA);
    }

    public void setMediaServiceAccountData(String data) {
        setData(MEDIA_SERVICE_ACCOUNT_DATA, data);
    }

    //public String getOAuth2AccountData() {
    //    return getString(OAUTH2_ACCOUNT_DATA, null);
    //}
    //
    //public void setOAuth2AccountData(String data) {
    //    putString(OAUTH2_ACCOUNT_DATA, data);
    //}

    public String getOAuth2AccountData() {
        return getData(OAUTH2_ACCOUNT_DATA);
    }

    public void setOAuth2AccountData(String data) {
        setData(OAUTH2_ACCOUNT_DATA, data);
    }

    //public String getMediaServiceData() {
    //    return getString(MEDIA_SERVICE_DATA, null);
    //}
    //
    //public void setMediaServiceData(String data) {
    //    putString(MEDIA_SERVICE_DATA, data);
    //}

    public String getMediaServiceData() {
        return getData(MEDIA_SERVICE_DATA);
    }

    public void setMediaServiceData(String data) {
        setData(MEDIA_SERVICE_DATA, data);
    }

    public String getPreferredLanguage() {
        return getString(PREFERRED_LANGUAGE_DATA, null);
    }

    public void setPreferredLanguage(String langData) {
        putString(PREFERRED_LANGUAGE_DATA, langData);
    }

    public String getPreferredCountry() {
        return getString(PREFERRED_COUNTRY_DATA, null);
    }

    public void setPreferredCountry(String countryData) {
        putString(PREFERRED_COUNTRY_DATA, countryData);
    }

    public boolean isChannelsServiceEnabled() {
        return getBoolean(ENABLE_CHANNELS_SERVICE, true);
    }

    public void setChannelsServiceEnabled(boolean enable) {
        putBoolean(ENABLE_CHANNELS_SERVICE, enable);
    }

    public int getPreferredDnsType() {
        return getInt(PREFERRED_DNS_TYPE, DNS_TYPE_IPV4);
    }

    public void setPreferredDnsType(int dnsType) {
        putInt(PREFERRED_DNS_TYPE, dnsType);
    }

    /**
     * How the public DNS servers are asked (see DNS_PROTOCOL_*). The system's DNS has its own (e.g. Android's Private DNS).
     */
    public int getPreferredDnsProtocol() {
        return getInt(PREFERRED_DNS_PROTOCOL, DNS_PROTOCOL_PLAIN);
    }

    public void setPreferredDnsProtocol(int protocol) {
        putInt(PREFERRED_DNS_PROTOCOL, protocol);
    }

    /**
     * The first of the custom DNS servers asked over UDP: an IPv4 address, null when not set
     */
    @Nullable
    public String getCustomDnsPrimary() {
        return getString(CUSTOM_DNS_PRIMARY, null);
    }

    public void setCustomDnsPrimary(@Nullable String address) {
        putString(CUSTOM_DNS_PRIMARY, address);
    }

    /**
     * The custom DNS server asked when the primary doesn't answer: an IPv4 address, null when not set
     */
    @Nullable
    public String getCustomDnsSecondary() {
        return getString(CUSTOM_DNS_SECONDARY, null);
    }

    public void setCustomDnsSecondary(@Nullable String address) {
        putString(CUSTOM_DNS_SECONDARY, address);
    }

    /**
     * The custom DNS-over-HTTPS server, e.g. https://dns.example/dns-query, null when not set
     */
    @Nullable
    public String getCustomDohUrl() {
        return getString(CUSTOM_DOH_URL, null);
    }

    public void setCustomDohUrl(@Nullable String url) {
        putString(CUSTOM_DOH_URL, url);
    }

    /**
     * The player's error fixing may switch the DNS to Google's when its lookups fail, whatever DNS is chosen
     */
    public boolean isDnsFixEnabled() {
        return getBoolean(DNS_FIX_ENABLED, true);
    }

    public void setDnsFixEnabled(boolean enable) {
        putBoolean(DNS_FIX_ENABLED, enable);
    }

    /**
     * IPv6 is tested once an app run, and left out till the app restarts when it doesn't connect (see Ipv6CheckDns).
     * Read at each lookup, so a switch applies without a restart.
     */
    public boolean isIpv6TestEnabled() {
        return getBoolean(IPV6_TEST_ENABLED, true);
    }

    public void setIpv6TestEnabled(boolean enable) {
        putBoolean(IPV6_TEST_ENABLED, enable);
    }

    public boolean isContentBlockAltServerEnabled() {
        return getBoolean(CONTENT_BLOCK_ALT_SERVER, false);
    }

    public void setContentBlockAltServerEnabled(boolean enable) {
        putBoolean(CONTENT_BLOCK_ALT_SERVER, enable);
    }

    public boolean is24HourLocaleEnabled() {
        return getBoolean(IS_24_HOUR_LOCALE_ENABLED, DateHelper.is24HourLocale());
    }

    public void set24HourLocaleEnabled(boolean enable) {
        putBoolean(IS_24_HOUR_LOCALE_ENABLED, enable);
    }
    
    @Override
    protected String getPrefsDir() {
        return PREFS_DIR;
    }
}
