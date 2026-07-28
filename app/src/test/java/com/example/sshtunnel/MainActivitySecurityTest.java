package com.example.sshtunnel;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MainActivitySecurityTest {
    @Test public void restoredInterfaceKeepsSecurityGates() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/example/sshtunnel/MainActivity.java");

        assertTrue(source.contains("ConfigSecurity.verifySafeExport"));
        assertTrue(source.contains("ensureSshHostKey"));
        assertTrue(source.contains("ServerAccessCode.importTls"));
        assertFalse(source.contains(".put(\"password\""));
        assertFalse(source.contains("Branding.isSecretInput"));
        assertFalse(source.contains("Branding.toggleSecret"));
    }

    @Test public void tlsMaintenanceDoesNotRequireAnEnabledTunnelMode()
            throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/example/sshtunnel/MainActivity.java");

        assertTrue(source.contains("saveSettings(false)"));
        assertTrue(source.contains(
                "private boolean saveSettings(boolean requireConnectionMode)"));
        assertTrue(source.contains("ensureSshHostKey(() -> new AlertDialog.Builder"));
    }

    @Test public void terminalConnectionFailuresUnlockTheInterface()
            throws IOException {
        String activity = readProjectFile(
                "app/src/main/java/com/example/sshtunnel/MainActivity.java");
        String service = readProjectFile(
                "app/src/main/java/com/example/sshtunnel/TunnelService.java");

        assertTrue(service.contains("shouldRetryAfterFailure"));
        assertTrue(service.contains("getBoolean(\"auto_reconnect\", true)"));
        assertTrue(service.contains("putExtra(EXTRA_RUNNING, wanted)"));
        assertTrue(activity.contains("intent.hasExtra(TunnelService.EXTRA_RUNNING)"));
    }

    @Test public void releaseSigningRequiresExternalCredentials()
            throws IOException {
        String build = readProjectFile("app/build.gradle");

        assertFalse(build.contains("signingConfig signingConfigs.debug"));
        assertTrue(build.contains("PELMENI_RELEASE_KEYSTORE"));
        assertTrue(build.contains("Release signing is not configured"));
    }

    @Test public void serverEditorCanReconfigureAnActiveConnection()
            throws IOException {
        String activity = readProjectFile(
                "app/src/main/java/com/example/sshtunnel/MainActivity.java");

        assertFalse(activity.contains("edit.setEnabled(!running)"));
        assertTrue(activity.contains("boolean reconnect = running;"));
        assertTrue(activity.contains(
                "toggle.postDelayed(this::startTunnel, 900)"));
    }

    @Test public void tunnelResourcesAndFinalTotalsAreBounded()
            throws IOException {
        String proxy = readProjectFile(
                "app/src/main/java/com/example/sshtunnel/SocksProxy.java");
        String service = readProjectFile(
                "app/src/main/java/com/example/sshtunnel/TunnelService.java");

        assertTrue(proxy.contains("MAX_CLIENTS = 24"));
        assertFalse(proxy.contains("Executors.newCachedThreadPool()"));
        assertTrue(service.contains("persistFinalTotals()"));
        assertTrue(service.contains("if (finalTotalsPersisted) return;"));
    }

    private static String readProjectFile(String relative) throws IOException {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path fromRoot = directory.resolve(relative);
        Path path = Files.exists(fromRoot)
                ? fromRoot
                : directory.resolve("..").normalize().resolve(relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
