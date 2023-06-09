package net.ripe.rpki.rsyncit.rrdp;

import com.google.common.annotations.VisibleForTesting;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.commons.crypto.cms.RpkiSignedObject;
import net.ripe.rpki.commons.crypto.cms.RpkiSignedObjectParser;
import net.ripe.rpki.commons.crypto.crl.X509Crl;
import net.ripe.rpki.commons.crypto.x509cert.X509ResourceCertificateParser;
import net.ripe.rpki.commons.util.RepositoryObjectType;
import net.ripe.rpki.commons.validation.ValidationResult;
import net.ripe.rpki.rsyncit.config.Config;
import net.ripe.rpki.rsyncit.util.Sha256;
import net.ripe.rpki.rsyncit.util.Time;
import net.ripe.rpki.rsyncit.util.XML;
import org.joda.time.DateTime;
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
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Getter
public class RrdpFetcher {

    private final Config config;
    private final WebClient httpClient;
    private final State state;
    private final RRDPFetcherMetrics metrics;

    public RrdpFetcher(Config config, WebClient httpClient, State state, RRDPFetcherMetrics metrics) {
        this.config = config;
        this.httpClient = httpClient;
        this.state = state;
        this.metrics = metrics;
        log.info("RrdpFetcher for {}", config.rrdpUrl());
    }

    private Downloaded download(String uri, Duration timeout) {
        var lastModified = new AtomicReference<Optional<Instant>>(Optional.empty());
        var body = httpClient.get().uri(uri).retrieve()
            .toEntity(byte[].class)
            .doOnSuccess(e -> {
                final long modified = e.getHeaders().getLastModified();
                if (modified != -1) {
                    lastModified.set(Optional.of(Instant.ofEpochMilli(modified)));
                }
            })
            .block(timeout)
            .getBody();
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
        } catch (IllegalStateException e) {
            if (e.getMessage().contains("Timeout")) {
                log.info("Timeout while loading RRDP repo: url={}", config.rrdpUrl());
                return new Timeout();
            }
            return new FailedFetch(e);
        } catch (WebClientResponseException e) {
            var maybeRequest = Optional.ofNullable(e.getRequest());
            // Can be either a HTTP non-2xx or a timeout
            log.error("Web client error for {} {}: Can be HTTP non-200 or a timeout. For 2xx we assume it's a timeout.",
                maybeRequest.map(HttpRequest::getMethod), maybeRequest.map(HttpRequest::getURI), e);
            if (e.getStatusCode().is2xxSuccessful()) {
                // Assume it's a timeout
                return new Timeout();
            }
            return new FailedFetch(e);
        } catch (WebClientRequestException e) {
            // TODO: Exception handling could be a lot nicer. However we are mixing reactive and synchronous code,
            // and a nice solution probably requires major changes.
            log.error("Web client request exception, only known cause is a timeout.", e);
            return new Timeout();
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

                    var hash = Sha256.asBytes(decoded);

                    // Try to get some creation timestamp from the object itself. If it's impossible to parse
                    // the object, use the default based on the last-modified header of the snapshot.
                    //
                    // Cache the timestamp per hash do avoid re-parsing every object in the snapshot every time.
                    //
                    final Instant modificationTime = state.cacheTimestamps(Sha256.asString(hash), now,
                        () -> incorporateHashInTimestamp(getTimestampForObject(objectUri, decoded, defaultTimestamp), hash));

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

    private static Instant extractSigningTime(RpkiSignedObject o) {
        return Instant.ofEpochMilli(o.getSigningTime().getMillis());
    }

    private Instant getTimestampForObject(final String objectUri, final byte[] decoded, Instant lastModified) {
        final RepositoryObjectType objectType = RepositoryObjectType.parse(objectUri);
        try {
            return switch (objectType) {
                case Manifest:
                case Aspa:
                case Roa:
                case Gbr:
                    var signedObjectParser = new RpkiSignedObjectParser() {
                        public DateTime getPublicSigningTime() {
                            return getSigningTime();
                        }
                    };

                    signedObjectParser.parse(ValidationResult.withLocation(objectUri), decoded);
                    yield Instant.ofEpochMilli(signedObjectParser.getPublicSigningTime().getMillis());
                case Certificate:
                    X509ResourceCertificateParser x509CertificateParser = new X509ResourceCertificateParser();
                    x509CertificateParser.parse(ValidationResult.withLocation(objectUri), decoded);
                    final var cert = x509CertificateParser.getCertificate().getCertificate();
                    yield Instant.ofEpochMilli(cert.getNotBefore().getTime());
                case Crl:
                    final X509Crl x509Crl = X509Crl.parseDerEncoded(decoded, ValidationResult.withLocation(objectUri));
                    final var crl = x509Crl.getCrl();
                    yield Instant.ofEpochMilli(crl.getThisUpdate().getTime());
                case Unknown:
                    yield lastModified;
            };
        } catch (Exception e) {
            metrics.badObject();
            var encoder = Base64.getEncoder();
            log.error("Could not parse the object url = {}, body = {} :", objectUri, encoder.encodeToString(decoded), e);
            return lastModified;
        }
    }

    private static <T extends RpkiSignedObjectParser> T tryParse(T parser, final String objectUri, final byte[] decoded) {
        final ValidationResult result = ValidationResult.withLocation(objectUri);
        parser.parse(result, decoded);
        checkResult(objectUri, result);
        return parser;
    }

    private static void checkResult(String objectUri, ValidationResult result) {
        if (result.hasFailures()) {
            throw new RuntimeException(String.format("Object %s, error %s", objectUri, result.getFailuresForAllLocations()));
        }
    }

    /**
     * Add artificial millisecond offset to the timestamp based on hash of the object.
     * This MAY help for the corner case of objects having second-accuracy timestamps
     * and the timestatmp in seconds being the same for multiple objects.
     */
    @VisibleForTesting
    public static Instant incorporateHashInTimestamp(Instant t, byte[] hash) {
        final BigInteger ms = new BigInteger(hash).mod(BigInteger.valueOf(1_000_000_000L));
        return t.truncatedTo(ChronoUnit.SECONDS).plusNanos(ms.longValue());
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

    ;

}

