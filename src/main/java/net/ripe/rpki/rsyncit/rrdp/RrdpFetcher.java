package net.ripe.rpki.rsyncit.rrdp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.Collections;
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

    private String lastSnapshotUrl;

    private final RRDPFetcherMetrics metrics;

    public RrdpFetcher(Config config, WebClient httpClient, State state, MeterRegistry meterRegistry) {
        this.config = config;
        this.httpClient = httpClient;
        this.state = state;
        this.metrics = new RRDPFetcherMetrics(meterRegistry);
        log.info("RrdpFetcher for {}", config.getRrdpUrl());
    }

    private byte[] blockForHttpGetRequest(String uri, Duration timeout) {
        return httpClient.get().uri(uri).retrieve().bodyToMono(byte[].class).block(timeout);
    }

    /**
     * Load snapshot and validate hash
     */
    private byte[] loadSnapshot(String snapshotUrl, String desiredSnapshotHash) throws SnapshotStructureException {
        log.info("loading RRDP snapshot from {}", snapshotUrl);

        final byte[] snapshotBytes = blockForHttpGetRequest(snapshotUrl, config.getRequestTimeout());

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
            return new FailedFetch(e);
        }
    }

    public FetchResult fetchObjectsEx() throws SnapshotStructureException, RepoUpdateAbortedException {
        try {
            final DocumentBuilder documentBuilder = XML.newDocumentBuilder();

            final byte[] notificationBytes = blockForHttpGetRequest(config.getRrdpUrl(), config.getRequestTimeout());
            final Document notificationXmlDoc = documentBuilder.parse(new ByteArrayInputStream(notificationBytes));

            final int notificationSerial = Integer.parseInt(notificationXmlDoc.getDocumentElement().getAttribute("serial"));
            final String notificationSessionId = notificationXmlDoc.getDocumentElement().getAttribute("session_id");

            final Node snapshotTag = notificationXmlDoc.getDocumentElement().getElementsByTagName("snapshot").item(0);
            final String snapshotUrl = snapshotTag.getAttributes().getNamedItem("uri").getNodeValue();
            final String desiredSnapshotHash = snapshotTag.getAttributes().getNamedItem("hash").getNodeValue();
            
            if (snapshotUrl.equals(lastSnapshotUrl)) {
                log.info("Not updating: snapshot url {} is the same as during the last check.", snapshotUrl);
                metrics.success(notificationSerial);
                return new NoUpdates(notificationSessionId, notificationSerial);
            }

            long begin = System.currentTimeMillis();
            final byte[] snapshotContent = loadSnapshot(snapshotUrl, desiredSnapshotHash);
            long end = System.currentTimeMillis();
            log.info("Downloaded snapshot in {}ms", (end - begin));

            final Document snapshotXmlDoc = documentBuilder.parse(new ByteArrayInputStream(snapshotContent));
            var doc = snapshotXmlDoc.getDocumentElement();

            validateSnapshotStructure(notificationSerial, snapshotUrl, doc);

            var processPublishElementResult = processPublishElements(doc);

            metrics.success(notificationSerial);
            // We have successfully updated from the snapshot, store the URL
            lastSnapshotUrl = snapshotUrl;

            return new SuccessfulFetch(processPublishElementResult.objects, notificationSessionId, notificationSerial);
        } catch (SnapshotStructureException e) {
            metrics.failure();
            throw e;
        } catch (ParserConfigurationException | XPathExpressionException | SAXException | IOException |
                 NumberFormatException e) {
            // recall: IOException, ConnectException are subtypes of IOException
            metrics.failure();
            throw new FetcherException(e);
        } catch (IllegalStateException e) {
            if (e.getMessage().contains("Timeout")) {
                log.info("Timeout while loading RRDP repo: url={}", config.getRrdpUrl());
                metrics.timeout();
                throw new RepoUpdateAbortedException(config.getRrdpUrl(), e);
            } else {
                throw e;
            }
        } catch (WebClientResponseException e) {
            var maybeRequest = Optional.ofNullable(e.getRequest());
            // Can be either a HTTP non-2xx or a timeout
            log.error("Web client error for {} {}: Can be HTTP non-200 or a timeout. For 2xx we assume it's a timeout.", maybeRequest.map(HttpRequest::getMethod), maybeRequest.map(HttpRequest::getURI), e);
            if (e.getStatusCode().is2xxSuccessful()) {
                // Assume it's a timeout
                metrics.timeout();
                throw new RepoUpdateAbortedException(Optional.ofNullable(e.getRequest()).map(HttpRequest::getURI).orElse(null), e);
            } else {
                metrics.failure();
                throw e;
            }
        } catch (WebClientRequestException e) {
            // TODO: Exception handling could be a lot nicer. However we are mixing reactive and synchronous code,
            //  and a nice solution probably requires major changes.
            log.error("Web client request exception, only known cause is a timeout.", e);
            metrics.timeout();
            throw new RepoUpdateAbortedException(config.getRrdpUrl(), e);
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
            .collect(Collectors.groupingBy(RpkiObject::getUrl))
            // invariant: every group has at least 1 item
            .entrySet().stream()
            .map(item -> {
                if (item.getValue().size() > 1) {
                    var collect = item.getValue().stream().map(coll ->
                            Sha256.asString(coll.getBytes())).
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

    public sealed interface FetchResult permits SuccessfulFetch, NoUpdates, FailedFetch {}

    public record SuccessfulFetch(List<RpkiObject> objects, String sessionId, Integer serial) implements FetchResult {}
    public record NoUpdates(String sessionId, Integer serial) implements FetchResult {}
    public record FailedFetch(Exception exception) implements FetchResult {}

    public static final class RRDPFetcherMetrics {
        final AtomicInteger rrdpSerial = new AtomicInteger();

        final Counter successfulUpdates;
        private final Counter failedUpdates;
        private final Counter timeoutUpdates;

        RRDPFetcherMetrics(MeterRegistry meterRegistry) {
            successfulUpdates = buildCounter("success", meterRegistry);
            failedUpdates = buildCounter("failed", meterRegistry);
            timeoutUpdates = buildCounter("timeout", meterRegistry);

            Gauge.builder("rsyncit.fetcher.rrdp.serial", rrdpSerial::get)
                .description("Serial of the RRDP notification.xml at the given URL")
                .register(meterRegistry);
        }

        public void success(int serial) {
            this.successfulUpdates.increment();
            this.rrdpSerial.set(serial);
        }

        public void failure() { this.failedUpdates.increment(); }

        public void timeout() { this.timeoutUpdates.increment(); }

        private static Counter buildCounter(String statusTag, MeterRegistry registry) {
            return Counter.builder("rsyncit.fetcher.updated")
                .description("Number of fetches")
                .tag("status", statusTag)
                .register(registry);
        }

    }
}

