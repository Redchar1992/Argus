package com.argus.tools.screening.ofac;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Validated OFAC ingest/freshness policy. Production cannot silently use the excerpt fixture. */
@Component
public class OfacProperties {

    public static final String OFFICIAL_SOURCE =
            "https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN_ADVANCED.XML";

    private final boolean enabled;
    private final String sourceUri;
    private final boolean required;
    private final boolean refreshOnStartup;
    private final Duration maxAge;
    private final Duration refreshIfOlderThan;
    private final Duration requestTimeout;
    private final long maxBytes;
    private final int minimumAddressCount;
    private final String userAgent;

    public OfacProperties(
            @Value("${argus.screening.providers:local,ofac}") List<String> providers,
            @Value("${argus.screening.ofac.source-uri:classpath:fixtures/ofac-sdn-advanced-excerpt.xml}") String sourceUri,
            @Value("${argus.screening.ofac.required:true}") boolean required,
            @Value("${argus.screening.ofac.refresh-on-startup:true}") boolean refreshOnStartup,
            @Value("${argus.screening.ofac.max-age:PT48H}") Duration maxAge,
            @Value("${argus.screening.ofac.refresh-if-older-than:PT6H}") Duration refreshIfOlderThan,
            @Value("${argus.screening.ofac.request-timeout:PT180S}") Duration requestTimeout,
            @Value("${argus.screening.ofac.max-bytes:200000000}") long maxBytes,
            @Value("${argus.screening.ofac.minimum-address-count:1}") int minimumAddressCount,
            @Value("${argus.screening.ofac.user-agent:ArgusComplianceDemo/0.1}") String userAgent,
            Environment environment) {
        this.enabled = providers.stream().map(String::trim).anyMatch("ofac"::equals);
        this.sourceUri = sourceUri.trim();
        this.required = required;
        this.refreshOnStartup = refreshOnStartup;
        this.maxAge = maxAge;
        this.refreshIfOlderThan = refreshIfOlderThan;
        this.requestTimeout = requestTimeout;
        this.maxBytes = maxBytes;
        this.minimumAddressCount = minimumAddressCount;
        this.userAgent = userAgent.trim();
        validate(environment);
    }

    private void validate(Environment environment) {
        if (maxAge.isNegative() || maxAge.isZero() || refreshIfOlderThan.isNegative()
                || refreshIfOlderThan.isZero() || refreshIfOlderThan.compareTo(maxAge) > 0) {
            throw new IllegalStateException("OFAC refresh and maximum-age durations are invalid");
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero() || maxBytes < 1_000_000
                || minimumAddressCount < 1 || userAgent.isBlank()) {
            throw new IllegalStateException("OFAC source safety bounds are invalid");
        }
        boolean production = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equalsIgnoreCase("prod")
                        || profile.equalsIgnoreCase("production"));
        if (!production) return;
        if (!enabled || !required || !refreshOnStartup || minimumAddressCount < 100) {
            throw new IllegalStateException(
                    "Production requires the official OFAC provider, startup refresh and integrity floor");
        }
        URI uri = URI.create(sourceUri);
        if (!URI.create(OFFICIAL_SOURCE).equals(uri)) {
            throw new IllegalStateException("Production OFAC source must be the official HTTPS SDN Advanced XML");
        }
    }

    public boolean enabled() { return enabled; }
    public String sourceUri() { return sourceUri; }
    public boolean required() { return required; }
    public boolean refreshOnStartup() { return refreshOnStartup; }
    public Duration maxAge() { return maxAge; }
    public Duration refreshIfOlderThan() { return refreshIfOlderThan; }
    public Duration requestTimeout() { return requestTimeout; }
    public long maxBytes() { return maxBytes; }
    public int minimumAddressCount() { return minimumAddressCount; }
    public String userAgent() { return userAgent; }
}
