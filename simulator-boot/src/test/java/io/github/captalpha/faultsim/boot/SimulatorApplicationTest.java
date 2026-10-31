package io.github.captalpha.faultsim.boot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import static org.junit.jupiter.api.Assertions.*;
class SimulatorApplicationTest {
    @Test void bindsYamlAndStartsProxy() {
        try (var context = new SpringApplicationBuilder(SimulatorApplication.class).run("--simulator.port=0")) {
            var config=context.getBean(SimulatorProperties.class);
            assertEquals("resume-demo",config.seed()); assertEquals(3,config.rules().size());
            assertEquals(io.github.captalpha.faultsim.Rule.Type.ERROR,config.rules().getFirst().action().type());
            assertEquals("/products",config.rules().get(1).match().pathPrefix());
            assertNotNull(context.getBean(io.github.captalpha.faultsim.FailureProxy.class));
        }
    }
}
