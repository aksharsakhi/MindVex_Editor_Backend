package ai.mindvex.backend.service;

import ai.mindvex.backend.entity.FileDependency;
import ai.mindvex.backend.repository.FileDependencyRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * DependencyEngine
 *
 * Derives file-to-file dependency edges from the SCIP occurrence index and
 * stores them in code_graph.file_dependencies.
 *
 * Edge extraction logic:
 * A symbol defined in file A (role_flags & 1 = definition) and referenced
 * in file B (role_flags & 2 = reference) creates an edge B → A
 * (B depends on A).
 *
 * The recursive CTE in FileDependencyRepository is used to compute transitive
 * closures and detect circular dependencies via the PostgreSQL CYCLE clause.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DependencyEngine {

    private final FileDependencyRepository depRepo;
    private final JdbcTemplate jdbc;

    // SCIP role_flag constants
    private static final int ROLE_DEFINITION = 1;
    private static final int ROLE_REFERENCE = 2;

    // ─── Edge Extraction ──────────────────────────────────────────────────────

    /**
     * Derive file→file edges from SCIP data and persist them.
     * Clears existing edges for the repo before rebuilding.
     */
    @Transactional
    public int extractEdges(Long userId, String repoUrl) {
        log.info("[DependencyEngine] Extracting edges for user={} repo={}", userId, repoUrl);

        // Delete stale edges
        depRepo.deleteByUserIdAndRepoUrl(userId, repoUrl);

        // Cross-join occurrences: find symbols defined in one doc, referenced in
        // another
        // role_flags & 1 = definition; role_flags & 2 = reference
        String sql = """
                SELECT DISTINCT
                    ref_doc.relative_uri  AS source_file,
                    def_doc.relative_uri  AS target_file,
                    'reference'           AS dep_type
                FROM code_intelligence.scip_occurrences ref_occ
                JOIN code_intelligence.scip_documents   ref_doc ON ref_doc.id = ref_occ.document_id
                JOIN code_intelligence.scip_occurrences def_occ ON def_occ.symbol = ref_occ.symbol
                JOIN code_intelligence.scip_documents   def_doc ON def_doc.id = def_occ.document_id
                WHERE ref_doc.user_id = ?
                  AND ref_doc.repo_url = ?
                  AND def_doc.user_id  = ?
                  AND def_doc.repo_url = ?
                  AND (ref_occ.role_flags & ?) > 0   -- reference
                  AND (def_occ.role_flags & ?) > 0   -- definition
                  AND ref_doc.id <> def_doc.id        -- cross-file only
                """;

        List<FileDependency> edges = new ArrayList<>();
        jdbc.query(sql,
                rs -> {
                    edges.add(new FileDependency(
                            userId,
                            repoUrl,
                            rs.getString("source_file"),
                            rs.getString("target_file"),
                            rs.getString("dep_type")));
                },
                userId, repoUrl, userId, repoUrl, ROLE_REFERENCE, ROLE_DEFINITION);

        depRepo.saveAll(edges);
        log.info("[DependencyEngine] Saved {} edges for {}", edges.size(), repoUrl);
        return edges.size();
    }

    // ─── Transitive Dependency Tree ───────────────────────────────────────────

    /**
     * Returns the transitive dependency tree for a root file.
     * Uses the recursive CTE with CYCLE detection in FileDependencyRepository.
     *
     * @return map with keys "edges" (List of EdgeRow) and "cycles" (List of String)
     */
    public Map<String, Object> buildTransitiveDeps(
            Long userId, String repoUrl, String rootFile, int maxDepth) {

        List<Object[]> rows = depRepo.findTransitiveDeps(userId, repoUrl, rootFile, maxDepth);

        List<Map<String, Object>> edges = new ArrayList<>();
        List<String> cycles = new ArrayList<>();

        for (Object[] row : rows) {
            String source = (String) row[0];
            String target = (String) row[1];
            int depth = ((Number) row[2]).intValue();
            boolean isCycle = row[3] != null && (Boolean) row[3];

            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("source", source);
            edge.put("target", target);
            edge.put("depth", depth);
            edge.put("cycle", isCycle);
            edges.add(edge);

            if (isCycle) {
                cycles.add(source + " → " + target);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("edges", edges);
        result.put("cycles", cycles);
        return result;
    }

    // ─── Full Repo Graph ──────────────────────────────────────────────────────

    /**
     * Returns all edges for the repo (used by the full graph view).
     */
    public List<Object[]> getAllEdgesRaw(Long userId, String repoUrl) {
        return depRepo.findAllEdgesRaw(userId, repoUrl);
    }
}
