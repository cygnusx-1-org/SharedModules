package com.liskovsoft.sharedutils.rx;

import androidx.annotation.NonNull;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A cancelled task leaves the queue at once. A ThreadPoolExecutor keeps it there until a thread takes it,
 * so tasks disposed while waiting fill a bounded queue and the next ones are rejected.
 */
class RemoveOnCancelThreadPoolExecutor extends ThreadPoolExecutor {
    RemoveOnCancelThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
                                     BlockingQueue<Runnable> workQueue, RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, handler);
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new RemoveOnCancelTask<>(runnable, value);
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new RemoveOnCancelTask<>(callable);
    }

    private class RemoveOnCancelTask<T> extends FutureTask<T> {
        RemoveOnCancelTask(@NonNull Callable<T> callable) {
            super(callable);
        }

        RemoveOnCancelTask(@NonNull Runnable runnable, T result) {
            super(runnable, result);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);

            if (cancelled) {
                remove(this);
            }

            return cancelled;
        }
    }
}
