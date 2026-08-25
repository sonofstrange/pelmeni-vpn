package com.example.sshtunnel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UserTrafficLimiterTest {
    @Test public void stopsTrafficAfterDailyLimit() throws Exception {
        UserTrafficLimiter limiter = new UserTrafficLimiter();
        limiter.refresh(
                new UserAccessPolicy.Policy(
                        true, "", 1, 0, 0, 0,
                        System.currentTimeMillis() / 1000L),
                new UserAccessPolicy.Usage(0, 0, 0, 0, 0));

        assertEquals(1024 * 1024, limiter.acquire(1024 * 1024));
        assertEquals(0, limiter.acquire(1));
    }

    @Test public void restoresPreviouslyRecordedMonthlyUsage() throws Exception {
        UserTrafficLimiter limiter = new UserTrafficLimiter();
        long limit = 2L * 1024L * 1024L;
        limiter.refresh(
                new UserAccessPolicy.Policy(
                        true, "", 0, 2, 0, 0,
                        System.currentTimeMillis() / 1000L),
                new UserAccessPolicy.Usage(0, limit, 0, 0, 0));

        assertEquals(0, limiter.acquire(4096));
    }

    @Test public void concurrentAcquisitionDoesNotDeadlock() throws Exception {
        UserTrafficLimiter limiter = new UserTrafficLimiter();
        limiter.refresh(
                new UserAccessPolicy.Policy(
                        true, "", 100, 1000, 10, 0,
                        System.currentTimeMillis() / 1000L),
                new UserAccessPolicy.Usage(0, 0, 0, 0, 0));

        Thread t1 = new Thread(() -> {
            try {
                limiter.acquire(1024);
            } catch (InterruptedException ignored) {}
        });
        Thread t2 = new Thread(() -> {
            try {
                limiter.acquire(1024);
            } catch (InterruptedException ignored) {}
        });
        t1.start();
        t2.start();
        t1.join(2000);
        t2.join(2000);
        assertEquals(Thread.State.TERMINATED, t1.getState());
        assertEquals(Thread.State.TERMINATED, t2.getState());
    }
}
