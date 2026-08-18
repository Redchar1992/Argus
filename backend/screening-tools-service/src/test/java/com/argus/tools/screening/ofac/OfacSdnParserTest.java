package com.argus.tools.screening.ofac;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfacSdnParserTest {

    private final OfacSdnParser parser = new OfacSdnParser();

    @Test
    void parsesOfficialShapeIntoEthereumAndTronAddresses() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "fixtures/ofac-sdn-advanced-excerpt.xml")) {
            OfacSdnParser.ParsedDataset dataset = parser.parse(input);

            assertEquals(LocalDate.of(2026, 8, 7), dataset.publishedOn());
            assertEquals(2, dataset.addresses().size());
            OfacSdnParser.ParsedAddress ethereum = dataset.addresses().stream()
                    .filter(entry -> entry.asset().equals("ETH")).findFirst().orElseThrow();
            assertEquals("0x098b716b8aaf21512996dc57eb0615e2383e2f96",
                    ethereum.normalizedAddress());
            assertEquals("Lazarus Group", ethereum.entity());
            assertEquals("DPRK3", ethereum.program());
            OfacSdnParser.ParsedAddress tron = dataset.addresses().stream()
                    .filter(entry -> entry.asset().equals("TRX")).findFirst().orElseThrow();
            assertEquals("TNiq9AXBp9EjUqhDhrwrfvAA8U3GUQZH81", tron.normalizedAddress());
            assertTrue(tron.program().contains("IRAN"));
            assertTrue(tron.program().contains("SDGT"));
        }
    }

    @Test
    void rejectsDoctypeAndExternalEntityInput() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE Sanctions [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <Sanctions><DateOfIssue><Year>2026</Year><Month>8</Month><Day>7</Day></DateOfIssue>
                <ReferenceValueSets><FeatureType ID="345">Digital Currency Address - ETH</FeatureType></ReferenceValueSets>
                <DistinctParty><Profile ID="1"><Identity><Alias Primary="true"><NamePartValue>&xxe;</NamePartValue></Alias></Identity>
                <Feature FeatureTypeID="345"><VersionDetail>0x123</VersionDetail></Feature></Profile></DistinctParty></Sanctions>
                """;
        assertThrows(IllegalStateException.class, () -> parser.parse(new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8))));
    }
}
