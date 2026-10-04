package io.github.fludakit.data.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EntityMetadata {

    public enum Kind { ID, SCALAR, EMBEDDED, MANY_TO_ONE, ONE_TO_MANY, ELEMENT_COLLECTION }

    public record Property(String name, Class<?> type, Kind kind, String column,
                           EntityMetadata target, Property inverse) {}

    public record ColumnRef(String column, String path, Class<?> type) {}

    private final Class<?> type;
    private final String tableName;
    private final Property id;
    private final List<Property> properties;
    private final Map<String, Property> byName;

    private EntityMetadata(Class<?> type, String tableName, Property id, List<Property> properties) {
        this.type = type;
        this.tableName = tableName;
        this.id = id;
        this.properties = List.copyOf(properties);
        this.byName = new LinkedHashMap<>();
        for (Property p : this.properties) byName.put(p.name(), p);
    }

    public static EntityMetadata of(Class<?> type, String tableName, Property id, List<Property> properties) {
        return new EntityMetadata(type, tableName, id, properties);
    }

    public Class<?> type() { return type; }
    public String tableName() { return tableName; }
    public Property id() { return id; }
    public List<Property> properties() { return properties; }
    public boolean isValueObject() { return tableName == null; }

    public Property property(String name) {
        Property p = byName.get(name);
        if (p == null) throw new IllegalArgumentException("Unknown property '" + name + "' on " + type.getName());
        return p;
    }

    public List<Property> propertiesOf(Kind kind, Class<?> targetType) {
        List<Property> out = new ArrayList<>();
        for (Property p : properties) {
            if (p.kind() == kind && p.target() != null && p.target().type().equals(targetType)) out.add(p);
        }
        return out;
    }

    /** Flat physical columns (id, scalar, fk, flattened embedded leaves) with their dot-separated property path. */
    public List<ColumnRef> columns() {
        List<ColumnRef> out = new ArrayList<>();
        if (id != null) out.add(new ColumnRef(id.column(), id.name(), id.type()));
        for (Property p : properties) {
            switch (p.kind()) {
                case SCALAR -> out.add(new ColumnRef(p.column(), p.name(), p.type()));
                case MANY_TO_ONE -> {
                    if (p.target() == null || p.target().id() == null) {
                        throw new IllegalStateException("Entity " + type().getName()
                                + " has a many-to-one property '" + p.name()
                                + "' whose target has no identifier");
                    }
                    out.add(new ColumnRef(p.column(), p.name(), p.target().id().type()));
                }
                case EMBEDDED -> {
                    for (ColumnRef leaf : p.target().columns()) {
                        out.add(new ColumnRef(p.column() + "_" + leaf.column(), p.name() + "." + leaf.path(), leaf.type()));
                    }
                }
                case ONE_TO_MANY, ELEMENT_COLLECTION -> { /* no column on this table */ }
                default -> { }
            }
        }
        return out;
    }
}
