package net.ripe.rpki.rsyncit.rrdp;

import com.google.common.hash.BloomFilter;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.commons.crypto.util.SignedObjectUtil;
import net.ripe.rpki.rsyncit.config.Config;
import net.ripe.rpki.rsyncit.util.Sha256;
import net.ripe.rpki.rsyncit.util.Time;
import net.ripe.rpki.rsyncit.util.XML;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import reactor.netty.http.client.HttpClient;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Getter
public class RrdpFetcher {

    private final Config config;
    private final HttpClient httpClient;
    private final State state;
    private final RRDPFetcherMetrics metrics;

    /**
     * Bloom filter with 0.5% false positives (and no false negatives) at 100K objects to reduce logging.
     *
     * Expected number of rejected objects is ~100k worst case, which would leave ~50 false positives that are logged
     * for each iteration.
     *
     * On a realistic number of rejected objects (~9k) this would leave < 10 false positives per update.
     */
    private final BloomFilter<String> loggedObjects = BloomFilter.create((from, into) -> into.putString(from, Charset.defaultCharset()), 100_000, 0.05);


    public RrdpFetcher(Config config, HttpClient httpClient, State state, RRDPFetcherMetrics metrics) {
        this.config = config;
        this.httpClient = httpClient;
        this.state = state;
        this.metrics = metrics;
        log.info("RrdpFetcher for {}", config.rrdpUrl());
    }

    private Downloaded download(String uri, Duration timeout) {
        var lastModified = new AtomicReference<Optional<Instant>>(Optional.empty());
        var body = httpClient
            .responseTimeout(timeout)
            .get()
            .uri(uri)
            .responseSingle((response, byteBufMono) -> {
                String lastModifiedHeader = response.responseHeaders().get("Last-Modified");
                if (lastModifiedHeader != null) {
                    try {
                        var parsed = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                            .parse(lastModifiedHeader, Instant::from);
                        lastModified.set(Optional.of(parsed));
                    } catch (Exception e) {
                        // Ignore parsing errors
                    }
                }
                return byteBufMono.asByteArray();
            })
            .block(timeout);
        return new Downloaded(body, lastModified.get());
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
            final byte[] notificationBytes = download(config.rrdpUrl(), config.requestTimeout()).content();
            return processNotificationXml(notificationBytes, this::loadSnapshot);
        } catch (NotificationStructureException |
                 SnapshotStructureException |
                 ParserConfigurationException |
                 XPathExpressionException |
                 SAXException |
                 IOException |
                 NumberFormatException e) {
            return new FailedFetch(e);
        } catch (io.netty.handler.timeout.ReadTimeoutException |
                 io.netty.handler.timeout.WriteTimeoutException e) {
            log.info("Timeout while loading RRDP repo: url={}", config.rrdpUrl());
            return new Timeout();
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("Timeout")) {
                log.info("Timeout while loading RRDP repo: url={}", config.rrdpUrl());
                return new Timeout();
            }
            if (e.getCause() instanceof TimeoutException) {
                log.info("Timeout while loading RRDP repo: url={}", config.rrdpUrl());
                return new Timeout();
            }
            return new FailedFetch(e);
        } catch (Exception e) {
            // Handle other runtime exceptions including PrematureCloseException
            log.error("Error fetching RRDP repo: url={}", config.rrdpUrl(), e);
            return new FailedFetch(e);
        }
    }

    FetchResult processNotificationXml(byte[] notificationBytes, Function<String, Downloaded> getSnapshot) throws NotificationStructureException, SAXException,
        IOException, XPathExpressionException, SnapshotStructureException, ParserConfigurationException {
        if (notificationBytes == null || notificationBytes.length == 0) {
            throw new NotificationStructureException("Empty notification file.");
        }
        final DocumentBuilder documentBuilder = XML.newDocumentBuilder();
        final Document notificationXmlDoc = documentBuilder.parse(new ByteArrayInputStream(notificationBytes));

        var notification = validateNotificationStructure(notificationXmlDoc);
        if (state.getRrdpState() != null &&
            notification.sessionId().equals(state.getRrdpState().getSessionId()) &&
            Objects.equals(notification.serial(), state.getRrdpState().getSerial())) {
            log.info("Not updating: session_id {} and serial {} are the same as previous run.", notification.sessionId(), notification.serial());
            return new NoUpdates(notification.sessionId(), notification.serial());
        }
        var actualSnapshotUrl = config.substituteHost().apply(notification.snapshotUrl());
        var downloaded = Time.timed(() -> getSnapshot.apply(actualSnapshotUrl));
        log.info("Downloaded snapshot in {}ms", downloaded.getTime());

        var snapshotContent = downloaded.getResult().content();
        if (snapshotContent == null || snapshotContent.length == 0) {
            throw new SnapshotStructureException(notification.snapshotUrl(), "Empty snapshot");
        }
        final String realSnapshotHash = Sha256.asString(snapshotContent);
        if (!realSnapshotHash.equalsIgnoreCase(notification.expectedSnapshotHash())) {
            throw new SnapshotStructureException(notification.snapshotUrl(),
                "with len(content) = %d had sha256(content) = %s, expected %s".formatted(snapshotContent.length, realSnapshotHash, notification.expectedSnapshotHash()));
        }
        var document = documentBuilder.parse(new ByteArrayInputStream(snapshotContent)).getDocumentElement();

        validateSnapshotStructure(notification.serial(), notification.snapshotUrl(), document);
        var processPublishElementResult = processPublishElements(document, downloaded.getResult().lastModified());

        return new SuccessfulFetch(processPublishElementResult.objects, notification.sessionId(), notification.serial());
    }

    private static NotificationXml validateNotificationStructure(Document notification) throws NotificationStructureException {
        final int serial = Integer.parseInt(notification.getDocumentElement().getAttribute("serial"));
        final String sessionId = notification.getDocumentElement().getAttribute("session_id");

        final NodeList snapshotTags = notification.getDocumentElement().getElementsByTagName("snapshot");
        if (snapshotTags.getLength() == 0) {
            throw new NotificationStructureException("No snapshot tag in the notification file.");
        }
        if (snapshotTags.getLength() > 1) {
            throw new NotificationStructureException("More than one snapshot tag in the notification file.");
        }
        final Node snapshotTag = snapshotTags.item(0);
        final String snapshotUrl = snapshotTag.getAttributes().getNamedItem("uri").getNodeValue();
        final String expectedSnapshotHash = snapshotTag.getAttributes().getNamedItem("hash").getNodeValue();
        return new NotificationXml(sessionId, serial, snapshotUrl, expectedSnapshotHash);
    }

    private Downloaded loadSnapshot(String snapshotUrl) {
        log.info("Loading RRDP snapshot from {}", snapshotUrl);
        return download(snapshotUrl, config.requestTimeout());
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

    private ProcessPublishElementResult processPublishElements(Element doc, Optional<Instant> lastModified) throws XPathExpressionException {
        var queryPublish = XPathFactory.newDefaultInstance().newXPath().compile("/snapshot/publish");
        final NodeList publishedObjects = (NodeList) queryPublish.evaluate(doc, XPathConstants.NODESET);

        // Generate timestamp that will be tracked per object and used as FS modification timestamp
        // if no value can be parsed from the object itself.
        //
        // Use last-modified header from the snapshot if available, otherwise truncate current time
        // to the closest hour -- it is unlikely that different instances will have clocks off by a lot,
        // so rounding down to an hour should generate the same timestamps _most of the time_.
        //
        if (lastModified.isEmpty()) {
            log.info("No last-modified header in response: Using current hour as timestamp");
        }
        var defaultTimestamp = lastModified.orElse(Instant.now().truncatedTo(ChronoUnit.HOURS));

        // This timestamp is only needed for marking objects in the timestamp cache.
        var now = Instant.now();

        var collisionCount = new AtomicInteger();
        var decoder = Base64.getDecoder();

        var objectItems = IntStream
            .range(0, publishedObjects.getLength())
            .mapToObj(publishedObjects::item)
            .toList();

        var objects = metrics.objectConstructionTimer.record(() -> objectItems
            .parallelStream()
            .map(item -> {
                var objectUri = item.getAttributes().getNamedItem("uri").getNodeValue();
                var content = item.getTextContent();
                try {
                    // Surrounding whitespace is allowed by xsd:base64Binary. Trim that
                    // off before decoding. See also:
                    // https://www.w3.org/TR/2004/PER-xmlschema-2-20040318/datatypes.html#base64Binary
                    var decoded = decoder.decode(content.trim());

                    //
                    // Cache the timestamp per hash do avoid re-parsing every object in the snapshot every time.
                    //
                    // We can not use hashes in sub-second precision because rsync may start syncing those by default.
                    // @see https://github.com/WayneD/rsync/commit/839dbff2aaf0277471e1986a3cd0f869e0bdda24
                    final Instant modificationTime = state.cacheTimestamps(Sha256.asString(decoded), now,
                        () -> getTimestampForObject(objectUri, decoded, defaultTimestamp));

                    return new RpkiObject(URI.create(objectUri), decoded, modificationTime);
                } catch (RuntimeException e) {
                    metrics.badObject();
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
                    var collect = item.getValue().
                        stream().
                        map(coll -> Sha256.asString(coll.bytes())).
                        collect(Collectors.joining(", "));
                    log.warn("Multiple objects for {}, keeping first element: {}", item.getKey(), collect);
                    collisionCount.addAndGet(item.getValue().size() - 1);
                    return item.getValue().get(0);
                }
                return item.getValue().get(0);
            })
            .collect(Collectors.toList()));

        log.info("Parsed {} objects", objects.size());
        return new ProcessPublishElementResult(objects, collisionCount.get());
    }

    /**
     * Try to get some creation timestamp from the object itself. If it's impossible to parse
     * the object, use the default (based on the last-modified header of the snapshot).
     *
     * @param objectUri uri of object
     * @param decoded content of object
     * @param lastModified modification time to use as fallback
     * @return
     */
    private Instant getTimestampForObject(final String objectUri, final byte[] decoded, Instant lastModified) {
        try {
            return Instant.ofEpochMilli(SignedObjectUtil.getFileCreationTime(URI.create(objectUri), decoded).getMillis());
        } catch (SignedObjectUtil.NoTimeParsedException e) {
            metrics.badObject();
            var contentHash = Sha256.asString(decoded);
            if (!loggedObjects.mightContain(contentHash)) {
                log.error("Could not parse the object url = {}, body = {} :", objectUri, Base64.getEncoder().encodeToString(decoded), e);
                loggedObjects.put(contentHash);
            }
            return lastModified;
        }
    }

    record NotificationXml(String sessionId, Integer serial, String snapshotUrl, String expectedSnapshotHash) {
    }

    record ProcessPublishElementResult(List<RpkiObject> objects, int collisionCount) {
    }

    public sealed interface FetchResult permits SuccessfulFetch, NoUpdates, FailedFetch, Timeout {
    }

    public record SuccessfulFetch(List<RpkiObject> objects, String sessionId, Integer serial) implements FetchResult {
    }

    public record NoUpdates(String sessionId, Integer serial) implements FetchResult {
    }

    public record FailedFetch(Exception exception) implements FetchResult {
    }

    public record Timeout() implements FetchResult {
    }

    public record Downloaded(byte[] content, Optional<Instant> lastModified) {
    }
}

