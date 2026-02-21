package ai.mindvex.backend.repository;

import ai.mindvex.backend.entity.FileDependency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FileDependencyRepository extends JpaRepository<FileDependency, Long> {

    /** All direct edges for a repo. */
    List<FileDependency> findByUserIdAndRepoUrl(Long userId, String repoUrl);

    /** Direct dependencies of a single file. */
    List<FileDependency> findByUserIdAndRepoUrlAndSourceFile(Long userId, String repoUrl, String sourceFile);

    /** Delete all edges for a repo before rebuilding. */
    @Modifying
    @Query("DELETE FROM FileDependency d WHERE d.userId = :uid AND d.repoUrl = :repo")
    void deleteByUserIdAndRepoUrl(@Param("uid") Long userId, @Param("repo") String repoUrl);

    /**
     * Recursive CTE: returns the full transitive dependency tree rooted at
     * rootFile.
     * Uses PostgreSQL CYCLE clause to detect and exclude circular paths.
     *
     * Result columns: source_file, target_file, depth, is_cycle
     */
    @Query(nativeQuery = true, value = """
            WITH RECURSIVE dep_tree AS (
                SELECT source_file, target_file, 0 AS depth
                FROM code_graph.file_dependencies
                WHERE user_id = :uid AND repo_url = :repo AND source_file = :root
                UNION ALL
                SELECT d.source_file, d.target_file, dt.depth + 1
                FROM code_graph.file_dependencies d
                JOIN dep_tree dt ON d.source_file = dt.target_file
                WHERE dt.depth < :maxDepth
            ) CYCLE target_file SET is_cycle USING cycle_path
            SELECT source_file AS sourceFile,
                   target_file AS targetFile,
                   depth,
                   is_cycle    AS isCycle
            FROM dep_tree
            """)
    List<Object[]> findTransitiveDeps(
            @Param("uid") Long userId,
            @Param("repo") String repoUrl,
            @Param("root") String rootFile,
            @Param("maxDepth") int maxDepth);

    /**
     * All edges for the whole repo (used to build the full graph).
     */
    @Query(nativeQuery = true, value = """
            SELECT source_file AS sourceFile,
                   target_file AS targetFile,
                   dep_type    AS depType
            FROM code_graph.file_dependencies
            WHERE user_id = :uid AND repo_url = :repo
            """)
    List<Object[]> findAllEdgesRaw(
            @Param("uid") Long userId,
            @Param("repo") String repoUrl);
}
