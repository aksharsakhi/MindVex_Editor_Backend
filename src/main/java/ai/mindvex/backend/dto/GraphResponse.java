package ai.mindvex.backend.dto;

import java.util.List;
import java.util.Map;

/**
 * GraphResponse — Cytoscape.js-compatible graph payload.
 *
 * nodes: [{ data: { id, label, filePath, language } }]
 * edges: [{ data: { id, source, target, type, cycle } }]
 * cycles: list of human-readable cycle descriptions
 */
public record GraphResponse(
        List<CyNode> nodes,
        List<CyEdge> edges,
        List<String> cycles) {

    public record CyNode(CyNodeData data) {
        public record CyNodeData(
                String id,
                String label,
                String filePath,
                String language) {
        }
    }

    public record CyEdge(CyEdgeData data) {
        public record CyEdgeData(
                String id,
                String source,
                String target,
                String type,
                boolean cycle) {
        }
    }
}
