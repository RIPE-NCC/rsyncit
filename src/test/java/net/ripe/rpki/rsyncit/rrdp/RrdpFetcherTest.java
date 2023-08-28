package net.ripe.rpki.rsyncit.rrdp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.ripe.rpki.TestDefaults;
import net.ripe.rpki.rsyncit.util.Sha256;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RrdpFetcherTest {

    @Test
    public void testNormalFlow() throws NotificationStructureException, XPathExpressionException, IOException, ParserConfigurationException, SAXException {
        final String snapshotXml = """
             <snapshot xmlns="http://www.ripe.net/rpki/rrdp" version="1" session_id="1c33ba5d-4e16-448d-9a22-b12599ef1cba" serial="29861">
            <publish uri="rsync://rsync.paas.rpki.ripe.net/repository/de7d55f7-ee60-4005-bad7-b42818cf50e8/3/326131323a646434373a333830303a3a2f34302d3430203d3e20313939353138.roa">
             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 </publish>
             </snapshot>
             """;

        final String notificationXml = String.format("""
            <notification xmlns="http://www.ripe.net/rpki/rrdp" version="1" session_id="1c33ba5d-4e16-448d-9a22-b12599ef1cba" serial="29861">
            <snapshot uri="https://rrdp.paas.rpki.ripe.net/1c33ba5d-4e16-448d-9a22-b12599ef1cba/29861/5d1d7670842dd277/snapshot.xml" hash="%s"/>
            <delta serial="29861" uri="https://rrdp.paas.rpki.ripe.net/1c33ba5d-4e16-448d-9a22-b12599ef1cba/29861/28f75f78dba58432/delta.xml" hash="770c21936e8129499d4f08698b0f08eadf3610a6624004a179e216d568ac04f5"/>
            <delta serial="29860" uri="https://rrdp.paas.rpki.ripe.net/1c33ba5d-4e16-448d-9a22-b12599ef1cba/29860/a04bbbe119bd2a54/delta.xml" hash="287e6323cf0507b9b6d12958894ba16c3991107f8e500e9058e09fd5f92fa47d"/>
            </notification>
            """, Sha256.asString(snapshotXml));

        var result = tryFetch(notificationXml, snapshotXml);
        assertThat(result).isInstanceOf(RrdpFetcher.SuccessfulFetch.class);
        assertThat(((RrdpFetcher.SuccessfulFetch) result).objects().size()).isEqualTo(1);
    }

    @Test
    public void testEmptyNotificationXml() {
        assertThatThrownBy(() -> tryFetch("", null))
                .isInstanceOf(NotificationStructureException.class)
                .hasMessage("Empty notification file.");
    }

    @Test
    public void testBrokenNotificationXml() {
        assertThrows(SAXParseException.class, () -> tryFetch("<notification xmlns=\"http", null));
    }

    @Test
    public void testNotificationXmlWithoutSnapshotTag() {
        final String notificationXml = """
            <notification xmlns="http://www.ripe.net/rpki/rrdp" version="1" session_id="1c33ba5d-4e16-448d-9a22-b12599ef1cba" serial="29861">            
            </notification>""";
        assertThatThrownBy(() -> tryFetch(notificationXml, null))
                .isInstanceOf(NotificationStructureException.class)
                .hasMessage("No snapshot tag in the notification file.");
    }

    @Test
    public void testNotificationXmlTwoSnapshotTags() {
        final String notificationXml = """
            <notification xmlns="http://www.ripe.net/rpki/rrdp" version="1" session_id="1c33ba5d-4e16-448d-9a22-b12599ef1cba" serial="29861">       
            <snapshot uri="https://host/snapshot1.xml" hash="b6dc8a81fea493c5c7a4314a3a9c5996c96cf94417983c8a92462aaf13d6cac8"/>
            <snapshot uri="https://host/snapshot2.xml" hash="b6dc8a81fea493c5c7a4314a3a9c5996c735234417983c8a92462aaf13d6cac8"/>     
            </notification>""";
        assertThatThrownBy(() -> tryFetch(notificationXml, null))
                .isInstanceOf(NotificationStructureException.class)
                .hasMessage("More than one snapshot tag in the notification file.");
    }

    @Test
    public void testSnapshotWrongHash() throws NotificationStructureException, XPathExpressionException, IOException, ParserConfigurationException, SAXException {
        final String snapshotXml = """
             <snapshot xmlns="http://www.ripe.net/rpki/rrdp" version="1" session_id="1c33ba5d-4e16-448d-9a22-b12599ef1cba" serial="29861">
            <publish uri="rsync://rsync.paas.rpki.ripe.net/repository/de7d55f7-ee60-4005-bad7-b42818cf50e8/3/326131323a646434373a333830303a3a2f34302d3430203d3e20313939353138.roa">
             AAAAABBBBCCCBSBSBDBDBBSBABABABABABABALIhoshdvashfviuhvkjsdfviuhdsfvkbdsflviubs
             </publish>          
             </snapshot>
             """;

        final String notificationXml = """
            <notification xmlns="http://www.ripe.net/rpki/rrdp" version="1" session_id="1c33ba5d-4e16-448d-9a22-b12599ef1cba" serial="29861">
            <snapshot uri="https://rrdp.paas.rpki.ripe.net/1c33ba5d-4e16-448d-9a22-b12599ef1cba/29861/5d1d7670842dd277/snapshot.xml" hash="770c21936e8129499d4f08698b0f08eadf3610a6624004a179e216d568ac04f5"/>            
            </notification>""";

        assertThatThrownBy(() -> tryFetch(notificationXml, snapshotXml))
                .isInstanceOf(SnapshotStructureException.class)
                .hasMessage(
                "Structure of snapshot at https://rrdp.paas.rpki.ripe.net/1c33ba5d-4e16-448d-9a22-b12599ef1cba/29861/5d1d7670842dd277/snapshot.xml " +
                "did not match expected structure: with len(content) = 400 had " +
                "sha256(content) = 25ffe0eb76860c269e6abdd16ec4eb991008e66de32173b6d3411ab3f6dcf058, " +
                "expected 770c21936e8129499d4f08698b0f08eadf3610a6624004a179e216d568ac04f5");
    }

    @Test
    public void testEmptySnapshot()  {
        final String notificationXml = """
            <notification xmlns="http://www.ripe.net/rpki/rrdp" version="1" session_id="1c33ba5d-4e16-448d-9a22-b12599ef1cba" serial="29861">
            <snapshot uri="https:/host/snapshot.xml" hash="770c21936e8129499d4f08698b0f08eadf3610a6624004a179e216d568ac04f5"/>            
            </notification>""";

        assertThatThrownBy(() -> tryFetch(notificationXml, ""))
                .isInstanceOf(SnapshotStructureException.class)
                .hasMessage("Structure of snapshot at https:/host/snapshot.xml did not match expected structure: Empty snapshot");
    }

    @Test
    public void testBrokenSnapshot()  {
        final String snapshotXml = """
             <snapshot xmlns="http://www.ripe.net/rpki/rrdp" version="1" session_id="1c33ba5d-4e16-448d-9a22-b12599ef1cba" serial="29861">
            <publish uri="     
             </snapt>
             """;

        final String notificationXml = String.format("""
            <notification xmlns="http://www.ripe.net/rpki/rrdp" version="1" session_id="1c33ba5d-4e16-448d-9a22-b12599ef1cba" serial="29861">
            <snapshot uri="https://rrdp.paas.rpki.ripe.net/1c33ba5d-4e16-448d-9a22-b12599ef1cba/29861/5d1d7670842dd277/snapshot.xml" hash="%s"/>            
            </notification>
            """, Sha256.asString(snapshotXml));

        assertThrows(SAXParseException.class, () -> tryFetch(notificationXml, snapshotXml));
    }

    private RrdpFetcher.FetchResult tryFetch(String notificationXml, String snapshotXml) throws NotificationStructureException, XPathExpressionException, IOException, ParserConfigurationException, SAXException {
        var fetcher = new RrdpFetcher(TestDefaults.defaultConfig(), TestDefaults.defaultWebClient(), new State(), new RRDPFetcherMetrics(new SimpleMeterRegistry()));
        return fetcher.processNotificationXml(notificationXml.getBytes(StandardCharsets.UTF_8),
            url -> new RrdpFetcher.Downloaded(snapshotXml.getBytes(StandardCharsets.UTF_8), Optional.of(Instant.now())));
    }
}