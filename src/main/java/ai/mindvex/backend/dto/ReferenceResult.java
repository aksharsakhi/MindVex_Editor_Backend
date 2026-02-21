package ai.mindvex.backend.dto;

/**
 * A single reference occurrence — used by the /api/graph/references endpoint.
 */
public record ReferenceResult(
        String filePath,
        int startLine,
        int startChar,
        int endLine,
        int endChar,
        String symbol,
        int roleFlags) {
}
