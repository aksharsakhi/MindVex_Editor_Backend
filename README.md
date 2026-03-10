<h1 align="center">CodeNexus Backend — Intelligent Codebase Analysis API</h1>

<p align="center">
  Spring Boot backend powering CodeNexus with code intelligence, git analytics, AI documentation, and semantic search.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Spring_Boot-3.2.1-green?logo=springboot" />
  <img src="https://img.shields.io/badge/Java-17-orange?logo=openjdk" />
  <img src="https://img.shields.io/badge/PostgreSQL-15-blue?logo=postgresql" />
  <img src="https://img.shields.io/badge/pgvector-enabled-purple" />
  <img src="https://img.shields.io/badge/License-MIT-green" />
</p>

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Environment Variables](#environment-variables)
- [Database Schema](#database-schema)
- [API Reference](#api-reference)
- [Authentication & Security](#authentication--security)
- [Core Services](#core-services)
- [Async Job System](#async-job-system)
- [WebSocket API](#websocket-api)
- [Docker Deployment](#docker-deployment)
- [Configuration Profiles](#configuration-profiles)
- [Database Migrations](#database-migrations)
- [Troubleshooting](#troubleshooting)
- [License](#license)

---

## Overview

The **CodeNexus Backend** is a Spring Boot REST API that provides the intelligence layer for the CodeNexus AI-powered code editor. It handles:

- **Authentication** — JWT-based auth with GitHub OAuth2 integration
- **SCIP Code Intelligence** — Language-agnostic hover info, find-all-references, and go-to-definition via SCIP index parsing
- **Dependency Graph** — File-level dependency extraction from SCIP data with recursive transitive closure and cycle detection
- **Git Analytics** — Repository mining via JGit for commit history, file churn metrics, hotspot detection, and evolutionary blame
- **Vector Embeddings** — Semantic code chunking with Gemini embeddings stored in PostgreSQL pgvector for similarity search
- **Living Wiki** — AI-generated documentation (README, ADR, API Reference) using Gemini with hallucination prevention
- **AI Code Reasoning** — Deep architectural analysis detecting design patterns, anti-patterns, and refactoring suggestions
- **MCP (Model Context Protocol)** — Standardized interface for AI assistants to access code intelligence tools
- **Real-time Updates** — WebSocket (STOMP/SockJS) for live dependency graph construction progress

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                    Spring Boot Application (8080)                  │
│                                                                    │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐ │
│  │    Auth     │  │    SCIP    │  │   Graph    │  │ Analytics  │ │
│  │ Controller  │  │ Controller │  │ Controller │  │ Controller │ │
│  └──────┬─────┘  └──────┬─────┘  └──────┬─────┘  └──────┬─────┘ │
│         │               │               │               │        │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │                     Service Layer                           │  │
│  │  ┌──────────┐  ┌───────────┐  ┌─────────────┐             │  │
│  │  │UserService│  │ScipIngest │  │DependencyEng│             │  │
│  │  │  + JWT    │  │  Service  │  │    ine      │             │  │
│  │  └──────────┘  └───────────┘  └─────────────┘             │  │
│  │  ┌──────────┐  ┌───────────┐  ┌─────────────┐             │  │
│  │  │Embedding │  │LivingWiki │  │ JGitMining  │             │  │
│  │  │Ingestion │  │  Service  │  │  Service    │             │  │
│  │  └──────────┘  └───────────┘  └─────────────┘             │  │
│  │  ┌──────────┐  ┌───────────┐  ┌─────────────┐             │  │
│  │  │CodeReason│  │ GitHubApi │  │   Churn     │             │  │
│  │  │ingEngine │  │  Service  │  │Calculation  │             │  │
│  │  └──────────┘  └───────────┘  └─────────────┘             │  │
│  └────────────────────────────────────────────────────────────┘  │
│                              │                                    │
│  ┌───────────────────────────▼────────────────────────────────┐  │
│  │              JPA Repositories + Flyway Migrations           │  │
│  └───────────────────────────┬────────────────────────────────┘  │
└──────────────────────────────┼───────────────────────────────────┘
                               │
              ┌────────────────▼────────────────┐
              │     PostgreSQL 15 + pgvector     │
              │                                  │
              │  public.*           (users, jobs) │
              │  code_intelligence.*(SCIP, vectors)│
              │  code_graph.*       (dependencies) │
              │  git_analytics.*    (commits, churn)│
              └──────────────────────────────────┘
```

---

## Tech Stack

| Category | Technology | Version |
|---|---|---|
| **Framework** | Spring Boot | 3.2.1 |
| **Language** | Java | 17 |
| **Build Tool** | Maven | 3.9+ |
| **Database** | PostgreSQL | 15+ |
| **Vector Search** | pgvector extension | — |
| **ORM** | Spring Data JPA + Hibernate | — |
| **Migrations** | Flyway | 10.20.1 |
| **Authentication** | JWT (jjwt 0.12.3) + Spring Security | — |
| **OAuth2** | GitHub OAuth2 (Spring Security) | — |
| **Git Operations** | JGit | 6.9.0 |
| **Code Intelligence** | SCIP Protobuf parsing | protobuf-java 4.29.3 |
| **AI / Embeddings** | Google Gemini API | — |
| **WebSocket** | Spring WebSocket + STOMP + SockJS | — |
| **API Documentation** | SpringDoc OpenAPI (Swagger UI) | 2.3.0 |
| **Environment** | spring-dotenv | 4.0.0 |
| **Containerization** | Docker (multi-stage Alpine) | — |

---

## Project Structure

```
src/main/java/ai/mindvex/backend/
├── MindvexBackendApplication.java     # Spring Boot entry point
├── config/
│   ├── CorsConfig.java                # CORS policy (origins, methods, headers)
│   ├── SecurityConfig.java            # Security filter chain, OAuth2, JWT filter
│   └── WebSocketConfig.java           # STOMP/SockJS WebSocket configuration
├── controller/
│   ├── AuthController.java            # POST /api/auth/register, /login
│   ├── UserController.java            # GET /api/users/me, GitHub connection
│   ├── RepositoryHistoryController.java # Repository history CRUD
│   ├── RepositoryCloneController.java # POST /api/repositories/clone
│   ├── ScipController.java            # SCIP upload, hover, job status
│   ├── GraphController.java           # Dependency graph, references, stats
│   ├── AnalyticsController.java       # Git mining, hotspots, blame
│   ├── McpController.java             # MCP tools (search, deps, wiki, chat)
│   ├── ReasoningController.java       # AI code reasoning analysis
│   ├── SettingsController.java        # Configured providers, health check
│   ├── GitProxyController.java        # CORS proxy for GitHub API
│   └── WebSocketGraphController.java  # Real-time graph updates
├── dto/
│   ├── AuthResponse.java              # JWT + refresh token response
│   ├── RegisterRequest.java           # Registration payload
│   ├── LoginRequest.java              # Login payload
│   ├── UserResponse.java              # User profile response
│   ├── RepositoryHistoryRequest.java  # Repo history entry
│   ├── RepositoryHistoryResponse.java
│   ├── IndexJobResponse.java          # Async job status
│   ├── HoverResponse.java             # SCIP hover data
│   ├── GraphResponse.java             # Cytoscape.js nodes + edges
│   ├── GraphNodeDto.java              # Graph node
│   ├── GraphEdgeDto.java              # Graph edge
│   ├── GraphUpdateMessage.java        # WebSocket update
│   ├── ReferenceResult.java           # Symbol occurrence
│   ├── HotspotResponse.java           # File churn hotspot
│   ├── WeeklyChurnResponse.java       # Weekly trend data
│   ├── BlameLineResponse.java         # Line-level blame
│   ├── ExtractedEndpoint.java         # Parsed API endpoint
│   ├── EndpointParameter.java         # Endpoint parameter
│   ├── ReasoningResultDto.java        # AI reasoning output
│   └── ErrorResponse.java             # Standard error
├── entity/
│   ├── User.java                      # users table
│   ├── RepositoryHistory.java         # repository_history table
│   ├── IndexJob.java                  # index_jobs table (async queue)
│   ├── ScipDocument.java              # scip_documents table
│   ├── ScipOccurrence.java            # scip_occurrences table
│   ├── ScipSymbolInfo.java            # scip_symbols table
│   ├── VectorEmbedding.java           # vector_embeddings table
│   ├── FileDependency.java            # file_dependencies table
│   ├── CommitStat.java                # commit_stats table
│   └── FileChurnStat.java             # file_churn_stats table
├── repository/
│   ├── UserRepository.java
│   ├── RepositoryHistoryRepository.java
│   ├── IndexJobRepository.java        # Pessimistic lock with SKIP LOCKED
│   ├── ScipDocumentRepository.java
│   ├── ScipOccurrenceRepository.java  # Spatial range queries for hover
│   ├── ScipSymbolInfoRepository.java
│   ├── VectorEmbeddingRepository.java # pgvector cosine similarity
│   ├── FileDependencyRepository.java  # Recursive CTE for transitive deps
│   ├── CommitStatRepository.java
│   └── FileChurnStatRepository.java   # Hotspot + trend queries
├── service/
│   ├── UserService.java               # Auth (register, login, JWT)
│   ├── RepositoryHistoryService.java  # Repo CRUD with 50-item eviction
│   ├── ScipIngestionService.java      # SCIP protobuf binary parsing
│   ├── ScipQueryService.java          # Hover/completion from SCIP index
│   ├── DependencyEngine.java          # Edge extraction + transitive closure
│   ├── EmbeddingIngestionService.java # Code chunking + Gemini embeddings
│   ├── JGitMiningService.java         # Git clone + commit history mining
│   ├── ChurnCalculationEngine.java    # Weekly churn aggregation
│   ├── LivingWikiService.java         # AI documentation generation
│   ├── CodeReasoningEngine.java       # AI architectural analysis
│   ├── GitHubApiService.java          # GitHub API (ADR context)
│   ├── DataCleaningService.java       # AI-extracted data deduplication
│   └── DocumentFormattingService.java # Markdown document formatting
├── security/
│   ├── JwtService.java                # JWT generation + validation (HS256)
│   ├── JwtAuthenticationFilter.java   # Bearer token extraction filter
│   ├── CustomUserDetailsService.java  # UserDetails from DB
│   ├── CustomOAuth2UserService.java   # GitHub OAuth profile loading
│   ├── OAuth2AuthenticationSuccessHandler.java
│   └── OAuth2AuthenticationFailureHandler.java
└── exception/
    ├── GlobalExceptionHandler.java    # @ControllerAdvice error mapping
    ├── ResourceNotFoundException.java # 404
    └── UnauthorizedException.java     # 401

src/main/resources/
├── application.yml                    # Base configuration
├── application-dev.yml                # Dev profile (verbose logging)
├── application-prod.yml               # Prod profile (minimal logging)
├── application-local.yml              # Local H2 profile
└── db/migration/                      # Flyway SQL migrations (V1–V14+)
```

---

## Getting Started

### Prerequisites

- **Java** 17+
- **Maven** 3.6+
- **PostgreSQL** 15+ with pgvector extension (or Docker)

### Quick Start

```bash
# 1. Clone the repository
git clone <repository-url>
cd MindVex_Editor_Backend

# 2. Create environment file
copy .env.example .env
# Edit .env with your database URL, JWT secret, GitHub OAuth credentials, etc.

# 3. Start PostgreSQL (via Docker)
docker-compose up -d

# 4. Run the application
mvn spring-boot:run
```

**API**: http://localhost:8080  
**Swagger UI**: http://localhost:8080/swagger-ui.html

---

## Environment Variables

| Variable | Description | Required |
|---|---|---|
| `DATABASE_URL` | JDBC PostgreSQL URL (e.g., `jdbc:postgresql://localhost:5432/mindvex`) | Yes |
| `DATABASE_USERNAME` | Database username (if not in URL) | Optional |
| `DATABASE_PASSWORD` | Database password (if not in URL) | Optional |
| `JWT_SECRET` | Minimum 64-character random secret for HS256 signing | Yes |
| `GITHUB_CLIENT_ID` | GitHub OAuth2 App client ID | Yes |
| `GITHUB_CLIENT_SECRET` | GitHub OAuth2 App client secret | Yes |
| `CORS_ORIGINS` | Comma-separated allowed origins (e.g., `http://localhost:5173`) | Yes |
| `APP_OAUTH2_AUTHORIZED_REDIRECT_URIS` | OAuth2 redirect URIs (e.g., `http://localhost:5173/auth/callback`) | Yes |
| `GEMINI_API_KEY` | Google Gemini API key for embeddings + AI documentation | For AI features |
| `GIT_REPO_BASE_DIR` | Directory for cloned repos (default: `/tmp/mindvex-repos`) | Optional |
| `SPRING_PROFILES_ACTIVE` | `dev` (local) or `prod` (production) | Optional |
| `PORT` | Server port (default: `8080`) | Optional |

---

## Database Schema

The database is organized across four schemas with Flyway-managed migrations:

### Schema: `public`

| Table | Purpose | Key Columns |
|---|---|---|
| **users** | User accounts (local + GitHub OAuth) | `id`, `email`, `full_name`, `provider`, `provider_id`, `github_access_token`, `avatar_url` |
| **repository_history** | User's cloned repositories (max 50) | `id`, `user_id`, `url`, `name`, `description`, `branch`, `commit_hash`, `last_accessed_at` |
| **index_jobs** | Async job queue (SCIP, git mining, graph building) | `id`, `user_id`, `repo_url`, `status`, `job_type`, `payload_path`, `error_msg` |

### Schema: `code_intelligence`

| Table | Purpose | Key Columns |
|---|---|---|
| **scip_documents** | Indexed source files | `id`, `user_id`, `repo_url`, `relative_uri`, `language` |
| **scip_occurrences** | Symbol positions in source code | `id`, `document_id`, `symbol`, `start_line`, `start_char`, `end_line`, `end_char`, `role_flags` |
| **scip_symbols** | Symbol metadata (signatures, docs) | `id`, `user_id`, `repo_url`, `symbol`, `display_name`, `signature_doc`, `documentation` |
| **vector_embeddings** | Code chunk embeddings (768-dim) | `id`, `user_id`, `repo_url`, `file_path`, `chunk_index`, `chunk_text`, `embedding` |

### Schema: `code_graph`

| Table | Purpose | Key Columns |
|---|---|---|
| **file_dependencies** | File-level dependency edges | `id`, `user_id`, `repo_url`, `source_file`, `target_file`, `dep_type` |

### Schema: `git_analytics`

| Table | Purpose | Key Columns |
|---|---|---|
| **commit_stats** | Git commit history | `id`, `user_id`, `repo_url`, `commit_hash`, `author_email`, `message`, `files_changed`, `insertions`, `deletions` |
| **file_churn_stats** | Weekly file change metrics | `id`, `user_id`, `repo_url`, `file_path`, `week_start`, `lines_added`, `lines_deleted`, `commit_count`, `churn_rate` |

**Total**: 10 tables across 4 schemas  
**Required Extension**: `pgvector` for vector similarity search

---

## API Reference

### Authentication (`/api/auth`)

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/api/auth/register` | Register with email/password → JWT + refresh token | No |
| `POST` | `/api/auth/login` | Login → JWT + refresh token | No |
| `GET` | `/api/auth/oauth2/authorize/github` | Initiate GitHub OAuth2 flow | No |

### Users (`/api/users`)

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `GET` | `/api/users/me` | Get current user profile | Yes |
| `GET` | `/api/users/me/github-connection` | Check GitHub OAuth link status | Yes |
| `DELETE` | `/api/users/me/github-connection` | Remove stored GitHub token | Yes |
| `POST` | `/api/users/me/github-token` | Manually set GitHub personal access token | Yes |

### Repository History (`/api/repository-history`)

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `GET` | `/api/repository-history` | List repos ordered by last access | Yes |
| `POST` | `/api/repository-history` | Add/update repo (auto-evicts if > 50) | Yes |
| `DELETE` | `/api/repository-history/{id}` | Remove specific repo entry | Yes |
| `DELETE` | `/api/repository-history` | Clear all history | Yes |

### Repository Clone (`/api/repositories`)

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/api/repositories/clone` | Clone repo for WebContainer (shallow, with auth) → file map | Optional |

Returns a flat map of `{ filePath: content }` with binary files Base64-encoded.

### SCIP Code Intelligence (`/api/scip`)

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/api/scip/upload?repoUrl=<url>` | Upload `.scip` binary (multipart) for indexing | Yes |
| `GET` | `/api/scip/hover?repoUrl=<url>&filePath=<path>&line=<n>&character=<n>` | Hover metadata at cursor position | Yes |
| `GET` | `/api/scip/jobs/{id}` | Check indexing job status | Yes |

### Dependency Graph (`/api/graph`)

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/api/graph/build?repoUrl=<url>` | Enqueue graph build job → `{jobId, status}` | Yes |
| `GET` | `/api/graph/dependencies?repoUrl=<url>&rootFile=<path>&depth=<n>` | Full dependency graph (Cytoscape.js format) | Yes |
| `GET` | `/api/graph/references?repoUrl=<url>&symbol=<sym>` | All occurrences of a symbol | Yes |
| `POST` | `/api/graph/semantic-filter` | Filter graph nodes by semantic search | Yes |
| `GET` | `/api/graph/stats?repoUrl=<url>` | Graph metrics (complexity, languages, etc.) | Yes |

**Graph Response Format (Cytoscape.js)**:
```json
{
  "nodes": [
    { "data": { "id": "n0", "label": "UserService.java", "file": "src/service/UserService.java", "language": "java" } }
  ],
  "edges": [
    { "data": { "id": "e0", "source": "n0", "target": "n1", "type": "reference", "isCycle": false } }
  ],
  "cycles": ["FileA.java → FileB.java → FileA.java"]
}
```

### Git Analytics (`/api/analytics`)

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/api/analytics/mine?repoUrl=<url>&days=90` | Enqueue git mining job | Yes |
| `GET` | `/api/analytics/hotspots?repoUrl=<url>&weeks=12&threshold=25.0` | Files with highest churn rates | Yes |
| `GET` | `/api/analytics/file-trend?repoUrl=<url>&filePath=<path>&weeks=12` | Weekly churn trend for a file | Yes |
| `GET` | `/api/analytics/blame?repoUrl=<url>&filePath=<path>` | Line-level evolutionary blame | Yes |

### MCP Tools (`/api/mcp`)

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `GET` | `/api/mcp/resources?repoUrl=<url>` | Available MCP resources and tools | Yes |
| `POST` | `/api/mcp/tools/search` | Semantic code search via embeddings | Yes |
| `POST` | `/api/mcp/tools/deps` | Dependency tree or full graph summary | Yes |
| `POST` | `/api/mcp/tools/wiki` | Generate Living Wiki documentation | Yes |
| `POST` | `/api/mcp/tools/describe` | AI-generated module description | Yes |
| `POST` | `/api/mcp/tools/chat` | Code Q&A conversation via Gemini | Yes |

### AI Reasoning (`/api/mcp/reasoning`)

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/api/mcp/reasoning/analyze` | Deep architectural analysis | Yes |

**Response includes**:
- `detectedPatterns` — Design patterns with confidence scores
- `antiPatterns` — Code smells with severity and remediation strategies
- `refactoringSuggestions` — Step-by-step improvement guides
- `suggestedBoundaries` — Service boundary recommendations

### Settings & Health (`/api`)

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `GET` | `/api/configured-providers` | List enabled AI providers | No |
| `GET` | `/api/health` | Service health check | No |

### Git Proxy (`/api/git-proxy`)

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `GET/POST/PUT` | `/api/git-proxy/{*path}` | CORS proxy for GitHub API with user token injection | Yes |

---

## Authentication & Security

### Authentication Methods

**1. Email/Password (JWT)**
```
POST /api/auth/register → { token, refreshToken, user }
POST /api/auth/login    → { token, refreshToken, user }
```
- Passwords hashed with BCrypt (strength 10)
- JWT signed with HS256 (HMAC-SHA256)
- Token expiration: 24 hours; Refresh: 7 days

**2. GitHub OAuth2**
```
GET /api/auth/oauth2/authorize/github
  → Redirects to GitHub consent screen
  → GitHub callback at /api/auth/oauth2/callback/github
  → Issues JWT → Redirects to frontend with ?token=<jwt>
```
- Scopes: `read:user`, `user:email`
- User's GitHub access token stored for API access (graph building, repo cloning)

### Security Configuration

- **CSRF**: Disabled (stateless REST API)
- **Sessions**: Stateless (no server-side sessions)
- **CORS**: Configurable origins via `CORS_ORIGINS` environment variable
- **JWT Filter**: Validates Bearer token on every authenticated request
- **Public endpoints**: `/api/auth/**`, `/api/health`, `/api/configured-providers`, `/api/git-proxy/**`
- **All other endpoints**: Require valid JWT

### Password Storage

- BCrypt with strength factor 10
- Only used for local registration; OAuth users have no password

---

## Core Services

### ScipIngestionService
Parses raw SCIP Protobuf binary files into structured database records. Handles manual wire-format parsing of documents, occurrences, and symbols from the SCIP index format.

### ScipQueryService
Provides semantic hover information by finding the innermost symbol occurrence at a given cursor position and joining with symbol metadata (type signatures, documentation).

### DependencyEngine
Extracts file-level dependency edges from SCIP cross-references (definition ↔ reference across files). Supports recursive transitive closure via PostgreSQL recursive CTEs with cycle detection.

### EmbeddingIngestionService
Clones repositories, walks the file tree, and generates semantic code chunks (200–800 characters). Calls the Gemini embedding API to produce 768-dimensional vectors, stored in PostgreSQL with pgvector for cosine similarity search.

### JGitMiningService
Clones repositories using JGit (with optional GitHub auth), traverses commit history with `RevWalk` + `DiffFormatter`, and extracts per-file change statistics. Also provides line-level blame via JGit's `BlameCommand`.

### ChurnCalculationEngine
Aggregates per-commit file diffs into ISO-week buckets. Calculates weekly `lines_added`, `lines_deleted`, `commit_count`, and `churn_rate` (percentage of estimated file size changed).

### LivingWikiService
Generates AI-powered documentation through a multi-phase pipeline:
1. **Extract existing README** from vector embeddings (Preserve-or-Update logic)
2. **Semantic search** for code context (entry points, data models, API routes)
3. **GitHub API** for architecture decision context (commits, PRs, issues with architecture keywords)
4. **Gemini generation** with anti-hallucination constraints (no hardcoded examples, micro-repo detection)

### CodeReasoningEngine
Performs deep AI-powered architectural analysis. Returns detected design patterns (with confidence scores), anti-patterns (with severity and remediation), refactoring suggestions (with step-by-step guides), and service boundary recommendations.

### GitHubApiService
Fetches architecture-related context from GitHub: commits containing keywords like "architecture", "design", "refactor", "breaking change"; PRs with design discussions; and issues labeled with architecture tags.

---

## Async Job System

Long-running operations (SCIP indexing, git mining, graph building) use an asynchronous job queue backed by the `index_jobs` table.

### Job Types

| Type | Description | Input | Output |
|---|---|---|---|
| `scip_index` | Parse and ingest SCIP binary | `payloadPath` (temp file) | `scip_documents`, `scip_occurrences`, `scip_symbols` |
| `git_mine` | Clone repo + extract history | `payload` JSON (`{"days": 90}`) | `commit_stats`, `file_churn_stats` |
| `graph_build` | Extract edges from SCIP data | — | `file_dependencies` |

### Job Lifecycle

```
pending → processing → completed | failed
```

- Workers poll the `index_jobs` table
- **Pessimistic locking** with `SELECT ... FOR UPDATE SKIP LOCKED` prevents double-processing
- Status can be checked via `GET /api/scip/jobs/{id}`
- Failed jobs store error messages for diagnostics

---

## WebSocket API

### Endpoint

```
ws://localhost:8080/ws-graph  (SockJS fallback enabled)
```

### STOMP Destinations

| Direction | Destination | Purpose |
|---|---|---|
| Client → Server | `/app/graph/subscribe/{repoId}` | Subscribe to graph updates |
| Client → Server | `/app/graph/ping` | Heartbeat |
| Server → Client | `/topic/graph-updates/{repoId}` | Graph construction progress |
| Server → Client | `/topic/graph-heartbeat` | Heartbeat acknowledgment |

### Message Format

```json
{
  "type": "subscription_confirmed | complete | heartbeat",
  "repoId": "string",
  "timestamp": 1234567890,
  "nodes": [{ "data": { "id": "n0", "label": "file.ts" } }],
  "edges": [{ "data": { "source": "n0", "target": "n1" } }],
  "metadata": {
    "status": "string",
    "message": "string",
    "totalNodes": 42,
    "totalEdges": 67
  }
}
```

---

## Docker Deployment

### Multi-Stage Dockerfile

```dockerfile
# Build: Maven 3.9 + Eclipse Temurin 17 Alpine
# Runtime: Eclipse Temurin 17-jre Alpine
# Non-root user (spring:spring)
# Port: 8080
# Profile: prod
```

### Docker Compose Services

| Service | Image | Port | Purpose |
|---|---|---|---|
| **mindvex-backend** | Built from Dockerfile | 8080 | Spring Boot application |
| **mindvex-postgres** | PostgreSQL 15 Alpine | 5432 | Primary database |
| **mindvex-redis** | Redis 7 Alpine | 6379 | Cache (available, not yet utilized) |

### Running with Docker

```bash
# Start all services
docker-compose up -d

# Build and start
docker-compose up --build

# View logs
docker-compose logs -f mindvex-backend
```

### Production Deployment (Render)

The Dockerfile is optimized for Render deployment:
- Dependencies cached in a separate layer (`mvn dependency:go-offline`)
- Non-root user for security
- UTC timezone set via JVM argument
- Environment variables configured in Render dashboard

---

## Configuration Profiles

### `dev` (Local Development)

```yaml
# Verbose logging
logging.level.ai.mindvex: DEBUG
spring.jpa.show-sql: true
hibernate.format_sql: true
```

### `prod` (Production)

```yaml
# Minimal logging, tighter pool
logging.level.ai.mindvex: INFO
hikari.maximum-pool-size: 5
spring.jpa.show-sql: false
```

### `local` (H2 In-Memory)

```yaml
# H2 database for quick testing without PostgreSQL
spring.datasource.url: jdbc:h2:mem:testdb
```

### Base Configuration

| Setting | Value |
|---|---|
| Server port | 8080 (configurable via `PORT`) |
| Max HTTP header size | 128KB |
| Hikari pool | max=10, min=2, timeout=30s |
| JPA DDL auto | `none` (Flyway manages schema) |
| Flyway | Enabled, baseline on migrate, out-of-order allowed |
| JWT expiration | 24 hours |
| Refresh token expiration | 7 days |

---

## Database Migrations

Flyway migrations are in `src/main/resources/db/migration/`:

| Version | Description |
|---|---|
| V1 | Initial schema — users, workspaces, chats |
| V2 | Add OAuth fields (provider, provider_id) |
| V3 | Repository history table |
| V4 | GitHub access token column |
| V6 | Remove OTP tables |
| V7 | Create `code_intelligence` schema |
| V8 | Create `git_analytics` schema (commit_stats) |
| V9 | Convert IDs to identity primary keys |
| V10 | Create SCIP tables (documents, occurrences, symbols) |
| V11 | Create index_jobs table |
| V12 | Vector embeddings table |
| V13 | File dependencies table (code_graph schema) |
| V14 | File churn stats table |

To run migrations manually:
```bash
mvn flyway:migrate
```

To reset (destructive):
```bash
mvn flyway:clean flyway:migrate
```

---

## Troubleshooting

| Issue | Solution |
|---|---|
| Port 8080 in use | `netstat -ano \| findstr :8080` then `taskkill /PID <PID> /F` |
| Database connection failed | Check `DATABASE_URL` env var; run `docker-compose restart postgres` |
| Flyway migration error | Run `mvn flyway:clean flyway:migrate` (destroys data) |
| pgvector not found | Install pgvector extension: `CREATE EXTENSION IF NOT EXISTS vector;` |
| SCIP upload fails | Ensure file is valid `.scip` binary; check `index_jobs` for error messages |
| GitHub OAuth redirect error | Verify `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, and redirect URIs |
| Gemini API errors | Verify `GEMINI_API_KEY` is set and valid |
| CORS blocked | Add your frontend origin to `CORS_ORIGINS` env var |
| Build fails on Render | Check Dockerfile; ensure `mvn clean package -DskipTests` succeeds locally |

---

## License

MIT License — Copyright (c) 2026 Sheela Akshar Sakhi
