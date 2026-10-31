package io.github.captalpha.faultsim;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import static org.junit.jupiter.api.Assertions.*;
class FailureProxyTest {
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private DisposableServer upstream() {
        return HttpServer.create().host("127.0.0.1").port(0).handle((req,res) -> res.status(201)
            .header("x-upstream", "yes").header("x-method", req.method().name()).header("x-uri", req.uri())
            .send(req.receive().retain())).bindNow();
    }
    private FailureProxy proxy(int port, List<Rule> rules, long timeout) {
        return new FailureProxy(URI.create("http://127.0.0.1:"+port), "test", rules, Duration.ofMillis(timeout), null);
    }
    private HttpRequest request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(3)).GET().build();
    }
    @Test void streamsPostQueryHeadersAndLargeBody() throws Exception {
        var upstream = upstream();
        try (var proxy = proxy(upstream.port(), List.of(), 3000)) {
            int port = proxy.start("127.0.0.1",0);
            String body = "payload".repeat(100000);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/echo?q=1"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(201,response.statusCode()); assertEquals(body,response.body());
            assertEquals("yes",response.headers().firstValue("x-upstream").orElseThrow());
            assertEquals("POST",response.headers().firstValue("x-method").orElseThrow());
            assertEquals("/echo?q=1",response.headers().firstValue("x-uri").orElseThrow());
        } finally { upstream.disposeNow(); }
    }
    @Test void syntheticErrorDoesNotContactUpstream() throws Exception {
        try (var proxy = proxy(1, List.of(new Rule("error",null,1.0,Rule.Action.error(503,"retry"))),1000)) {
            int port=proxy.start("127.0.0.1",0);
            var response=client.send(request(port,"/checkout"),HttpResponse.BodyHandlers.ofString());
            assertEquals(503,response.statusCode()); assertEquals("retry",response.body());
        }
    }
    @Test void delaysBeforeForwarding() throws Exception {
        var upstream = upstream();
        try (var proxy = proxy(upstream.port(),List.of(new Rule("delay",null,1.0,Rule.Action.delay(100))),1000)) {
            int port=proxy.start("127.0.0.1",0); long before=System.nanoTime();
            assertEquals(201,client.send(request(port,"/"),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(Duration.ofNanos(System.nanoTime()-before).toMillis()>=90);
        } finally { upstream.disposeNow(); }
    }
    @Test void dropsSocketWithoutHttpResponse() {
        try (var proxy = proxy(1,List.of(new Rule("drop",null,1.0,Rule.Action.drop())),1000)) {
            int port=proxy.start("127.0.0.1",0);
            assertThrows(java.io.IOException.class, () -> client.send(request(port,"/"),HttpResponse.BodyHandlers.ofString()));
        }
    }
    @Test void deadlineReturns502() throws Exception {
        var upstream=HttpServer.create().host("127.0.0.1").port(0).handle((req,res)->Mono.never()).bindNow();
        try (var proxy=proxy(upstream.port(),List.of(),50)) {
            int port=proxy.start("127.0.0.1",0);
            assertEquals(502,client.send(request(port,"/"),HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally { upstream.disposeNow(); }
    }
    @Test void closeTerminatesDelayedRequests() throws Exception {
        var decided = new java.util.concurrent.CountDownLatch(1);
        var proxy = new FailureProxy(URI.create("http://127.0.0.1:1"), "x",
            List.of(new Rule("slow", null, 1.0, Rule.Action.delay(60000))), Duration.ofSeconds(1),
            decision -> decided.countDown());
        int port=proxy.start("127.0.0.1",0);
        var pending=client.sendAsync(request(port,"/"),HttpResponse.BodyHandlers.ofString());
        assertTrue(decided.await(2, java.util.concurrent.TimeUnit.SECONDS));
        proxy.close();
        assertThrows(Exception.class, () -> pending.get(2,java.util.concurrent.TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> proxy.start("127.0.0.1",0));
    }
    @Test void absoluteFormRequestsCannotOverrideTarget() throws Exception {
        var upstream = upstream();
        try (var proxy = proxy(upstream.port(), List.of(), 1000)) {
            int port = proxy.start("127.0.0.1", 0);
            // A raw socket avoids the HTTP client's rewriting of absolute-form request targets.
            try (var socket = new java.net.Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(("GET http://127.0.0.1:1/fixed?x=1 HTTP/1.1\r\n"
                    + "Host: attacker.invalid\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(response.startsWith("HTTP/1.1 201"), response);
                assertTrue(response.contains("/fixed?x=1"), response);
            }
        } finally { upstream.disposeNow(); }
    }
    @Test void upstreamConnectionFailureReturns502() throws Exception {
        var unavailable = upstream(); int upstreamPort = unavailable.port(); unavailable.disposeNow();
        try (var proxy = proxy(upstreamPort, List.of(), 1000)) {
            int port = proxy.start("127.0.0.1", 0);
            assertEquals(502, client.send(request(port, "/"), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void rejectsTargetsWithCredentialsOrPaths() {
        for(String target:List.of("file:///tmp","http://user:pass@localhost","http://localhost/path"))
            assertThrows(IllegalArgumentException.class, () -> new FailureProxy(URI.create(target),"x",List.of(),Duration.ofSeconds(1),null));
    }
}
