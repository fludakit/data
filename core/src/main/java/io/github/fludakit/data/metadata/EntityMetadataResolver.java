package io.github.fludakit.data.metadata;

import io.github.fludakit.data.Names;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Entity;
import org.jmolecules.ddd.annotation.Identity;
import org.jmolecules.ddd.annotation.ValueObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class EntityMetadataResolver {

    private final Map<Class<?>, EntityMetadata> cache;

    public EntityMetadataResolver() { this(true); }

    public EntityMetadataResolver(boolean cacheEnabled) {
        this.cache = cacheEnabled ? new ConcurrentHashMap<>() : null;
    }

    public EntityMetadata resolve(Class<?> type) {
        if (cache != null) {
            EntityMetadata hit = cache.get(type);
            if (hit != null) return hit;
        }
        EntityMetadata md = build(type);
        if (cache != null) cache.put(type, md);
        return md;
    }

    private EntityMetadata build(Class<?> type) {
        boolean isEntity = isEntityType(type);
        boolean isValueObject = isValueObjectType(type);

        String tableName = isEntity ? Names.tableName(type) : null;

        List<EntityMetadata.Property> props = new ArrayList<>();
        EntityMetadata.Property id = null;

        for (Field f : allFields(type)) {
            String name = f.getName();
            Class<?> ft = f.getType();
            if (f.isAnnotationPresent(Identity.class) || (!isValueObject && name.equals("id"))) {
                id = new EntityMetadata.Property(name, ft, EntityMetadata.Kind.ID, Names.columnName(name), null, null);
            } else if (isEntityType(ft)) {
                EntityMetadata target = resolve(ft);
                props.add(new EntityMetadata.Property(name, ft, EntityMetadata.Kind.MANY_TO_ONE,
                        Names.fkColumn(name), target, null));
            } else if (ft == List.class) {
                Class<?> elem = elementType(type, name);
                if (elem != null && isEntityType(elem)) {
                    EntityMetadata target = resolve(elem);
                    props.add(new EntityMetadata.Property(name, ft, EntityMetadata.Kind.ONE_TO_MANY,
                            null, target, findInverse(type, target)));
                } else if (elem != null && isValueObjectType(elem)) {
                    EntityMetadata target = resolve(elem);
                    props.add(new EntityMetadata.Property(name, ft, EntityMetadata.Kind.ELEMENT_COLLECTION,
                            null, target, null));
                } else {
                    props.add(new EntityMetadata.Property(name, ft, EntityMetadata.Kind.SCALAR,
                            Names.columnName(name), null, null));
                }
            } else if (isValueObjectType(ft)) {
                props.add(new EntityMetadata.Property(name, ft, EntityMetadata.Kind.EMBEDDED,
                        Names.columnName(name), resolve(ft), null));
            } else {
                props.add(new EntityMetadata.Property(name, ft, EntityMetadata.Kind.SCALAR,
                        Names.columnName(name), null, null));
            }
        }

        return EntityMetadata.of(type, tableName, id, props);
    }

    private EntityMetadata.Property findInverse(Class<?> parent, EntityMetadata child) {
        List<EntityMetadata.Property> candidates = child.propertiesOf(EntityMetadata.Kind.MANY_TO_ONE, parent);
        if (candidates.size() == 1) return candidates.get(0);
        if (candidates.size() > 1) {
            for (EntityMetadata.Property p : candidates) {
                if (p.target().type().equals(parent)) return p;
            }
            throw new IllegalStateException("Ambiguous one-to-many inverse between "
                    + parent.getSimpleName() + " and " + child.type().getSimpleName());
        }
        throw new IllegalStateException("No many-to-one from " + child.type().getSimpleName()
                + " back to " + parent.getSimpleName() + " for one-to-many resolution");
    }

    private boolean isEntityType(Class<?> c) {
        return c.isAnnotationPresent(Entity.class) || c.isAnnotationPresent(AggregateRoot.class)
                || org.jmolecules.ddd.types.Entity.class.isAssignableFrom(c);
    }

    private boolean isValueObjectType(Class<?> c) {
        return c.isAnnotationPresent(ValueObject.class)
                || org.jmolecules.ddd.types.ValueObject.class.isAssignableFrom(c);
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
