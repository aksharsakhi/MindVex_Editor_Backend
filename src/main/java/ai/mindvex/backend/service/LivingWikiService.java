package ai.mindvex.backend.service;

import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.entity.VectorEmbedding;
import ai.mindvex.backend.repository.FileDependencyRepository;
import ai.mindvex.backend.repository.UserRepository;
import ai.mindvex.backend.repository.VectorEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Living Wiki Generator
 *
 * Uses Gemini to generate module-level descriptions and architecture overviews
 * based on the code intelligence data (file dependencies + embeddings).
 *
 * Produces structured wiki content after every analysis run.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LivingWikiService {

    private final FileDependencyRepository depRepo;
    private final VectorEmbeddingRepository embeddingRepo;
    private final EmbeddingIngestionService embeddingService;
    private final UserRepository userRepository;
    private final GitHubApiService githubApiService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${gemini.api-key:#{null}}")
    private String geminiApiKey;

    /**
     * Generate a module-level wiki overview for a repository.
     * Returns Markdown-formatted content describing the project structure.
     */
    public Map<String, String> generateWiki(Long userId, String repoUrl, Map<String, Object> provider) {
        log.info("[LivingWiki] Generating wiki for user={} repo={}", userId, repoUrl);

        // Gather context from code intelligence tables — these may not exist yet
        List<?> deps = List.of();
        long embeddingCount = 0;
        Map<String, Set<String>> moduleFiles = new LinkedHashMap<>();
        Set<String> allFiles = new HashSet<>();

        try {
            deps = depRepo.findByUserIdAndRepoUrl(userId, repoUrl);
            for (var dep : deps) {
                var d = (ai.mindvex.backend.entity.FileDependency) dep;
                allFiles.add(d.getSourceFile());
                allFiles.add(d.getTargetFile());
                String module = extractModule(d.getSourceFile());
                moduleFiles.computeIfAbsent(module, k -> new LinkedHashSet<>()).add(d.getSourceFile());
            }
        } catch (Exception e) {
            log.warn("[LivingWiki] Could not load dependency graph (table may not exist yet): {}", e.getMessage());
        }

        try {
            embeddingCount = embeddingRepo.countByUserIdAndRepoUrl(userId, repoUrl);
        } catch (Exception e) {
            log.warn("[LivingWiki] Could not load embedding count (table may not exist yet): {}", e.getMessage());
        }

        // ─── Semantic Context Retrieval ─────────────────────────────────────
        // Use embeddings to gather rich semantic context for documentation
        StringBuilder semanticContext = new StringBuilder();
        if (embeddingCount > 0) {
            try {
                log.info("[LivingWiki] Retrieving semantic context via embeddings (count={})", embeddingCount);

                // Multi-aspect semantic search for comprehensive understanding
                // REDUCED queries and chunks to avoid "Payload Too Large" errors
                String[] queries = {
                        "main entry point, startup, initialization",
                        "API endpoints, routes, controllers",
                        "data models, entities, database schema"
                };

                Set<String> seenChunks = new HashSet<>();
                int totalChunks = 0;
                int maxTotalChunks = 10; // Limit total chunks to avoid token overflow
                int maxChunkLength = 500; // Truncate long chunks

                for (String query : queries) {
                    if (totalChunks >= maxTotalChunks) break; // Stop if limit reached
                    
                    try {
                        List<VectorEmbedding> chunks = embeddingService.semanticSearch(userId, repoUrl, query, 2);
                        for (VectorEmbedding chunk : chunks) {
                            if (totalChunks >= maxTotalChunks) break;
                            
                            String chunkId = chunk.getFilePath() + ":" + chunk.getChunkIndex();
                            if (!seenChunks.contains(chunkId)) {
                                seenChunks.add(chunkId);
                                String chunkText = chunk.getChunkText();
                                // Truncate long chunks to save tokens
                                if (chunkText.length() > maxChunkLength) {
                                    chunkText = chunkText.substring(0, maxChunkLength) + "...";
                                }
                                semanticContext.append("\n// ").append(chunk.getFilePath())
                                        .append(" (chunk ").append(chunk.getChunkIndex()).append(")\n")
                                        .append(chunkText).append("\n");
                                totalChunks++;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[LivingWiki] Semantic search failed for query '{}': {}", query, e.getMessage());
                    }
                }

                log.info("[LivingWiki] Retrieved {} unique code chunks (max {}) for semantic context", 
                        totalChunks, maxTotalChunks);
            } catch (Exception e) {
                log.warn("[LivingWiki] Could not retrieve semantic context: {}", e.getMessage());
            }
        }

        // Build a structural summary
        StringBuilder context = new StringBuilder();
        context.append("Repository: ").append(repoUrl).append("\n");
        context.append("Total files: ").append(allFiles.size()).append("\n");
        context.append("Total dependencies: ").append(deps.size()).append("\n");
        context.append("Total code chunks embedded: ").append(embeddingCount).append("\n\n");
        context.append("Module Structure:\n");

        if (moduleFiles.isEmpty()) {
            context.append("(Repository has not been indexed yet — generating documentation from repo URL)\n");
        } else {
            for (var entry : moduleFiles.entrySet()) {
                context.append("- ").append(entry.getKey())
                        .append(": ").append(entry.getValue().size()).append(" files\n");
                entry.getValue().stream().limit(5).forEach(f -> context.append("  - ").append(f).append("\n"));
                if (entry.getValue().size() > 5) {
                    context.append("  - ... and ").append(entry.getValue().size() - 5).append(" more\n");
                }
            }
        }

        // Append semantic code context if available
        if (semanticContext.length() > 0) {
            context.append("\nRelevant Code Samples (from semantic analysis):\n");
            context.append(semanticContext.toString());
        }

        // ─── GitHub Architecture Decision Context ──────────────────────────
        // Fetch architectural decisions from GitHub (commits, PRs, issues)
        String githubToken = getUserGithubToken(userId);
        if (githubToken != null && !githubToken.isBlank()) {
            try {
                log.info("[LivingWiki] Fetching architecture context from GitHub");
                GitHubApiService.ArchitectureContext archContext = 
                    githubApiService.fetchArchitectureContext(repoUrl, githubToken);

                if (!archContext.isEmpty()) {
                    context.append("\n─── GitHub Architecture Decision Records ───\n\n");
                    
                    // Add architectural commits
                    if (!archContext.getCommits().isEmpty()) {
                        context.append("## Architectural Commits\n");
                        context.append("Recent commits with architectural significance:\n\n");
                        archContext.getCommits().stream().limit(10).forEach(commit -> {
                            context.append(String.format("- **%s** (%s)\n",
                                commit.getMessage().split("\n")[0], // First line only
                                commit.getDate() != null ? commit.getDate().substring(0, 10) : "unknown"));
                            context.append(String.format("  Author: %s | URL: %s\n",
                                commit.getAuthor(), commit.getUrl()));
                        });
                        context.append("\n");
                    }

                    // Add architectural pull requests
                    if (!archContext.getPullRequests().isEmpty()) {
                        context.append("## Architectural Pull Requests\n");
                        context.append("Pull requests discussing design decisions:\n\n");
                        archContext.getPullRequests().stream().limit(8).forEach(pr -> {
                            context.append(String.format("- **PR #%d: %s** [%s]\n",
                                pr.getNumber(), pr.getTitle(), pr.getState()));
                            if (!pr.getLabels().isEmpty()) {
                                context.append(String.format("  Labels: %s\n",
                                    String.join(", ", pr.getLabels())));
                            }
                            if (pr.getBody() != null && !pr.getBody().isBlank()) {
                                String description = pr.getBody().length() > 150 
                                    ? pr.getBody().substring(0, 150) + "..." 
                                    : pr.getBody();
                                context.append(String.format("  Description: %s\n", description));
                            }
                            context.append(String.format("  URL: %s\n", pr.getUrl()));
                        });
                        context.append("\n");
                    }

                    // Add architectural issues
                    if (!archContext.getIssues().isEmpty()) {
                        context.append("## Architectural Issues & Discussions\n");
                        context.append("Issues discussing architecture and design:\n\n");
                        archContext.getIssues().stream().limit(8).forEach(issue -> {
                            context.append(String.format("- **Issue #%d: %s** [%s]\n",
                                issue.getNumber(), issue.getTitle(), issue.getState()));
                            if (!issue.getLabels().isEmpty()) {
                                context.append(String.format("  Labels: %s\n",
                                    String.join(", ", issue.getLabels())));
                            }
                            if (issue.getBody() != null && !issue.getBody().isBlank()) {
                                String description = issue.getBody().length() > 150
                                    ? issue.getBody().substring(0, 150) + "..."
                                    : issue.getBody();
                                context.append(String.format("  Description: %s\n", description));
                            }
                            context.append(String.format("  URL: %s\n", issue.getUrl()));
                        });
                        context.append("\n");
                    }

                    log.info("[LivingWiki] Added GitHub architecture context ({} commits, {} PRs, {} issues)",
                        archContext.getCommits().size(), 
                        archContext.getPullRequests().size(),
                        archContext.getIssues().size());
                }

            } catch (Exception e) {
                log.warn("[LivingWiki] Could not fetch GitHub architecture context: {}", e.getMessage());
            }
        } else {
            log.info("[LivingWiki] No GitHub token available, skipping architecture context from GitHub");
        }

        if (provider != null) {
            try {
                String aiResponse = callAiForWiki(context.toString(), provider);
                Map<String, String> files = parseResponse(aiResponse);
                if (files.size() > 1)
                    return files;
                log.warn("[LivingWiki] Provider '{}' returned only 1 file, trying Gemini", provider.get("name"));
            } catch (Exception e) {
                log.warn("[LivingWiki] Provider '{}' failed: {}", provider.get("name"), e.getMessage());
            }
        }

        // Generate with Gemini if API key available
        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            try {
                return parseResponse(callGeminiForWiki(context.toString()));
            } catch (Exception e) {
                log.warn("[LivingWiki] Gemini failed: {}", e.getMessage());
            }
        }

        // Static fallback
        log.info("[LivingWiki] Using static fallback for repo={}", repoUrl);
        return Map.of("README.md", generateFallbackWiki(repoUrl, moduleFiles, allFiles.size(), deps.size()));
    }

    private Map<String, String> parseResponse(String response) {
        if (response == null || response.isBlank()) {
            return Map.of("README.md", "No content generated.");
        }

        // ── Strategy 1: Delimiter format (===FILE: name===) ──────────────────
        // This is the primary format we ask LLMs to use: 100% newline-safe.
        String DELIM = "===FILE:";
        if (response.contains(DELIM)) {
            Map<String, String> files = new LinkedHashMap<>();
            String[] sections = response.split("(?m)^===FILE:");
            for (String section : sections) {
                if (section.isBlank())
                    continue;
                int lineEnd = section.indexOf('\n');
                if (lineEnd < 0)
                    continue;
                String filename = section.substring(0, lineEnd).replace("===", "").trim();
                String content = section.substring(lineEnd + 1);
                // Strip trailing delimiter line or markdown fence if present
                if (content.endsWith("==="))
                    content = content.substring(0, content.lastIndexOf("==="));
                content = content.trim();
                if (!filename.isBlank() && !content.isBlank()) {
                    files.put(filename, content);
                }
            }
            if (files.size() > 1) {
                log.info("[LivingWiki] Parsed {} files via delimiter format", files.size());
                return files;
            }
        }

        // ── Strategy 2: JSON parsing (try several sub-strategies) ────────────
        ObjectMapper mapper = new ObjectMapper();
        String[] candidates = {
                response.trim(),
                // Strip ```json ... ``` fences
                response.trim().replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("```\\s*$", "").trim()
        };
        for (String candidate : candidates) {
            // Find outermost { ... }
            int fb = candidate.indexOf('{');
            int lb = candidate.lastIndexOf('}');
            if (fb >= 0 && lb > fb) {
                try {
                    Map<String, String> result = mapper.readValue(
                            candidate.substring(fb, lb + 1),
                            new TypeReference<Map<String, String>>() {
                            });
                    if (result.size() > 1) {
                        log.info("[LivingWiki] Parsed {} files via JSON", result.size());
                        return result;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        log.warn("[LivingWiki] All parse strategies failed, using raw content as README.md (len={})",
                response.length());
        return Map.of("README.md", response);
    }

    // repairTruncatedJson kept for legacy JSON fallback
    @SuppressWarnings("unused")
    private String repairTruncatedJson(String json) {
        int open = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{')
                    open++;
                else if (c == '}')
                    open--;
            }
        }
        StringBuilder sb = new StringBuilder(json);
        if (inString)
            sb.append('"');
        for (int i = 0; i < open; i++)
            sb.append('}');
        return sb.toString();
    }

    /**
     * Generate a description for a specific module/directory.
     */
    public String describeModule(Long userId, String repoUrl, String modulePath, Map<String, Object> provider) {
        // Find relevant code chunks
        List<VectorEmbedding> chunks = embeddingService.semanticSearch(
                userId, repoUrl, "What does the " + modulePath + " module do?", 5);

        if (!chunks.isEmpty()) {
            String codeContext = chunks.stream()
                    .map(c -> "// " + c.getFilePath() + " (chunk " + c.getChunkIndex() + ")\n" + c.getChunkText())
                    .collect(Collectors.joining("\n\n"));

            if (provider != null) {
                try {
                    return callAiForModuleDescription(modulePath, codeContext, provider);
                } catch (Exception e) {
                    log.warn("[LivingWiki] Provider failed for module description: {}", e.getMessage());
                }
            }

            if (geminiApiKey != null && !geminiApiKey.isBlank()) {
                try {
                    return callGeminiForModuleDescription(modulePath, codeContext);
                } catch (Exception e) {
                    log.warn("[LivingWiki] Gemini failed for module description: {}", e.getMessage());
                }
            }

            return "## " + modulePath + "\n\nThis module contains " + chunks.size() + " code chunks.\n\n"
                    + "Files:\n"
                    + chunks.stream().map(c -> "- " + c.getFilePath()).distinct().collect(Collectors.joining("\n"));
        }

        // Fallback: use file dependency data to describe the module
        var deps = depRepo.findByUserIdAndRepoUrl(userId, repoUrl);
        var moduleFiles = deps.stream()
                .flatMap(d -> java.util.stream.Stream.of(d.getSourceFile(), d.getTargetFile()))
                .filter(f -> f.startsWith(modulePath) || f.contains("/" + modulePath + "/") || f.contains(modulePath))
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        if (moduleFiles.isEmpty()) {
            return "No data found for module: **" + modulePath
                    + "**. Make sure the repository has been analyzed via Analytics → Mine first.";
        }

        // Build context from dependency data
        StringBuilder context = new StringBuilder();
        context.append("Module: ").append(modulePath).append("\n");
        context.append("Files found: ").append(moduleFiles.size()).append("\n\n");
        for (String file : moduleFiles.stream().limit(15).collect(Collectors.toList())) {
            context.append("- ").append(file).append("\n");
            var fileDeps = deps.stream()
                    .filter(d -> d.getSourceFile().equals(file))
                    .map(d -> "  imports: " + d.getTargetFile())
                    .limit(3)
                    .collect(Collectors.toList());
            fileDeps.forEach(fd -> context.append("  ").append(fd).append("\n"));
        }

        if (provider != null) {
            try {
                return callAiForModuleDescription(modulePath, context.toString(), provider);
            } catch (Exception e) {
                log.warn("[LivingWiki] Provider fallback failed for module: {}", e.getMessage());
            }
        }

        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            try {
                return callGeminiForModuleDescription(modulePath, context.toString());
            } catch (Exception e) {
                log.warn("[LivingWiki] Gemini fallback failed for module: {}", e.getMessage());
            }
        }

        return "## " + modulePath + "\n\nThis module contains " + moduleFiles.size() + " files.\n\n"
                + "Files:\n"
                + moduleFiles.stream().limit(20).map(f -> "- `" + f + "`").collect(Collectors.joining("\n"));
    }

    // ─── General AI Provider Calls
    // ───────────────────────────────────────────────────

    private String callAiForWiki(String structureContext, Map<String, Object> provider) {
        String prompt = """
                You are a senior technical documentation engineer specializing in industrial-grade codebase documentation.
                Generate a comprehensive, deep-dive documentation suite for this repository.

                For EACH file, output it in this EXACT format:
                ===FILE: <filename>===
                <file content here>

                Files to generate:
                
                1. README.md — Comprehensive project documentation with the following REQUIRED sections:
                   # Project Title
                   - Clear, descriptive title as H1 header
                   - 1-2 sentence description of what the project does, its purpose, and problem it solves
                   
                   ## Table of Contents
                   - Anchor links to all major sections for quick navigation
                   
                   ## Features
                   - Bulleted list of key features and functionality
                   - Highlight what makes this project stand out
                   
                   ## Tech Stack
                   - List technologies with versions (e.g., Java 21, Spring Boot 3.2.1, React 18)
                   - Include frameworks, libraries, and tools used
                   
                   ## Installation and Setup
                   ### Prerequisites
                   - Required software, libraries, OS versions
                   - System requirements
                   
                   ### Installation Steps
                   - Step-by-step commands to clone repo
                   - Dependency installation commands (npm install, mvn install, etc.)
                   
                   ### Configuration
                   - Environment variables needed
                   - Configuration files to set up
                   - Database setup if applicable
                   
                   ## Usage Examples
                   - Clear instructions with code snippets
                   - Command-line examples
                   - Expected output descriptions
                   
                   ## Project Structure
                   - Brief overview of main directories and their purpose
                   - ASCII tree of key folders (do NOT hallucinate, use actual structure provided)
                   
                   ## Contributing
                   - Guidelines for bug reports
                   - Pull request process
                   - Coding standards
                   
                   ## License
                   - Clear license statement (e.g., MIT, Apache 2.0)
                   
                   ## Credits and Acknowledgments
                   - Contributors and team members
                   - Third-party libraries and resources used
                   
                   ## Support
                   - How to get help (issue tracker, discussions)
                   - Contact information if applicable
                   
                   ## Project Status
                   - Current development stage (active, maintenance, etc.)
                   - Roadmap of planned features (if applicable)
                   
                   CRITICAL: Base ALL content on the actual repository structure provided below. DO NOT invent features, dependencies, or technologies that aren't evident in the structure. If information is missing, state "To be documented" rather than hallucinating.
                
                2. adr.md — Architecture Decision Records (ADRs)
                   
                   **CRITICAL: Use the GitHub Architecture Decision Records section below (if provided) to create factual ADRs based on actual commits, pull requests, and issues.**
                   
                   Document at least 5-10 key architectural decisions using this format for EACH decision:
                   
                   ### ADR-###: [Decision Title]
                   
                   **Status:** Accepted | Proposed | Deprecated | Superseded
                   
                   **Context:**
                   - What is the situation/problem requiring a decision?
                   - What constraints exist (technical, business, time)?
                   - When was this decision made? (Use GitHub commit/PR dates if available)
                   - Who proposed it? (Use GitHub authors if available)
                   
                   **Decision:**
                   - What was decided? Be specific and concrete.
                   - What alternatives were considered?
                   - Why was this chosen over alternatives?
                   
                   **Consequences:**
                   - **Positive:**
                     - Benefits gained
                     - Non-functional requirements addressed (security, performance, scalability)
                   - **Negative:**
                     - Trade-offs accepted
                     - Technical debt incurred
                     - Maintenance overhead
                   - **Risks:**
                     - Potential future issues
                     - Migration challenges
                   
                   **References:**
                   - Link to GitHub commits, PRs, or issues if mentioned in the GitHub ADR section
                   - Link to documentation or RFCs
                   
                   ---
                   
                   **When to create an ADR (include these if evident in GitHub history):**
                   1. Multiple technical options were considered
                   2. Decision impacts future development (breaking changes, migrations)
                   3. Decision affects non-functional requirements (security, performance, scalability)
                   4. Decision affects multiple teams or systems
                   5. Framework/library choice or version upgrade
                   6. Database schema changes or migration
                   7. API design or breaking changes
                   8. Architecture pattern adoption (microservices, event-driven, etc.)
                   
                   **Example ADR Topics to Look For:**
                   - Framework choice (e.g., "Why React over Vue", "Why Spring Boot over Express")
                   - Database decisions (e.g., "Migration from MySQL to PostgreSQL")
                   - Architecture patterns (e.g., "Adopting microservices", "Implementing CQRS")
                   - Security implementations (e.g., "OAuth2 vs JWT authentication")
                   - Performance optimizations (e.g., "Adding Redis cache layer")
                   - Breaking changes (e.g., "API versioning strategy", "Refactoring to REST from GraphQL")
                   
                   IMPORTANT: Base ADRs on actual evidence from:
                   - GitHub commits with keywords like "refactor", "breaking change", "migration", "architecture"
                   - GitHub PRs discussing design decisions
                   - GitHub issues about architectural choices
                   - Code structure patterns visible in the repository
                   
                   If no GitHub data is available, infer decisions from code structure only. DO NOT hallucinate decisions.
                
                
                3. api-reference.md — API documentation. For endpoints/services evident in the code, include base URL, auth, parameter tables, example requests/responses, and error codes.
                
                4. architecture.md — System design documentation. Describe design patterns visible in the code, state management, security model, and data flow based on actual implementation.
                
                5. documentation-health.md — Health report analyzing coverage, clarity, and consistency. Provide a health score (X out of 100) based on actual code analysis.
                
                6. api-descriptions.json — Schema-compliant JSON of all endpoints found in controllers/routes.
                
                7. doc_snapshot.json — Project statistics in JSON format:
                   {
                     "project_stats": {
                       "files": <actual count>,
                       "lines_of_code": <estimate based on file count>,
                       "dependencies": <actual dependency count>
                     },
                     "module_counts": {
                       "module_name": <file count>,
                       ...
                     },
                     "health_tier": "green|yellow|red"
                   }
                
                8. tree.txt — Professional ASCII tree of the repository structure (use actual structure, do not hallucinate).
                
                9. tree.json — Structured hierarchical map:
                   {
                     "name": "root",
                     "type": "directory",
                     "children": [...]
                   }
                
                10. architecture-graph.json — Interactive graph visualization based on actual module relationships:
                    {
                      "nodes": [{"id": "module_name", "label": "Module Name", "type": "module"}],
                      "edges": [{"source": "parent", "target": "child", "label": "contains"}]
                    }

                REQUIREMENTS:
                - Content must be FACTUAL and based on the provided repository structure
                - Avoid placeholders like "Coming soon" - use "To be documented" if info is unavailable
                - For README.md, ensure ALL 10+ sections are included
                - Use professional, industrial-grade terminology
                - For diagrams, use clean, well-aligned ASCII art based on actual structure
                - DO NOT wrap in markdown code blocks. Output starts with ===FILE: README.md===

                Repository Structure:
                """
                + structureContext;

        return executeAiCall(prompt, provider);
    }

    private String callAiForModuleDescription(String modulePath, String codeContext, Map<String, Object> provider) {
        String prompt = """
                Analyze the following code from the '%s' module and generate a concise description.
                Include: what it does, key classes/functions, and how it relates to other parts of the codebase.

                Code:
                %s
                """.formatted(modulePath, codeContext.length() > 4000 ? codeContext.substring(0, 4000) : codeContext);

        return executeAiCall(prompt, provider);
    }

    @SuppressWarnings("unchecked")
    private String executeAiCall(String prompt, Map<String, Object> provider) {
        String providerName = (String) provider.get("name");
        String model = (String) provider.get("model");
        String apiKey = (String) provider.get("apiKey");
        String baseUrl = (String) provider.get("baseUrl");

        if ("Ollama".equals(providerName)) {
            String url = (baseUrl != null && !baseUrl.isEmpty() ? baseUrl : "http://localhost:11434") + "/api/chat";
            Map<String, Object> request = Map.of(
                    "model", model != null ? model : "llama3",
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "stream", false);
            return extractReply(restTemplate.postForEntity(url, request, Map.class), providerName);

        } else if ("Anthropic".equals(providerName)) {
            String url = "https://api.anthropic.com/v1/messages";
            Map<String, Object> request = Map.of(
                    "model", model != null ? model : "claude-3-5-sonnet-20240620",
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "max_tokens", 4096);
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");
            return extractReply(
                    restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(request, headers), Map.class),
                    providerName);

        } else if ("Groq".equals(providerName) || "OpenAI".equals(providerName) || "XAI".equals(providerName)
                || "LMStudio".equals(providerName)) {
            // Determine correct URL first (before using baseUrl)
            String url;
            if ("Groq".equals(providerName))
                url = "https://api.groq.com/openai/v1/chat/completions";
            else if ("XAI".equals(providerName))
                url = "https://api.x.ai/v1/chat/completions";
            else if ("OpenAI".equals(providerName))
                url = "https://api.openai.com/v1/chat/completions";
            else if ("LMStudio".equals(providerName))
                url = (baseUrl != null && !baseUrl.isEmpty() ? baseUrl : "http://localhost:1234")
                        + "/v1/chat/completions";
            else
                url = (baseUrl != null && !baseUrl.isEmpty() ? baseUrl : "http://localhost:11434")
                        + "/v1/chat/completions";

            // Null-safe model
            String safeModel = (model != null && !model.isBlank()) ? model : "llama3";
            Map<String, Object> request = new java.util.LinkedHashMap<>();
            request.put("model", safeModel);
            request.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            request.put("max_tokens", 8000); // Must be large enough for full JSON
            request.put("temperature", 0.3); // Lower temp = more predictable JSON output
            HttpHeaders headers = new HttpHeaders();
            if (apiKey != null && !apiKey.isBlank())
                headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            return extractReply(
                    restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(request, headers), Map.class),
                    providerName);
        }

        if ("Google".equals(providerName) || "Gemini".equals(providerName)) {
            // Route through Gemini with the supplied apiKey
            String key = apiKey != null && !apiKey.isBlank() ? apiKey : geminiApiKey;
            if (key == null || key.isBlank())
                throw new RuntimeException("Google/Gemini API key not configured");
            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + (model != null ? model : "gemini-2.0-flash") + ":generateContent?key=" + key;
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers),
                    Map.class);
            @SuppressWarnings("unchecked")
            var candidates = (List<Map<String, Object>>) resp.getBody().get("candidates");
            @SuppressWarnings("unchecked")
            var parts2 = (List<Map<String, Object>>) ((Map<String, Object>) candidates.get(0).get("content"))
                    .get("parts");
            return (String) parts2.get(0).get("text");
        }

        throw new RuntimeException("Unsupported AI Provider: " + providerName);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private String extractReply(ResponseEntity<Map> response, String providerName) {
        try {
            Map<String, Object> body = response.getBody();
            if (body == null)
                throw new RuntimeException("Empty response body from " + providerName);

            if ("Ollama".equals(providerName)) {
                return (String) ((Map<String, Object>) body.get("message")).get("content");
            } else if ("Anthropic".equals(providerName)) {
                return (String) ((Map<String, Object>) ((List<?>) body.get("content")).get(0)).get("text");
            } else { // OpenAI-like schemas (Groq, OpenAI, XAI, LMStudio)
                return (String) ((Map<String, Object>) ((Map<String, Object>) ((List<?>) body
                        .get("choices")).get(0)).get("message")).get("content");
            }
        } catch (Exception e) {
            log.error("[LivingWiki] Failed to extract reply from {}: {}", providerName, e.getMessage());
            throw new RuntimeException("Failed to parse " + providerName + " response: " + e.getMessage());
        }
    }

    // ─── Gemini Legacy Defaults
    // ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String callGeminiForWiki(String structureContext) {
        String prompt = """
                You are a senior technical documentation engineer. Generate industrial-grade documentation files for this repository.

                For EACH file, use this EXACT format:
                ===FILE: <filename>===
                <file content here>

                Files to generate:
                
                1. README.md — Comprehensive project documentation with these REQUIRED sections:
                   # Project Title
                   - Clear, descriptive title
                   - 1-2 sentence description of purpose and problem solved
                   
                   ## Table of Contents
                   - Anchor links to all major sections
                   
                   ## Features
                   - Bulleted list of key features
                   
                   ## Tech Stack
                   - Technologies with versions (e.g., Java 21, Spring Boot 3.2.1)
                   
                   ## Installation and Setup
                   ### Prerequisites
                   - Required software and versions
                   ### Installation Steps
                   - Step-by-step clone and dependency installation
                   ### Configuration
                   - Environment variables and config files
                   
                   ## Usage Examples
                   - Code snippets and command examples
                   
                   ## Project Structure
                   - Overview of main directories (use actual structure provided, DO NOT hallucinate)
                   
                   ## Contributing
                   - Guidelines for contributions
                   
                   ## License
                   - License statement
                   
                   ## Credits and Acknowledgments
                   - Contributors and third-party libraries
                   
                   ## Support
                   - How to get help
                   
                   ## Project Status
                   - Development stage and roadmap
                   
                   CRITICAL: Base content on actual repository structure. DO NOT invent features or technologies. Use "To be documented" if info is missing.
                
                2. adr.md — Architecture Decision Records
                   **USE GitHub Architecture Decision Records section (if provided) to create factual ADRs from actual commits/PRs/issues.**
                   
                   Format each ADR as:
                   ### ADR-###: [Decision Title]
                   **Status:** Accepted|Proposed|Deprecated
                   **Context:** Problem, constraints, when/who (use GitHub data if available)
                   **Decision:** What was decided, why chosen over alternatives
                   **Consequences:**
                   - Positive: Benefits, NFR improvements (security, performance, scalability)
                   - Negative: Trade-offs, technical debt, maintenance overhead
                   - Risks: Future issues, migration challenges
                   **References:** Link to GitHub commits/PRs/issues if mentioned
                   
                   Include ADRs for: framework choice, database decisions, architecture patterns, security implementations, performance optimizations, breaking changes
                   Base on GitHub commits/PRs/issues with keywords: refactor, breaking change, migration, architecture, decision
                   If no GitHub data, infer from code structure only. DO NOT hallucinate.
                   
                3. api-reference.md — API documentation for endpoints found in code
                4. architecture.md — System design based on actual implementation
                5. documentation-health.md — Health score (X out of 100) based on code analysis
                6. api-descriptions.json — Schema-compliant endpoint JSON from actual controllers
                7. doc_snapshot.json — {"project_stats": {"files": <count>, "lines_of_code": <estimate>, "dependencies": <count>}, "module_counts": {...}, "health_tier": "green|yellow|red"}
                8. tree.txt — ASCII tree of actual structure (do not hallucinate)
                9. tree.json — {"name": "root", "type": "directory", "children": [...]}
                10. architecture-graph.json — {"nodes": [...], "edges": [...]} based on actual module relationships

                REQUIREMENTS:
                - Content must be FACTUAL based on provided structure
                - Avoid hallucination - use "To be documented" when uncertain
                - README.md must include ALL sections listed above
                - DO NOT wrap in markdown code blocks

                Repository Structure:
                """
                + structureContext;

        return callGemini(prompt);
    }

    @SuppressWarnings("unchecked")
    private String callGeminiForModuleDescription(String modulePath, String codeContext) {
        String prompt = """
                Analyze the following code from the '%s' module and generate a concise description.
                Include: what it does, key classes/functions, and how it relates to other parts of the codebase.

                Code:
                %s
                """.formatted(modulePath, codeContext.length() > 4000 ? codeContext.substring(0, 4000) : codeContext);

        return callGemini(prompt);
    }

    @SuppressWarnings("unchecked")
    private String callGemini(String prompt) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="
                + geminiApiKey;

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", prompt)))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        var candidates = (List<Map<String, Object>>) response.getBody().get("candidates");
        if (candidates != null && !candidates.isEmpty()) {
            var content = (Map<String, Object>) candidates.get(0).get("content");
            var parts = (List<Map<String, Object>>) content.get("parts");
            if (parts != null && !parts.isEmpty()) {
                return (String) parts.get(0).get("text");
            }
        }

        return "Could not generate wiki content.";
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private String extractModule(String filePath) {
        String[] parts = filePath.split("[/\\\\]");
        if (parts.length <= 1)
            return "(root)";
        return parts[0]; // top-level directory
    }

    private String generateFallbackWiki(String repoUrl, Map<String, Set<String>> modules, int totalFiles,
            int totalDeps) {
        StringBuilder wiki = new StringBuilder();
        
        // Extract project name from repo URL
        String projectName = repoUrl.substring(repoUrl.lastIndexOf('/') + 1).replace(".git", "");
        
        wiki.append("# ").append(projectName).append("\n\n");
        wiki.append("> A comprehensive software project managed and analyzed by MindVex\n\n");
        
        wiki.append("## Table of Contents\n\n");
        wiki.append("- [Overview](#overview)\n");
        wiki.append("- [Features](#features)\n");
        wiki.append("- [Tech Stack](#tech-stack)\n");
        wiki.append("- [Installation and Setup](#installation-and-setup)\n");
        wiki.append("- [Usage Examples](#usage-examples)\n");
        wiki.append("- [Project Structure](#project-structure)\n");
        wiki.append("- [Contributing](#contributing)\n");
        wiki.append("- [License](#license)\n");
        wiki.append("- [Support](#support)\n");
        wiki.append("- [Project Status](#project-status)\n\n");
        
        wiki.append("## Overview\n\n");
        wiki.append("**Repository:** ").append(repoUrl).append("\n\n");
        wiki.append("This project contains **").append(totalFiles).append(" files** across **")
                .append(modules.size()).append(" modules** with **")
                .append(totalDeps).append(" dependency relationships**.\n\n");
        
        wiki.append("## Features\n\n");
        wiki.append("- ✅ Modular architecture with ").append(modules.size()).append(" distinct modules\n");
        wiki.append("- ✅ Comprehensive codebase with ").append(totalFiles).append(" source files\n");
        wiki.append("- ✅ Well-defined dependency relationships (").append(totalDeps).append(" connections)\n");
        wiki.append("- ✅ Managed and analyzed by MindVex intelligent code analysis platform\n\n");
        
        wiki.append("## Tech Stack\n\n");
        wiki.append("*To be documented* - Please run full analysis to detect technologies and frameworks used in this project.\n\n");
        
        wiki.append("## Installation and Setup\n\n");
        wiki.append("### Prerequisites\n\n");
        wiki.append("*To be documented* - Prerequisites will be identified after analyzing package managers and build files.\n\n");
        wiki.append("### Installation Steps\n\n");
        wiki.append("```bash\n");
        wiki.append("# Clone the repository\n");
        wiki.append("git clone ").append(repoUrl).append("\n");
        wiki.append("cd ").append(projectName).append("\n\n");
        wiki.append("# Install dependencies\n");
        wiki.append("# (Command will be identified based on project type)\n");
        wiki.append("```\n\n");
        wiki.append("### Configuration\n\n");
        wiki.append("*To be documented* - Configuration details will be extracted from config files during analysis.\n\n");
        
        wiki.append("## Usage Examples\n\n");
        wiki.append("*To be documented* - Usage examples will be generated based on entry points and API endpoints found in the code.\n\n");
        
        wiki.append("## Project Structure\n\n");
        wiki.append("The project is organized into the following modules:\n\n");
        
        for (var entry : modules.entrySet()) {
            wiki.append("### ").append(entry.getKey()).append("\n\n");
            wiki.append("Contains ").append(entry.getValue().size()).append(" files:\n\n");
            entry.getValue().stream().limit(10).forEach(f -> wiki.append("- `").append(f).append("`\n"));
            if (entry.getValue().size() > 10) {
                wiki.append("- *... and ").append(entry.getValue().size() - 10).append(" more*\n");
            }
            wiki.append("\n");
        }
        
        wiki.append("## Contributing\n\n");
        wiki.append("Contributions are welcome! Please follow these guidelines:\n\n");
        wiki.append("1. Fork the repository\n");
        wiki.append("2. Create a feature branch (`git checkout -b feature/amazing-feature`)\n");
        wiki.append("3. Commit your changes (`git commit -m 'Add amazing feature'`)\n");
        wiki.append("4. Push to the branch (`git push origin feature/amazing-feature`)\n");
        wiki.append("5. Open a Pull Request\n\n");
        
        wiki.append("## License\n\n");
        wiki.append("*To be documented* - License information will be extracted from LICENSE file if present.\n\n");
        
        wiki.append("## Credits and Acknowledgments\n\n");
        wiki.append("- **Analysis Platform**: [MindVex](https://github.com/hariPrasathK-Dev/MindVex_Editor_Frontend)\n");
        wiki.append("- **Contributors**: To be documented\n");
        wiki.append("- **Third-party Libraries**: To be identified during dependency analysis\n\n");
        
        wiki.append("## Support\n\n");
        wiki.append("For questions and support:\n\n");
        wiki.append("- 📝 Create an issue in the [issue tracker](").append(repoUrl.replace(".git", "/issues")).append(")\n");
        wiki.append("- 💬 Check existing discussions for common questions\n\n");
        
        wiki.append("## Project Status\n\n");
        wiki.append("🔄 **Active Development** - This project is being actively analyzed and documented.\n\n");
        wiki.append("### Roadmap\n\n");
        wiki.append("- [ ] Complete dependency analysis\n");
        wiki.append("- [ ] Generate comprehensive API documentation\n");
        wiki.append("- [ ] Identify and document all tech stack components\n");
        wiki.append("- [ ] Create architecture diagrams\n");
        wiki.append("- [ ] Document configuration and deployment procedures\n\n");
        wiki.append("---\n\n");
        wiki.append("*This documentation was auto-generated by MindVex. For comprehensive documentation, please run a full Living Wiki generation with an AI provider configured.*\n");
        
        return wiki.toString();
    }

    /**
     * Fetch user's GitHub access token from the database.
     * Returns null if user not found or token not set.
     * 
     * @param userId User ID
     * @return GitHub access token or null
     */
    private String getUserGithubToken(Long userId) {
        return userRepository.findById(userId)
                .map(User::getGithubAccessToken)
                .orElse(null);
    }
}
