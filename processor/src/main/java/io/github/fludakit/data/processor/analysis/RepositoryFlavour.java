package io.github.fludakit.data.processor.analysis;

/**
 * What a repository interface INHERITS, which is the only thing about the interface itself that
 * changes how a method on it is validated or compiled.
 *
 * <p>Three decisions hang on it, and all three are about the spec's own vocabulary rather than about
 * the user's method:</p>
 *
 * <ul>
 *   <li>whether the spec's built-in method names ({@code findAll}, {@code save}, …) are recognised —
 *       those names are ordinary query-by-method-name spellings on a plain repository, so matching
 *       them on one would hijack a name the user meant literally ({@link #spec()});</li>
 *   <li>whether an un-annotated abstract method is an error at all — on a plain repository it is,
 *       because nothing else declares the operation ({@link #spec()}, read the other way);</li>
 *   <li>whether the spec's write lifecycle names ({@code insert}, {@code update}, {@code insertAll},
 *       {@code updateAll}) are inherited at all ({@link #crud()}).</li>
 * </ul>
 *
 * <p>{@code CRUD_REPOSITORY} is a value of its own rather than a {@code boolean extendsCrud} carried
 * beside the enum, because the two walked the SAME supertype hierarchy to answer the same question —
 * "which of the three spec interfaces is this?" — and holding one as an enum and the other as a flag
 * let them disagree. As one enum, the third decision is an accessor on the answer instead of a second
 * argument threaded through every method signature that needed it.</p>
 */
public enum RepositoryFlavour {

    /** Extends none of {@code DataRepository}, {@code BasicRepository} or {@code CrudRepository}. */
    PLAIN,

    /** Extends {@code DataRepository} or {@code BasicRepository}, but not {@code CrudRepository}. */
    DATA_REPOSITORY,

    /**
     * Extends {@code CrudRepository}, which extends {@code BasicRepository}, which extends
     * {@code DataRepository} — so this is the most specific answer, not a separate hierarchy.
     */
    CRUD_REPOSITORY;

    /** Whether this is one of the three spec interfaces: {@link #PLAIN} is the only one that is not. */
    public boolean spec() {
        return this != PLAIN;
    }

    /** Whether the spec's write lifecycle method names are inherited methods on this repository. */
    public boolean crud() {
        return this == CRUD_REPOSITORY;
    }
}
