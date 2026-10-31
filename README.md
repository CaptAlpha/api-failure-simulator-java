# API Failure Simulator — Java / Spring Boot

A local HTTP reverse proxy for testing API client resilience. Inject delays, synthetic HTTP errors, and dropped TCP connections using ordered, seeded rules. Built with Java 21, Spring Boot 4.1.2, Reactor Netty, Maven, and JUnit.

The project has two modules:

| Module | Purpose |
| --- | --- |
| `simulator-core` | Reusable Java library: immutable rules, seeded engine, streaming proxy |
| `simulator-boot` | Executable Spring Boot app: YAML configuration, decision logs, lifecycle management |

Source code is hosted on GitHub; no artifacts are published to Maven Central and no service is deployed.

## Run the app

Requires JDK 21+ and Maven 3.6.3+. From the repository root:

```sh
mvn verify
# Terminal 1: optional sample API, or run your own API on port 3000
java examples/Upstream.java
# Terminal 2: run the proxy
java -jar simulator-boot/target/simulator-boot-0.1.0-SNAPSHOT.jar
# Terminal 3: exercise the proxy
curl -i http://127.0.0.1:8080/products    # 500 ms injected delay
curl -i -X POST http://127.0.0.1:8080/checkout  # 30% chance of 503
curl -v http://127.0.0.1:8080/disconnect # connection closes, no HTTP response
```

The default listener is `127.0.0.1:8080`, forwarding to `http://127.0.0.1:3000`. Stop with Ctrl+C. Set your Spring API client's base URL to the proxy rather than its dependency. The upstream itself can be Spring MVC, WebFlux, or any HTTP service.

```java
// In a Spring application with spring-web available:
RestClient client = RestClient.builder().baseUrl("http://127.0.0.1:8080").build();
String result = client.get().uri("/products").retrieve().body(String.class);
```

To choose another upstream:

```sh
java -jar simulator-boot/target/simulator-boot-0.1.0-SNAPSHOT.jar \
  --simulator.target=http://127.0.0.1:9000 --simulator.port=8085
```

## Configure rules

The bundled `simulator-boot/src/main/resources/application.yml` contains examples. To replace it, put a complete configuration in `rules.yml` and run with `--spring.config.location=file:./rules.yml`.

```yaml
simulator:
  target: http://127.0.0.1:3000
  host: 127.0.0.1
  port: 8080
  seed: checkout-test
  upstream-timeout-ms: 10000
  rules:
    - id: checkout-outage
      match:
        method: POST
        path: /checkout
      probability: 0.25
      action:
        type: error
        status: 503
        body: Please retry
```

| Action | Fields | Behavior |
| --- | --- | --- |
| `delay` | `ms`: integer 0–2147483647 | Wait asynchronously, then forward |
| `error` | `status`: 400–599; optional string `body` | Respond without contacting the upstream |
| `drop` | none | Close the TCP connection without an HTTP response |

Conditions are ANDed. `method` is case-insensitive; `path` matches an exact raw URL pathname and `path-prefix` matches a prefix. Query strings are ignored for matching but forwarded. Omit `match` to match all requests. Probability defaults to 1. Rules require unique nonempty IDs. Invalid configuration fails at startup.

For each matching rule, the engine draws a random number and selects the rule if the draw is below its probability. The first selected rule wins; otherwise requests pass through. One action applies per request. There is no automatic upstream retry. Your client may retry independently and consume more decisions.

## Use as a local Maven dependency

```sh
mvn install
```

Add this to another project's POM:

```xml
<dependency>
  <groupId>io.github.captalpha</groupId>
  <artifactId>simulator-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
import io.github.captalpha.faultsim.FailureProxy;
import io.github.captalpha.faultsim.Rule;
import java.net.URI;
import java.time.Duration;
import java.util.List;

try (var proxy = new FailureProxy(
    URI.create("http://127.0.0.1:3000"), "test-seed",
    List.of(new Rule("slow", new Rule.Match("GET", null, "/products"),
        1.0, Rule.Action.delay(500))), Duration.ofSeconds(10),
    decision -> System.out.println(decision.actionName()))) {
    int port = proxy.start("127.0.0.1", 0);
    // Execute API client requests against http://127.0.0.1:<port> here.
}
```

Use the core JAR as the dependency; the Boot executable JAR is the standalone runner. The core has no Spring runtime dependency. This version does not provide an auto-configuring Spring Boot starter.

## Design and repeatability

```text
Client → Netty HTTP listener → synchronized rule engine
                            ├─ error → local HTTP response
                            ├─ drop  → close socket
                            └─ delay/pass → streaming upstream HTTP client → response
```

FNV-1a seeds a Mulberry32 generator. A synchronized decision method assigns IDs and consumes random draws atomically across Netty event loops. The same seed, rules, and decision-call order reproduce decisions. Concurrent arrival scheduling and network timings are not deterministic. A golden test matches the original JavaScript demo's decision sequence for the ASCII seed `demo`; Unicode seeds and URL normalization are not guaranteed cross-language compatible.

The proxy streams request/response bytes with backpressure. Delay uses a Reactor timer, without sleeping an event-loop thread. It strips hop-by-hop headers, rewrites Host, and pins all traffic to the configured origin, including absolute-form request targets. A total upstream deadline begins after the injected delay. Failures before response headers yield 502; failures afterward close the connection. Closing the proxy stops listening and closes active client channels, cancelling their reactive pipelines. Boot logs request ID, method, pathname, rule ID, and action; request bodies and query strings are omitted. Library observers run synchronously and should be fast and nonthrowing.

## Tests and CI

`mvn verify` runs rule-engine unit tests, real local HTTP integration tests, and a Spring context/YAML binding test. Coverage includes the JavaScript decision fixture, concurrency, scoped matching, fallthrough, validation, large POST streaming, delays, socket closure, upstream timeouts/failures, fixed-origin routing, and shutdown. GitHub Actions runs the build on Java 21.

## Boundaries and next steps

HTTP/1.1 inbound; HTTP or HTTPS upstream. No WebSocket, CONNECT tunneling, HTTP/2, inbound TLS, hot rule reload, recording/replay, or composed faults. Dropping a socket does not guarantee a particular OS-level reset code. The proxy has no authentication and is intended for local development; it binds loopback by default. Do not use it as a public gateway. Rules may include raw pathnames in local logs.

Next: versioned JSON Schema, more shared golden fixtures, an optional Boot starter, metrics, and retry/circuit-breaker examples. The [JavaScript implementation](https://github.com/CaptAlpha/api-failure-simulator) remains a separate repository.

## Resume wording

Adapt this to what you personally understand and can demonstrate:

> Built a Java/Spring Boot HTTP fault-injection toolkit with a reusable library and YAML-configured runner; implemented seeded failure rules, nonblocking latency injection, streaming forwarding, socket drops, structured logs, and automated integration tests.

Interview topics: event-loop safety, backpressure and buffer ownership, reproducibility under concurrency, request retry semantics, and separating a library from its Spring runner. No benchmark or adoption claims are made.

## References

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Reactor Netty reference](https://projectreactor.io/docs/netty/release/reference/)
