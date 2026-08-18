package com.argus.tools.screening.ofac;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/** Loads either the explicit offline excerpt or the official HTTPS publication with hard bounds. */
@Component
public class OfacSourceLoader {

    private final OfacProperties properties;
    private final HttpClient http;

    public OfacSourceLoader(OfacProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout().compareTo(java.time.Duration.ofSeconds(30)) > 0
                        ? java.time.Duration.ofSeconds(30) : properties.requestTimeout())
                // Production pins the exact official endpoint. Refuse redirects so a 3xx cannot
                // silently move ingestion to a different host or path.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public SourceDocument load() {
        String source = properties.sourceUri();
        try {
            if (source.startsWith("classpath:")) {
                String path = source.substring("classpath:".length());
                InputStream input = new ClassPathResource(path).getInputStream();
                return new SourceDocument(new BoundedInputStream(input, properties.maxBytes()),
                        source, Instant.now(), null);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                    .GET()
                    .timeout(properties.requestTimeout())
                    .header("Accept", "application/xml,text/xml;q=0.9")
                    .header("User-Agent", properties.userAgent())
                    .build();
            HttpResponse<InputStream> response = http.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IllegalStateException("OFAC source returned HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase(Locale.ROOT).contains("xml")) {
                response.body().close();
                throw new IllegalStateException("OFAC source did not return XML");
            }
            String expectedSha256 = response.headers().firstValue("Digest")
                    .map(OfacSourceLoader::parseSha256Digest)
                    .orElse(null);
            return new SourceDocument(new BoundedInputStream(response.body(), properties.maxBytes()),
                    source, Instant.now(), expectedSha256);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OFAC source request was interrupted", failure);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Unable to load OFAC source", failure);
        }
    }

    static String parseSha256Digest(String digest) {
        for (String candidate : digest.split(",")) {
            String value = candidate.trim();
            if (!value.toLowerCase(Locale.ROOT).startsWith("sha-256")) continue;
            value = value.substring("sha-256".length()).trim();
            if (value.startsWith("=") || value.startsWith(":")) value = value.substring(1).trim();
            if (value.matches("(?i)[0-9a-f]{64}")) return value.toLowerCase(Locale.ROOT);
            try {
                byte[] decoded = Base64.getDecoder().decode(value);
                if (decoded.length == 32) return HexFormat.of().formatHex(decoded);
            } catch (IllegalArgumentException ignored) {
                // Rejected below with one stable operator-facing error.
            }
            throw new IllegalStateException("OFAC source returned an invalid SHA-256 Digest header");
        }
        return null;
    }

    public record SourceDocument(InputStream input, String sourceUri,
                                 Instant fetchedAt, String expectedSha256) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    static final class BoundedInputStream extends FilterInputStream {
        private final long maximum;
        private long read;

        BoundedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) count(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) count(count);
            return count;
        }

        private void count(int count) throws IOException {
            read += count;
            if (read > maximum) throw new IOException("OFAC source exceeds configured byte limit");
        }
    }
}
