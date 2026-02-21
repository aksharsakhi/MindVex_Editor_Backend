package ai.mindvex.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for code_graph.file_dependencies.
 * Represents a directed dependency edge: sourceFile → targetFile.
 */
@Entity
@Table(schema = "code_graph", name = "file_dependencies")
@Getter
@Setter
@NoArgsConstructor
public class FileDependency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "repo_url", nullable = false, length = 1000)
    private String repoUrl;

    @Column(name = "source_file", nullable = false, length = 2000)
    private String sourceFile;

    @Column(name = "target_file", nullable = false, length = 2000)
    private String targetFile;

    @Column(name = "dep_type", nullable = false, length = 50)
    private String depType = "reference"; // 'import' | 'reference'

    public FileDependency(Long userId, String repoUrl, String sourceFile, String targetFile, String depType) {
        this.userId = userId;
        this.repoUrl = repoUrl;
        this.sourceFile = sourceFile;
        this.targetFile = targetFile;
        this.depType = depType;
    }
}
