package com.example.sshtunnel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class ApkUpdateInstaller {
    interface Progress {
        void update(int percent);
    }

    static File downloadAndVerify(Context context, SecureStore store,
                                  UpdateChecker.Result update,
                                  Progress progress) throws Exception {
        if (!update.canInstallSecurely()) {
            throw new Exception("В релизе нет проверяемого SHA-256.");
        }
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new Exception("Не удалось создать временную папку.");
        }
        String safeVersion = update.version.replaceAll("[^0-9A-Za-z._-]", "_");
        File apk = new File(directory, "pelmeni-" + safeVersion + ".apk");
        if (apk.exists() && !apk.delete()) {
            throw new Exception("Не удалось заменить старый файл обновления.");
        }

        HttpURLConnection connection = openDownload(
                store, update.downloadUrl);
        long received = 0;
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(apk)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) {
                received += count;
                if (received > 200L * 1024 * 1024) {
                    throw new Exception("APK превышает допустимый размер.");
                }
                output.write(buffer, 0, count);
                if (update.size > 0) {
                    progress.update((int) Math.min(
                            100, received * 100 / update.size));
                }
            }
        } catch (Exception error) {
            apk.delete();
            throw error;
        } finally {
            connection.disconnect();
        }
        File verified = verifyDownloaded(context, apk, update);
        progress.update(100);
        return verified;
    }

    static File verifyDownloaded(Context context, File apk,
                                 UpdateChecker.Result update)
            throws Exception {
        if (update.size > 0 && apk.length() != update.size) {
            apk.delete();
            throw new Exception("Размер APK не совпал с данными релиза.");
        }
        String actualDigest = sha256(apk);
        if (!MessageDigest.isEqual(
                actualDigest.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                update.sha256.toLowerCase(Locale.ROOT).getBytes(
                        java.nio.charset.StandardCharsets.US_ASCII))) {
            apk.delete();
            throw new Exception("SHA-256 APK не совпал с релизом.");
        }
        verifyPackage(context, apk, update.version);
        return apk;
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) {
                digest.update(buffer, 0, count);
            }
        }
        return toHex(digest.digest());
    }

    static void install(Activity activity, File apk) {
        Uri uri = FileProvider.getUriForFile(
                activity, BuildConfig.APPLICATION_ID + ".updates", apk);
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri,
                        "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(install);
    }

    private static HttpURLConnection openDownload(
            SecureStore store, String address) throws Exception {
        URL url = new URL(address);
        for (int redirects = 0; redirects <= 5; redirects++) {
            requireTrustedUrl(url);
            java.net.URLConnection raw;
            if (TunnelService.isConnected()) {
                Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                        new InetSocketAddress("127.0.0.1",
                                TunnelMode.testSocksPort(store)));
                raw = url.openConnection(proxy);
            } else {
                raw = url.openConnection();
            }
            HttpURLConnection connection = (HttpURLConnection) raw;
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setUseCaches(false);
            connection.setRequestProperty(
                    "User-Agent", "PelmeniVPN-Updater/2");
            int status = connection.getResponseCode();
            if (status == 200) return connection;
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new Exception("GitHub вернул пустое перенаправление.");
                }
                url = new URL(url, location);
                continue;
            }
            connection.disconnect();
            throw new Exception("Не удалось скачать APK: HTTP " + status);
        }
        throw new Exception("Слишком много перенаправлений при скачивании.");
    }

    static boolean isTrustedDownloadUrl(String address) {
        try {
            return isTrustedUrl(new URL(address));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void requireTrustedUrl(URL url) throws Exception {
        if (!isTrustedUrl(url)) {
            throw new Exception("Неподдерживаемый адрес обновления.");
        }
    }

    private static boolean isTrustedUrl(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        boolean trustedHost = host.equals("github.com")
                || host.endsWith(".github.com")
                || host.equals("githubusercontent.com")
                || host.endsWith(".githubusercontent.com");
        return "https".equalsIgnoreCase(url.getProtocol()) && trustedHost;
    }

    private static void verifyPackage(
            Context context, File apk, String expectedVersion)
            throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo archive = manager.getPackageArchiveInfo(
                apk.getAbsolutePath(), flags);
        PackageInfo installed = manager.getPackageInfo(
                context.getPackageName(), flags);
        if (archive == null
                || !context.getPackageName().equals(archive.packageName)) {
            apk.delete();
            throw new Exception("APK принадлежит другому приложению.");
        }
        String releaseVersion = expectedVersion.replaceFirst("^[vV]", "");
        if (archive.versionName == null
                || (!releaseVersion.equals(archive.versionName)
                && !releaseVersion.startsWith(archive.versionName + "-")
                && !archive.versionName.startsWith(releaseVersion + "-"))) {
            apk.delete();
            throw new Exception("Версия внутри APK не совпадает с релизом.");
        }
        Set<String> archiveSigners = signerDigests(archive);
        Set<String> installedSigners = signerDigests(installed);
        if (archiveSigners.isEmpty() || installedSigners.isEmpty()
                || !archiveSigners.equals(installedSigners)) {
            apk.delete();
            throw new Exception("Сертификат APK не совпадает с установленным приложением.");
        }
    }

    private static Set<String> signerDigests(PackageInfo info)
            throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null) return new HashSet<>();
            signatures = info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        Set<String> digests = new HashSet<>();
        if (signatures == null) return digests;
        for (Signature signature : signatures) {
            digests.add(toHex(MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())));
        }
        return digests;
    }

    static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private ApkUpdateInstaller() {
    }
}
