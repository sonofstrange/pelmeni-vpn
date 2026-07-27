package com.example.sshtunnel;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;

final class UserAccessPolicy {
    static final class Policy {
        final boolean configured;
        final String expires;
        final long dailyMb;
        final long monthlyMb;
        final long speedMbps;

        Policy(boolean configured, String expires,
               long dailyMb, long monthlyMb, long speedMbps) {
            this.configured = configured;
            this.expires = expires;
            this.dailyMb = dailyMb;
            this.monthlyMb = monthlyMb;
            this.speedMbps = speedMbps;
        }

        boolean hasLimits() {
            return dailyMb > 0 || monthlyMb > 0 || speedMbps > 0;
        }
    }

    static final class Usage {
        final long dayBytes;
        final long monthBytes;

        Usage(long dayBytes, long monthBytes) {
            this.dayBytes = dayBytes;
            this.monthBytes = monthBytes;
        }
    }

    static final class Alert {
        final String title;
        final String text;
        final boolean critical;

        Alert(String title, String text, boolean critical) {
            this.title = title;
            this.text = text;
            this.critical = critical;
        }
    }

    static void saveFromCode(
            SecureStore store, String profileId, JSONObject code) {
        String prefix = prefix(profileId);
        long dailyMb = Math.max(0, code.optLong("daily_mb", 0));
        long monthlyMb = Math.max(0, code.optLong("monthly_mb", 0));
        boolean dailyChanged = dailyMb != store.getLong(prefix + "daily_mb", 0);
        boolean monthlyChanged = monthlyMb != store.getLong(prefix + "monthly_mb", 0);
        store.putBoolean(prefix + "configured", true);
        store.putPlain(prefix + "expires", code.optString("expires", ""));
        store.putLong(prefix + "daily_mb", dailyMb);
        store.putLong(prefix + "monthly_mb", monthlyMb);
        store.putLong(prefix + "speed_mbps", Math.max(0, code.optLong("speed_mbps", 0)));
        if (dailyChanged) store.putLong(prefix + "day_notice", 0);
        if (monthlyChanged) store.putLong(prefix + "month_notice", 0);
        normalizeUsage(store, profileId);
    }

    static Policy load(SecureStore store, String profileId) {
        String prefix = prefix(profileId);
        return new Policy(
                store.getBoolean(prefix + "configured", false),
                store.getPlain(prefix + "expires", ""),
                store.getLong(prefix + "daily_mb", 0),
                store.getLong(prefix + "monthly_mb", 0),
                store.getLong(prefix + "speed_mbps", 0));
    }

    static void syncFromServer(
            SecureStore store, String profileId, Session session) throws Exception {
        if (!load(store, profileId).configured || session == null
                || !session.isConnected()) return;
        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            channel.connect(5_000);
            channel.get(".pelmeni-policy.json", output);
            JSONObject policy = new JSONObject(
                    output.toString(StandardCharsets.UTF_8.name()));
            saveFromCode(store, profileId, policy);
        } finally {
            channel.disconnect();
        }
    }

    static Usage usage(SecureStore store, String profileId) {
        normalizeUsage(store, profileId);
        String prefix = prefix(profileId);
        return new Usage(
                store.getLong(prefix + "day_bytes", 0),
                store.getLong(prefix + "month_bytes", 0));
    }

    static Alert record(
            SecureStore store, String profileId, long addedBytes) {
        Policy policy = load(store, profileId);
        if (!policy.configured || addedBytes <= 0) return null;
        normalizeUsage(store, profileId);
        String prefix = prefix(profileId);
        long dayBytes = store.getLong(prefix + "day_bytes", 0) + addedBytes;
        long monthBytes = store.getLong(prefix + "month_bytes", 0) + addedBytes;
        store.putLong(prefix + "day_bytes", dayBytes);
        store.putLong(prefix + "month_bytes", monthBytes);

        int dayLevel = level(dayBytes, policy.dailyMb);
        int monthLevel = level(monthBytes, policy.monthlyMb);
        int oldDay = (int) store.getLong(prefix + "day_notice", 0);
        int oldMonth = (int) store.getLong(prefix + "month_notice", 0);
        boolean dayChanged = dayLevel > oldDay;
        boolean monthChanged = monthLevel > oldMonth;
        if (dayChanged) store.putLong(prefix + "day_notice", dayLevel);
        if (monthChanged) store.putLong(prefix + "month_notice", monthLevel);
        if (!dayChanged && !monthChanged) return null;

        boolean daily = dayChanged && (!monthChanged || dayLevel >= monthLevel);
        int alertLevel = daily ? dayLevel : monthLevel;
        long used = daily ? dayBytes : monthBytes;
        long limitMb = daily ? policy.dailyMb : policy.monthlyMb;
        String period = daily ? "дневного" : "месячного";
        if (alertLevel >= 100) {
            return new Alert("Лимит трафика достигнут",
                    "Использовано " + formatMb(used) + " из " + limitMb
                            + " МБ " + period + " лимита. Сервер остановит трафик.",
                    true);
        }
        return new Alert("Заканчивается трафик",
                "Использовано " + alertLevel + "% " + period + " лимита: "
                        + formatMb(used) + " из " + limitMb + " МБ.", false);
    }

    private static int level(long bytes, long limitMb) {
        if (limitMb <= 0) return 0;
        double ratio = bytes / (limitMb * 1024.0 * 1024.0);
        if (ratio >= 1.0) return 100;
        if (ratio >= 0.90) return 90;
        if (ratio >= 0.75) return 75;
        return 0;
    }

    static String warning(Policy policy, Usage usage) {
        double daily = policy.dailyMb <= 0 ? 0
                : usage.dayBytes / (policy.dailyMb * 1024.0 * 1024.0);
        double monthly = policy.monthlyMb <= 0 ? 0
                : usage.monthBytes / (policy.monthlyMb * 1024.0 * 1024.0);
        double ratio = Math.max(daily, monthly);
        if (ratio < 0.75) return "";
        String period = daily >= monthly ? "дневного" : "месячного";
        return ratio >= 1
                ? "Лимит " + period + " трафика исчерпан"
                : "Осталось около " + Math.max(0, 100 - (int) (ratio * 100))
                + "% " + period + " лимита";
    }

    private static void normalizeUsage(SecureStore store, String profileId) {
        String prefix = prefix(profileId);
        if (store.getLong(prefix + "notice_version", 0) != 2) {
            store.putLong(prefix + "notice_version", 2);
            store.putLong(prefix + "day_notice", 0);
            store.putLong(prefix + "month_notice", 0);
        }
        String today = LocalDate.now().toString();
        String month = YearMonth.now().toString();
        if (!today.equals(store.getPlain(prefix + "day", ""))) {
            store.putPlain(prefix + "day", today);
            store.putLong(prefix + "day_bytes", 0);
            store.putLong(prefix + "day_notice", 0);
        }
        if (!month.equals(store.getPlain(prefix + "month", ""))) {
            store.putPlain(prefix + "month", month);
            store.putLong(prefix + "month_bytes", 0);
            store.putLong(prefix + "month_notice", 0);
        }
    }

    private static String formatMb(long bytes) {
        return String.format(java.util.Locale.getDefault(), "%.1f",
                bytes / 1024.0 / 1024.0);
    }

    private static String prefix(String profileId) {
        return "access_" + profileId + "_";
    }

    private UserAccessPolicy() {
    }
}
