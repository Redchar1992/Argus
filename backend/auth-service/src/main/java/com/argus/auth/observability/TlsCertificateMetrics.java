package com.argus.auth.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

/** Publishes only certificate expiry; key bytes, paths and passwords never become labels. */
@Component
public class TlsCertificateMetrics {

    public TlsCertificateMetrics(
            MeterRegistry registry,
            ResourceLoader resources,
            @Value("${server.ssl.enabled:false}") boolean enabled,
            @Value("${server.ssl.key-store:}") String keyStoreLocation,
            @Value("${server.ssl.key-store-password:}") String keyStorePassword,
            @Value("${server.ssl.key-store-type:PKCS12}") String keyStoreType) {
        if (!enabled || keyStoreLocation.isBlank()) return;
        double expiry = earliestExpiry(resources, keyStoreLocation, keyStorePassword, keyStoreType);
        Gauge.builder("argus.identity.tls.certificate.expiry.timestamp.seconds", () -> expiry)
                .description("Not-after timestamp for the auth-service TLS identity certificate.")
                .tag("certificate", "server")
                .register(registry);
    }

    private static double earliestExpiry(ResourceLoader resources, String location,
                                         String password, String type) {
        try {
            Resource resource = location.startsWith("classpath:") || location.startsWith("file:")
                    ? resources.getResource(location) : new FileSystemResource(location);
            KeyStore keyStore = KeyStore.getInstance(type);
            try (InputStream input = resource.getInputStream()) {
                keyStore.load(input, password.toCharArray());
            }
            long earliest = Long.MAX_VALUE;
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                Certificate certificate = keyStore.getCertificate(aliases.nextElement());
                if (certificate instanceof X509Certificate x509) {
                    earliest = Math.min(earliest, x509.getNotAfter().toInstant().getEpochSecond());
                }
            }
            if (earliest == Long.MAX_VALUE) throw new IllegalStateException("TLS key store contains no X.509 certificate");
            return earliest;
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to inspect auth-service TLS certificate expiry", failure);
        }
    }
}
