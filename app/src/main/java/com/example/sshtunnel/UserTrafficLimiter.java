package com.example.sshtunnel;

/** Shared client-side quota and bandwidth limiter for all proxy modes. */
final class UserTrafficLimiter {
    private static final long MAX_FINAL_BLOCK_OVERAGE = 32 * 1024L;
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

    int acquire(int requested) throws InterruptedException {
        if (dailyLimitBytes <= 0 && monthlyLimitBytes <= 0 && bytesPerSecond <= 0) {
            return requested;
        }
        long waitNanos = 0;
        long allowed;
        synchronized (this) {
            refreshPeriods();
            long remaining = Long.MAX_VALUE;
            if (dailyLimitBytes > 0) {
                remaining = Math.min(remaining, dailyLimitBytes - dayUsed);
            }
            if (monthlyLimitBytes > 0) {
                remaining = Math.min(remaining, monthlyLimitBytes - monthUsed);
            }
            if (remaining <= 0) return 0;
            long withOverage = remaining > Long.MAX_VALUE - MAX_FINAL_BLOCK_OVERAGE
                    ? Long.MAX_VALUE : remaining + MAX_FINAL_BLOCK_OVERAGE;
            allowed = Math.min(requested, withOverage);
            if (bytesPerSecond > 0) {
                long now = System.nanoTime();
                long start = Math.max(now, nextAvailableNanos);
                long duration = Math.max(1,
                        (allowed * 1_000_000_000L + bytesPerSecond - 1) / bytesPerSecond);
                nextAvailableNanos = start + duration;
                long wait = start - now;
                if (wait > 0) {
                    waitNanos = wait;
                }
            }
            dayUsed += allowed;
            monthUsed += allowed;
        }
        if (waitNanos > 0) {
            long millis = waitNanos / 1_000_000L;
            int nanos = (int) (waitNanos % 1_000_000L);
            Thread.sleep(millis, nanos);
        }
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

    private static long toBytes(long megabytes) {
        if (megabytes <= 0) return 0;
        if (megabytes > Long.MAX_VALUE / 1024L / 1024L) return Long.MAX_VALUE;
        return megabytes * 1024L * 1024L;
    }
}
