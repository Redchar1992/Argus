package com.argus.tools.screening.ofac;

import com.argus.tools.screening.AddressNormalizer;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * XXE-safe streaming parser for OFAC's Advanced Sanctions Data Model. The official file is
 * currently well over 100 MB, so this intentionally never builds a DOM.
 */
@Component
public class OfacSdnParser {

    private static final String DIGITAL_ADDRESS_PREFIX = "Digital Currency Address - ";
    private static final int MAX_DIGITAL_ADDRESSES = 20_000;

    public ParsedDataset parse(InputStream input) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        set(factory, XMLInputFactory.SUPPORT_DTD, false);
        set(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) ->
                new ByteArrayInputStream(new byte[0]));

        Map<String, String> digitalFeatureTypes = new LinkedHashMap<>();
        List<ProvisionalAddress> provisional = new ArrayList<>();
        Map<String, Set<String>> programsByProfile = new LinkedHashMap<>();

        boolean inDateOfIssue = false;
        int issueYear = 0;
        int issueMonth = 0;
        int issueDay = 0;
        String currentProfileId = null;
        String currentEntity = null;
        boolean currentAliasPrimary = false;
        List<String> currentPrimaryNameParts = new ArrayList<>();
        String currentFeatureAsset = null;
        String currentSanctionsProfile = null;
        boolean currentProgramMeasure = false;
        Set<String> currentPrograms = new LinkedHashSet<>();

        try {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String element = reader.getLocalName();
                    switch (element) {
                        case "DateOfIssue" -> inDateOfIssue = true;
                        case "Year" -> {
                            if (inDateOfIssue) issueYear = parseInt(reader.getElementText(), "issue year");
                        }
                        case "Month" -> {
                            if (inDateOfIssue) issueMonth = parseInt(reader.getElementText(), "issue month");
                        }
                        case "Day" -> {
                            if (inDateOfIssue) issueDay = parseInt(reader.getElementText(), "issue day");
                        }
                        case "FeatureType" -> {
                            String featureId = attribute(reader, "ID");
                            String label = reader.getElementText().trim();
                            if (label.startsWith(DIGITAL_ADDRESS_PREFIX)) {
                                String asset = label.substring(DIGITAL_ADDRESS_PREFIX.length()).trim();
                                if (!featureId.isBlank() && asset.matches("[A-Z0-9_-]{2,24}")) {
                                    digitalFeatureTypes.put(featureId, asset);
                                }
                            }
                        }
                        case "Profile" -> {
                            currentProfileId = attribute(reader, "ID");
                            currentEntity = null;
                        }
                        case "Alias" -> {
                            currentAliasPrimary = Boolean.parseBoolean(attribute(reader, "Primary"));
                            currentPrimaryNameParts = new ArrayList<>();
                        }
                        case "NamePartValue" -> {
                            if (currentAliasPrimary) {
                                String value = reader.getElementText().trim();
                                if (!value.isBlank()) currentPrimaryNameParts.add(value);
                            }
                        }
                        case "Feature" -> currentFeatureAsset =
                                digitalFeatureTypes.get(attribute(reader, "FeatureTypeID"));
                        case "VersionDetail" -> {
                            if (currentFeatureAsset != null && currentProfileId != null) {
                                String displayAddress = reader.getElementText().trim();
                                String normalized = AddressNormalizer.normalize(displayAddress);
                                if (validAddress(normalized)) {
                                    provisional.add(new ProvisionalAddress(currentProfileId,
                                            currentEntity == null ? "OFAC SDN entity " + currentProfileId : currentEntity,
                                            currentFeatureAsset, displayAddress, normalized));
                                    if (provisional.size() > MAX_DIGITAL_ADDRESSES) {
                                        throw new IllegalStateException("OFAC digital-address count exceeds safety bound");
                                    }
                                }
                            }
                        }
                        case "SanctionsEntry" -> {
                            currentSanctionsProfile = attribute(reader, "ProfileID");
                            currentPrograms = new LinkedHashSet<>();
                        }
                        case "SanctionsMeasure" -> currentProgramMeasure =
                                "1".equals(attribute(reader, "SanctionsTypeID"));
                        case "Comment" -> {
                            if (currentProgramMeasure && currentSanctionsProfile != null) {
                                String value = reader.getElementText().trim();
                                if (!value.isBlank()) currentPrograms.add(value);
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String element = reader.getLocalName();
                    switch (element) {
                        case "DateOfIssue" -> inDateOfIssue = false;
                        case "Alias" -> {
                            if (currentAliasPrimary && currentEntity == null
                                    && !currentPrimaryNameParts.isEmpty()) {
                                currentEntity = String.join(" ", currentPrimaryNameParts);
                            }
                            currentAliasPrimary = false;
                            currentPrimaryNameParts = new ArrayList<>();
                        }
                        case "Feature" -> currentFeatureAsset = null;
                        case "Profile" -> {
                            currentProfileId = null;
                            currentEntity = null;
                        }
                        case "SanctionsMeasure" -> currentProgramMeasure = false;
                        case "SanctionsEntry" -> {
                            if (currentSanctionsProfile != null) {
                                programsByProfile.computeIfAbsent(currentSanctionsProfile,
                                                ignored -> new LinkedHashSet<>())
                                        .addAll(currentPrograms);
                            }
                            currentSanctionsProfile = null;
                            currentPrograms = new LinkedHashSet<>();
                        }
                        default -> {
                        }
                    }
                }
            }
            reader.close();
        } catch (XMLStreamException failure) {
            throw new IllegalStateException("Invalid OFAC Advanced XML", failure);
        }

        LocalDate publishedOn;
        try {
            publishedOn = LocalDate.of(issueYear, issueMonth, issueDay);
        } catch (DateTimeException failure) {
            throw new IllegalStateException("OFAC dataset has no valid DateOfIssue", failure);
        }
        if (digitalFeatureTypes.isEmpty()) {
            throw new IllegalStateException("OFAC dataset defines no digital-currency feature types");
        }
        if (provisional.isEmpty()) {
            throw new IllegalStateException("OFAC dataset contains no digital-currency addresses");
        }

        Map<String, ParsedAddress> deduplicated = new LinkedHashMap<>();
        for (ProvisionalAddress value : provisional) {
            String program = String.join(",", programsByProfile.getOrDefault(
                    value.profileId(), Set.of("SDN")));
            ParsedAddress address = new ParsedAddress(value.normalizedAddress(), value.displayAddress(),
                    value.asset(), value.entity(), program.isBlank() ? "SDN" : program, value.profileId());
            deduplicated.putIfAbsent(value.asset() + "|" + value.normalizedAddress()
                    + "|" + value.profileId(), address);
        }
        return new ParsedDataset(publishedOn, List.copyOf(deduplicated.values()));
    }

    private static void set(XMLInputFactory factory, String property, boolean value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("XML parser cannot enforce required security property " + property,
                    failure);
        }
    }

    private static String attribute(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        return value == null ? "" : value.trim();
    }

    private static int parseInt(String value, String label) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("Invalid OFAC " + label, failure);
        }
    }

    private static boolean validAddress(String value) {
        return value.length() >= 3 && value.length() <= 256
                && value.chars().noneMatch(Character::isWhitespace);
    }

    private record ProvisionalAddress(String profileId, String entity, String asset,
                                      String displayAddress, String normalizedAddress) {
    }

    public record ParsedDataset(LocalDate publishedOn, List<ParsedAddress> addresses) {
    }

    public record ParsedAddress(String normalizedAddress, String displayAddress, String asset,
                                String entity, String program, String profileId) {
    }
}
