package com.argus.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayApplicationTests {

    private static final Set<String> EXPECTED_ROUTE_IDS = Set.of(
            "auth-service",
            "orchestrator-service",
            "tools-service",
            "case-service"
    );

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void startsAndBindsAllConfiguredRoutes() {
        List<String> routeIds = routeLocator.getRoutes()
                .map(route -> route.getId())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(routeIds);
        assertEquals(EXPECTED_ROUTE_IDS, new HashSet<>(routeIds));
    }
}
