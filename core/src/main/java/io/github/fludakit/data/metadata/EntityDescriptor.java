package io.github.fludakit.data.metadata;

import io.github.fludakit.data.metamodel.ColumnAttribute;
import io.github.fludakit.data.metamodel.EmbeddedAttribute;
import io.github.fludakit.data.metamodel.IdAttribute;
import jakarta.data.metamodel.Attribute;

import java.util.ArrayList;
import java.util.List;

/**
 * Acyclic, reflection-free description of an entity (or value object), built from the generated
 * Jakarta Data static metamodel: the accessors return the {@code _Entity} attribute field
 * instances themselves, so the same object is both the end-user's type-safe API
 * ({@code Order.by(_Profile.address_city.asc())}) and the engine's descriptor.
 *
 * <p>Classification is carried by the attribute's type, not an enum: {@link ColumnAttribute} for
 * scalar/leaf/foreign-key columns, {@link EmbeddedAttribute} for {@code @ValueObject} properties
 * (with the value object's own descriptor), and one-to-many / element-collection attributes for
 * relationships. This descriptor's role is relationship and column resolution; SQL rendering
 * consumes only the inherited {@link EntitySchema} view.</p>
 *
 * <p>Embedded leaves appear twice: as the container's dot-path leaf attributes flattened into
 * {@link #attributes()} (name = dot path, column = flattened column) so
 * {@code attribute("address.city")} resolves in one step, and inside the value object's own
 * descriptor for leaf-by-leaf mapping.</p>
 *
 * @param <T> the entity type
 */
public interface EntityDescriptor<T> extends EntitySchema {

    Class<T> entityClass();

    /** Physical table name, or {@code null} for a value object. */
    @Override
    String tableName();

    /** The identifier attribute, or {@code null} for a value object. */
    IdAttribute<T> id();

    /**
     * All non-identifier attributes in declaration order: scalars and flattened embedded leaves,
     * embedded containers, foreign keys, one-to-many and element-collection relationships.
     */
    List<Attribute<T>> attributes();

    default boolean isValueObject() {
        return tableName() == null;
    }

    @Override
    default String idColumn() {
        return id() == null ? null : id().column();
    }

    /** Resolves an attribute by name; dot paths hit the flattened embedded leaves directly. */
    default Attribute<T> attribute(String name) {
        if (id() != null && id().name().equals(name)) return id();
        for (Attribute<T> a : attributes()) {
            if (name.equals(a.name())) return a;
        }
        throw new IllegalArgumentException("Unknown property '" + name + "' on "
                + entityClass().getSimpleName());
    }

    @Override
    default List<ColumnRef> columns() {
        List<ColumnRef> out = new ArrayList<>();
        if (id() != null) out.add(new ColumnRef(id().column(), id().name()));
        for (Attribute<T> a : attributes()) {
            if (a instanceof ColumnAttribute<?> column) {
                out.add(new ColumnRef(column.column(), column.name()));
            } else if (a instanceof EmbeddedAttribute<?, ?> embedded && embedded.embedded() == null) {
                throw new IllegalStateException("Entity " + entityClass().getSimpleName()
                        + " has an embedded property '" + a.name() + "' whose target is unresolved");
            }
        }
        return out;
    }
}
