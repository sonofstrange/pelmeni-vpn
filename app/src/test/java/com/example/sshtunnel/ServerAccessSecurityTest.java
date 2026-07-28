package com.example.sshtunnel;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServerAccessSecurityTest {
    @Test public void authenticatedManagementUsesPinnedHostKey() throws IOException {
        String manager = readProjectFile(
                "app/src/main/java/com/example/sshtunnel/ServerAccessManager.java");

        assertTrue(manager.contains("SshHostKeys.newPinnedSession"));
        assertFalse(manager.contains("StrictHostKeyChecking"));
        assertFalse(manager.contains("new JSch().getSession"));
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
