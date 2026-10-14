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

    /** A binding whose value is bound as-is: every operator but the substring-matching LIKE family. */
    public ParamBinding(int argIndex, String paramName) {
        this(argIndex, paramName, LikePattern.NONE);
    }

    public ParamBinding {
        pattern = pattern == null ? LikePattern.NONE : pattern;
    }
}
