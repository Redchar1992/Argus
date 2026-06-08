package com.argus.tools.config;

import com.argus.tools.model.SanctionedAddress;
import com.argus.tools.model.ToolStatus;
import com.argus.tools.model.TransactionEdge;
import com.argus.tools.repository.SanctionedAddressRepository;
import com.argus.tools.repository.ToolStatusRepository;
import com.argus.tools.repository.TransactionEdgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Seeds the sanctions list, a small but non-trivial transaction graph, and the
 * tool catalog. The graph is hand-built so the demo wallets produce interesting,
 * deterministic agent investigations (direct hit, multi-hop exposure, and clean).
 *
 * Addresses are illustrative (0xC1.. style); none are real OFAC entries.
 */
@Configuration
public class ToolsDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(ToolsDataSeeder.class);

    // Demo wallets referenced in the README / frontend quick-pick list.
    // 0xc1ean...  -> clean, low risk
    // 0xc0ffee... -> 1 hop from a mixer (medium/high)
    // 0xbadc0de... -> directly sanctioned (high)
    // 0xdeadbeef.. -> structuring pattern (many small transfers)

    @Bean
    public ApplicationRunner seedTools(SanctionedAddressRepository sanctions,
                                       TransactionEdgeRepository edges,
                                       ToolStatusRepository tools) {
        return args -> {
            if (sanctions.count() == 0) {
                sanctions.saveAll(List.of(
                        new SanctionedAddress("0xbadc0de000000000000000000000000000000bad",
                                "Lazarus-linked wallet", "OFAC-SDN", "DPRK", 95),
                        new SanctionedAddress("0x515c70000000000000000000000000000000m1xr",
                                "TornadoCash-style mixer", "OFAC-SDN", "CYBER2", 90),
                        new SanctionedAddress("0x4444f1a6000000000000000000000000000scam1",
                                "Known fraud cash-out", "INTERNAL-WATCHLIST", "FRAUD", 70),
                        new SanctionedAddress("0x9999dark00000000000000000000000000market",
                                "Darknet market hot wallet", "EU-CONSOLIDATED", "NARCOTICS", 85)
                ));
                log.info("Seeded {} sanctioned addresses", sanctions.count());
            }

            if (edges.count() == 0) {
                edges.saveAll(List.of(
                        // 0xc0ffee deposits into a mixer (1 hop direct exposure)
                        edge("0xc0ffee00000000000000000000000000000c0ffee",
                                "0x515c70000000000000000000000000000000m1xr", 250_000, "ETH", "0xtx001"),
                        edge("0xc0ffee00000000000000000000000000000c0ffee",
                                "0xaaa1110000000000000000000000000000000aaa", 40_000, "USDT", "0xtx002"),
                        // mixer pays out to a downstream wallet (2 hops from 0xaaa)
                        edge("0x515c70000000000000000000000000000000m1xr",
                                "0xbbb2220000000000000000000000000000000bbb", 180_000, "ETH", "0xtx003"),
                        // 0xaaa -> 0xbbb makes a 2-hop path 0xc0ffee..0xaaa..0xbbb..mixer style chain
                        edge("0xaaa1110000000000000000000000000000000aaa",
                                "0xbbb2220000000000000000000000000000000bbb", 15_000, "USDT", "0xtx004"),
                        // 0xbbb -> darknet market (so 0xaaa reaches a flagged addr in 2 hops)
                        edge("0xbbb2220000000000000000000000000000000bbb",
                                "0x9999dark00000000000000000000000000market", 60_000, "ETH", "0xtx005"),

                        // structuring wallet 0xdeadbeef: many small transfers to many counterparties
                        edge("0xdeadbeef0000000000000000000000000deadbeef",
                                "0xd0010000000000000000000000000000000d001", 8_500, "USDT", "0xtx010"),
                        edge("0xdeadbeef0000000000000000000000000deadbeef",
                                "0xd0020000000000000000000000000000000d002", 8_200, "USDT", "0xtx011"),
                        edge("0xdeadbeef0000000000000000000000000deadbeef",
                                "0xd0030000000000000000000000000000000d003", 7_900, "USDT", "0xtx012"),
                        edge("0xdeadbeef0000000000000000000000000deadbeef",
                                "0xd0040000000000000000000000000000000d004", 8_800, "USDT", "0xtx013"),
                        edge("0xdeadbeef0000000000000000000000000deadbeef",
                                "0xd0050000000000000000000000000000000d005", 8_100, "USDT", "0xtx014"),
                        edge("0xdeadbeef0000000000000000000000000deadbeef",
                                "0xd0060000000000000000000000000000000d006", 8_600, "USDT", "0xtx015"),
                        edge("0xd0010000000000000000000000000000000d001",
                                "0x4444f1a6000000000000000000000000000scam1", 7_000, "USDT", "0xtx016"),

                        // clean wallet: normal-looking exchange flows, no flagged neighbours
                        edge("0xc1ean000000000000000000000000000000c1ean",
                                "0xexchange00000000000000000000000000binance", 5_000, "USDT", "0xtx020"),
                        edge("0xexchange00000000000000000000000000binance",
                                "0xc1ean000000000000000000000000000000c1ean", 3_000, "USDT", "0xtx021"),
                        edge("0xc1ean000000000000000000000000000000c1ean",
                                "0xfriend0000000000000000000000000000friend", 1_200, "ETH", "0xtx022")
                ));
                log.info("Seeded {} transaction edges", edges.count());
            }

            if (tools.count() == 0) {
                tools.saveAll(List.of(
                        new ToolStatus("sanctions_screen",
                                "Check addresses against the sanctions/watchlist", true),
                        new ToolStatus("trace_transactions",
                                "Walk the transaction graph N hops to find exposure to flagged addresses", true),
                        new ToolStatus("address_profile",
                                "Aggregate inflow/outflow/counterparty stats for an address", true),
                        new ToolStatus("risk_rules",
                                "Evaluate AML threshold rules over gathered facts", true)
                ));
                log.info("Seeded {} tools in catalog", tools.count());
            }
        };
    }

    private static TransactionEdge edge(String from, String to, double usd, String asset, String hash) {
        return new TransactionEdge(from, to, usd, asset, hash);
    }
}
