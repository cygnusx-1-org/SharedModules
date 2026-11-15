package com.liskovsoft.sharedutils.rx;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class OverflowPolicyTest {
    private final CountDownLatch mRelease = new CountDownLatch(1);
    private ExecutorService mOverflow;
    private ThreadPoolExecutor mExecutor;

    @Before
    public void setUp() throws InterruptedException {
        mOverflow = Executors.newSingleThreadExecutor();
        // One thread, one queue slot: the third task is the overflow's
        mExecutor = new RemoveOnCancelThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1), new OverflowPolicy(mOverflow));

        CountDownLatch started = new CountDownLatch(1);
        mExecutor.submit(() -> {
            started.countDown();
            mRelease.await();
            return null;
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        mExecutor.submit(() -> {});
    }

    @After
    public void tearDown() {
        mRelease.countDown();
        mExecutor.shutdownNow();
        mOverflow.shutdownNow();
    }

    @Test
    public void taskOfTheFullPoolRunsOnTheOverflowNotTheCaller() throws InterruptedException {
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);

        mExecutor.submit(() -> {
            ranOn.set(Thread.currentThread());
            ran.countDown();
        });

        assertTrue(ran.await(5, TimeUnit.SECONDS));
        assertNotNull(ranOn.get());
        assertNotSame(Thread.currentThread(), ranOn.get());
    }

    @Test
    public void taskOfTheFullPoolStillRunsWhileThePoolWaits() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);

        // The pool's thread is held (mRelease): the task doesn't wait for it
        mExecutor.submit(ran::countDown);

        assertTrue(ran.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void cancelledOverflowTaskDoesNotRun() throws InterruptedException {
        CountDownLatch holdOverflow = new CountDownLatch(1);
        mOverflow.submit(() -> {
            holdOverflow.await();
            return null;
        });
        AtomicReference<Boolean> ran = new AtomicReference<>(false);

        mExecutor.submit(() -> ran.set(true)).cancel(false);
        holdOverflow.countDown();
        mOverflow.shutdown();

        assertTrue(mOverflow.awaitTermination(5, TimeUnit.SECONDS));
        assertSame(false, ran.get());
    }

    @Test(expected = RejectedExecutionException.class)
    public void shutDownPoolStillRejects() {
        ThreadPoolExecutor executor = new RemoveOnCancelThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1), new OverflowPolicy(mOverflow));
        executor.shutdown();

        executor.submit(() -> {});
    }
}
