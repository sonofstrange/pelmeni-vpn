package com.example.sshtunnel;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeveloperBrandingSecurityTest {
    @Test
    public void receiverRequiresSignaturePermission() throws IOException {
        String manifest = readProjectFile("app/src/main/AndroidManifest.xml");

        assertTrue(manifest.contains(
                "android:name=\"com.example.sshtunnel.permission.DEVELOPER_BRANDING\""));
        assertTrue(manifest.contains("android:protectionLevel=\"signature\""));
        assertTrue(manifest.contains(
                "android:permission=\"com.example.sshtunnel.permission.DEVELOPER_BRANDING\""));
    }

    @Test
    public void brandingContainsNoLegacyCredentialGate() throws IOException {
        String source = readProjectFile(
                "app/src/main/java/com/example/sshtunnel/Branding.java");

        assertFalse(source.contains("SECRET_PASSWORD"));
        assertFalse(source.contains("SECRET_HOST"));
        assertFalse(source.contains("isSecretInput"));
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
