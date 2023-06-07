package net.ripe.rpki.rsyncit.rrdp;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.commons.crypto.cms.aspa.AspaCmsParser;
import net.ripe.rpki.commons.crypto.cms.roa.RoaCmsParser;
import net.ripe.rpki.commons.crypto.cms.manifest.ManifestCmsParser;
import net.ripe.rpki.commons.crypto.cms.ghostbuster.GhostbustersCmsParser;
import net.ripe.rpki.commons.crypto.x509cert.X509ResourceCertificateParser;
import net.ripe.rpki.commons.crypto.crl.X509Crl;
import net.ripe.rpki.commons.util.RepositoryObjectType;
import net.ripe.rpki.commons.validation.ValidationResult;
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
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Getter
public class RrdpFetcher {

    private final Config config;
    private final WebClient httpClient;
    private final State state;

    private String lastSnapshotUrl;

    public RrdpFetcher(Config config, WebClient httpClient, State state) {
        this.config = config;
        this.httpClient = httpClient;
        this.state = state;
        log.info("RrdpFetcher for {}", config.rrdpUrl());
    }

    private Downloaded blockForHttpGetRequest(String uri, Duration timeout) {
        var lastModified = new AtomicReference<Instant>(null);
        var body = httpClient.get().uri(uri).retrieve()
            .toEntity(byte[].class)
            .doOnSuccess(e -> {
                final long modified = e.getHeaders().getLastModified();
                if (modified != -1) {
                    lastModified.set(Instant.ofEpochMilli(modified));
                }
            })
            .block(timeout)
            .getBody();
        return new Downloaded(body, lastModified.get());
    }

    /**
     * Load snapshot and validate hash
     */
    private Downloaded loadSnapshot(String snapshotUrl, String expectedSnapshotHash) throws SnapshotStructureException {
        log.info("loading RRDP snapshot from {}", snapshotUrl);

        var snapshot = blockForHttpGetRequest(snapshotUrl, config.requestTimeout());
        final String realSnapshotHash = Sha256.asString(snapshot.content());
        if (!realSnapshotHash.equalsIgnoreCase(expectedSnapshotHash)) {
            throw new SnapshotStructureException(snapshotUrl,
                "with len(content) = %d had sha256(content) = %s, expected %s".formatted(snapshot.content().length, realSnapshotHash, expectedSnapshotHash));
        }
        return snapshot;
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

            final byte[] notificationBytes = blockForHttpGetRequest(config.rrdpUrl(), config.requestTimeout()).content();
            final Document notificationXmlDoc = documentBuilder.parse(new ByteArrayInputStream(notificationBytes));

            final int notificationSerial = Integer.parseInt(notificationXmlDoc.getDocumentElement().getAttribute("serial"));
            final String notificationSessionId = notificationXmlDoc.getDocumentElement().getAttribute("session_id");

            final Node snapshotTag = notificationXmlDoc.getDocumentElement().getElementsByTagName("snapshot").item(0);
            final String snapshotUrl = snapshotTag.getAttributes().getNamedItem("uri").getNodeValue();
            final String desiredSnapshotHash = snapshotTag.getAttributes().getNamedItem("hash").getNodeValue();

            if (snapshotUrl.equals(lastSnapshotUrl)) {
                log.info("Not updating: snapshot url {} is the same as during the last check.", snapshotUrl);
                return new NoUpdates(notificationSessionId, notificationSerial);
            }

            long begin = System.currentTimeMillis();
            var snapshot = loadSnapshot(snapshotUrl, desiredSnapshotHash);
            long end = System.currentTimeMillis();
            log.info("Downloaded snapshot in {}ms", (end - begin));

            final Document snapshotXmlDoc = documentBuilder.parse(new ByteArrayInputStream(snapshot.content()));
            var doc = snapshotXmlDoc.getDocumentElement();

            validateSnapshotStructure(notificationSerial, snapshotUrl, doc);

            var processPublishElementResult = processPublishElements(doc, snapshot.lastModified());

            // We have successfully updated from the snapshot, store the URL
            lastSnapshotUrl = snapshotUrl;

            return new SuccessfulFetch(processPublishElementResult.objects, notificationSessionId, notificationSerial);
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

    private ProcessPublishElementResult processPublishElements(Element doc, Instant lastModified) throws XPathExpressionException {
        var queryPublish = XPathFactory.newDefaultInstance().newXPath().compile("/snapshot/publish");
        final NodeList publishedObjects = (NodeList) queryPublish.evaluate(doc, XPathConstants.NODESET);

        var defaultTimestamp = makeTimestampForObjects(lastModified);

        var now = Instant.now();
        var collisionCount = new AtomicInteger();

        var decoder = Base64.getDecoder();

        var t = Time.timed(() -> IntStream
            .range(0, publishedObjects.getLength())
            .mapToObj(publishedObjects::item)
            .parallel()
            .map(item -> {
                var objectUri = item.getAttributes().getNamedItem("uri").getNodeValue();
                var content = item.getTextContent();

                try {
                    // Surrounding whitespace is allowed by xsd:base64Binary. Trim that
                    // off before decoding. See also:
                    // https://www.w3.org/TR/2004/PER-xmlschema-2-20040318/datatypes.html#base64Binary
                    var decoded = decoder.decode(content.trim());

                    var hash = Sha256.asString(decoded);

                    // Try to get some creation timestamp from the object itself. If it's not impossible to parse
                    // the object, use the default based on the last-modified header of the snapshot.
                    //
                    // Cache the timestamp per hash do avoid re-parsing every object in the snapshot every time.
                    //
                    final Instant createAt = state.cacheTimestamps(hash, now,
                        () -> getTimestampForObject(objectUri, decoded, defaultTimestamp));

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

        var objects = t.getResult();
        log.info("Constructed {} objects in {}ms", objects.size(), t.getTime());
        return new ProcessPublishElementResult(objects, collisionCount.get());
    }

    /**
     * Generate timestamp that will be tracked per object and used as FS modification timestamp.
     * Use last-modified header from the snapshot if accessible, otherwise truncate current time
     * to the closest hour -- it is unlikely that different instances will have clocks off by a lot,
     * so rounding down to an hour should generate the same timestamps _most of the time_.
     */
    private Instant makeTimestampForObjects(Instant lastModified) {
        if (lastModified != null) {
            return lastModified;
        }
        return Instant.now().truncatedTo(ChronoUnit.HOURS);
    }

    private Instant getTimestampForObject(final String objectUri, final byte[] decoded, Instant lastModified) {
        final RepositoryObjectType objectType = RepositoryObjectType.parse(objectUri);
        try {
            return switch (objectType) {
                case Manifest -> {
                    ManifestCmsParser manifestCmsParser = new ManifestCmsParser();
                    manifestCmsParser.parse(ValidationResult.withLocation(objectUri), decoded);
                    final var manifestCms = manifestCmsParser.getManifestCms();
                    yield Instant.ofEpochMilli(manifestCms.getThisUpdateTime().getMillis());
                }
                case Aspa -> {
                    var aspaCmsParser = new AspaCmsParser();
                    aspaCmsParser.parse(ValidationResult.withLocation(objectUri), decoded);
                    var aspaCms = aspaCmsParser.getAspa();
                    yield Instant.ofEpochMilli(aspaCms.getNotValidBefore().getMillis());
                }
                case Roa -> {
                    RoaCmsParser roaCmsParser = new RoaCmsParser();
                    roaCmsParser.parse(ValidationResult.withLocation(objectUri), decoded);
                    final var roaCms = roaCmsParser.getRoaCms();
                    yield Instant.ofEpochMilli(roaCms.getNotValidBefore().getMillis());
                }
                case Certificate -> {
                    X509ResourceCertificateParser x509CertificateParser = new X509ResourceCertificateParser();
                    x509CertificateParser.parse(ValidationResult.withLocation(objectUri), decoded);
                    final var cert = x509CertificateParser.getCertificate().getCertificate();
                    yield Instant.ofEpochMilli(cert.getNotBefore().getTime());
                }
                case Crl -> {
                    final X509Crl x509Crl = X509Crl.parseDerEncoded(decoded, ValidationResult.withLocation(objectUri));
                    final var crl = x509Crl.getCrl();
                    yield Instant.ofEpochMilli(crl.getThisUpdate().getTime());
                }
                case Gbr -> {
                    GhostbustersCmsParser ghostbustersCmsParser = new GhostbustersCmsParser();
                    ghostbustersCmsParser.parse(ValidationResult.withLocation(objectUri), decoded);
                    final var ghostbusterCms = ghostbustersCmsParser.getGhostbustersCms();
                    yield Instant.ofEpochMilli(ghostbusterCms.getNotValidBefore().getMillis());
                }
                case Unknown -> lastModified;
            };
        } catch (Exception e) {
            return lastModified;
        }
    }

    record ProcessPublishElementResult(List<RpkiObject> objects, int collisionCount) {}

    public sealed interface FetchResult permits SuccessfulFetch, NoUpdates, FailedFetch, Timeout {}

    public record SuccessfulFetch(List<RpkiObject> objects, String sessionId, Integer serial) implements FetchResult {}
    public record NoUpdates(String sessionId, Integer serial) implements FetchResult {}
    public record FailedFetch(Exception exception) implements FetchResult {}
    public record Timeout() implements FetchResult {}

    public record Downloaded(byte[] content, Instant lastModified) {};

}

