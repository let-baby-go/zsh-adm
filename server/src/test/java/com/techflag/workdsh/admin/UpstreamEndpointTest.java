package com.techflag.workdsh.admin;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UpstreamEndpointTest {
    private static String openAi(String base) { return UpstreamEndpoint.openAi(base).uri().toString(); }
    private static String anthropic(String base) { return UpstreamEndpoint.anthropic(base).uri().toString(); }

    @Test
    void addsVersionPrefixUnlessPresent() {
        assertEquals("https://api.example.com/v1/chat/completions", openAi("https://api.example.com"));
        assertEquals("https://api.example.com/v1/chat/completions", openAi("https://api.example.com/v1"));
        assertEquals("https://api.example.com/v1/chat/completions", openAi("https://api.example.com/v1//"));
        assertEquals("https://gw.example.com/proxy/v1/messages", anthropic("https://gw.example.com/proxy"));
        assertEquals("https://gw.example.com/proxy/v1/messages", anthropic("https://gw.example.com/proxy/v1/"));
    }

    @Test
    void deepSeekAnthropicPrefixOnlyOnBareHost() {
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", anthropic("https://api.deepseek.com"));
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", anthropic("https://api.deepseek.com/"));
        assertEquals("https://api.deepseek.com/custom/v1/messages", anthropic("https://api.deepseek.com/custom"));
        assertEquals("https://api.deepseek.com/v1/chat/completions", openAi("https://api.deepseek.com"));
    }

    @Test
    void springAiAndNativeTransportsShareOneTarget() {
        var target = UpstreamEndpoint.anthropic("https://api.deepseek.com");
        assertEquals("https://api.deepseek.com/anthropic", target.root());
        assertEquals("/v1/messages", target.path());
    }
}
