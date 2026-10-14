package io.github.fludakit.data.metadata;

/**
 * An attribute path the mapping does not publish: a sort, a predicate, a projection or a navigation
 * naming a property that does not exist, or a relation the mapping cannot traverse.
 *
 * <p>One type for the whole class of error because the callers answer it DIFFERENTLY and a catch site
 * has to be able to say which rejection it is handling. {@code QueryCompiler#resolveColumn} degrades
 * it to the path's own snake_cased column on the root table, so a hand-written {@code @Query} over a
 * column the descriptor does not publish still renders; everything that cannot degrade reports it as
 * a compile-time ERROR naming the property. Catching {@link IllegalArgumentException} instead — which
 * this extends, so existing catches keep working — meant a programming error raised anywhere inside
 * the walk was silently turned into a wrong column instead of failing the statement.</p>
 *
 * <p>It extends {@link IllegalArgumentException} on purpose: an unknown property IS an illegal
 * argument, so a caller that only reads {@link #getMessage()} is no worse off than before, and a user
 * can catch it without knowing this type exists. The two accessors are what a caller that needs more
 * than the message reads — the processor reports the path, not the sentence.</p>
 */
public final class UnknownAttributePathException extends IllegalArgumentException {

    private final String path;
    private final String typeName;

    public UnknownAttributePathException(String path, String typeName) {
        super("Unknown property '" + path + "' on " + typeName);
        this.path = path;
        this.typeName = typeName;
    }

    /** The path that did not resolve. */
    public String path() {
        return path;
    }

    /** The type the path was resolved against. */
    public String typeName() {
        return typeName;
    }
}
