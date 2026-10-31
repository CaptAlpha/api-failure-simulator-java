package io.github.captalpha.faultsim.boot;

import java.net.URI;
import java.util.List;
import io.github.captalpha.faultsim.Rule;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("simulator")
public record SimulatorProperties(
    @DefaultValue("http://127.0.0.1:3000") URI target,
    @DefaultValue("127.0.0.1") String host,
    @DefaultValue("8080") int port,
    @DefaultValue("default") String seed,
    @DefaultValue("10000") long upstreamTimeoutMs,
    List<Rule> rules) {
    public SimulatorProperties { rules = rules == null ? List.of() : List.copyOf(rules); }
}
