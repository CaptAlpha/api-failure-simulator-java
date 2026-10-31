package io.github.captalpha.faultsim;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RuleEngineTest {
    private Rule rule(String id, Double chance, Rule.Action action) { return new Rule(id, null, chance, action); }
    @Test void matchesJavascriptGoldenSequence() {
        var engine = new RuleEngine("demo", List.of(rule("random", .5, Rule.Action.error(503, null))));
        var actual = new java.util.ArrayList<String>();
        for (int i=0; i<8; i++) actual.add(engine.decide("GET", "/").actionName());
        assertEquals(List.of("pass", "error", "error", "error", "error", "pass", "error", "pass"), actual);
    }
    @Test void scopesRulesAndUsesFirstSelection() {
        var engine = new RuleEngine("x", List.of(
            new Rule("checkout", new Rule.Match("post", "/checkout", null), 1.0, Rule.Action.error(503, "retry")),
            new Rule("prefix", new Rule.Match(null, null, "/check"), 1.0, Rule.Action.delay(1))));
        assertEquals("checkout", engine.decide("POST", "/checkout").ruleId());
        assertEquals("prefix", engine.decide("GET", "/checkout").ruleId());
        assertNull(engine.decide("GET", "/other").ruleId());
    }
    @Test void zeroProbabilityFallsThrough() {
        var e = new RuleEngine("x", List.of(rule("never", 0.0, Rule.Action.drop()), rule("always", 1.0, Rule.Action.error(429, null))));
        assertEquals("always", e.decide("GET", "/").ruleId());
    }
    @Test void validatesConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> Rule.Action.delay(-1));
        assertThrows(IllegalArgumentException.class, () -> Rule.Action.error(200, null));
        assertThrows(IllegalArgumentException.class, () -> rule("bad", Double.NaN, Rule.Action.drop()));
        assertThrows(IllegalArgumentException.class, () -> new RuleEngine("x", List.of(rule("same", 1.0, Rule.Action.drop()), rule("same", 1.0, Rule.Action.drop()))));
    }
    @Test void numbersConcurrentDecisionsWithoutDuplicates() throws Exception {
        var e = new RuleEngine("x", List.of());
        var ids = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i=0;i<200;i++) executor.submit(() -> ids.add(e.decide("GET", "/").requestId()));
        }
        assertEquals(200, ids.size());
    }
}
