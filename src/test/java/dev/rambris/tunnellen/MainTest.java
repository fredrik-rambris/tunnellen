package dev.rambris.tunnellen;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MainTest {

    private static Configuration configWithSuffix(Optional<String> socksPodSuffix) {
        return new Configuration(List.of(), List.of(), Duration.ofMinutes(1), Duration.ofMinutes(1), 3000, false, socksPodSuffix);
    }

    @Test
    void prefersConfiguredSocksPodSuffixOverSystemProperty() {
        var previous = System.getProperty("user.name");
        try {
            System.setProperty("user.name", "boost");
            assertEquals("fredrik", Main.resolveSocksPodSuffix(configWithSuffix(Optional.of("fredrik"))));
        } finally {
            restore(previous);
        }
    }

    @Test
    void fallsBackToUserNameSystemPropertyWhenConfigUnset() {
        var previous = System.getProperty("user.name");
        try {
            System.setProperty("user.name", "boost");
            assertEquals("boost", Main.resolveSocksPodSuffix(configWithSuffix(Optional.empty())));
        } finally {
            restore(previous);
        }
    }

    @Test
    void throwsWhenNeitherConfigNorSystemPropertyIsSet() {
        var previous = System.getProperty("user.name");
        try {
            System.clearProperty("user.name");
            assertThrows(IllegalStateException.class, () -> Main.resolveSocksPodSuffix(configWithSuffix(Optional.empty())));
        } finally {
            restore(previous);
        }
    }

    private static void restore(String previous) {
        if (previous != null) {
            System.setProperty("user.name", previous);
        } else {
            System.clearProperty("user.name");
        }
    }
}
