package io.github.fludakit.data.query;

/**
 * The Jakarta Data query-by-method-name action a repository method name starts with.
 *
 * <p>Lives in {@code core}, not {@code qbn}: the processor must classify a method name before it
 * knows whether the optional query-by-method-name parser is on its classpath.
 */
public enum Action {
    FIND("find"),
    DELETE("delete"),
    COUNT("count"),
    EXISTS("exists");

    private final String prefix;

    Action(String prefix) {
        this.prefix = prefix;
    }

    /** The lowercase method-name prefix this action is spelled with. */
    public String prefix() {
        return prefix;
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
