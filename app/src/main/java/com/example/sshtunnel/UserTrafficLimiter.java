package com.example.sshtunnel;

/** Shared client-side quota and bandwidth limiter for all proxy modes. */
final class UserTrafficLimiter {
    private long dailyLimitBytes;
    private long monthlyLimitBytes;
    private long bytesPerSecond;
    private long issuedAt;
    private long dayUsed;
    private long monthUsed;
    private long dayPeriod = -1;
    private long monthPeriod = -1;
    private long resetAt = -1;
    private long nextAvailableNanos;

    synchronized void refresh(
            UserAccessPolicy.Policy policy, UserAccessPolicy.Usage usage) {
        dailyLimitBytes = toBytes(policy.dailyMb);
        monthlyLimitBytes = toBytes(policy.monthlyMb);
        bytesPerSecond = policy.speedMbps <= 0 ? 0
                : policy.speedMbps > Long.MAX_VALUE / 1_000_000L
                ? Long.MAX_VALUE / 8L
                : policy.speedMbps * 1_000_000L / 8L;
        issuedAt = policy.issuedAt;
        boolean manuallyReset = resetAt >= 0 && resetAt != usage.resetAt;
        if (dayPeriod != usage.dayPeriod || manuallyReset) {
            dayUsed = usage.dayBytes;
        } else {
            dayUsed = Math.max(dayUsed, usage.dayBytes);
        }
        if (monthPeriod != usage.monthPeriod || manuallyReset) {
            monthUsed = usage.monthBytes;
        } else {
            monthUsed = Math.max(monthUsed, usage.monthBytes);
        }
        dayPeriod = usage.dayPeriod;
        monthPeriod = usage.monthPeriod;
        resetAt = usage.resetAt;
        nextAvailableNanos = System.nanoTime();
    }

    synchronized int acquire(int requested) throws InterruptedException {
        refreshPeriods();
        long allowed = requested;
        if (dailyLimitBytes > 0) {
            allowed = Math.min(allowed, Math.max(0, dailyLimitBytes - dayUsed));
        }
        if (monthlyLimitBytes > 0) {
            allowed = Math.min(allowed, Math.max(0, monthlyLimitBytes - monthUsed));
        }
        if (allowed <= 0) return 0;
        throttle(allowed);
        dayUsed += allowed;
        monthUsed += allowed;
        return (int) allowed;
    }

    private void refreshPeriods() {
        long now = System.currentTimeMillis() / 1000L;
        long origin = issuedAt > 0 ? issuedAt : now;
        long elapsed = Math.max(0, now - origin);
        long currentDay = elapsed / UserAccessPolicy.DAY_SECONDS;
        long currentMonth = elapsed / UserAccessPolicy.MONTH_SECONDS;
        if (currentDay != dayPeriod) {
            dayPeriod = currentDay;
            dayUsed = 0;
        }
        if (currentMonth != monthPeriod) {
            monthPeriod = currentMonth;
            monthUsed = 0;
        }
    }

    private void throttle(long bytes) throws InterruptedException {
        if (bytesPerSecond <= 0) return;
        long now = System.nanoTime();
        long start = Math.max(now, nextAvailableNanos);
        long duration = Math.max(1,
                (bytes * 1_000_000_000L + bytesPerSecond - 1) / bytesPerSecond);
        nextAvailableNanos = start + duration;
        long wait = start - now;
        if (wait <= 0) return;
        long millis = wait / 1_000_000L;
        int nanos = (int) (wait % 1_000_000L);
        Thread.sleep(millis, nanos);
    }

    private static long toBytes(long megabytes) {
        if (megabytes <= 0) return 0;
        if (megabytes > Long.MAX_VALUE / 1024L / 1024L) return Long.MAX_VALUE;
        return megabytes * 1024L * 1024L;
    }
}
