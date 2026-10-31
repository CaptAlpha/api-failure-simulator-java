package io.github.captalpha.faultsim.boot;

import io.github.captalpha.faultsim.FailureProxy;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(SimulatorProperties.class)
public class SimulatorApplication {
    private static final Logger log = LoggerFactory.getLogger(SimulatorApplication.class);
    public static void main(String[] args) {
        var context = SpringApplication.run(SimulatorApplication.class, args);
        context.getBean(FailureProxy.class).awaitTermination();
    }

    @Bean(destroyMethod = "close")
    FailureProxy failureProxy(SimulatorProperties config) {
        var proxy = new FailureProxy(config.target(), config.seed(), config.rules(),
            Duration.ofMillis(config.upstreamTimeoutMs()), decision -> log.atInfo()
                .addKeyValue("requestId", decision.requestId())
                .addKeyValue("method", decision.method()).addKeyValue("path", decision.path())
                .addKeyValue("ruleId", decision.ruleId()).addKeyValue("action", decision.actionName())
                .log("proxy-decision"));
        int port = proxy.start(config.host(), config.port());
        log.info("Failure proxy listening on {}:{}", config.host(), port);
        return proxy;
    }
}
