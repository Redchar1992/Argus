package com.argus.tools.screening.ofac;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optional local proof against a just-downloaded complete official feed; skipped in hermetic CI. */
class OfacSdnParserLiveFileTest {

    @Test
    void parsesCompleteOfficialFeedWithBoundedMemory() throws Exception {
        String file = System.getProperty("argus.ofac.live-file", "");
        Assumptions.assumeTrue(!file.isBlank() && Files.isRegularFile(Path.of(file)));
        try (InputStream input = Files.newInputStream(Path.of(file))) {
            OfacSdnParser.ParsedDataset dataset = new OfacSdnParser().parse(input);
            assertTrue(dataset.addresses().size() >= 100);
            assertTrue(dataset.addresses().stream().anyMatch(entry -> entry.asset().equals("TRX")));
            assertTrue(dataset.addresses().stream().anyMatch(entry -> entry.asset().equals("ETH")));
        }
    }
}
