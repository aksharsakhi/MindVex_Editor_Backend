-- ============================================================
-- V14: code_graph schema — file-level dependency edges
-- Derived from SCIP occurrence data by DependencyEngine.
-- ============================================================

CREATE SCHEMA IF NOT EXISTS code_graph;

-- Direct file-to-file dependency edges
-- source_file imports/references a symbol defined in target_file
CREATE TABLE code_graph.file_dependencies (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    repo_url    VARCHAR(1000) NOT NULL,
    source_file VARCHAR(2000) NOT NULL,   -- file that imports/references
    target_file VARCHAR(2000) NOT NULL,   -- file being imported/referenced
    dep_type    VARCHAR(50)  NOT NULL DEFAULT 'reference',  -- 'import' | 'reference'
    CONSTRAINT uq_file_dep UNIQUE (user_id, repo_url, source_file, target_file, dep_type)
);

CREATE INDEX idx_file_dep_source ON code_graph.file_dependencies(user_id, repo_url, source_file);
CREATE INDEX idx_file_dep_target ON code_graph.file_dependencies(user_id, repo_url, target_file);
CREATE INDEX idx_file_dep_repo   ON code_graph.file_dependencies(user_id, repo_url);
