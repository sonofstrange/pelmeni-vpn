package com.example.sshtunnel;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UpdateCheckerTest {
    @Test public void comparesStableVersions() {
        assertTrue(UpdateChecker.isNewer("v1.28.0", "1.27.9"));
        assertFalse(UpdateChecker.isNewer("1.27.9", "1.28.0"));
        assertFalse(UpdateChecker.isNewer("1.28.0", "1.28.0"));
    }

    @Test public void stableReleaseWinsOverPrerelease() {
        assertTrue(UpdateChecker.isNewer("1.28.0", "1.28.0-beta.3"));
        assertFalse(UpdateChecker.isNewer("1.28.0-beta.3", "1.28.0"));
    }

    @Test public void comparesPrereleaseNumbers() {
        assertTrue(UpdateChecker.isNewer("1.28.0-beta.4", "1.28.0-beta.3"));
        assertFalse(UpdateChecker.isNewer("1.28.0-beta.2", "1.28.0-beta.3"));
    }

    @Test public void readsGitHubAssetDigest() throws Exception {
        JSONObject asset = new JSONObject()
                .put("name", "pelmeni.apk")
                .put("browser_download_url",
                        "https://github.com/example/pelmeni.apk")
                .put("digest", "sha256:"
                        + "ab".repeat(32))
                .put("size", 1234);
        JSONObject release = new JSONObject()
                .put("tag_name", "v9.0")
                .put("assets", new JSONArray().put(asset));

        UpdateChecker.Result result = UpdateChecker.resultFrom(release);

        assertTrue(result.canInstallSecurely());
        assertEquals("ab".repeat(32), result.sha256);
        assertEquals(1234, result.size);
    }

    @Test public void rejectsMissingOrMalformedDigest() {
        UpdateChecker.Result missing = new UpdateChecker.Result(
                "9.0", "", "https://github.com/release",
                "https://github.com/app.apk", "", 100, false);
        UpdateChecker.Result malformed = new UpdateChecker.Result(
                "9.0", "", "https://github.com/release",
                "https://github.com/app.apk", "abc", 100, false);

        assertFalse(missing.canInstallSecurely());
        assertFalse(malformed.canInstallSecurely());
    }

    @Test public void formatsSha256AsLowercaseHex() {
        assertEquals("000fff80",
                ApkUpdateInstaller.toHex(new byte[] {0, 15, -1, -128}));
    }

    @Test public void onlyAllowsHttpsGitHubDownloadHosts() {
        assertTrue(ApkUpdateInstaller.isTrustedDownloadUrl(
                "https://github.com/project/app.apk"));
        assertTrue(ApkUpdateInstaller.isTrustedDownloadUrl(
                "https://release-assets.githubusercontent.com/app.apk"));
        assertFalse(ApkUpdateInstaller.isTrustedDownloadUrl(
                "http://github.com/project/app.apk"));
        assertFalse(ApkUpdateInstaller.isTrustedDownloadUrl(
                "https://github.com.evil.example/app.apk"));
        assertFalse(ApkUpdateInstaller.isTrustedDownloadUrl(
                "https://evilgithub.com/app.apk"));
    }
}
