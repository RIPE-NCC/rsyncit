package net.ripe.rpki.rsyncit.rsync;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RsyncWriterTest {

    @Test
    public void testDateFormat() {
        Instant now = Instant.now();
        final String formattedNow = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.of("UTC")).format(now);
        assertNotNull(formattedNow);
    }

    @Test
    public void testRegexPublished() {
        assertTrue(RsyncWriter.PUBLICATION_DIRECTORY_PATTERN.matcher("published-2023-04-17T15:40:14.577267").matches());
        assertTrue(RsyncWriter.PUBLICATION_DIRECTORY_PATTERN.matcher("tmp-2023-04-17T15:40:14.577267").matches());
        assertTrue(RsyncWriter.PUBLICATION_DIRECTORY_PATTERN.matcher("tmp-2023-04-17T15:40:14.764528-98762876").matches());
        assertTrue(RsyncWriter.PUBLICATION_DIRECTORY_PATTERN.matcher("tmp-2023-04-18T15:18:14.543335-14322749955653697403").matches());
    }
}