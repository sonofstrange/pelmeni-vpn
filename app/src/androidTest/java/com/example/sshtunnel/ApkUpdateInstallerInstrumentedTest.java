package com.example.sshtunnel;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class ApkUpdateInstallerInstrumentedTest {
    @Test public void acceptsSignedApkAndRejectsWrongDigest()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File apk = copyInstalledApk(context);
        UpdateChecker.Result valid = update(
                ApkUpdateInstaller.sha256(apk), apk.length());
        ApkUpdateInstaller.verifyDownloaded(context, apk, valid);

        assertNotNull(apk);
        assertTrue(apk.isFile());
        assertTrue(apk.delete());

        File changed = copyInstalledApk(context);
        UpdateChecker.Result invalid = update(
                "00".repeat(32), changed.length());
        try {
            ApkUpdateInstaller.verifyDownloaded(context, changed, invalid);
            fail("A mismatched digest must reject the APK");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("SHA-256"));
        }
        assertFalse(changed.exists());
    }

    private static UpdateChecker.Result update(
            String digest, long size) {
        return new UpdateChecker.Result(
                BuildConfig.VERSION_NAME, "",
                "https://github.com/sonofstrange/pelmeni-vpn/releases",
                "https://github.com/sonofstrange/pelmeni-vpn/app.apk",
                digest, size, true);
    }

    private static File copyInstalledApk(Context context)
            throws Exception {
        File directory = new File(context.getCacheDir(), "updates");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File target = new File(directory, "instrumented-test.apk");
        try (FileInputStream input = new FileInputStream(
                context.getApplicationInfo().sourceDir);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) {
                output.write(buffer, 0, count);
            }
        }
        return target;
    }
}
