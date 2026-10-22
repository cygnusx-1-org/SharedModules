package com.liskovsoft.sharedutils.okhttp;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;

/**
 * The calls one task makes on its thread (see RequestScopeInterceptor), so cancelling the task cancels them.<br/>
 * Interrupting the thread doesn't stop a call that waits on a connect: it holds the thread to the connect timeout.
 * Cancelling the call closes its socket.
 */
public final class RequestScope {
    private static final ThreadLocal<RequestScope> sCurrent = new ThreadLocal<>();
    private final List<Call> mCalls = new ArrayList<>();
    private boolean mIsCancelled;

    /**
     * The scope of the task running on this thread or null
     */
    @Nullable
    public static RequestScope current() {
        return sCurrent.get();
    }

    /**
     * Makes this the scope of the calls on this thread until {@link #exit}
     * @return the scope it replaces, to give back to {@link #exit}
     */
    @Nullable
    public RequestScope enter() {
        RequestScope previous = sCurrent.get();
        sCurrent.set(this);
        return previous;
    }

    public void exit(@Nullable RequestScope previous) {
        if (previous != null) {
            sCurrent.set(previous);
        } else {
            sCurrent.remove();
        }

        synchronized (this) {
            mCalls.clear();
        }
    }

    /**
     * A call made after the cancel is cancelled at once
     */
    public void add(Call call) {
        synchronized (this) {
            if (!mIsCancelled) {
                mCalls.add(call);
                return;
            }
        }

        call.cancel();
    }

    public void cancel() {
        List<Call> calls;

        synchronized (this) {
            mIsCancelled = true;
            calls = new ArrayList<>(mCalls);
            mCalls.clear();
        }

        for (Call call : calls) {
            call.cancel();
        }
    }
}
