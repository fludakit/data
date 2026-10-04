package io.github.fludakit.data.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compile-time-friendly description of an entity (or value object) and its physical columns.
 *
 * <p>Everything here is expressed with strings rather than {@link Class} references so the model can
 * be built by an annotation processor (which only ever has {@code TypeElement}/{@code TypeMirror}
 * data) as well as by reflection at runtime. {@link io.github.fludakit.data.sql.SqlBuilder} renders
 * SQL from this model using only {@link #tableName()}, {@link #columns()} and {@link #id()}.</p>
 */
public final class EntityMetadata {

    public enum Kind { ID, SCALAR, EMBEDDED, MANY_TO_ONE, ASSOCIATION, ONE_TO_MANY, ELEMENT_COLLECTION }

    public record Property(String name, String typeName, Kind kind, String column,
                           EntityMetadata target, Property inverse) {}

    /** A flat physical column plus the dot-separated property path that selects it. */
    public record ColumnRef(String column, String path) {}

    private final String typeName;
    private final String tableName;
    private final Property id;
    private final List<Property> properties;
    private final Map<String, Property> byName;

    private EntityMetadata(String typeName, String tableName, Property id, List<Property> properties) {
        this.typeName = typeName;
        this.tableName = tableName;
        this.id = id;
        this.properties = List.copyOf(properties);
        this.byName = new LinkedHashMap<>();
        for (Property p : this.properties) byName.put(p.name(), p);
    }

    public static EntityMetadata of(String typeName, String tableName, Property id, List<Property> properties) {
        return new EntityMetadata(typeName, tableName, id, properties);
    }

    public String typeName() { return typeName; }
    public String tableName() { return tableName; }
    public Property id() { return id; }
    public List<Property> properties() { return properties; }
    public boolean isValueObject() { return tableName == null; }

    public Property property(String name) {
        Property p = byName.get(name);
        if (p == null) throw new IllegalArgumentException("Unknown property '" + name + "' on " + typeName);
        return p;
    }

    /** Properties of the given kind whose {@code target} metadata has the given type name. */
    public List<Property> propertiesOf(Kind kind, String targetTypeName) {
        List<Property> out = new ArrayList<>();
        for (Property p : properties) {
            if (p.kind() == kind && p.target() != null && p.target().typeName().equals(targetTypeName)) out.add(p);
        }
        return out;
    }

    /** Flat physical columns (id, scalar, fk, flattened embedded leaves) with their dot-separated property path. */
    public List<ColumnRef> columns() {
        List<ColumnRef> out = new ArrayList<>();
        if (id != null) out.add(new ColumnRef(id.column(), id.name()));
        for (Property p : properties) {
            switch (p.kind()) {
                case SCALAR, MANY_TO_ONE, ASSOCIATION -> out.add(new ColumnRef(p.column(), p.name()));
                case EMBEDDED -> {
                    if (p.target() == null) {
                        throw new IllegalStateException("Entity " + typeName
                                + " has an embedded property '" + p.name()
                                + "' whose target is unresolved");
                    }
                    for (ColumnRef leaf : p.target().columns()) {
                        out.add(new ColumnRef(p.column() + "_" + leaf.column(), p.name() + "." + leaf.path()));
                    }
                }
                case ONE_TO_MANY, ELEMENT_COLLECTION -> { /* no column on this table */ }
                default -> { }
            }
        }
        return out;
    }
}
