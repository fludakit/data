package io.github.fludakit.data.metadata;

/**
 * Reflection-free path value access for cursor keys. Generated per query definition so an accessor
 * covers exactly the paths that query sorts by, rather than every path on the entity.
 *
 * @param <T> the root entity type
 */
public interface ValueAccess<T> {

    /** Reads the value at {@code path} from {@code root}. A to-many path yields its value list. */
    Object value(String path, T root);
}
