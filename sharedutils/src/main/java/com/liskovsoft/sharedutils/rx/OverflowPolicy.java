package com.liskovsoft.sharedutils.rx;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import io.reactivex.schedulers.Schedulers;

/**
 * A task the full pool can't take runs on another thread (one of Schedulers.io() by default, which grows as needed), never on the one
 * that submitted it. With ThreadPoolExecutor.CallerRunsPolicy, a subscription made on the main thread ran there while the pool was full
 * (e.g. while the sections loaded), and its work that waits (a request, a poToken) froze the app: an ANR.
 */
final class OverflowPolicy implements RejectedExecutionHandler {
    private final Executor mOverflow;

    OverflowPolicy() {
        this(task -> Schedulers.io().scheduleDirect(task));
    }

    /**
     * @param overflow where the tasks the pool can't take run
     */
    OverflowPolicy(Executor overflow) {
        mOverflow = overflow;
    }

    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
        if (executor.isShutdown()) {
            throw new RejectedExecutionException("The pool is shut down");
        }

        mOverflow.execute(task);
    }
}
