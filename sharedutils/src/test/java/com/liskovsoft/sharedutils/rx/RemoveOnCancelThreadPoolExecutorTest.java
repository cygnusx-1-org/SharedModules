package com.liskovsoft.sharedutils.rx;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RemoveOnCancelThreadPoolExecutorTest {
    private final CountDownLatch mRelease = new CountDownLatch(1);
    private ThreadPoolExecutor mExecutor;

    @Before
    public void setUp() throws InterruptedException {
        // One thread, one queue slot, rejects when both are taken
        mExecutor = new RemoveOnCancelThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());

        CountDownLatch started = new CountDownLatch(1);
        mExecutor.submit(() -> {
            started.countDown();
            mRelease.await();
            return null;
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
    }

    @After
    public void tearDown() {
        mRelease.countDown();
        mExecutor.shutdownNow();
    }

    @Test
    public void cancelledTaskLeavesTheQueue() {
        Future<?> queued = mExecutor.submit(() -> {});
        assertEquals(1, mExecutor.getQueue().size());

        queued.cancel(true);

        assertEquals(0, mExecutor.getQueue().size());
    }

    @Test
    public void cancelledTaskFreesItsSlotForTheNextOne() {
        mExecutor.submit(() -> {}).cancel(true);

        // Rejected (RejectedExecutionException) if the cancelled task still held the only slot
        mExecutor.submit(() -> {});

        assertEquals(1, mExecutor.getQueue().size());
    }

    @Test
    public void cancelledRunnableLeavesTheQueue() {
        Future<?> queued = mExecutor.submit((Runnable) () -> {});

        queued.cancel(false);

        assertEquals(0, mExecutor.getQueue().size());
    }
}
