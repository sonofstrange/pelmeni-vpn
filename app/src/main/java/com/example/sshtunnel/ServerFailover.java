package com.example.sshtunnel;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

final class ServerFailover {
    static final int FAILURE_THRESHOLD = 3;

    static ServerProfiles.Profile next(
            List<ServerProfiles.Profile> profiles,
            String currentId,
            Set<String> excluded,
            Predicate<ServerProfiles.Profile> eligible) {
        if (profiles.size() < 2) return null;
        int currentIndex = 0;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(currentId)) {
                currentIndex = i;
                break;
            }
        }
        for (int offset = 1; offset < profiles.size(); offset++) {
            ServerProfiles.Profile candidate =
                    profiles.get((currentIndex + offset) % profiles.size());
            if (!excluded.contains(candidate.id) && eligible.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private ServerFailover() {
    }
}
