package dev.mars.qraft.controller;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QraftControllerApplicationTest {

    @Test
    void startupFailureHandsProcessExitOffTheCompletingThread() throws Exception {
        Thread completingThread = Thread.currentThread();
        AtomicReference<Thread> exitThread = new AtomicReference<>();
        CountDownLatch exitCalled = new CountDownLatch(1);

        Thread thread = QraftControllerApplication.requestProcessExit(() -> {
            exitThread.set(Thread.currentThread());
            exitCalled.countDown();
        });

        assertTrue(exitCalled.await(2, TimeUnit.SECONDS));
        thread.join(2000);
        assertNotSame(completingThread, exitThread.get());
        assertTrue(exitThread.get().getName().startsWith("qraft-startup-exit"));
    }
}
