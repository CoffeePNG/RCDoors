package nl.pim16aap2.animatedarchitecture.spigot.core.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class ConfigSpigotTest
{
    @Test
    void normalizesCustomAliasesWithRcDoorsAndLegacyCompatibilityNamesFirst()
    {
        final List<String> normalized = ConfigSpigot.normalizeCommandAliases(List.of("custom", "aa"));

        Assertions.assertEquals(
            List.of("rcdoors", "RCDoors", "animatedarchitecture", "AnimatedArchitecture", "aa", "custom"),
            normalized);
    }
}
