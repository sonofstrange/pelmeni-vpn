package com.example.sshtunnel;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PublicServerSecurityTest {
    @Test public void publicRegistrarCannotForwardTraffic() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/example/sshtunnel/PublicServerManager.java");
        String registry = readProjectFile(
                "app/src/main/java/com/example/sshtunnel/PublicServerRegistry.java");

        assertTrue(source.contains("AllowTcpForwarding no"));
        assertTrue(source.contains("PermitTTY no"));
        assertTrue(source.contains("ForceCommand sudo -n "
                + "/usr/local/sbin/pelmeni-public-claim"));
        assertFalse(registry.contains("ServerProfiles.password"));
        assertFalse(registry.contains("getSecret()"));
    }

    @Test public void everyClaimCreatesASeparateLimitedAccount()
            throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/example/sshtunnel/PublicServerManager.java");

        assertTrue(source.contains("login='pel_pub_'"));
        assertTrue(source.contains("'daily_mb':int(cfg.get('daily_mb',0))"));
        assertTrue(source.contains("'monthly_mb':int(cfg.get('monthly_mb',0))"));
        assertTrue(source.contains("'speed_mbps':int(cfg.get('speed_mbps',0))"));
        assertTrue(source.contains("Повтори выдачу через минуту."));
    }

    @Test public void developerStatsReportActualProxyState()
            throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/example/sshtunnel/TunnelService.java");

        assertTrue(source.contains(
                ".putExtra(\"debug_tg_running\", telegramRunning)"));
        assertTrue(source.contains(
                ".putExtra(\"debug_vpn_running\", vpnRunning)"));
        assertTrue(source.contains("telegram != null && telegram.isRunning()"));
        assertTrue(source.contains("vpn != null && vpn.isRunning()"));
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
