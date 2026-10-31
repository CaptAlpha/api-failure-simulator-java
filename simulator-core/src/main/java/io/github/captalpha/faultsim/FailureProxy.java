package io.github.captalpha.faultsim;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/** Fixed-origin HTTP/1.1 streaming fault proxy, usable without a Spring application context. */
public final class FailureProxy implements AutoCloseable {
    private static final List<String> HOP = List.of("connection", "keep-alive", "proxy-authenticate",
        "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");
    private final URI target;
    private final Duration timeout;
    private final RuleEngine engine;
    private final Consumer<RuleEngine.Decision> observer;
    private final Set<Channel> channels = ConcurrentHashMap.newKeySet();
    private DisposableServer server;
    private boolean closed;

    public FailureProxy(URI target, String seed, List<Rule> rules, Duration timeout,
                        Consumer<RuleEngine.Decision> observer) {
        if (target == null || !("http".equals(target.getScheme()) || "https".equals(target.getScheme()))
            || target.getHost() == null || target.getRawUserInfo() != null || target.getRawQuery() != null
            || target.getRawFragment() != null || !(target.getRawPath().isEmpty() || target.getRawPath().equals("/")))
            throw new IllegalArgumentException("Target must be an HTTP(S) origin without credentials, path, query or fragment");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.toMillis() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Timeout must be positive and <= 2147483647ms");
        this.target = target;
        this.timeout = timeout;
        this.engine = new RuleEngine(seed == null ? "default" : seed, rules);
        this.observer = observer == null ? decision -> {} : observer;
    }
    public synchronized int start(String host, int port) {
        if (server != null || closed) throw new IllegalStateException("Proxy already started or closed");
        server = HttpServer.create().host(host).port(port)
            .doOnConnection(connection -> {
                Channel channel = connection.channel();
                channels.add(channel);
                channel.closeFuture().addListener(ignored -> channels.remove(channel));
            })
            .handle(this::handle).bindNow(Duration.ofSeconds(10));
        return server.port();
    }
    private Mono<Void> handle(HttpServerRequest request, HttpServerResponse response) {
        if (request.method().name().equals("CONNECT") || request.requestHeaders().contains("upgrade"))
            return response.status(501).send().then();
        final URI incoming;
        try { incoming = URI.create(request.uri()); }
        catch (IllegalArgumentException ex) { return response.status(400).send().then(); }
        String path = incoming.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        RuleEngine.Decision decision = engine.decide(request.method().name(), path);
        observer.accept(decision);
        Rule.Action action = decision.action();
        if (action != null && action.type() == Rule.Type.DROP) {
            response.withConnection(connection -> connection.dispose());
            return Mono.empty();
        }
        if (action != null && action.type() == Rule.Type.ERROR)
            return response.status(action.status()).header("Content-Type", "text/plain; charset=utf-8")
                .sendString(Mono.just(action.body() == null ? "Injected failure" : action.body())).then();
        String requestPath = path + (incoming.getRawQuery() == null ? "" : "?" + incoming.getRawQuery());
        Mono<Void> forward = Mono.defer(() -> HttpClient.create().keepAlive(false).disableRetry(true)
            .headers(headers -> {
                headers.set(cleanHeaders(request.requestHeaders()));
                headers.set("Host", target.getRawAuthority());
            })
            .request(request.method())
            .uri(target.getScheme() + "://" + target.getRawAuthority() + requestPath)
            .send(request.receive().retain())
            .response((upstream, body) -> {
                response.status(upstream.status());
                response.headers(cleanHeaders(upstream.responseHeaders()));
                return response.send(body.retain());
            }).then().timeout(timeout)
            .onErrorResume(error -> {
                if (!response.hasSentHeaders()) return response.status(502)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .sendString(Mono.just("Upstream unavailable")).then();
                response.withConnection(connection -> connection.dispose());
                return Mono.empty();
            }));
        return action != null && action.type() == Rule.Type.DELAY
            ? Mono.delay(Duration.ofMillis(action.ms())).then(forward) : forward;
    }
    private static HttpHeaders cleanHeaders(HttpHeaders source) {
        var headers = new DefaultHttpHeaders().set(source);
        for (String value : source.getAll("connection"))
            for (String token : value.split(",")) headers.remove(token.trim());
        HOP.forEach(headers::remove);
        return headers;
    }
    /** Keeps a standalone process alive until the listener is disposed. */
    public void awaitTermination() {
        final DisposableServer running;
        synchronized (this) { running = server; }
        if (running == null) throw new IllegalStateException("Proxy has not started");
        running.onDispose().block();
    }
    @Override public synchronized void close() {
        closed = true;
        if (server != null) server.disposeNow(Duration.ofSeconds(5));
        for (Channel channel : channels) channel.close().syncUninterruptibly();
        channels.clear();
    }
}
