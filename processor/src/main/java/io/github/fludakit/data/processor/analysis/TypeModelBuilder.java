package io.github.fludakit.data.processor.analysis;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.annotation.Association;
import io.github.fludakit.data.annotation.Identity;
import io.github.fludakit.data.metadata.AggregateTypeMapping;
import io.github.fludakit.data.metadata.ElementCollectionValueObjectMapping;
import io.github.fludakit.data.metadata.EmbeddedValueObjectMapping;
import io.github.fludakit.data.metadata.EntityTypeMapping;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.ValueObjectMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.support.MemberNames;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds and memoizes the compile-time entity and value-object models used by repository generation. */
public final class TypeModelBuilder {

    private final Types types;
    private final Elements elements;
    private final Diagnostics diagnostics;
    private final Map<String, TypeMapping> descriptorCache = new HashMap<>();
    private final Set<String> descriptorsInProgress = new HashSet<>();
    /**
     * The element-collection mapping per PLACEMENT, keyed by the owner table and the field name that
     * decide it — the same memoization {@link #descriptorCache} gives a descriptor per TYPE NAME.
     *
     * <p>The two caches answer different questions and must not be merged: a descriptor is one per
     * mapped type, while a value object placed by two owners is one field model with two placements
     * (the child table is {@code ownerTable + "_" + field}). Keying on the type name would make the
     * second owner reuse the first owner's child table.</p>
     */
    private final Map<String, ElementCollectionValueObjectMapping<?>> elementCollectionCache = new HashMap<>();
    private final Map<String, TypeElement> elementsByName = new HashMap<>();
    /**
     * The JavaPoet type of one MEMBER, keyed by the type that declares it plus the member's name —
     * the one compile-time fact a scan sees that the shipping descriptor cannot carry.
     *
     * <p>It is a Map keyed by declaring type rather than a list carried on the model because the
     * descriptor already carries the property ORDER and SHAPE: {@link TypeMapping#rowProps()} is the
     * list, and this is only the rendering of each one's type. Storing the pair again as a
     * {@code Property} record next to the descriptor made the same field answerable in two places.
     */
    private final Map<String, TypeName> memberTypes = new HashMap<>();

    public TypeModelBuilder(Types types, Elements elements, Diagnostics diagnostics) {
        this.types = types;
        this.elements = elements;
        this.diagnostics = diagnostics;
    }

    /**
     * The mapping of one declared type: the descriptor the generated {@code <Entity>Metadata} prints,
     * and the only model the emitters work from.
     *
     * <p>What they additionally need — this type's {@code ClassName} and each member's JavaPoet
     * {@code TypeName} — is memoized beside it by {@link #classNameFor} and {@link #memberType}, so
     * there is no second model to keep in step with this one.</p>
     */
    public TypeMapping<?> buildEntity(TypeElement element) {
        return descriptorFor(element);
    }

    /**
     * The {@code ClassName} generated code refers to a mapped type by.
     *
     * <p>Derived from the memoized {@link #elementFor} rather than carried on the model: a mapped
     * type is always declared, so its element IS its name, and a {@code ClassName} built from the
     * element gets nested types right ({@code pkg.Outer.Inner}) and knows its own package — which
     * is what half a dozen call sites used to ask {@code Elements} for.
     */
    public ClassName classNameFor(String fqn) {
        TypeElement element = elementFor(fqn);
        return element == null ? null : ClassName.get(element);
    }

    /**
     * The JavaPoet type of {@code owner}'s member {@code name}, or {@code null} when the scan
     * recorded none — which is exactly the members {@link TypeMapping#rowProps()} excludes.
     */
    public TypeName memberType(String ownerFqn, String name) {
        return memberTypes.get(ownerFqn + "#" + name);
    }

    /**
     * Scans the fields ONCE and produces the shipping {@link TypeMapping} — the one printed into the
     * generated {@code <Entity>Metadata}.
     *
     * <p>This is the only pass that makes a mapping decision — which shape each field is, what column
     * it takes, which target it points at. It used to also assemble a compile-time model in the same
     * breath, so every one of its seven branches constructed two parallel things.
     *
     * <p>The one fact the descriptor cannot hold is recorded alongside it in {@link #memberTypes},
     * keyed by this type's name and the member's name: the member's JavaPoet {@code TypeName}.
     *
     * <p>The type name genuinely cannot be recovered later. The descriptor carries a type as an FQN
     * {@code String}, and the way back to a {@code TypeName} is lossy: {@code ClassName.bestGuess}
     * reads the last dotted segment as the simple name, so a nested {@code pkg.Outer.Inner} comes
     * back as a class {@code Inner} in a package {@code pkg.Outer}, and a parameterized field type
     * is a string no {@code TypeName} factory parses. Worse, {@code TypeMapping} could not hold a
     * {@code TypeName} even if one were recoverable: JavaPoet is a processor-only dependency, and
     * the descriptor ships into the user's jar.
     *
     * <p>An embedded target needs no second model at all: {@link Prop.Embedded#target()} IS the
     * target's mapping, resolved by this same memoized scan, and recursing into it by name reaches
     * the same object. It prints as {@code null} in the descriptor — a value object's descriptor is
     * generated as its own artifact — but that is about what ships, not about what is reachable.
     */
    private TypeMapping<?> buildDescriptor(TypeElement element, boolean valueObject,
                                           boolean ownerOwns, String table) {
        String typeNameString = element.getQualifiedName().toString();
        Prop.Id idProp = null;
        List<Prop> props = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element member : elements.getAllMembers(element)) {
            if (member.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) member;
            if (field.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            String name = field.getSimpleName().toString();
            if (!seen.add(name)) {
                continue;
            }
            String fieldType = field.asType().toString();
            // Read for every field, not only the ones recorded below: it REPORTS a type JavaPoet
            // cannot represent, and that report must not depend on which shape the field turned out
            // to be.
            TypeName fieldTypeName = resolveFieldTypeName(field);
            boolean identityAnnotated = field.getAnnotation(Identity.class) != null;

            if (valueObject && identityAnnotated) {
                // Value objects have no identity; keep the field scalar and report the ignored marker.
                diagnostics.note(
                        "value object '" + element.getSimpleName() + "' declares '" + name
                                + "'; ignored — value objects have no identity", element);
            }
            if (!valueObject && (identityAnnotated || name.equals(MemberNames.ID_PROPERTY))) {
                idProp = new Prop.Id(name, Names.columnBase(name), fieldType);
                memberTypes.put(memberKey(typeNameString, name), fieldTypeName);

            } else if (field.getAnnotation(Association.class) != null) {
                TypeElement target = declaredElement(field);
                props.add(referenceProp(name, Names.fkColumn(name),
                        target != null ? target.getQualifiedName().toString() : null, false, false, target));
                memberTypes.put(memberKey(typeNameString, name), fieldTypeName);

            } else if (embeddedTarget(field) != null) {
                TypeElement vo = embeddedTarget(field);
                props.add(new Prop.Embedded(name, embeddedOf(descriptorFor(vo))));
                memberTypes.put(memberKey(typeNameString, name), fieldTypeName);

            } else if (isEntityType(declaredElement(field))) {
                TypeElement target = declaredElement(field);
                // Bare entity references compose only when owned by an aggregate root; on a non-root
                // entity they are the inverse FK back to the root — a write-only back-reference.
                boolean composition = ownerOwns;
                props.add(referenceProp(name, Names.fkColumn(name),
                        target.getQualifiedName().toString(), composition, !composition, target));
                memberTypes.put(memberKey(typeNameString, name), fieldTypeName);

            } else if (valueObjectElementType(field) != null) {
                TypeElement vo = valueObjectElementType(field);
                if (table == null) {
                    // A value object has no table of its own, so a collection nested inside one has
                    // no parent table to derive a child table and parent FK from. It used to be a
                    // NullPointerException out of the processor (Names.childTable(null, ..)), surfacing
                    // as a javac stack trace that aborted the round and took every other repository in
                    // it down. Reject the declaration instead: it is a shape the model cannot express,
                    // not a state the generator can degrade gracefully from.
                    //
                    // Reported HERE, where the field is declared, rather than in the element-collection
                    // scan that used to own it — that scan only ran when the owning value object was
                    // itself used as a collection somewhere, so the same declaration was rejected or
                    // silently dropped depending on an unrelated use site.
                    diagnostics.error(
                            "field '" + name + "' of value object '" + element.getSimpleName() + "' is a nested"
                                    + " collection of '" + vo.getSimpleName() + "': a value object in an"
                                    + " element collection has no table of its own, so a collection inside one has no"
                                    + " child table to live in", field);
                    continue;
                }
                props.add(buildCollection(table, name, vo));
                // No fact recorded. The element type an emitter names in the generated
                // `for (Point item : ...)` loop used to be carried here, but it is recoverable from
                // the FQN the mapping already holds — AttributeTypes.classArgOf resolves the name
                // back to the erased element type, which is exactly what this scan would have stored.

            } else if (entityTypeElement(field) != null) {
                // No member type recorded: a to-many entity collection is neither a row prop nor a
                // child-table collection, so nothing asks for a member type of it.
                TypeElement child = entityTypeElement(field);
                props.add(new Prop.EntityCollection(name, descriptorFor(child), null, null));

            } else {
                props.add(new Prop.Scalar(name, Names.columnBase(name), fieldType));
                memberTypes.put(memberKey(typeNameString, name), fieldTypeName);
            }
        }
        if (!valueObject && idProp == null) {
            // Inventing an identifier silently produced a row mapper calling setId(...) on an entity
            // that has no such method — an uncompilable generated source with no hint of the cause.
            // Report it; the placeholder below only keeps this round from failing with an NPE.
            diagnostics.error(
                    "entity '" + element.getSimpleName() + "' declares no identifier; annotate one field with"
                            + " @Identity (or name it 'id')", element);
            idProp = new Prop.Id(MemberNames.ID_PROPERTY, Names.columnBase(MemberNames.ID_PROPERTY), "java.lang.Long");
            memberTypes.put(memberKey(typeNameString, MemberNames.ID_PROPERTY), ClassName.get(Long.class));
        }

        // Resolve one-to-many inverse references after collecting all parent properties.
        List<Prop> resolved = new ArrayList<>(props.size());
        for (Prop prop : props) {
            if (prop instanceof Prop.EntityCollection entityCollection && entityCollection.targetTypeName() != null) {
                TypeElement childElement = elements.getTypeElement(entityCollection.targetTypeName());
                TypeMapping<?> child = childElement != null
                        ? descriptorFor(childElement) : null;
                String inverse = child != null ? findInverseName(typeNameString, child) : null;
                resolved.add(new Prop.EntityCollection(entityCollection.name(), entityCollection.target(),
                        inverse, inverse == null ? null : Names.fkColumn(inverse)));
            } else {
                resolved.add(prop);
            }
        }
        return entityMapping(valueObject, ownerOwns, typeNameString, table, idProp, resolved);
    }

    /**
     * The declared type NAMED {@code fqn}, or {@code null} when the name does not resolve in this
     * compilation.
     *
     * <p>This is the one place a name becomes an element. Five generators used to call
     * {@code elements.getTypeElement(fqn)} themselves, which is why five of them held
     * {@code Elements} — and a generator that holds {@code Elements} cannot be constructed without a
     * round, so none of them could be exercised directly. Turning a name into a type is one question
     * with one answer per round, so it is asked here and memoized, next to the models that are
     * already memoized by the same name.
     *
     * <p>{@code null} is cached as well as a hit, so an unresolvable name is resolved once — the
     * same reason {@link #descriptorFor(String)} does not retry a name that already failed.</p>
     */
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

    /** As {@link #descriptorFor(TypeElement)}, for a type a caller knows only by name. */
    public TypeMapping<?> descriptorFor(String fqn) {
        TypeElement element = elementFor(fqn);
        return element == null ? null : descriptorFor(element);
    }

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
            boolean valueObject = isValueObjectType(element);
            TypeMapping<?> descriptor = buildDescriptor(element, valueObject,
                    ownsAggregateLifecycle(element),
                    valueObject ? null : Names.tableName(element.getSimpleName().toString()));
            descriptorCache.put(fqn, descriptor);
            return descriptor;
        } finally {
            descriptorsInProgress.remove(fqn);
        }
    }

    /**
     * Re-points the relationship targets a mid-cycle build captured as placeholders. While one
     * descriptor is in progress {@link #descriptorFor} returns an empty stand-in, so a
     * {@link Prop.Reference} or {@link Prop.EntityCollection} on an entity built BEFORE its cyclic
     * partner bakes a target with no id — and, for the collection, a null inverse. Once every
     * managed entity is complete those names resolve to real models; this pass rebuilds each managed
     * entity against them so the JOIN projection, the read loaders and the {@code value} switch no
     * longer silently drop the relation. A model whose props already resolve unchanged is left
     * untouched, so a non-cyclic mapping stays byte-identical.
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

    /**
     * Whether {@code element} declares or inherits a method called {@code methodName}.
     *
     * <p>Generated code calls an accessor only after this check, so a bean-less type degrades to a
     * NOTE instead of emitting a call that does not compile.
     *
     * <p>It lives here rather than as a static beside {@link MemberNames} because a static that
     * TAKES {@code Elements} does not keep the environment off its callers — measured, the same
     * shape as {@code AttributeTypes}: two generators held {@code Elements} for this scan and for
     * nothing else. An {@code Elements} parameter means every caller must already hold one.
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

    private static String memberKey(String ownerFqn, String name) {
        return ownerFqn + "#" + name;
    }

    public boolean isValueObjectType(TypeElement element) {
        return element != null
                && element.getAnnotation(io.github.fludakit.data.annotation.ValueObject.class) != null;
    }

    /**
     * Whether the annotated type owns the lifecycle of everything inside it.
     *
     * <p>Only {@code @AggregateRoot} does. A plain {@code @Entity} and a value object never do —
     * the aggregate boundary is the root's, not each member's.</p>
     */
    public boolean ownsAggregateLifecycle(TypeElement element) {
        return element != null
                && element.getAnnotation(io.github.fludakit.data.annotation.AggregateRoot.class) != null;
    }

    /**
     * Builds the mapping whose TYPE carries the domain role: a value object, an aggregate root, or a
     * plain entity. The three are distinct implementations rather than one record with a flag, so
     * {@code switch} over a {@link TypeMapping} stays exhaustive.</p>
     */
    private static TypeMapping<?> entityMapping(boolean valueObject, boolean ownsAggregate,
                                                String typeName, String table, Prop.Id id, List<Prop> props) {
        if (valueObject) {
            return new EmbeddedValueObjectMapping<>(typeName, props);
        }
        return ownsAggregate
                ? new AggregateTypeMapping(typeName, table, id, props)
                : new EntityTypeMapping(typeName, table, id, props);
    }

    private Prop.Reference referenceProp(String name, String fkColumn, String targetTypeName,
                                         boolean composition, boolean backReference, TypeElement target) {
        TypeMapping<?> targetModel = target == null ? null : descriptorFor(target);
        return new Prop.Reference(name, fkColumn, targetTypeName, targetModel, composition,
                backReference);
    }

    /**
     * The child table and its descriptor — the value object's ONE field model, PLACED on that child
     * table, memoized by the placement.
     *
     * <p>The field model comes from {@link #descriptorFor}: the same props the {@code <VO>Metadata}
     * descriptor and the {@code <VO>RowMapper} are generated from. It used to be built a second time
     * by a separate scan of the same fields ({@code valueObjectMetadata}), which is how the two paths
     * came to disagree — one dropped an {@code @Identity} field the other kept as a scalar, so a
     * child-table INSERT and the row mapper reading it back disagreed on the column set.
     *
     * <p>Only the placement is decided here, because it is the part the type cannot answer: the child
     * table comes from the owner's table and this field's name, and the parent FK is the column on it
     * that refers back to the owner. So the cache is keyed on the placement, not the type — the same
     * value object placed by two owners is one field model with two entries.</p>
     */
    private Prop.ElementCollection buildCollection(
            String parentTable, String field, TypeElement valueObject) {
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

    /**
     * Maps a field's type to the {@link TypeName} the generated row mapper declares for it. A type
     * JavaPoet cannot represent (a wildcard, intersection, or unresolvable type argument) would
     * otherwise silently read/write the field as {@code Object} — report it instead, and keep the
     * placeholder so this round does not fail with an NPE.
     */
    private TypeName resolveFieldTypeName(VariableElement field) {
        try {
            return TypeName.get(field.asType());
        } catch (IllegalArgumentException unmappable) {
            diagnostics.error(
                    "field '" + field.getSimpleName() + "' has type '" + field.asType()
                            + "' that cannot be mapped to a generated row-mapper type", field);
            return TypeName.OBJECT;
        }
    }

    private boolean isEntityType(TypeElement element) {
        return element != null
                && (element.getAnnotation(io.github.fludakit.data.annotation.Entity.class) != null
                || element.getAnnotation(io.github.fludakit.data.annotation.AggregateRoot.class) != null);
    }

    private TypeElement embeddedTarget(VariableElement field) {
        if (field.getAnnotation(Association.class) != null
                || !(field.asType() instanceof DeclaredType declared)
                || !(declared.asElement() instanceof TypeElement element)) {
            return null;
        }
        return isValueObjectType(element) ? element : null;
    }

    private TypeElement listElementType(VariableElement field,
                                        java.util.function.Predicate<TypeElement> elementMatcher) {
        if (field.getAnnotation(Association.class) != null
                || !(field.asType() instanceof DeclaredType declared)
                || declared.getTypeArguments().size() != 1
                || !"java.util.List".equals(types.erasure(declared).toString())) {
            return null;
        }
        TypeMirror argument = declared.getTypeArguments().get(0);
        if (!(argument instanceof DeclaredType element)
                || !(element.asElement() instanceof TypeElement typeElement)) {
            return null;
        }
        return elementMatcher.test(typeElement) ? typeElement : null;
    }

    private TypeElement entityTypeElement(VariableElement field) {
        return listElementType(field, this::isEntityType);
    }

    private TypeElement valueObjectElementType(VariableElement field) {
        return listElementType(field, this::isValueObjectType);
    }

    private static TypeElement declaredElement(VariableElement field) {
        if (field.asType() instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement element) {
            return element;
        }
        return null;
    }

    private static ValueObjectMapping<?> embeddedOf(TypeMapping<?> model) {
        return model instanceof ValueObjectMapping<?> vo ? vo : null;
    }

    /**
     * The child's reference back to the parent, by name. It needs the parent's TYPE NAME and nothing
     * else — the parent mapping used to be assembled a second time, still unresolved, purely to hand
     * {@code typeName()} to this loop, so every entity paid for two descriptors to resolve one
     * inverse.
     */
    private static String findInverseName(String parentTypeName, TypeMapping<?> child) {
        for (Prop prop : child.props()) {
            if (prop instanceof Prop.Reference reference
                    && parentTypeName.equals(reference.targetTypeName())) {
                return reference.name();
            }
        }
        return null;
    }
}
