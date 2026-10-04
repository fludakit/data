package io.github.fludakit.data.engine;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.mapping.JdbcEntityMapper;
import io.github.fludakit.data.metadata.EntityMetadata;
import io.github.fludakit.data.metadata.EntityMetadataResolver;
import io.github.fludakit.data.page.SimplePage;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryModel;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.support.DataExceptions;
import io.github.fludakit.jdbc.JdbcClient;
import io.github.fludakit.jdbc.support.GeneratedKeyHolder;
import jakarta.data.Order;
import jakarta.data.Sort;
import jakarta.data.page.PageRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Executes a {@link QueryModel} against a relational database through {@link JdbcClient}.
 *
 * <p>This is the single entry point the generated repository implementations call. SQL text is
 * produced by {@link SqlBuilder}; rows are read back through {@link JdbcEntityMapper}, which uses
 * {@code JdbcClient}'s own row mapper for flat columns and issues further {@code JdbcClient} queries
 * to resolve relationships. Failures raised by {@code JdbcClient} are translated into the Jakarta Data
 * exception hierarchy by {@link DataExceptions}.</p>
 */
@ApplicationScoped
public class DataEngine {

    private final JdbcClient client;
    private final EntityMetadataResolver metadata = new EntityMetadataResolver();
    private final SqlBuilder sql = new SqlBuilder();

    @Inject
    public DataEngine(JdbcClient client) {
        this.client = client;
    }

    JdbcEntityMapper mapper() {
        return new JdbcEntityMapper(client, metadata);
    }

    /**
     * Executes the supplied query model with the given positional arguments.
     *
     * @param model the operation, predicates, sorts and result shape to execute
     * @param args  the bound arguments, addressed by the predicates' {@code argN} parameter names
     * @return the operation result: an entity, a list, a page, an optional, a stream, a count or {@code null}
     */
    public Object invoke(QueryModel model, Object[] args) {
        try {
            return doInvoke(model, args);
        } catch (RuntimeException e) {
            throw DataExceptions.translate(e);
        }
    }

    private Object doInvoke(QueryModel model, Object[] args) {
        EntityMetadata md = metadata.resolve(model.entityClass());
        List<Predicate> predicates = model.predicates();
        return switch (model.operation()) {
            case INSERT -> model.resultKind() == ResultKind.LIST
                    ? insertAll(md, (List<?>) args[0]) : insert(md, args[0]);
            case UPDATE -> model.resultKind() == ResultKind.LIST
                    ? updateAll(md, (List<?>) args[0]) : update(md, args[0]);
            case SAVE -> model.resultKind() == ResultKind.LIST
                    ? saveAll(md, (List<?>) args[0]) : save(md, args[0]);
            case DELETE -> delete(md, model, args);
            case FIND -> find(md, model, args);
            case COUNT -> count(md, predicates, args);
            case EXISTS -> count(md, predicates, args) > 0;
        };
    }

    // ------------------------------------------------------------------ writes

    private Object insert(EntityMetadata md, Object entity) {
        Object id = md.id() == null ? null : fieldValue(entity, md.id().name());
        Map<String, Object> params = bindInsert(md, entity);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        // Omit the identity column when no id is set; an explicit NULL is rejected by H2/PostgreSQL.
        client.sql(sql.insert(md, id != null)).params(params).update(keys);
        if (md.id() != null && keys.getKey() != null) {
            setField(entity, md.id().name(), keys.getKey());
        }
        writeElementCollections(md, entity, idOf(md, entity));
        return entity;
    }

    private Object update(EntityMetadata md, Object entity) {
        client.sql(sql.update(md)).params(bindUpdate(md, entity)).update();
        // Replacing an element collection on update (delete-then-insert child rows) is out of v1 scope.
        return entity;
    }

    private Object save(EntityMetadata md, Object entity) {
        return idOf(md, entity) == null ? insert(md, entity) : update(md, entity);
    }

    private List<Object> insertAll(EntityMetadata md, List<?> entities) {
        List<Object> out = new ArrayList<>(entities.size());
        for (Object e : entities) out.add(insert(md, e));
        return out;
    }

    private List<Object> updateAll(EntityMetadata md, List<?> entities) {
        List<Object> out = new ArrayList<>(entities.size());
        for (Object e : entities) out.add(update(md, e));
        return out;
    }

    private List<Object> saveAll(EntityMetadata md, List<?> entities) {
        List<Object> out = new ArrayList<>(entities.size());
        for (Object e : entities) out.add(save(md, e));
        return out;
    }

    private Object delete(EntityMetadata md, QueryModel model, Object[] args) {
        List<Predicate> predicates = model.predicates();
        if (!predicates.isEmpty()) {
            client.sql(sql.deleteBy(md, predicates)).params(predicateParams(predicates, args)).update();
            return null;
        }
        Object first = args[0];
        Iterable<?> entities = first instanceof List ? (List<?>) first : List.of(first);
        for (Object e : entities) {
            client.sql(sql.deleteById(md)).param("id", idOf(md, e)).update();
        }
        return null;
    }

    /**
     * Binds an INSERT statement. Keys match {@link SqlBuilder#insert}'s {@code :name} placeholders
     * (the property path, dots replaced by underscores). A null identifier is omitted so the database
     * can generate it; a many-to-one or association property binds the referenced entity's identifier,
     * not the entity itself (an association never cascades and is not resolved back on read).
     */
    private Map<String, Object> bindInsert(EntityMetadata md, Object entity) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (md.id() != null) {
            Object id = fieldValue(entity, md.id().name());
            if (id != null) out.put(md.id().name(), id);
        }
        for (EntityMetadata.Property p : md.properties()) {
            switch (p.kind()) {
                case SCALAR -> out.put(p.name(), fieldValue(entity, p.name()));
                case MANY_TO_ONE, ASSOCIATION -> {
                    Object ref = fieldValue(entity, p.name());
                    out.put(p.name(), ref == null ? null : fieldValue(ref, p.target().id().name()));
                }
                case EMBEDDED, ONE_TO_MANY, ELEMENT_COLLECTION -> { /* collections written separately; embedded out of v1 scope */ }
                default -> { }
            }
        }
        return out;
    }

    private Map<String, Object> bindUpdate(EntityMetadata md, Object entity) {
        Map<String, Object> out = bindInsert(md, entity);
        out.put("id", idOf(md, entity));
        return out;
    }

    /**
     * Inserts each element of an element collection into its child table
     * ({@code <parentTable>_<field>}), keyed by the parent foreign key {@code <parentTable>_id}.
     */
    private void writeElementCollections(EntityMetadata md, Object entity, Object parentId) {
        for (EntityMetadata.Property p : md.properties()) {
            if (p.kind() != EntityMetadata.Kind.ELEMENT_COLLECTION) continue;
            Collection<?> items = (Collection<?>) fieldValue(entity, p.name());
            if (items == null || items.isEmpty()) continue;
            String childTable = Names.childTable(md.tableName(), p.name());
            String parentFk = Names.toSnakeCase(md.tableName()) + "_id";
            for (Object item : items) {
                List<String> cols = new ArrayList<>();
                cols.add(parentFk);
                Map<String, Object> params = new LinkedHashMap<>();
                params.put(parentFk, parentId);
                for (EntityMetadata.Property cp : p.target().properties()) {
                    cols.add(cp.column());
                    switch (cp.kind()) {
                        case SCALAR -> params.put(cp.column(), fieldValue(item, cp.name()));
                        case MANY_TO_ONE, ASSOCIATION -> {
                            Object ref = fieldValue(item, cp.name());
                            params.put(cp.column(), ref == null ? null : fieldValue(ref, cp.target().id().name()));
                        }
                        default -> { }
                    }
                }
                String sqlText = "INSERT INTO " + Names.quote(childTable) + " ("
                        + cols.stream().map(Names::quote).collect(Collectors.joining(", "))
                        + ") VALUES ("
                        + cols.stream().map(c -> ":" + c).collect(Collectors.joining(", ")) + ")";
                client.sql(sqlText).params(params).update();
            }
        }
    }

    // ------------------------------------------------------------------- reads

    private Object find(EntityMetadata md, QueryModel model, Object[] args) {
        List<Predicate> predicates = model.predicates();
        Map<String, Object> params = predicateParams(predicates, args);

        List<SortSpec> sorts = new ArrayList<>(model.sorts());
        if (model.orderParamIndex() >= 0) {
            for (Sort<?> s : (Order<?>) args[model.orderParamIndex()]) {
                sorts.add(new SortSpec(s.property(), s.isAscending(), s.ignoreCase()));
            }
        }
        if (model.sortParamIndex() >= 0) {
            for (Sort<?> s : (Sort<?>[]) args[model.sortParamIndex()]) {
                sorts.add(new SortSpec(s.property(), s.isAscending(), s.ignoreCase()));
            }
        }

        if (model.pageParamIndex() >= 0) {
            PageRequest page = (PageRequest) args[model.pageParamIndex()];
            if (page.mode() != PageRequest.Mode.OFFSET) {
                throw new UnsupportedOperationException("cursor pagination not supported yet");
            }
            long offset = (page.page() - 1) * page.size();
            String select = sql.selectBy(md, predicates, sorts, 0, null)
                    + " LIMIT " + page.size() + " OFFSET " + offset;
            List<?> content = mapper().list(md.type(), select, params);
            long total = page.requestTotal()
                    ? client.sql(sql.countBy(md, predicates)).params(params).singleValue(Long.class)
                    : -1L;
            return new SimplePage<>(page, content, total);
        }

        int limit = model.staticLimit();
        String select = sql.selectBy(md, predicates, sorts, limit, null);
        return switch (model.resultKind()) {
            case SINGLE -> mapper().single(md.type(), select, params);
            case OPTIONAL -> mapper().optional(md.type(), select, params);
            case STREAM -> client.sql(select).params(params).query(md.type()).stream();
            case PAGE, CURSORED_PAGE, LIST, COUNT, EXISTS, VOID -> mapper().list(md.type(), select, params);
            default -> mapper().list(md.type(), select, params);
        };
    }

    private long count(EntityMetadata md, List<Predicate> predicates, Object[] args) {
        return client.sql(sql.countBy(md, predicates)).params(predicateParams(predicates, args))
                .singleValue(Long.class);
    }

    /**
     * Binds predicate parameters by position ({@code argN} names are resolved against {@code args})
     * and applies LIKE wildcard wrapping for the contains/starts-with/ends-with operators.
     */
    private Map<String, Object> predicateParams(List<Predicate> predicates, Object[] args) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Predicate p : predicates) {
            Object v;
            if (p.param().startsWith("arg")) v = args[Integer.parseInt(p.param().substring(3))];
            else v = args[0];
            switch (p.operator()) {
                case CONTAINS -> v = "%" + v + "%";
                case STARTS_WITH -> v = v + "%";
                case ENDS_WITH -> v = "%" + v;
                default -> { }
            }
            out.put(p.param(), v);
        }
        return out;
    }

    // ------------------------------------------------------------- reflection

    private Object idOf(EntityMetadata md, Object entity) { return fieldValue(entity, md.id().name()); }

    private void setField(Object entity, String name, Object value) {
        Field f = findField(entity.getClass(), name);
        if (f != null) {
            try {
                f.setAccessible(true);
                f.set(entity, value);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private Object fieldValue(Object entity, String name) {
        Field f = findField(entity.getClass(), name);
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private Field findField(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) { }
            cur = cur.getSuperclass();
        }
        return null;
    }
}
