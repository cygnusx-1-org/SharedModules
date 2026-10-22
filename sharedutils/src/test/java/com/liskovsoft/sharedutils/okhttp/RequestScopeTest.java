package com.liskovsoft.sharedutils.okhttp;

import org.junit.Test;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RequestScopeTest {
    private final OkHttpClient mClient = new OkHttpClient();

    private Call newCall() {
        return mClient.newCall(new Request.Builder().url("http://127.0.0.1/").build());
    }

    @Test
    public void cancelCancelsTheCallsAdded() {
        RequestScope scope = new RequestScope();
        Call call = newCall();
        scope.add(call);

        scope.cancel();

        assertTrue(call.isCanceled());
    }

    @Test
    public void callAddedAfterCancelIsCancelledAtOnce() {
        RequestScope scope = new RequestScope();
        scope.cancel();
        Call call = newCall();

        scope.add(call);

        assertTrue(call.isCanceled());
    }

    @Test
    public void callsOfAnExitedScopeAreLeftAlone() {
        RequestScope scope = new RequestScope();
        RequestScope previous = scope.enter();
        Call call = newCall();
        scope.add(call);
        scope.exit(previous);

        scope.cancel();

        assertFalse(call.isCanceled());
    }

    @Test
    public void exitGivesBackTheScopeItReplaced() {
        RequestScope outer = new RequestScope();
        RequestScope inner = new RequestScope();

        RequestScope none = outer.enter();
        RequestScope previous = inner.enter();
        assertSame(inner, RequestScope.current());

        inner.exit(previous);
        assertSame(outer, RequestScope.current());

        outer.exit(none);
        assertNull(RequestScope.current());
    }
}
