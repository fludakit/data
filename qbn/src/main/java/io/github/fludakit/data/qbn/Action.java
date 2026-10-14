package io.github.fludakit.data.qbn;

import io.github.fludakit.data.query.Operation;

/**
 * The query-by-method-name action a repository method name starts with.
 *
 * <p>This is a NAME concept, not a query concept, so it lives here rather than in {@code core}: it
 * exists only to turn the leading hump of a method name into something the parser and the processor
 * can branch on. {@link Operation} is the query-side vocabulary and stays in {@code core}, where the
 * SQL renderers can see it; {@link #operation()} is the one place the two meet.</p>
 */
public enum Action {
    FIND("find", Operation.FIND),
    DELETE("delete", Operation.DELETE),
    COUNT("count", Operation.COUNT),
    EXISTS("exists", Operation.EXISTS);

    private final String prefix;
    private final Operation operation;

    Action(String prefix, Operation operation) {
        this.prefix = prefix;
        this.operation = operation;
    }

    /** The lowercase method-name prefix this action is spelled with. */
    public String prefix() {
        return prefix;
    }

    /** The query operation this action asks for — the bridge from a method name to a query. */
    public Operation operation() {
        return operation;
    }

    /** The action whose prefix {@code name} starts with, or {@code null} when none does. */
    public static Action of(String name) {
        for (Action action : values()) {
            if (name.startsWith(action.prefix)) {
                return action;
            }
        }
        return null;
    }
}
