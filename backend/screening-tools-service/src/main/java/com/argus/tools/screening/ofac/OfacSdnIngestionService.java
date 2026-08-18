package com.argus.tools.screening.ofac;

import org.springframework.stereotype.Service;

import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;

/** Downloads/parses/validates fully before atomically replacing the last-known-good dataset. */
@Service
public class OfacSdnIngestionService {

    private final OfacProperties properties;
    private final OfacSourceLoader sourceLoader;
    private final OfacSdnParser parser;
    private final OfacDatasetStore store;

    public OfacSdnIngestionService(OfacProperties properties, OfacSourceLoader sourceLoader,
                                   OfacSdnParser parser, OfacDatasetStore store) {
        this.properties = properties;
        this.sourceLoader = sourceLoader;
        this.parser = parser;
        this.store = store;
    }

    public synchronized RefreshResult refresh() {
        try (OfacSourceLoader.SourceDocument document = sourceLoader.load()) {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            OfacSdnParser.ParsedDataset parsed = parser.parse(
                    new DigestInputStream(document.input(), sha256));
            if (parsed.addresses().size() < properties.minimumAddressCount()) {
                throw new IllegalStateException("OFAC address count is below the integrity floor");
            }
            if (parsed.publishedOn().isAfter(LocalDate.now().plusDays(1))) {
                throw new IllegalStateException("OFAC publication date is in the future");
            }
            String checksum = HexFormat.of().formatHex(sha256.digest());
            if (document.expectedSha256() != null
                    && !MessageDigest.isEqual(HexFormat.of().parseHex(checksum),
                    HexFormat.of().parseHex(document.expectedSha256()))) {
                throw new IllegalStateException("OFAC source SHA-256 Digest does not match the response body");
            }
            String mode = document.sourceUri().startsWith("classpath:") ? "snapshot" : "official";
            String version = mode + "-" + parsed.publishedOn() + "-" + checksum.substring(0, 16);
            store.replace(parsed, document.sourceUri(), document.fetchedAt(), checksum, version);
            return new RefreshResult(version, parsed.publishedOn(), parsed.addresses().size(),
                    checksum, document.sourceUri(), document.fetchedAt());
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to ingest OFAC SDN dataset", failure);
        }
    }

    public record RefreshResult(String version, LocalDate publishedOn, int addressCount,
                                String sha256, String sourceUri, java.time.Instant fetchedAt) {
    }
}
