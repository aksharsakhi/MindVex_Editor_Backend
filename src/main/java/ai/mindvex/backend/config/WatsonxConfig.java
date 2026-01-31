package ai.mindvex.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Configuration for IBM watsonx Orchestrate integration.
 * Loads credentials from environment variables.
 * 
 * Required environment variables:
 * - WATSONX_API_KEY: IBM Cloud API key
 * - WATSONX_ORCHESTRATE_ENDPOINT: The Orchestrate runtime endpoint
 * 
 * Agent IDs are configured per deployed agent in watsonx Orchestrate.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "watsonx")
public class WatsonxConfig {

    // IBM Cloud API Key for IAM authentication
    private String apiKey;

    // watsonx Orchestrate Runtime Endpoint
    private String orchestrateEndpoint;

    // IAM Token URL
    private String iamUrl;

    // Deployed Agent IDs (from watsonx Orchestrate)
    private AgentIds agents;

    @Data
    public static class AgentIds {
        private String codebaseAnalyzer;
        private String dependencyMapper;
        private String codeQa;
        private String codeModifier;
        private String codeReviewer;
        private String documentationGenerator;
        private String gitAssistant;
    }

    @Bean
    public WebClient orchestrateWebClient() {
        String baseUrl = orchestrateEndpoint != null
                ? orchestrateEndpoint
                : "https://us-south.watson-orchestrate.cloud.ibm.com";

        return WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024)) // 10MB buffer for large responses
                .build();
    }
}
