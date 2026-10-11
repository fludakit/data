package io.github.fludakit.data.metadata;

import io.github.fludakit.data.metamodel.IdAttribute;
import io.github.fludakit.data.metamodel.ListAttribute;
import io.github.fludakit.data.metamodel.PathAttribute;

import java.util.List;

/**
 * Acyclic, reflection-free description of an entity (or value object), built from the generated
 * Jakarta Data static metamodel: the accessors return the {@code _Entity} attribute field
 * instances themselves, so the same object is both the end-user's type-safe API
 * ({@code Order.by(_Profile.address.city.asc())}) and the engine's runtime metadata.
 *
 * <p>Classification is carried by the attribute's type, not an enum: {@code ScalarAttribute} for
 * scalar/leaf attributes and the {@code NavigableAttribute} subtypes for the containers —
 * {@code EmbeddableAttribute} for {@code @ValueObject} properties, {@code EntityAttribute} for
 * to-one associations and {@code ListAttribute} for one-to-many / element collections. Attributes
 * navigate only: the physical column and the cross-table placement of every path live in this
 * metadata ({@link #columns()} plus {@link #columnRefs()}). SQL rendering consumes its
 * {@link TableSchema} view for table-local columns and this entity-level mapping for path resolution.</p>
 *
 * <p>{@link #attributes()} lists root-level nodes once, including embedded and relation containers.
 * {@link #columnRefs()} associates every SQL-resolvable logical path (a string) with its
 * physical placement. It includes flattened embedded leaves and relation-traversing leaves; container
 * nodes remain in {@code attributes()} because they do not have one physical column mapping.</p>
 *
 * @param <T> the entity type
 */
public interface EntityMetadata<T> extends TableSchema, AttributePathResolver {

    Class<T> entityClass();

    /** Domain role of the mapped type: entity, value object, or aggregate root. */
    Stereotype stereotype();

    /** Physical table name, or {@code null} for a value object. */
    @Override
    String tableName();

    /** The identifier attribute, or {@code null} for a value object. */
    IdAttribute<T> id();

    /**
     * All non-identifier attributes in declaration order: scalars, embedded containers, to-one
     * associations and collections. Embedded leaves are NOT flattened in here; they resolve
     * through {@link #columnRef(String)}.
     */
    List<PathAttribute<T>> attributes();

    /**
     * The physical columns owned by this root table, in attribute order. Navigated columns are
     * excluded because their mappings carry one or more joins. The generated index preserves
     * declaration order, so filtering it produces a stable root-column view.
     */
    @Override
    default List<ColumnRef> columns() {
        return columnRefs().values().stream()
                .filter(ref -> ref.joins().isEmpty()
                        && ref.ownerAlias() == null
                        && (tableName() == null || ref.ownerTableName() == null
                                || ref.ownerTableName().equals(tableName())))
                .toList();
    }

    default boolean isValueObject() {
        return stereotype() == Stereotype.VALUE_OBJECT;
    }

    default String idColumn() {
        return id() == null ? null : columnRef(id().name()).column();
    }

    /** Resolves a root-level attribute by name (the identifier first, then {@link #attributes()}). */
    default PathAttribute<T> attribute(String name) {
        if (id() != null && id().name().equals(name)) return id();
        for (PathAttribute<T> a : attributes()) {
            if (name.equals(a.name())) return a;
        }
        throw new IllegalArgumentException("Unknown property '" + name + "' on "
                + entityClass().getSimpleName());
    }

    /** A relation head whose attribute is a {@link ListAttribute} fans root rows: never a join. */
    @Override
    default boolean isToManyPath(String attributePath) {
        int dot = attributePath.indexOf('.');
        String pathHead = dot < 0 ? attributePath : attributePath.substring(0, dot);
        try {
            return attribute(pathHead) instanceof io.github.fludakit.data.metamodel.ListAttribute;
        } catch (IllegalArgumentException unknownProperty) {
            return false;
        }
    }

    /**
     * Navigates from {@code root} through a dot path and returns the resulting runtime value,
     * without reflection. Generated metadata overrides this with a {@code switch} over paths resolved
     * by {@link #columnRef(String)} at the generated depth (one relation hop). A local,
     * flattened-embedded or to-one leaf returns its single (possibly {@code null}) value; a path whose
     * first segment is a to-many container returns the {@code List} of child leaf values (an empty
     * list, never {@code null}, when the collection is null/empty). Unknown paths throw
     * {@link IllegalArgumentException}. The default throws {@link UnsupportedOperationException}
     * so hand-written metadata cannot be mistaken for a value-capable implementation.
     */
    default Object navigate(String path, T root) {
        throw new UnsupportedOperationException(getClass().getSimpleName()
                + " does not support property navigation");
    }

    /** Resolves a dot path through the mapping index, including flattened and navigated leaves. */
    @Override
    default ColumnRef columnRef(String path) {
        ColumnRef ref = columnRefs().get(path);
        if (ref == null) {
            throw new IllegalArgumentException("Unknown property '" + path + "' on "
                    + entityClass().getSimpleName());
        }
        return ref;
    }
}
