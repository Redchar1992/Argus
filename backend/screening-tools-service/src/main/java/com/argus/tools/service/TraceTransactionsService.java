package com.argus.tools.service;

import com.argus.tools.dto.ToolDtos.FlaggedExposure;
import com.argus.tools.dto.ToolDtos.TraceRequest;
import com.argus.tools.dto.ToolDtos.TraceResponse;
import com.argus.tools.dto.ToolDtos.TracedPathStep;
import com.argus.tools.model.SanctionedAddress;
import com.argus.tools.model.TransactionEdge;
import com.argus.tools.repository.SanctionedAddressRepository;
import com.argus.tools.repository.TransactionEdgeRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * trace_transactions tool: performs a real breadth-first walk over the seeded
 * transaction graph starting at the root address, up to {@code maxHops} hops.
 * Whenever the frontier reaches a sanctioned address it records the full path
 * (so an analyst can see exactly how the money flows to a flagged entity).
 *
 * The graph is treated as undirected for reachability (funds in OR out both
 * indicate a relationship), which is the conservative choice for AML tracing.
 */
@Service
public class TraceTransactionsService {

    private static final int DEFAULT_MAX_HOPS = 3;
    private static final int HOP_CEILING = 6;

    private final TransactionEdgeRepository edgeRepository;
    private final SanctionedAddressRepository sanctionedRepository;

    public TraceTransactionsService(TransactionEdgeRepository edgeRepository,
                                    SanctionedAddressRepository sanctionedRepository) {
        this.edgeRepository = edgeRepository;
        this.sanctionedRepository = sanctionedRepository;
    }

    public TraceResponse trace(TraceRequest request) {
        String root = request.address().toLowerCase(Locale.ROOT).trim();
        int maxHops = request.maxHops() == null ? DEFAULT_MAX_HOPS
                : Math.min(Math.max(request.maxHops(), 1), HOP_CEILING);

        Set<String> sanctionedSet = new HashSet<>();
        Map<String, String> entityByAddress = new HashMap<>();
        for (SanctionedAddress s : sanctionedRepository.findAll()) {
            sanctionedSet.add(s.getAddress());
            entityByAddress.put(s.getAddress(), s.getEntity());
        }

        Set<String> visited = new HashSet<>();
        visited.add(root);
        // Tracks the edge used to first reach each address, to reconstruct paths.
        Map<String, TracedPathStep> arrivalEdge = new HashMap<>();
        Map<String, Integer> hopOf = new HashMap<>();
        hopOf.put(root, 0);

        Queue<String> frontier = new LinkedList<>();
        frontier.add(root);

        int edgesVisited = 0;
        List<FlaggedExposure> exposures = new ArrayList<>();
        Set<String> exposureRecorded = new HashSet<>();

        while (!frontier.isEmpty()) {
            // Process one full hop level at a time.
            List<String> currentLevel = new ArrayList<>(frontier);
            frontier.clear();
            List<TransactionEdge> edges = edgeRepository
                    .findByFromAddressInOrToAddressIn(currentLevel, currentLevel);

            for (TransactionEdge edge : edges) {
                edgesVisited++;
                for (String[] dir : neighbours(edge, currentLevel)) {
                    String parent = dir[0];
                    String child = dir[1];
                    if (visited.contains(child)) {
                        continue;
                    }
                    int childHop = hopOf.get(parent) + 1;
                    if (childHop > maxHops) {
                        continue;
                    }
                    visited.add(child);
                    hopOf.put(child, childHop);
                    arrivalEdge.put(child, new TracedPathStep(
                            edge.getFromAddress(), edge.getToAddress(), edge.getAmountUsd(),
                            edge.getAsset(), edge.getTxHash(), childHop));
                    frontier.add(child);

                    if (sanctionedSet.contains(child) && !exposureRecorded.contains(child)) {
                        exposureRecorded.add(child);
                        List<TracedPathStep> path = reconstructPath(child, arrivalEdge);
                        double pathAmount = path.stream()
                                .mapToDouble(TracedPathStep::amountUsd).min().orElse(0);
                        exposures.add(new FlaggedExposure(
                                child, entityByAddress.getOrDefault(child, "unknown"),
                                childHop, pathAmount, path));
                    }
                }
            }
        }

        return new TraceResponse(root, maxHops, edgesVisited, visited.size() - 1,
                !exposures.isEmpty(), exposures);
    }

    /** Yields {parent, child} pairs for an edge relative to the addresses we expanded from. */
    private List<String[]> neighbours(TransactionEdge edge, List<String> expandedFrom) {
        Set<String> from = new HashSet<>(expandedFrom);
        List<String[]> out = new ArrayList<>(2);
        if (from.contains(edge.getFromAddress())) {
            out.add(new String[]{edge.getFromAddress(), edge.getToAddress()});
        }
        if (from.contains(edge.getToAddress())) {
            out.add(new String[]{edge.getToAddress(), edge.getFromAddress()});
        }
        return out;
    }

    private List<TracedPathStep> reconstructPath(String target, Map<String, TracedPathStep> arrivalEdge) {
        // Walk arrival edges back toward the root, then reverse to get root -> target order.
        List<TracedPathStep> reversed = new ArrayList<>();
        String cursor = target;
        Set<String> guard = new HashSet<>();
        while (arrivalEdge.containsKey(cursor) && guard.add(cursor)) {
            TracedPathStep step = arrivalEdge.get(cursor);
            reversed.add(step);
            // The "previous" node is whichever endpoint isn't the current cursor.
            cursor = step.fromAddress().equals(cursor) ? step.toAddress() : step.fromAddress();
        }
        List<TracedPathStep> path = new ArrayList<>(reversed);
        java.util.Collections.reverse(path);
        return path;
    }
}
