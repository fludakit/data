package io.github.fludakit.data.query;

/**
 * How a LIKE-family operator decorates the value it binds with the {@code %} wildcard.
 *
 * <p>{@code Contains}/{@code StartsWith}/{@code EndsWith} take a SUBSTRING, so the argument is a
 * literal and the wildcards are the operator's job — {@code findByNameContains("dd")} must bind
 * {@code %dd%}, not {@code dd}. {@code Like} takes a PATTERN, so its argument already carries
 * whatever wildcards the caller meant and the operator adds none: decorating it would silently
 * rewrite {@code findByNameLike("%DD%")} into {@code %%DD%%} and make the keyword unusable for an
 * anchored or single-character pattern.</p>
 *
 * <p>The mapping lives here — on the query model, next to {@link Operator} — and nowhere else. The
 * processor reads it when it emits the {@code .param(...)} call, so the code generator carries no
 * second copy of the rule that can drift from it.</p>
 */
public enum LikePattern {

    /** The value is used as-is. Every non-LIKE operator, and {@code Like}/{@code NotLike}. */
    NONE("", ""),

    /** {@code %value} — {@link Operator#ENDS_WITH} and {@link Operator#NOT_ENDS_WITH}. */
    LEADING("%", ""),

    /** {@code value%} — {@link Operator#STARTS_WITH} and {@link Operator#NOT_STARTS_WITH}. */
    TRAILING("", "%"),

    /** {@code %value%} — {@link Operator#CONTAINS} and {@link Operator#NOT_CONTAINS}. */
    BOTH("%", "%");

    private final String prefix;
    private final String suffix;

    LikePattern(String prefix, String suffix) {
        this.prefix = prefix;
        this.suffix = suffix;
    }

    /** The {@code %} run prepended to the bound value; empty when none. */
    public String prefix() {
        return prefix;
    }

    /** The {@code %} run appended to the bound value; empty when none. */
    public String suffix() {
        return suffix;
    }

    /** Whether this pattern decorates the value at all. */
    public boolean wildcarded() {
        return this != NONE;
    }

    /** The value as the comparison must see it: {@code prefix + value + suffix}. */
    public String decorate(String value) {
        return prefix + value + suffix;
    }

    /**
     * The pattern {@code operator} imposes on the value it binds. This switch is the ONE place the
     * rule is written; a negated operator carries the same decoration as its positive counterpart,
     * because the wildcard is a property of what is being matched, not of which way the comparison
     * points.
     */
    public static LikePattern of(Operator operator) {
        return switch (operator) {
            case CONTAINS, NOT_CONTAINS -> BOTH;
            case STARTS_WITH, NOT_STARTS_WITH -> TRAILING;
            case ENDS_WITH, NOT_ENDS_WITH -> LEADING;
            default -> NONE;
        };
    }
}
