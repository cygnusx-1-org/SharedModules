package com.liskovsoft.sharedutils.okhttp;

import com.liskovsoft.sharedutils.okhttp.interceptors.RequestScopeInterceptor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class OkHttpCommonsTest {
    @Test
    public void setupBuilderAddsRequestScopeInterceptor() {
        OkHttpClient client = OkHttpCommons.setupBuilder(new OkHttpClient.Builder()).build();

        boolean found = false;

        for (Interceptor interceptor : client.interceptors()) {
            found |= interceptor instanceof RequestScopeInterceptor;
        }

        assertTrue("Disposing an RxHelper task wouldn't cancel the calls of the app's client", found);
    }
}
