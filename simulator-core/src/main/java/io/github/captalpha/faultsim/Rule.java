package io.github.captalpha.faultsim;

/** Immutable, portable rule model. One selected rule applies one action. */
public record Rule(String id, Match match, Double probability, Action action) {
    public Rule {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Rule id is required");
        if (probability == null) probability = 1.0;
        if (!Double.isFinite(probability) || probability < 0 || probability > 1)
            throw new IllegalArgumentException("Probability must be in [0,1]");
        if (match == null) match = new Match(null, null, null);
        if (action == null) throw new IllegalArgumentException("Action is required");
    }
    public record Match(String method, String path, String pathPrefix) {
        boolean matches(String incomingMethod, String incomingPath) {
            return (method == null || method.equalsIgnoreCase(incomingMethod))
                && (path == null || path.equals(incomingPath))
                && (pathPrefix == null || incomingPath.startsWith(pathPrefix));
        }
    }
    public enum Type { DELAY, ERROR, DROP }
    public record Action(Type type, Long ms, Integer status, String body) {
        public Action {
            if (type == null) throw new IllegalArgumentException("Action type is required");
            if (type == Type.DELAY && (ms == null || ms < 0 || ms > Integer.MAX_VALUE))
                throw new IllegalArgumentException("Delay ms must be between 0 and 2147483647");
            if (type == Type.ERROR && (status == null || status < 400 || status > 599))
                throw new IllegalArgumentException("Error status must be 400–599");
        }
        public static Action delay(long ms) { return new Action(Type.DELAY, ms, null, null); }
        public static Action error(int status, String body) { return new Action(Type.ERROR, null, status, body); }
        public static Action drop() { return new Action(Type.DROP, null, null, null); }
    }
}
