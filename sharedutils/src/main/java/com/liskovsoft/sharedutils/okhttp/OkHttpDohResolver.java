package com.liskovsoft.sharedutils.okhttp;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.xbill.DNS.EDNSOption;
import org.xbill.DNS.Message;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.TSIG;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * DNS-over-HTTPS (RFC 8484) for dnsjava's lookups: the query's DNS message POSTed with OkHttp. The answer's records keep their TTL,
 * which PublicDnsResolver caches by (OkHttp's DnsOverHttps gives addresses only).<br/>
 * Its own client, not the app's: that one trusts any certificate up to Android 7.0 (see OkHttpCommons.configureToIgnoreCertificate),
 * and its DNS is the one this resolver serves.
 */
final class OkHttpDohResolver implements Resolver {
    private static final MediaType DNS_MESSAGE = MediaType.get("application/dns-message");
    private static final long DEFAULT_TIMEOUT_MS = 5_000;
    private static volatile OkHttpClient sBaseClient;
    private final OkHttpClient mClient;
    private final HttpUrl mUrl;
    private volatile long mTimeoutMs = DEFAULT_TIMEOUT_MS;

    /**
     * @param bootstrapAddresses where the server's host is, so it isn't looked up with the system's DNS. None: it is.
     */
    OkHttpDohResolver(@NonNull HttpUrl url, @NonNull List<InetAddress> bootstrapAddresses) {
        mUrl = url;
        mClient = getBaseClient().newBuilder()
                .dns(new BootstrapDns(url.host(), bootstrapAddresses))
                .build();
    }

    @VisibleForTesting
    OkHttpDohResolver(@NonNull HttpUrl url, @NonNull OkHttpClient client) {
        mUrl = url;
        mClient = client;
    }

    @Override
    public Message send(Message query) throws IOException {
        Request request = new Request.Builder()
                .url(mUrl)
                .header("Accept", DNS_MESSAGE.toString())
                .post(RequestBody.create(query.toWire(), DNS_MESSAGE))
                .build();
        Call call = mClient.newCall(request);
        call.timeout().timeout(mTimeoutMs, TimeUnit.MILLISECONDS);

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("DNS-over-HTTPS server answered HTTP " + response.code());
            }

            return new Message(response.body().bytes());
        }
    }

    @Override
    public CompletionStage<Message> sendAsync(Message query, Executor executor) {
        CompletableFuture<Message> result = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                result.complete(send(query));
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });

        return result;
    }

    @Override
    public void setTimeout(Duration timeout) {
        mTimeoutMs = timeout.toMillis();
    }

    @Override
    public Duration getTimeout() {
        return Duration.ofMillis(mTimeoutMs);
    }

    @Override
    public void setPort(int port) {
        // The URL's
    }

    @Override
    public void setTCP(boolean flag) {
        // Always HTTPS
    }

    @Override
    public void setIgnoreTruncation(boolean flag) {
        // An HTTPS answer isn't truncated
    }

    @Override
    public void setEDNS(int version, int payloadSize, int flags, List<EDNSOption> options) {
        // Not used by the lookups
    }

    @Override
    public void setTSIGKey(TSIG key) {
        // Not used by the lookups
    }

    @NonNull
    @Override
    public String toString() {
        return "OkHttpDohResolver [" + mUrl + "]";
    }

    /**
     * One connection pool for every server: a switch of the settings makes new resolvers
     */
    private static OkHttpClient getBaseClient() {
        if (sBaseClient == null) {
            synchronized (OkHttpDohResolver.class) {
                if (sBaseClient == null) {
                    sBaseClient = new OkHttpClient.Builder()
                            .connectTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            // An answer comes from the server asked, or none does
                            .followRedirects(false)
                            .followSslRedirects(false)
                            .build();
                }
            }
        }

        return sBaseClient;
    }

    /**
     * The server's host is where it was told to be: its IPv4 addresses only while IPv6 doesn't connect (see Ipv6CheckDns), as the
     * app's lookups give. Any other host (none is asked) and a server without addresses: the system's DNS.
     */
    private static final class BootstrapDns implements Dns {
        private final String mHost;
        private final List<InetAddress> mAddresses;

        BootstrapDns(String host, List<InetAddress> addresses) {
            mHost = host;
            mAddresses = new ArrayList<>(addresses);
        }

        @NonNull
        @Override
        public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
            if (mAddresses.isEmpty() || !mHost.equalsIgnoreCase(hostname)) {
                return Dns.SYSTEM.lookup(hostname);
            }

            if (Ipv6CheckDns.isIpv6Disabled()) {
                List<InetAddress> ipv4Addresses = new ArrayList<>();

                for (InetAddress address : mAddresses) {
                    if (address instanceof Inet4Address) {
                        ipv4Addresses.add(address);
                    }
                }

                if (!ipv4Addresses.isEmpty()) {
                    return ipv4Addresses;
                }
            }

            return new ArrayList<>(mAddresses);
        }
    }
}
