package net.ripe.rpki.rsyncit.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppConfigTest {
    @Test
    public void tesNotUrlSubstitution() {
        assertThat(AppConfig.substitutor(null)
            .apply("https://host1.net/notification.xml"))
            .isEqualTo("https://host1.net/notification.xml");
    }

    @Test
    public void testBrokenUrlSubstitution() {
        assertThrows(IllegalArgumentException.class, () -> AppConfig.substitutor(""));
        assertThrows(IllegalArgumentException.class, () -> AppConfig.substitutor("random stuff"));
        assertThrows(IllegalArgumentException.class, () -> AppConfig.substitutor("random stuff/sdcsdcc/ss"));
    }

    @Test
    public void testUrlSubstitution() {
        assertThat(AppConfig.substitutor("host1.bla/host2.bla")
            .apply("https://host1.bla.net/notification.xml"))
            .isEqualTo("https://host2.bla.net/notification.xml");
    }
}