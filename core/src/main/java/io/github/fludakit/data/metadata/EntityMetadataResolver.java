package io.github.fludakit.data.metadata;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Association;
import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;
import io.github.fludakit.data.annotation.ValueObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class EntityMetadataResolver {

    private final Map<Class<?>, EntityMetadata> cache;
    private final ThreadLocal<Set<Class<?>>> inProgress = ThreadLocal.withInitial(HashSet::new);

    public EntityMetadataResolver() { this(true); }

    public EntityMetadataResolver(boolean cacheEnabled) {
        this.cache = cacheEnabled ? new ConcurrentHashMap<>() : null;
    }

    public EntityMetadata resolve(Class<?> type) {
        if (cache != null) {
            EntityMetadata hit = cache.get(type);
            if (hit != null) return hit;
        }
        Set<Class<?>> stack = inProgress.get();
        if (stack.contains(type)) {
            if (cache == null) {
                throw new IllegalStateException("Cyclic aggregate reference to " + type.getName()
                        + " cannot be resolved when caching is disabled");
            }
            return cache.get(type); // in-progress (shallow) placeholder breaks the cycle
        }
        stack.add(type);
        try {
            EntityMetadata md = build(type);
            if (cache != null) cache.put(type, md);
            return md;
        } finally {
            stack.remove(type);
        }
    }

    private EntityMetadata build(Class<?> type) {
        boolean isEntity = isEntityType(type);
        boolean isValueObject = isValueObjectType(type);

        String tableName = isEntity ? Names.tableName(type) : null;

        // Phase 1: classify fields without resolving relationship targets, so a cycle cannot recurse.
        List<EntityMetadata.Property> props = new ArrayList<>();
        EntityMetadata.Property id = null;

        for (Field f : allFields(type)) {
            String name = f.getName();
            Class<?> ft = f.getType();
            if (f.isAnnotationPresent(Identity.class) || (!isValueObject && name.equals("id"))) {
                id = new EntityMetadata.Property(name, ft.getName(), EntityMetadata.Kind.ID, Names.columnName(name), null, null);
            } else if (f.isAnnotationPresent(Association.class)) {
                props.add(new EntityMetadata.Property(name, ft.getName(), EntityMetadata.Kind.ASSOCIATION,
                        Names.fkColumn(name), null, null));
            } else if (isEntityType(ft)) {
                props.add(new EntityMetadata.Property(name, ft.getName(), EntityMetadata.Kind.MANY_TO_ONE,
                        Names.fkColumn(name), null, null));
            } else if (ft == List.class) {
                Class<?> elem = elementType(type, name);
                if (elem != null && isEntityType(elem)) {
                    props.add(new EntityMetadata.Property(name, ft.getName(), EntityMetadata.Kind.ONE_TO_MANY,
                            null, null, null));
                } else if (elem != null && isValueObjectType(elem)) {
                    props.add(new EntityMetadata.Property(name, ft.getName(), EntityMetadata.Kind.ELEMENT_COLLECTION,
                            null, null, null));
                } else {
                    props.add(new EntityMetadata.Property(name, ft.getName(), EntityMetadata.Kind.SCALAR,
                            Names.columnName(name), null, null));
                }
            } else if (isValueObjectType(ft)) {
                props.add(new EntityMetadata.Property(name, ft.getName(), EntityMetadata.Kind.EMBEDDED,
                        Names.columnName(name), null, null));
            } else {
                props.add(new EntityMetadata.Property(name, ft.getName(), EntityMetadata.Kind.SCALAR,
                        Names.columnName(name), null, null));
            }
        }

        // Phase 2: register a shallow metadata first, so a cycle resolving back to this type
        // returns this instance instead of recursing forever.
        EntityMetadata shallow = EntityMetadata.of(type.getName(), tableName, id, List.copyOf(props));
        if (cache != null) cache.put(type, shallow);

        // Phase 3: resolve targets and rebuild with the deep metadata.
        List<EntityMetadata.Property> deepProps = new ArrayList<>(props.size());
        for (EntityMetadata.Property p : props) {
            switch (p.kind()) {
                case MANY_TO_ONE, ASSOCIATION, EMBEDDED -> deepProps.add(new EntityMetadata.Property(
                        p.name(), p.typeName(), p.kind(), p.column(), resolve(forName(p.typeName())), p.inverse()));
                case ELEMENT_COLLECTION -> deepProps.add(new EntityMetadata.Property(
                        p.name(), p.typeName(), p.kind(), p.column(),
                        resolve(elementType(type, p.name())), p.inverse()));
                case ONE_TO_MANY -> {
                    EntityMetadata child = resolve(elementType(type, p.name()));
                    deepProps.add(new EntityMetadata.Property(p.name(), p.typeName(), p.kind(), p.column(),
                            child, findInverse(type, child)));
                }
                default -> deepProps.add(p);
            }
        }

        EntityMetadata result = EntityMetadata.of(type.getName(), tableName, id, List.copyOf(deepProps));
        if (cache != null) cache.put(type, result);
        return result;
    }

    private static Class<?> forName(String typeName) {
        try {
            return Class.forName(typeName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot load entity type " + typeName, e);
        }
    }

    private EntityMetadata.Property findInverse(Class<?> parent, EntityMetadata child) {
        List<EntityMetadata.Property> candidates = child.propertiesOf(EntityMetadata.Kind.MANY_TO_ONE, parent.getName());
        if (candidates.isEmpty()) {
            // The child may still be the shallow, cycle-breaking instance registered before its own
            // targets were resolved. Fall back to the declared field type so the inverse is found
            // regardless of which side of the relationship was resolved first.
            for (EntityMetadata.Property p : child.properties()) {
                if (p.kind() == EntityMetadata.Kind.MANY_TO_ONE
                        && p.typeName().equals(parent.getName()) && !candidates.contains(p)) {
                    candidates.add(p);
                }
            }
        }
        if (candidates.size() == 1) return candidates.get(0);
        if (candidates.size() > 1) {
            for (EntityMetadata.Property p : candidates) {
                if (p.target() != null && p.target().typeName().equals(parent.getName())) return p;
            }
            throw new IllegalStateException("Ambiguous one-to-many inverse between "
                    + parent.getSimpleName() + " and " + child.typeName());
        }
        throw new IllegalStateException("No many-to-one from " + child.typeName()
                + " back to " + parent.getSimpleName() + " for one-to-many resolution");
    }

    private boolean isEntityType(Class<?> c) {
        return c.isAnnotationPresent(Entity.class) || c.isAnnotationPresent(AggregateRoot.class);
    }

    private boolean isValueObjectType(Class<?> c) {
        return c.isAnnotationPresent(ValueObject.class);
    }

    private List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> c = type;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                fields.add(f);
            }
            c = c.getSuperclass();
        }
        return fields;
    }

    private Class<?> elementType(Class<?> owner, String fieldName) {
        try {
            java.lang.reflect.Type t = owner.getDeclaredField(fieldName).getGenericType();
            if (t instanceof java.lang.reflect.ParameterizedType pt) {
                java.lang.reflect.Type arg = pt.getActualTypeArguments()[0];
                return arg instanceof Class<?> c ? c : null;
            }
        } catch (NoSuchFieldException ignored) { }
        return null;
    }
}
