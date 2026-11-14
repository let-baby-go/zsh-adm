package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestClient;

/**
 * Anthropic non-streaming transport through Spring AI. DSH retains tool execution and conversation ownership.
 * Every other gateway path forwards raw bytes in {@link ModelForwarder}.
 */
@Component
public class SpringAiModelClient {
    private final ObjectMapper json;
    private final JdkClientHttpRequestFactory requests;

    public SpringAiModelClient(ObjectMapper json, GatewaySettings settings) {
        this.json = json;
        this.requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build());
        this.requests.setReadTimeout(settings.responseTimeout());
    }

    /**
     * Returns the provider's response as serialized by Spring AI. Provider errors surface as
     * RestClientResponseException with the original status and body; timeouts as ResourceAccessException.
     */
    public byte[] completeMessages(String baseUrl, String supplierKey, String version, byte[] body) throws java.io.IOException {
        var target = UpstreamEndpoint.anthropic(baseUrl);
        var api = AnthropicApi.builder()
                .baseUrl(target.root()).completionsPath(target.path()).apiKey(supplierKey)
                .anthropicVersion(version == null ? "2023-06-01" : version)
                .restClientBuilder(RestClient.builder().requestFactory(requests))
                .responseErrorHandler(new DefaultResponseErrorHandler())
                .build();
        var input = json.readTree(body);
        if (input instanceof ObjectNode object) {
            // "stream" may be omitted; Spring AI's request DTO unboxes it and fails on null.
            object.put("stream", false);
            // Anthropic accepts plain text or blocks; Spring AI's DTO requires blocks.
            for (var message : object.path("messages")) {
                if (message.path("content").isTextual()) {
                    var blocks = json.createArrayNode();
                    blocks.addObject().put("type", "text").put("text", message.path("content").asText());
                    ((ObjectNode) message).set("content", blocks);
                }
            }
        }
        var response = api.chatCompletionEntity(json.treeToValue(input, AnthropicApi.ChatCompletionRequest.class)).getBody();
        return json.writeValueAsBytes(response);
    }
}
