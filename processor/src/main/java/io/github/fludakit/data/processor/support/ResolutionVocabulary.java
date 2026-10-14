package io.github.fludakit.data.processor.support;
import io.github.fludakit.data.processor.analysis.PathResolver;

/**
 * The single-source NOTE texts for relation-path resolution failures. The compile-side
 * {@link PathResolver#resolveCompilePaths} reporting and the runtime-mirror messages must render
 * these strings verbatim; the diagnostics pins keep their own literal copies as the byte guard,
 * so changing a constant here is a user-visible drift.
 */
public final class ResolutionVocabulary {
    /** Every resolution NOTE opens with the quoted property path. */
    public static final String PATH_PREFIX = "path '";

    /** Multi-hop traversal rejection: the text after the path. */
    public static final String MULTI_HOP_SUFFIX =
            "' traverses more than one relation; not supported";

    /** Unresolvable relation target, predicate left unqualified: the text after the path. */
    public static final String UNRESOLVED_SUFFIX =
            "' traverses a relation whose target could not be resolved; the predicate was left unqualified";

    public static String multiHop(String path) {
        return PATH_PREFIX + path + MULTI_HOP_SUFFIX;
    }

    public static String unresolved(String path) {
        return PATH_PREFIX + path + UNRESOLVED_SUFFIX;
    }

    private ResolutionVocabulary() {
    }
}
