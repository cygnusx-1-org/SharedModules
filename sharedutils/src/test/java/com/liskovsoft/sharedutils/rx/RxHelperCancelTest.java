package com.liskovsoft.sharedutils.rx;

import com.liskovsoft.sharedutils.okhttp.interceptors.RequestScopeInterceptor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.disposables.Disposable;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static org.junit.Assert.assertTrue;

/**
 * A server that takes the request and never answers: the call waits on the response, like one stalled on a connect.<br/>
 * On the JVM interrupting the thread closes its socket too (Android's doesn't), so the call is checked as well.
 */
@RunWith(RobolectricTestRunner.class)
public class RxHelperCancelTest {
    private ServerSocket mServer;
    private final CountDownLatch mRequestRead = new CountDownLatch(1);
    private final CountDownLatch mClientClosed = new CountDownLatch(1);

    @Before
    public void setUp() throws IOException {
        mServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());

        Thread server = new Thread(() -> {
            try (Socket socket = mServer.accept()) {
                InputStream in = socket.getInputStream();
                byte[] buffer = new byte[4096];

                if (in.read(buffer) > 0) {
                    mRequestRead.countDown();
                }

                // Until the client closes the connection
                while (in.read(buffer) != -1) {
                    // NOP
                }

                mClientClosed.countDown();
            } catch (IOException e) {
                mClientClosed.countDown();
            }
        });
        server.setDaemon(true);
        server.start();
    }

    @After
    public void tearDown() throws IOException {
        mServer.close();
    }

    @Test
    public void disposeCancelsTheRunningRequest() throws InterruptedException {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new RequestScopeInterceptor())
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url("http://127.0.0.1:" + mServer.getLocalPort() + "/").build();
        AtomicReference<Call> call = new AtomicReference<>();

        Disposable action = RxHelper.fromCallable(() -> {
            call.set(client.newCall(request));

            try (Response response = call.get().execute()) {
                return response.code();
            }
        }).subscribe(code -> {}, error -> {});

        assertTrue("The request didn't reach the server", mRequestRead.await(10, TimeUnit.SECONDS));

        action.dispose();

        assertTrue("The dispose didn't cancel the call", call.get().isCanceled());
        // Otherwise the call holds its thread until the read timeout
        assertTrue("The dispose didn't close the connection", mClientClosed.await(3, TimeUnit.SECONDS));
    }
}
