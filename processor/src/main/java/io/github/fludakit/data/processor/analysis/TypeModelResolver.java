package io.github.fludakit.data.processor.analysis;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.AggregateTypeMapping;
import io.github.fludakit.data.metadata.ElementCollectionValueObjectMapping;
import io.github.fludakit.data.metadata.EmbeddedValueObjectMapping;
import io.github.fludakit.data.metadata.EntityTypeMapping;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.metadata.ValueObjectMapping;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The memoization behind the compile-time model: every descriptor, element, member type and
 * element-collection placement the field scan resolves is cached here, keyed so a type is built once
 * and a cyclic graph degrades to a placeholder instead of recursing forever.
 *
 * <p>Split from {@link TypeModelBuilder} so the scan (how fields are classified) is separate from the
 * cache (how their results are remembered). The one coupling is deliberate: {@link #descriptorFor}
 * drives the scan through the {@code descriptorScan} callback, and the scan records each member's
 * JavaPoet type through {@link #recordMemberType} — both are the memoization's job, not the scan's.
 */
public final class TypeModelResolver {

    private final Types types;
    private final Elements elements;
    private final Function<TypeElement, TypeMapping<?>> descriptorScan;

    private final Map<String, TypeMapping<?>> descriptorCache = new HashMap<>();
    private final Set<String> descriptorsInProgress = new HashSet<>();

    /**
     * The element-collection mapping per PLACEMENT, keyed by owner table and field name — the same
     * memoization {@link #descriptorCache} gives a descriptor per TYPE NAME. A value object placed by
     * two owners is one field model with two placements, so the two caches must not be merged.
     */
    private final Map<String, ElementCollectionValueObjectMapping<?>> elementCollectionCache = new HashMap<>();
    private final Map<String, TypeElement> elementsByName = new HashMap<>();
    /** The JavaPoet type of one MEMBER, keyed by declaring type plus member name (see {@link #recordMemberType}). */
    private final Map<String, TypeName> memberTypes = new HashMap<>();

    public TypeModelResolver(Types types, Elements elements, Function<TypeElement, TypeMapping<?>> descriptorScan) {
        this.types = types;
        this.elements = elements;
        this.descriptorScan = descriptorScan;
    }

    /**
     * The memoized descriptor for {@code element}: the scan is run on miss, a cyclic re-entry returns
     * an empty placeholder, and the result is cached under the type's qualified name.
     */
    public TypeMapping<?> descriptorFor(TypeElement element) {
        String fqn = element.getQualifiedName().toString();
        TypeMapping<?> cached = descriptorCache.get(fqn);
        if (cached != null) {
            return cached;
        }
        if (descriptorsInProgress.contains(fqn)) {
            return isValueObjectType(element)
                    ? new EmbeddedValueObjectMapping<>(fqn, List.of())
                    : entityMapping(false, ownsAggregateLifecycle(element), fqn,
                            Names.tableName(element.getSimpleName().toString()), null, List.of());
        }
        descriptorsInProgress.add(fqn);
        try {
            TypeMapping<?> descriptor = descriptorScan.apply(element);
            descriptorCache.put(fqn, descriptor);
            return descriptor;
        } finally {
            descriptorsInProgress.remove(fqn);
        }
    }

    /** As {@link #descriptorFor(TypeElement)}, for a type a caller knows only by name. */
    public TypeMapping<?> descriptorFor(String fqn) {
        TypeElement element = elementFor(fqn);
        return element == null ? null : descriptorFor(element);
    }

    /** The declared type NAMED {@code fqn}, memoized (null included), or {@code null} when unresolvable. */
    public TypeElement elementFor(String fqn) {
        if (fqn == null) {
            return null;
        }
        if (elementsByName.containsKey(fqn)) {
            return elementsByName.get(fqn);
        }
        TypeElement element = elements.getTypeElement(fqn);
        elementsByName.put(fqn, element);
        return element;
    }

    /** The {@code ClassName} generated code refers to a mapped type by, or {@code null}. */
    public ClassName classNameFor(String fqn) {
        TypeElement element = elementFor(fqn);
        return element == null ? null : ClassName.get(element);
    }

    /** The JavaPoet type of {@code owner}'s member {@code name}, or {@code null} when the scan recorded none. */
    public TypeName memberType(String ownerFqn, String name) {
        return memberTypes.get(memberKey(ownerFqn, name));
    }

    /** Records the JavaPoet type of one member the scan classified. */
    public void recordMemberType(String ownerFqn, String name, TypeName type) {
        memberTypes.put(memberKey(ownerFqn, name), type);
    }

    /**
     * Re-points the relationship targets a mid-cycle build captured as placeholders, so the JOIN
     * projection, the read loaders and the {@code value} switch no longer silently drop the relation.
     * A model whose props already resolve unchanged is left untouched.
     */
    public void reconcileRelationships(Set<String> managedEntities) {
        for (String fqn : managedEntities) {
            TypeMapping<?> model = descriptorCache.get(fqn);
            if (model == null || model.id() == null) {
                continue;
            }
            List<Prop> props = model.props();
            List<Prop> reconciled = new ArrayList<>(props.size());
            boolean changed = false;
            for (Prop prop : props) {
                Prop next = reconcileProp(model.typeName(), prop);
                changed |= next != prop;
                reconciled.add(next);
            }
            if (changed) {
                descriptorCache.put(fqn, entityMapping(false, model.ownsAggregateLifecycle(),
                        model.typeName(), model.tableName(), model.id(), List.copyOf(reconciled)));
            }
        }
    }

    /** The same prop with its target re-resolved against the completed cache, or itself when nothing moved. */
    private Prop reconcileProp(String ownerTypeName, Prop prop) {
        if (prop instanceof Prop.Reference reference) {
            TypeMapping<?> real = descriptorCache.get(reference.targetTypeName());
            if (real == null || real.id() == null || real == reference.target()) {
                return reference;
            }
            return new Prop.Reference(reference.name(), reference.fkColumn(), reference.targetTypeName(),
                    real, reference.composition(), reference.backReference());
        }
        if (prop instanceof Prop.EntityCollection collection) {
            TypeMapping<?> real = descriptorCache.get(collection.targetTypeName());
            if (real == null || real.id() == null) {
                return collection;
            }
            String inverse = collection.inverseName() != null
                    ? collection.inverseName() : findInverseName(ownerTypeName, real);
            String inverseFk = collection.inverseFkColumn() != null
                    ? collection.inverseFkColumn()
                    : (inverse == null ? null : Names.fkColumn(inverse));
            if (real == collection.target() && Objects.equals(inverse, collection.inverseName())
                    && Objects.equals(inverseFk, collection.inverseFkColumn())) {
                return collection;
            }
            return new Prop.EntityCollection(collection.name(), real, inverse, inverseFk);
        }
        return prop;
    }

    /** The element-collection mapping for one PLACEMENT, memoized by owner table plus field name. */
    public Prop.ElementCollection buildCollection(String parentTable, String field, TypeElement valueObject) {
        String placement = parentTable + '#' + field;
        ElementCollectionValueObjectMapping<?> cached = elementCollectionCache.get(placement);
        if (cached != null) {
            return new Prop.ElementCollection(field, cached);
        }
        String childTable = Names.childTable(parentTable, field);
        String parentFk = Names.fkColumn(parentTable);
        ValueObjectMapping<?> model = embeddedOf(descriptorFor(valueObject));
        ElementCollectionValueObjectMapping<?> target = model == null
                ? new ElementCollectionValueObjectMapping<>(valueObject.getQualifiedName().toString(),
                        childTable, parentFk, List.of())
                : new ElementCollectionValueObjectMapping<>(model.typeName(), childTable, parentFk,
                        model.props());
        elementCollectionCache.put(placement, target);
        return new Prop.ElementCollection(field, target);
    }

    // ------------------------------------------------------------ the type-kind predicates

    /** Whether the annotated type is a value object. */
    public boolean isValueObjectType(TypeElement element) {
        return element != null
                && element.getAnnotation(io.github.fludakit.data.annotation.ValueObject.class) != null;
    }

    /** Whether the annotated type is an aggregate root (owns the lifecycle of everything inside it). */
    public boolean ownsAggregateLifecycle(TypeElement element) {
        return element != null
                && element.getAnnotation(io.github.fludakit.data.annotation.AggregateRoot.class) != null;
    }

    /**
     * Whether {@code element} declares or inherits a method called {@code methodName}.
     *
     * <p>Generated code calls an accessor only after this check, so a bean-less type degrades to a
     * NOTE instead of emitting a call that does not compile.</p>
     */
    public boolean hasMethod(TypeElement element, String methodName) {
        if (element == null) {
            return false;
        }
        for (Element member : elements.getAllMembers(element)) {
            if (member.getKind() == ElementKind.METHOD && member.getSimpleName().contentEquals(methodName)) {
                return true;
            }
        }
        return false;
    }

    /** The mapping whose TYPE carries the domain role: value object, aggregate root, or plain entity. */
    public static TypeMapping<?> entityMapping(boolean valueObject, boolean ownsAggregate,
                                               String typeName, String table, Prop.Id id, List<Prop> props) {
        if (valueObject) {
            return new EmbeddedValueObjectMapping<>(typeName, props);
        }
        return ownsAggregate
                ? new AggregateTypeMapping(typeName, table, id, props)
                : new EntityTypeMapping(typeName, table, id, props);
    }

    private static ValueObjectMapping<?> embeddedOf(TypeMapping<?> model) {
        return model instanceof ValueObjectMapping<?> vo ? vo : null;
    }

    private static String findInverseName(String parentTypeName, TypeMapping<?> child) {
        for (Prop prop : child.props()) {
            if (prop instanceof Prop.Reference reference
                    && parentTypeName.equals(reference.targetTypeName())) {
                return reference.name();
            }
        }
        return null;
    }

    private static String memberKey(String ownerFqn, String name) {
        return ownerFqn + "#" + name;
    }
}
