package io.github.fludakit.data.qbn;

/**
 * The query-by-method-name action a repository method name starts with.
 *
 * <p>Name vocabulary only — lives in qbn, not core. The processor maps {@link Action} into a
 * {@link io.github.fludakit.data.query.QueryDefinition} when assembling the method plan.</p>
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
