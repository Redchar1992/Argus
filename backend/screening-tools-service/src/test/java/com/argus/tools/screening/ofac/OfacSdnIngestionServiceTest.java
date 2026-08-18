package com.argus.tools.screening.ofac;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OfacSdnIngestionServiceTest {

    private HttpServer server;
    private byte[] fixture;
    private String fixtureSha256;

    @BeforeEach
    void startServer() throws Exception {
        fixture = getClass().getClassLoader().getResourceAsStream(
                "fixtures/ofac-sdn-advanced-excerpt.xml").readAllBytes();
        fixtureSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(fixture));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ofac.xml", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/xml");
            // OFAC currently sends its hexadecimal Digest without an equals separator.
            exchange.getResponseHeaders().set("Digest", "sha-256" + fixtureSha256);
            exchange.sendResponseHeaders(200, fixture.length);
            exchange.getResponseBody().write(fixture);
            exchange.close();
        });
        server.createContext("/bad-digest.xml", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/xml");
            exchange.getResponseHeaders().set("Digest", "sha-256" + "0".repeat(64));
            exchange.sendResponseHeaders(200, fixture.length);
            exchange.getResponseBody().write(fixture);
            exchange.close();
        });
        server.createContext("/down.xml", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void downloadsParsesHashesAndHandsCompleteDatasetToAtomicStore() {
        OfacDatasetStore store = mock(OfacDatasetStore.class);
        OfacSdnIngestionService service = service("/ofac.xml", store);

        OfacSdnIngestionService.RefreshResult result = service.refresh();

        assertEquals(2, result.addressCount());
        assertEquals(fixtureSha256, result.sha256());
        assertTrue(result.version().startsWith("official-2026-08-07-"));
        ArgumentCaptor<OfacSdnParser.ParsedDataset> parsed =
                ArgumentCaptor.forClass(OfacSdnParser.ParsedDataset.class);
        verify(store).replace(parsed.capture(), anyString(), any(), anyString(), anyString());
        assertEquals(2, parsed.getValue().addresses().size());
    }

    @Test
    void httpFailureDoesNotReplaceLastKnownGoodDataset() {
        OfacDatasetStore store = mock(OfacDatasetStore.class);
        OfacSdnIngestionService service = service("/down.xml", store);

        assertThrows(IllegalStateException.class, service::refresh);
        verify(store, never()).replace(any(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void digestMismatchDoesNotReplaceLastKnownGoodDataset() {
        OfacDatasetStore store = mock(OfacDatasetStore.class);
        OfacSdnIngestionService service = service("/bad-digest.xml", store);

        IllegalStateException failure = assertThrows(IllegalStateException.class, service::refresh);
        assertTrue(failure.getMessage().contains("Digest does not match"));
        verify(store, never()).replace(any(), anyString(), any(), anyString(), anyString());
    }

    private OfacSdnIngestionService service(String path, OfacDatasetStore store) {
        String source = "http://127.0.0.1:" + server.getAddress().getPort() + path;
        OfacProperties properties = new OfacProperties(List.of("ofac"), source, true, true,
                Duration.ofHours(48), Duration.ofHours(6), Duration.ofSeconds(5),
                1_000_000, 1, "ArgusTest/0.1", new MockEnvironment());
        return new OfacSdnIngestionService(properties, new OfacSourceLoader(properties),
                new OfacSdnParser(), store);
    }
}
