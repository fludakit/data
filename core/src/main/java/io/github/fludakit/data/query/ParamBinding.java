package io.github.fludakit.data.query;

/**
 * Maps a method-argument slot to the named-parameter token its value binds to.
 *
 * @param argIndex  the method argument whose value this token carries
 * @param paramName the named-parameter token, as rendered into the SQL
 * @param pattern   how the LIKE family decorates that value with {@code %} before it is bound;
 *                  {@link LikePattern#NONE} for every other operator, which binds the value as-is
 */
public record ParamBinding(int argIndex, String paramName, LikePattern pattern) {

    public ParamBinding {
        pattern = pattern == null ? LikePattern.NONE : pattern;
    }
}
