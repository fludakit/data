package io.github.fludakit.data.mapping;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.EntityMetadata;
import io.github.fludakit.data.metadata.EntityMetadataResolver;
import io.github.fludakit.jdbc.JdbcClient;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps flat result sets to entities and resolves their relationships.
 *
 * <p>Flat mapping (identifier, scalar and embedded leaves) is delegated to
 * {@link JdbcClient}'s {@code query(Class)} row mapper, which matches column labels to
 * field names case-insensitively with underscores ignored and converts database values
 * to the declared field type. Relationships are resolved afterwards with one extra
 * query per association; the resulting N+1 behaviour is accepted for v1.</p>
 */
public final class JdbcEntityMapper {

    private final JdbcClient client;
    private final EntityMetadataResolver metadata;

    public JdbcEntityMapper(JdbcClient client, EntityMetadataResolver metadata) {
        this.client = client;
        this.metadata = metadata;
    }

    public <T> List<T> list(Class<T> type, String sql, Map<String, Object> params) {
        EntityMetadata md = metadata.resolve(type);
        List<T> rows = client.sql(sql).params(params).query(type).list();
        rows.forEach(e -> resolveRelationships(md, e));
        return rows;
    }

    public <T> Optional<T> optional(Class<T> type, String sql, Map<String, Object> params) {
        EntityMetadata md = metadata.resolve(type);
        Optional<T> row = client.sql(sql).params(params).query(type).optional();
        row.ifPresent(e -> resolveRelationships(md, e));
        return row;
    }

    public <T> T single(Class<T> type, String sql, Map<String, Object> params) {
        EntityMetadata md = metadata.resolve(type);
        T row = client.sql(sql).params(params).query(type).single();
        resolveRelationships(md, row);
        return row;
    }

    private void resolveRelationships(EntityMetadata md, Object entity) {
        if (md == null || md.isValueObject() || md.id() == null) {
            return; // no table, or no identifier to key the relationship queries on
        }
        Object id = idOf(md, entity);
        for (EntityMetadata.Property p : md.properties()) {
            switch (p.kind()) {
                case MANY_TO_ONE -> {
                    Object fk = readFk(md, p, id);
                    Object ref = fk == null ? null
                            : client.sql("SELECT * FROM " + Names.quote(p.target().tableName())
                                    + " WHERE " + Names.quote(p.target().id().column()) + " = :id")
                            .param("id", fk).query(forName(p.target().typeName())).optional().orElse(null);
                    setField(entity, p.name(), ref);
                }
                case ONE_TO_MANY -> {
                    EntityMetadata.Property inv = p.inverse();
                    List<?> children = client.sql("SELECT * FROM " + Names.quote(p.target().tableName())
                                    + " WHERE " + Names.quote(inv.column()) + " = :id")
                            .param("id", id).query(forName(p.target().typeName())).list();
                    setField(entity, p.name(), children);
                }
                case ELEMENT_COLLECTION -> {
                    String childTable = Names.childTable(md.tableName(), p.name());
                    String parentFk = Names.toSnakeCase(md.tableName()) + "_id";
                    List<?> items = client.sql("SELECT * FROM " + Names.quote(childTable)
                                    + " WHERE " + Names.quote(parentFk) + " = :id")
                            .param("id", id).query(forName(p.target().typeName())).list();
                    setField(entity, p.name(), items);
                }
                default -> { /* ID, SCALAR, EMBEDDED: already mapped flat by query(Class);
                                ASSOCIATION: an external reference is left null (lazy), never resolved here */ }
            }
        }
    }

    private Object readFk(EntityMetadata md, EntityMetadata.Property p, Object id) {
        return client.sql("SELECT " + Names.quote(p.column()) + " FROM " + Names.quote(md.tableName())
                        + " WHERE " + Names.quote(md.id().column()) + " = :id")
                .param("id", id).optionalValue(forName(p.target().id().typeName())).orElse(null);
    }

    private Object idOf(EntityMetadata md, Object entity) { return fieldValue(entity, md.id().name()); }

    private void setField(Object entity, String name, Object value) {
        Field f = findField(entity.getClass(), name);
        if (f != null) { try { f.setAccessible(true); f.set(entity, value); } catch (IllegalAccessException e) { throw new IllegalStateException(e); } }
    }

    private Object fieldValue(Object entity, String name) {
        Field f = findField(entity.getClass(), name);
        if (f == null) return null;
        try { f.setAccessible(true); return f.get(entity); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    private Field findField(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try { return cur.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static Class<?> forName(String typeName) {
        try {
            return Class.forName(typeName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot load entity type " + typeName, e);
        }
    }
}
