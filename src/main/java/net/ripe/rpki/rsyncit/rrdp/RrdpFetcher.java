package net.ripe.rpki.rsyncit.rrdp;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.rsyncit.config.Config;
import net.ripe.rpki.rsyncit.util.Sha256;
import net.ripe.rpki.rsyncit.util.Time;
import net.ripe.rpki.rsyncit.util.XML;
import org.springframework.http.HttpRequest;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Getter
public class RrdpFetcher {

    private final Config config;
    private final WebClient httpClient;
    private final State state;

    public RrdpFetcher(Config config, WebClient httpClient, State state) {
        this.config = config;
        this.httpClient = httpClient;
        this.state = state;
        log.info("RrdpFetcher for {}", config.rrdpUrl());
    }

    private byte[] blockForHttpGetRequest(String uri, Duration timeout) {
        return httpClient.get().uri(uri).retrieve().bodyToMono(byte[].class).block(timeout);
    }

    /**
     * Load snapshot and validate hash
     */
    private byte[] loadSnapshot(String snapshotUrl, String desiredSnapshotHash) throws SnapshotStructureException {
        log.info("loading RRDP snapshot from {}", snapshotUrl);

        final byte[] snapshotBytes = blockForHttpGetRequest(snapshotUrl, config.requestTimeout());

        final String realSnapshotHash = Sha256.asString(snapshotBytes);
        if (!realSnapshotHash.equalsIgnoreCase(desiredSnapshotHash)) {
            throw new SnapshotStructureException(snapshotUrl,
                "with len(content) = %d had sha256(content) = %s, expected %s".formatted(snapshotBytes.length, realSnapshotHash, desiredSnapshotHash));
        }

        return snapshotBytes;
    }

    public FetchResult fetchObjects() {
        try {
            return fetchObjectsEx();
        } catch (Exception e) {
            // it still may throw something unknown
            return new FailedFetch(e);
        }
    }

    public FetchResult fetchObjectsEx() {
        try {
            final DocumentBuilder documentBuilder = XML.newDocumentBuilder();

            final byte[] notificationBytes = blockForHttpGetRequest(config.rrdpUrl(), config.requestTimeout());
            final Document notificationXmlDoc = documentBuilder.parse(new ByteArrayInputStream(notificationBytes));

            final int serial = Integer.parseInt(notificationXmlDoc.getDocumentElement().getAttribute("serial"));
            final String sessionId = notificationXmlDoc.getDocumentElement().getAttribute("session_id");

            final Node snapshotTag = notificationXmlDoc.getDocumentElement().getElementsByTagName("snapshot").item(0);
            final String snapshotUrl = snapshotTag.getAttributes().getNamedItem("uri").getNodeValue();
            final String desiredSnapshotHash = snapshotTag.getAttributes().getNamedItem("hash").getNodeValue();

            if (state.getRrdpState() != null &&
                sessionId.equals(state.getRrdpState().getSessionId()) &&
                serial == state.getRrdpState().getSerial()) {
                log.info("Not updating: session_id {} and serial {} are the same as previous run.", sessionId, serial);
                return new NoUpdates(sessionId, serial);
            }

            long begin = System.currentTimeMillis();
            final byte[] snapshotContent = loadSnapshot(snapshotUrl, desiredSnapshotHash);
            long end = System.currentTimeMillis();
            log.info("Downloaded snapshot in {}ms", (end - begin));

            final Document snapshotXmlDoc = documentBuilder.parse(new ByteArrayInputStream(snapshotContent));
            var doc = snapshotXmlDoc.getDocumentElement();

            validateSnapshotStructure(serial, snapshotUrl, doc);

            var processPublishElementResult = processPublishElements(doc);

            return new SuccessfulFetch(processPublishElementResult.objects, sessionId, serial);
        } catch (SnapshotStructureException | ParserConfigurationException | XPathExpressionException | SAXException |
                 IOException | NumberFormatException e) {
            return new FailedFetch(e);
        } catch (IllegalStateException e) {
            if (e.getMessage().contains("Timeout")) {
                log.info("Timeout while loading RRDP repo: url={}", config.rrdpUrl());
                return new Timeout();
            }
            return new FailedFetch(e);
        } catch (WebClientResponseException e) {
            var maybeRequest = Optional.ofNullable(e.getRequest());
            // Can be either a HTTP non-2xx or a timeout
            log.error("Web client error for {} {}: Can be HTTP non-200 or a timeout. For 2xx we assume it's a timeout.", maybeRequest.map(HttpRequest::getMethod), maybeRequest.map(HttpRequest::getURI), e);
            if (e.getStatusCode().is2xxSuccessful()) {
                // Assume it's a timeout
                return new Timeout();
            }
            return new FailedFetch(e);
        } catch (WebClientRequestException e) {
            // TODO: Exception handling could be a lot nicer. However we are mixing reactive and synchronous code,
            //  and a nice solution probably requires major changes.
            log.error("Web client request exception, only known cause is a timeout.", e);
            return new Timeout();
        }
    }

    private static void validateSnapshotStructure(int notificationSerial, String snapshotUrl, Element doc) throws XPathExpressionException, SnapshotStructureException {
        // Check attributes of root snapshot element (mostly: that serial matches)
        var querySnapshot = XPathFactory.newDefaultInstance().newXPath().compile("/snapshot");
        var snapshotNodes = (NodeList) querySnapshot.evaluate(doc, XPathConstants.NODESET);
        // It is invariant that there is only one root element in an XML file, but it could still contain a different
        // root tag => 0
        if (snapshotNodes.getLength() != 1) {
            throw new SnapshotStructureException(snapshotUrl, "No <snapshot>...</snapshot> root element found");
        } else {
            var item = snapshotNodes.item(0);
            int snapshotSerial = Integer.parseInt(item.getAttributes().getNamedItem("serial").getNodeValue());

            if (notificationSerial != snapshotSerial) {
                throw new SnapshotStructureException(snapshotUrl, "contained serial=%d, expected=%d".formatted(snapshotSerial, notificationSerial));
            }
        }
    }

    private ProcessPublishElementResult processPublishElements(Element doc) throws XPathExpressionException {
        var queryPublish = XPathFactory.newDefaultInstance().newXPath().compile("/snapshot/publish");
        final NodeList publishedObjects = (NodeList) queryPublish.evaluate(doc, XPathConstants.NODESET);

        var now = Instant.now();
        var collisionCount = new AtomicInteger();

        var decoder = Base64.getDecoder();

        var t = Time.timed(() -> IntStream
            .range(0, publishedObjects.getLength())
            .mapToObj(publishedObjects::item)
            .map(item -> {
                var objectUri = item.getAttributes().getNamedItem("uri").getNodeValue();
                var content = item.getTextContent();

                try {
                    // Surrounding whitespace is allowed by xsd:base64Binary. Trim that
                    // off before decoding. See also:
                    // https://www.w3.org/TR/2004/PER-xmlschema-2-20040318/datatypes.html#base64Binary
                    var decoded = decoder.decode(content.trim());
                    var hash = Sha256.asString(decoded);
                    final Instant createAt = state.getOrUpdateCreatedAt(hash, now);
                    return new RpkiObject(URI.create(objectUri), decoded, createAt);
                } catch (RuntimeException e) {
                    log.error("Cannot decode object data for URI {}\n{}", objectUri, content);
                    throw e;
                }
            })
            // group by url to detect duplicate urls: keeps the first element, will cause a diff between
            // the sources being monitored.
            .collect(Collectors.groupingBy(RpkiObject::url))
            // invariant: every group has at least 1 item
            .entrySet().stream()
            .map(item -> {
                if (item.getValue().size() > 1) {
                    var collect = item.getValue().stream().map(coll ->
                            Sha256.asString(coll.bytes())).
                        collect(Collectors.joining(", "));
                    log.warn("Multiple objects for {}, keeping first element: {}", item.getKey(), collect);
                    collisionCount.addAndGet(item.getValue().size() - 1);
                    return item.getValue().get(0);
                }
                return item.getValue().get(0);
            })
            .collect(Collectors.toList()));

        var objects = t.getResult();
        log.info("Constructed {} objects in {}ms", objects.size(), t.getTime());
        return new ProcessPublishElementResult(objects, collisionCount.get());
    }

    record ProcessPublishElementResult(List<RpkiObject> objects, int collisionCount) {}

    public sealed interface FetchResult permits SuccessfulFetch, NoUpdates, FailedFetch, Timeout {}

    public record SuccessfulFetch(List<RpkiObject> objects, String sessionId, Integer serial) implements FetchResult {}
    public record NoUpdates(String sessionId, Integer serial) implements FetchResult {}
    public record FailedFetch(Exception exception) implements FetchResult {}
    public record Timeout() implements FetchResult {}

}

