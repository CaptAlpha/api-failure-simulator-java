package io.github.captalpha.faultsim;

import java.util.HashSet;
import java.util.List;

/** FNV-1a/Mulberry32 with Java's uint32 overflow, matching the JavaScript engine for ASCII seeds. */
public final class RuleEngine {
    private final List<Rule> rules;
    private int state = 0x811c9dc5;
    private long sequence;
    public RuleEngine(String seed, List<Rule> rules) {
        this.rules = List.copyOf(rules);
        var ids = new HashSet<String>();
        for (var rule : rules) if (!ids.add(rule.id())) throw new IllegalArgumentException("Duplicate rule id: " + rule.id());
        for (char c : seed.toCharArray()) state = (state ^ c) * 16777619;
    }
    // One lock covers both request sequencing and random draws across Netty event loops.
    public synchronized Decision decide(String method, String path) {
        long id = ++sequence;
        for (var rule : rules) {
            if (rule.match().matches(method, path) && nextDouble() < rule.probability())
                return new Decision(id, method, path, rule.id(), rule.action());
        }
        return new Decision(id, method, path, null, null);
    }
    private double nextDouble() {
        state += 0x6D2B79F5;
        int t = (state ^ (state >>> 15)) * (1 | state);
        t ^= t + (t ^ (t >>> 7)) * (61 | t);
        return Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
    }
    public record Decision(long requestId, String method, String path, String ruleId, Rule.Action action) {
        public String actionName() { return action == null ? "pass" : action.type().name().toLowerCase(java.util.Locale.ROOT); }
    }
}
