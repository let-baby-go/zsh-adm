package com.techflag.workdsh.admin;

import java.net.URI;

/**
 * The only place that turns a configured provider base URL into an upstream endpoint.
 *
 * <pre>
 * protocol            gateway route                     transport                            upstream path
 * openai-completions  /v1/chat/completions (stream=no)  native HttpClient, bytes relayed     {root}[/v1]/chat/completions
 * openai-completions  /v1/chat/completions (stream=yes) native HttpClient, SSE relayed       {root}[/v1]/chat/completions
 * anthropic-messages  /v1/messages (stream=no)          Spring AI AnthropicApi               {root}[/v1]/messages
 * anthropic-messages  /v1/messages (stream=yes)         native HttpClient, SSE relayed       {root}[/v1]/messages
 * </pre>
 *
 * "/v1" is added unless the base URL already ends with it. DeepSeek serves its Anthropic-compatible API under
 * "/anthropic" on the bare api.deepseek.com host, so that prefix is added for Anthropic calls to that host only.
 */
final class UpstreamEndpoint {
    record Target(String root, String path) {
        URI uri() { return URI.create(root + path); }
    }

    private UpstreamEndpoint() {}

    static Target openAi(String baseUrl) { return target(baseUrl, "/chat/completions", false); }

    static Target anthropic(String baseUrl) { return target(baseUrl, "/messages", true); }

    private static Target target(String baseUrl, String operation, boolean anthropic) {
        String root = baseUrl.replaceAll("/+$", "");
        URI uri = URI.create(root);
        if (anthropic && "api.deepseek.com".equals(uri.getHost()) && (uri.getPath() == null || uri.getPath().isEmpty())) {
            root += "/anthropic";
        }
        return new Target(root, root.endsWith("/v1") ? operation : "/v1" + operation);
    }
}
