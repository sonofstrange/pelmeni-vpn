package com.example.sshtunnel;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ServerFailoverTest {
    private final ServerProfiles.Profile first = profile("a");
    private final ServerProfiles.Profile second = profile("b");
    private final ServerProfiles.Profile third = profile("c");
    private final List<ServerProfiles.Profile> profiles =
            List.of(first, second, third);

    @Test public void selectsNextEligibleProfileInOrder() {
        ServerProfiles.Profile result = ServerFailover.next(
                profiles, first.id, Set.of(),
                profile -> !profile.id.equals(second.id));

        assertEquals(third.id, result.id);
    }

    @Test public void wrapsAfterLastProfile() {
        ServerProfiles.Profile result = ServerFailover.next(
                profiles, third.id, Set.of(), profile -> true);

        assertEquals(first.id, result.id);
    }

    @Test public void skipsProfilesAlreadyTried() {
        Set<String> excluded = new HashSet<>();
        excluded.add(second.id);
        ServerProfiles.Profile result = ServerFailover.next(
                profiles, first.id, excluded, profile -> true);

        assertEquals(third.id, result.id);
    }

    @Test public void returnsNullWithoutAnotherEligibleProfile() {
        assertNull(ServerFailover.next(
                profiles, first.id, Set.of(),
                profile -> profile.id.equals(first.id)));
        assertNull(ServerFailover.next(
                List.of(first), first.id, Set.of(), profile -> true));
    }

    private static ServerProfiles.Profile profile(String id) {
        return new ServerProfiles.Profile(
                id, id, id + ".example", "22", "root", "1080",
                NetworkTuning.DEFAULT_WINDOW_KIB,
                NetworkTuning.DEFAULT_PACKET_KIB,
                NetworkTuning.DEFAULT_MTU);
    }
}
