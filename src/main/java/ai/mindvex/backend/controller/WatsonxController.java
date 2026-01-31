package ai.mindvex.backend.controller;

import ai.mindvex.backend.dto.WatsonxChatRequest;
import ai.mindvex.backend.dto.WatsonxChatResponse;
import ai.mindvex.backend.service.WatsonxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for MindVex AI capabilities powered by IBM watsonx
 * Orchestrate.
 * 
 * This controller provides clean AI endpoints that:
 * - Accept user intent + context
 * - Call the corresponding deployed Orchestrate agent
 * - Return agent response to frontend
 * 
 * Architecture:
 * Frontend → /api/ai/* → This Controller → WatsonxService → Orchestrate Runtime
 * API
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "ai", description = "MindVex AI Agent Endpoints (via IBM watsonx Orchestrate)")
@SecurityRequirement(name = "Bearer Authentication")
public class WatsonxController {

    private final WatsonxService watsonxService;

    // ============================================
    // Primary AI Endpoints (as per report: /api/ai/*)
    // ============================================

    @PostMapping("/api/ai/codebase/analyze")
    @Operation(summary = "Analyze codebase", description = "Perform comprehensive codebase analysis using MindVex Codebase Analyzer agent")
    public ResponseEntity<WatsonxChatResponse> analyzeCodebase(
            @Valid @RequestBody WatsonxChatRequest request) {
        log.info("AI Request: Codebase Analysis");
        request.setAgentId("codebase-analysis");
        return ResponseEntity.ok(watsonxService.chat(request));
    }

    @PostMapping("/api/ai/code/modify")
    @Operation(summary = "Modify code", description = "Request code modifications using MindVex Code Modifier agent")
    public ResponseEntity<WatsonxChatResponse> modifyCode(
            @Valid @RequestBody WatsonxChatRequest request) {
        log.info("AI Request: Code Modification");
        request.setAgentId("code-modifier");
        return ResponseEntity.ok(watsonxService.chat(request));
    }

    @PostMapping("/api/ai/code/ask")
    @Operation(summary = "Ask about code", description = "Ask questions about the codebase using MindVex Code Q&A agent")
    public ResponseEntity<WatsonxChatResponse> askAboutCode(
            @Valid @RequestBody WatsonxChatRequest request) {
        log.info("AI Request: Code Q&A");
        request.setAgentId("code-qa");
        return ResponseEntity.ok(watsonxService.chat(request));
    }

    @PostMapping("/api/ai/code/review")
    @Operation(summary = "Review code", description = "Review code changes using MindVex Code Reviewer agent")
    public ResponseEntity<WatsonxChatResponse> reviewCode(
            @Valid @RequestBody WatsonxChatRequest request) {
        log.info("AI Request: Code Review");
        request.setAgentId("code-review");
        return ResponseEntity.ok(watsonxService.chat(request));
    }

    @PostMapping("/api/ai/code/document")
    @Operation(summary = "Generate documentation", description = "Generate documentation using MindVex Documentation Generator agent")
    public ResponseEntity<WatsonxChatResponse> generateDocumentation(
            @Valid @RequestBody WatsonxChatRequest request) {
        log.info("AI Request: Documentation Generation");
        request.setAgentId("documentation");
        return ResponseEntity.ok(watsonxService.chat(request));
    }

    @PostMapping("/api/ai/git/assist")
    @Operation(summary = "Git assistance", description = "Get help with Git operations using MindVex Git Assistant agent")
    public ResponseEntity<WatsonxChatResponse> gitAssist(
            @Valid @RequestBody WatsonxChatRequest request) {
        log.info("AI Request: Git Assistance");
        request.setAgentId("git-assistant");
        return ResponseEntity.ok(watsonxService.chat(request));
    }

    @PostMapping("/api/ai/dependencies/analyze")
    @Operation(summary = "Analyze dependencies", description = "Analyze code dependencies using MindVex Dependency Mapper agent")
    public ResponseEntity<WatsonxChatResponse> analyzeDependencies(
            @Valid @RequestBody WatsonxChatRequest request) {
        log.info("AI Request: Dependency Analysis");
        request.setAgentId("dependency-graph");
        return ResponseEntity.ok(watsonxService.chat(request));
    }

    // ============================================
    // Management Endpoints (/api/watsonx/*)
    // ============================================

    @PostMapping("/api/watsonx/chat")
    @Operation(summary = "Generic agent chat", description = "Send a message to a specific agent by ID")
    public ResponseEntity<WatsonxChatResponse> chat(
            @Valid @RequestBody WatsonxChatRequest request) {
        log.info("Generic chat request for agent: {}", request.getAgentId());
        return ResponseEntity.ok(watsonxService.chat(request));
    }

    @GetMapping("/api/watsonx/agents")
    @Operation(summary = "List available agents", description = "Get list of all available AI agents and their configuration status")
    public ResponseEntity<List<Map<String, Object>>> listAgents() {
        return ResponseEntity.ok(watsonxService.listAgents());
    }

    @GetMapping("/api/watsonx/health")
    @Operation(summary = "Check health", description = "Check if watsonx Orchestrate is configured and accessible")
    public ResponseEntity<Map<String, Object>> checkHealth() {
        return ResponseEntity.ok(watsonxService.checkHealth());
    }
}
