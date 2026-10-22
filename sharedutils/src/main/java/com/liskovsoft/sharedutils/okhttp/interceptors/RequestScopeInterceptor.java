package com.liskovsoft.sharedutils.okhttp.interceptors;

import androidx.annotation.NonNull;

import com.liskovsoft.sharedutils.okhttp.RequestScope;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Response;

/**
 * Adds each call to the scope of the task that makes it (see RequestScope)
 */
public class RequestScopeInterceptor implements Interceptor {
    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        RequestScope scope = RequestScope.current();

        if (scope != null) {
            scope.add(chain.call());
        }

        return chain.proceed(chain.request());
    }
}
