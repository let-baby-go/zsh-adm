package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;
import static org.junit.jupiter.api.Assertions.*;

class SpringAiModelClientTest {
    private static final GatewaySettings SETTINGS = new GatewaySettings(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(10));

    @Test void messagesUsesSupplierKeyAndVersionAndAcceptsOmittedStream() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var key=new AtomicReference<String>();
        var version=new AtomicReference<String>();
        server.createContext("/v1/messages",exchange->{
            key.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            exchange.getRequestBody().readAllBytes();
            byte[] result="{\"id\":\"msg-test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"test-model\",\"content\":[{\"type\":\"text\",\"text\":\"fixture\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,result.length);
            try(var out=exchange.getResponseBody()){out.write(result);}
        });
        server.start();
        try {
            var client=new SpringAiModelClient(new ObjectMapper(), SETTINGS);
            byte[] request="{\"model\":\"test-model\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}".getBytes(StandardCharsets.UTF_8);
            var result=new String(client.completeMessages("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","supplier-test-key",null,request), StandardCharsets.UTF_8);
            assertTrue(result.contains("fixture"));
            assertEquals("supplier-test-key",key.get());
            assertEquals("2023-06-01",version.get());
        } finally {server.stop(0);}
    }

    @Test void providerErrorsKeepStatusAndBody() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/messages",exchange->{
            exchange.getRequestBody().readAllBytes();
            byte[] result="{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens required\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(400,result.length);
            try(var out=exchange.getResponseBody()){out.write(result);}
        });
        server.start();
        try {
            var client=new SpringAiModelClient(new ObjectMapper(), SETTINGS);
            var error=assertThrows(RestClientResponseException.class,()->client.completeMessages("http://127.0.0.1:"+server.getAddress().getPort(),"k",null,
                    "{\"model\":\"m\",\"messages\":[]}".getBytes(StandardCharsets.UTF_8)));
            assertEquals(400,error.getStatusCode().value());
            assertTrue(error.getResponseBodyAsString().contains("max_tokens required"));
        } finally {server.stop(0);}
    }
}
