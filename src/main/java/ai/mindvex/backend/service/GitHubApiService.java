package ai.mindvex.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GitHub API Service for Architecture Decision Records
 * 
 * Fetches relevant context from GitHub to enhance ADR generation:
 * - Commits with architectural keywords
 * - Pull requests with design discussions
 * - Issues about architecture decisions
 * - Code review comments on design choices
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GitHubApiService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Keywords that indicate architectural decisions
    private static final Set<String> ARCHITECTURE_KEYWORDS = Set.of(
        "architecture", "design", "refactor", "breaking change", "migration",
        "ADR", "decision", "pattern", "restructure", "technical debt",
        "performance", "scalability", "security", "framework", "library",
        "database", "cache", "API", "microservice", "monolith"
    );

    /**
     * Fetch architectural decision context from GitHub repository
     * 
     * @param repoUrl Repository URL (e.g., https://github.com/owner/repo)
     * @param accessToken GitHub access token (nullable for public repos)
     * @return ArchitectureContext with commits, PRs, issues, and reviews
     */
    public ArchitectureContext fetchArchitectureContext(String repoUrl, String accessToken) {
        try {
            String[] parts = extractOwnerAndRepo(repoUrl);
            if (parts == null) {
                log.warn("[GitHubAPI] Invalid repository URL: {}", repoUrl);
                return new ArchitectureContext();
            }

            String owner = parts[0];
            String repo = parts[1];

            log.info("[GitHubAPI] Fetching architecture context for {}/{}", owner, repo);

            // Fetch data in parallel for efficiency
            List<CommitInfo> commits = fetchArchitecturalCommits(owner, repo, accessToken);
            List<PullRequestInfo> pullRequests = fetchArchitecturalPRs(owner, repo, accessToken);
            List<IssueInfo> issues = fetchArchitecturalIssues(owner, repo, accessToken);

            ArchitectureContext context = new ArchitectureContext();
            context.setCommits(commits);
            context.setPullRequests(pullRequests);
            context.setIssues(issues);

            log.info("[GitHubAPI] Found {} commits, {} PRs, {} issues with architectural context",
                    commits.size(), pullRequests.size(), issues.size());

            return context;

        } catch (Exception e) {
            log.error("[GitHubAPI] Error fetching architecture context: {}", e.getMessage());
            return new ArchitectureContext();
        }
    }

    /**
     * Fetch commits with architectural significance
     */
    private List<CommitInfo> fetchArchitecturalCommits(String owner, String repo, String accessToken) {
        try {
            String url = String.format("https://api.github.com/repos/%s/%s/commits?per_page=100", owner, repo);
            
            HttpHeaders headers = createHeaders(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<List> response = restTemplate.exchange(url, HttpMethod.GET, entity, List.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return ((List<Map<String, Object>>) response.getBody()).stream()
                    .filter(this::isArchitecturalCommit)
                    .limit(20) // Limit to 20 most recent architectural commits
                    .map(this::parseCommitInfo)
                    .collect(Collectors.toList());
            }

        } catch (Exception e) {
            log.warn("[GitHubAPI] Error fetching commits: {}", e.getMessage());
        }

        return Collections.emptyList();
    }

    /**
     * Fetch pull requests with architectural discussions
     */
    private List<PullRequestInfo> fetchArchitecturalPRs(String owner, String repo, String accessToken) {
        try {
            // Fetch both open and closed PRs with architecture-related labels
            String url = String.format(
                "https://api.github.com/repos/%s/%s/pulls?state=all&per_page=50&sort=updated&direction=desc",
                owner, repo
            );

            HttpHeaders headers = createHeaders(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<List> response = restTemplate.exchange(url, HttpMethod.GET, entity, List.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return ((List<Map<String, Object>>) response.getBody()).stream()
                    .filter(this::isArchitecturalPR)
                    .limit(15) // Limit to 15 most relevant PRs
                    .map(this::parsePullRequestInfo)
                    .collect(Collectors.toList());
            }

        } catch (Exception e) {
            log.warn("[GitHubAPI] Error fetching pull requests: {}", e.getMessage());
        }

        return Collections.emptyList();
    }

    /**
     * Fetch issues discussing architectural decisions
     */
    private List<IssueInfo> fetchArchitecturalIssues(String owner, String repo, String accessToken) {
        try {
            // Search for issues with architecture-related keywords
            String searchQuery = String.format(
                "repo:%s/%s is:issue (architecture OR design OR refactor OR \"breaking change\" OR decision OR ADR)",
                owner, repo
            );

            String url = String.format(
                "https://api.github.com/search/issues?q=%s&per_page=15&sort=updated",
                java.net.URLEncoder.encode(searchQuery, "UTF-8")
            );

            HttpHeaders headers = createHeaders(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
                
                if (items != null) {
                    return items.stream()
                        .map(this::parseIssueInfo)
                        .collect(Collectors.toList());
                }
            }

        } catch (Exception e) {
            log.warn("[GitHubAPI] Error fetching issues: {}", e.getMessage());
        }

        return Collections.emptyList();
    }

    /**
     * Check if commit message contains architectural keywords
     */
    private boolean isArchitecturalCommit(Map<String, Object> commit) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> commitData = (Map<String, Object>) commit.get("commit");
            if (commitData != null) {
                String message = (String) commitData.get("message");
                if (message != null) {
                    String messageLower = message.toLowerCase();
                    return ARCHITECTURE_KEYWORDS.stream()
                        .anyMatch(messageLower::contains);
                }
            }
        } catch (Exception e) {
            log.debug("[GitHubAPI] Error checking commit: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Check if PR title/body contains architectural keywords
     */
    private boolean isArchitecturalPR(Map<String, Object> pr) {
        try {
            String title = (String) pr.get("title");
            String body = (String) pr.get("body");
            
            String combined = ((title != null ? title : "") + " " + (body != null ? body : "")).toLowerCase();
            
            return ARCHITECTURE_KEYWORDS.stream()
                .anyMatch(combined::contains);

        } catch (Exception e) {
            log.debug("[GitHubAPI] Error checking PR: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Parse commit data into CommitInfo
     */
    private CommitInfo parseCommitInfo(Map<String, Object> commit) {
        CommitInfo info = new CommitInfo();
        try {
            info.setSha((String) commit.get("sha"));
            
            @SuppressWarnings("unchecked")
            Map<String, Object> commitData = (Map<String, Object>) commit.get("commit");
            if (commitData != null) {
                info.setMessage((String) commitData.get("message"));
                
                @SuppressWarnings("unchecked")
                Map<String, Object> author = (Map<String, Object>) commitData.get("author");
                if (author != null) {
                    info.setAuthor((String) author.get("name"));
                    info.setDate((String) author.get("date"));
                }
            }

            info.setUrl((String) commit.get("html_url"));

        } catch (Exception e) {
            log.debug("[GitHubAPI] Error parsing commit: {}", e.getMessage());
        }
        return info;
    }

    /**
     * Parse pull request data into PullRequestInfo
     */
    private PullRequestInfo parsePullRequestInfo(Map<String, Object> pr) {
        PullRequestInfo info = new PullRequestInfo();
        try {
            info.setNumber(((Number) pr.get("number")).intValue());
            info.setTitle((String) pr.get("title"));
            info.setBody((String) pr.get("body"));
            info.setState((String) pr.get("state"));
            info.setUrl((String) pr.get("html_url"));
            info.setCreatedAt((String) pr.get("created_at"));
            info.setMergedAt((String) pr.get("merged_at"));

            @SuppressWarnings("unchecked")
            Map<String, Object> user = (Map<String, Object>) pr.get("user");
            if (user != null) {
                info.setAuthor((String) user.get("login"));
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> labels = (List<Map<String, Object>>) pr.get("labels");
            if (labels != null) {
                info.setLabels(labels.stream()
                    .map(label -> (String) label.get("name"))
                    .collect(Collectors.toList()));
            }

        } catch (Exception e) {
            log.debug("[GitHubAPI] Error parsing PR: {}", e.getMessage());
        }
        return info;
    }

    /**
     * Parse issue data into IssueInfo
     */
    private IssueInfo parseIssueInfo(Map<String, Object> issue) {
        IssueInfo info = new IssueInfo();
        try {
            info.setNumber(((Number) issue.get("number")).intValue());
            info.setTitle((String) issue.get("title"));
            info.setBody((String) issue.get("body"));
            info.setState((String) issue.get("state"));
            info.setUrl((String) issue.get("html_url"));
            info.setCreatedAt((String) issue.get("created_at"));

            @SuppressWarnings("unchecked")
            Map<String, Object> user = (Map<String, Object>) issue.get("user");
            if (user != null) {
                info.setAuthor((String) user.get("login"));
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> labels = (List<Map<String, Object>>) issue.get("labels");
            if (labels != null) {
                info.setLabels(labels.stream()
                    .map(label -> (String) label.get("name"))
                    .collect(Collectors.toList()));
            }

        } catch (Exception e) {
            log.debug("[GitHubAPI] Error parsing issue: {}", e.getMessage());
        }
        return info;
    }

    /**
     * Create HTTP headers with GitHub authentication
     */
    private HttpHeaders createHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github.v3+json");
        headers.set("User-Agent", "MindVex-Backend");
        
        if (accessToken != null && !accessToken.isBlank()) {
            headers.set("Authorization", "Bearer " + accessToken);
        }
        
        return headers;
    }

    /**
     * Extract owner and repo from GitHub URL
     * 
     * @param repoUrl URL like https://github.com/owner/repo or git@github.com:owner/repo.git
     * @return [owner, repo] or null if invalid
     */
    private String[] extractOwnerAndRepo(String repoUrl) {
        try {
            // Handle HTTPS URLs: https://github.com/owner/repo.git
            if (repoUrl.contains("github.com/")) {
                String path = repoUrl.substring(repoUrl.indexOf("github.com/") + 11);
                path = path.replace(".git", "");
                String[] parts = path.split("/");
                if (parts.length >= 2) {
                    return new String[]{parts[0], parts[1]};
                }
            }
            
            // Handle SSH URLs: git@github.com:owner/repo.git
            if (repoUrl.contains("git@github.com:")) {
                String path = repoUrl.substring(repoUrl.indexOf(":") + 1);
                path = path.replace(".git", "");
                String[] parts = path.split("/");
                if (parts.length >= 2) {
                    return new String[]{parts[0], parts[1]};
                }
            }

        } catch (Exception e) {
            log.warn("[GitHubAPI] Error parsing repo URL: {}", e.getMessage());
        }

        return null;
    }

    // Data classes for GitHub API responses
    
    public static class ArchitectureContext {
        private List<CommitInfo> commits = new ArrayList<>();
        private List<PullRequestInfo> pullRequests = new ArrayList<>();
        private List<IssueInfo> issues = new ArrayList<>();

        public List<CommitInfo> getCommits() { return commits; }
        public void setCommits(List<CommitInfo> commits) { this.commits = commits; }

        public List<PullRequestInfo> getPullRequests() { return pullRequests; }
        public void setPullRequests(List<PullRequestInfo> pullRequests) { this.pullRequests = pullRequests; }

        public List<IssueInfo> getIssues() { return issues; }
        public void setIssues(List<IssueInfo> issues) { this.issues = issues; }

        public boolean isEmpty() {
            return commits.isEmpty() && pullRequests.isEmpty() && issues.isEmpty();
        }
    }

    public static class CommitInfo {
        private String sha;
        private String message;
        private String author;
        private String date;
        private String url;

        // Getters and setters
        public String getSha() { return sha; }
        public void setSha(String sha) { this.sha = sha; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    public static class PullRequestInfo {
        private int number;
        private String title;
        private String body;
        private String state;
        private String author;
        private String createdAt;
        private String mergedAt;
        private String url;
        private List<String> labels = new ArrayList<>();

        // Getters and setters
        public int getNumber() { return number; }
        public void setNumber(int number) { this.number = number; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }

        public String getState() { return state; }
        public void setState(String state) { this.state = state; }

        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }

        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

        public String getMergedAt() { return mergedAt; }
        public void setMergedAt(String mergedAt) { this.mergedAt = mergedAt; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public List<String> getLabels() { return labels; }
        public void setLabels(List<String> labels) { this.labels = labels; }
    }

    public static class IssueInfo {
        private int number;
        private String title;
        private String body;
        private String state;
        private String author;
        private String createdAt;
        private String url;
        private List<String> labels = new ArrayList<>();

        // Getters and setters
        public int getNumber() { return number; }
        public void setNumber(int number) { this.number = number; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }

        public String getState() { return state; }
        public void setState(String state) { this.state = state; }

        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }

        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public List<String> getLabels() { return labels; }
        public void setLabels(List<String> labels) { this.labels = labels; }
    }
}
