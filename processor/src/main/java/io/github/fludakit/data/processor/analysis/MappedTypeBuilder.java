package io.github.fludakit.data.processor.analysis;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.annotation.Association;
import io.github.fludakit.data.annotation.Identity;
import io.github.fludakit.data.metadata.AggregateTypeMapping;
import io.github.fludakit.data.metadata.EmbeddedValueObjectMapping;
import io.github.fludakit.data.metadata.ElementCollectionValueObjectMapping;
import io.github.fludakit.data.metadata.EntityTypeMapping;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.ValueObjectMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.model.ElementCollection;
import io.github.fludakit.data.processor.model.EntityModel;
import io.github.fludakit.data.processor.model.Property;
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
import java.util.Set;

/** Builds and memoizes the compile-time entity and value-object models used by repository generation. */
public final class MappedTypeBuilder {

    private final Types types;
    private final Elements elements;
    private final Diagnostics diagnostics;
    private final Map<String, TypeMapping> descriptorCache = new HashMap<>();
    private final Set<String> descriptorsInProgress = new HashSet<>();
    private final Map<String, TypeElement> elementsByName = new HashMap<>();
    private final Map<String, EntityModel> entityModels = new HashMap<>();
    private final Map<String, EntityModel> valueObjectModels = new HashMap<>();
    private final Set<String> valueObjectModelsInProgress = new HashSet<>();

    public MappedTypeBuilder(Types types, Elements elements, Diagnostics diagnostics) {
        this.types = types;
        this.elements = elements;
        this.diagnostics = diagnostics;
    }

    /**
     * One mapped type in two shapes: the descriptor the generated {@code <Entity>Metadata} prints,
     * and the compile-time model the emitters work from. They are built by two METHODS, not by one
     * loop doing both — see
     * {@link #buildDescriptor} and {@link #compileModel}.
     */
    public EntityModel buildEntity(TypeElement element) {
        boolean valueObject = isValueObjectType(element);
        boolean ownerOwns = ownsAggregateLifecycle(element);
        TypeMirror typeMirror = types.erasure(element.asType());
        // An entity or value object is always a declared type, so its erased name is a ClassName —
        // and a ClassName knows its own package, which is what six call sites used to ask Elements
        // for. Building it from the element also gets NESTED types right (pkg.Outer.Inner), which
        // TypeName.get(erasure) plus getSimpleName() did not.
        ClassName typeName = ClassName.get(element);
        String table = valueObject ? null : Names.tableName(element.getSimpleName().toString());
        return compileModel(typeMirror, typeName, buildDescriptor(element, valueObject, ownerOwns, table));
    }

    /**
     * Scans the fields ONCE and produces the shipping {@link TypeMapping} — the one printed into the
     * generated {@code <Entity>Metadata}.
     *
     * <p>This is the only pass that makes a mapping decision — which shape each field is, what column
     * it takes, which target it points at. It used to also assemble the compile-time
     * {@link EntityModel} in the same breath, so every one of its seven branches constructed two
     * parallel things and the method carried three accumulators plus two identifier variables.
     *
     * <p>The two facts the descriptor cannot hold are recorded alongside it in
     * {@link DescriptorBuild#facts()} and joined back on by {@link #compileModel}: a JavaPoet type
     * name, and an embedded target's compile-time model. They are carried for two different reasons.
     *
     * <p>The type name genuinely cannot be recovered later. The descriptor carries a type as an FQN
     * {@code String}, and the way back to a {@code TypeName} is lossy: {@code ClassName.bestGuess}
     * reads the last dotted segment as the simple name, so a nested {@code pkg.Outer.Inner} comes
     * back as a class {@code Inner} in a package {@code pkg.Outer}, and a parameterized field type
     * is a string no {@code TypeName} factory parses. Worse, {@code TypeMapping} could not hold a
     * {@code TypeName} even if one were recoverable: JavaPoet is a processor-only dependency, and
     * the descriptor ships into the user's jar.
     *
     * <p>The embedded model is carried for a different reason. It IS reachable by looking the target
     * up by {@code typeName()}, but that lookup would resolve a value object that was still in
     * progress during the scan, where the direct reference correctly stays {@code null}; and
     * {@link Prop.Embedded#target()} prints as {@code null} in the descriptor, so the richer
     * compile-time target is not part of what ships.
     */
    private DescriptorBuild buildDescriptor(TypeElement element, boolean valueObject,
                                            boolean ownerOwns, String table) {
        String typeNameString = element.getQualifiedName().toString();
        Prop.Id idProp = null;
        List<Prop> props = new ArrayList<>();
        Map<String, FieldFacts> facts = new HashMap<>();
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
                facts.put(name, new FieldFacts(fieldTypeName, null));

            } else if (field.getAnnotation(Association.class) != null) {
                TypeElement target = declaredElement(field);
                props.add(referenceProp(name, Names.fkColumn(name),
                        target != null ? target.getQualifiedName().toString() : null, false, false, target));
                facts.put(name, new FieldFacts(fieldTypeName, null));

            } else if (embeddedTarget(field) != null) {
                TypeElement vo = embeddedTarget(field);
                props.add(new Prop.Embedded(name, embeddedOf(descriptorFor(vo))));
                facts.put(name, new FieldFacts(fieldTypeName, valueObjectModel(vo)));

            } else if (isEntityType(declaredElement(field))) {
                TypeElement target = declaredElement(field);
                // Bare entity references compose only when owned by an aggregate root; on a non-root
                // entity they are the inverse FK back to the root — a write-only back-reference.
                boolean composition = ownerOwns;
                props.add(referenceProp(name, Names.fkColumn(name),
                        target.getQualifiedName().toString(), composition, !composition, target));
                facts.put(name, new FieldFacts(fieldTypeName, null));

            } else if (valueObjectElementType(field) != null) {
                TypeElement vo = valueObjectElementType(field);
                if (table == null) {
                    // A value object has no table, so a collection nested inside one has no parent
                    // table to derive its own child table and parent FK from — Names.childTable(null, ..)
                    // used to NPE here, which javac surfaced as a stack trace and which aborted the
                    // whole round. The rejection is REPORTED by valueObjectMetadata, the one place
                    // always reached for an element-collection value object; here it only has to not
                    // crash, so the field is dropped from this view of the model.
                    continue;
                }
                props.add(buildCollection(table, name, vo));
                // The element type, not the List type: it is what an emitter names in the generated
                // `for (Point item : ...)` loop and in the <VO>RowMapper it calls.
                facts.put(name, new FieldFacts(TypeName.get(types.erasure(vo.asType())), null));

            } else if (entityTypeElement(field) != null) {
                // No fact recorded: a to-many entity collection is neither a Property nor a
                // child-table collection, so compileModel skips it without needing a type.
                TypeElement child = entityTypeElement(field);
                props.add(new Prop.EntityCollection(name, descriptorFor(child), null, null));

            } else {
                props.add(new Prop.Scalar(name, Names.columnBase(name), fieldType));
                facts.put(name, new FieldFacts(fieldTypeName, null));
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
            facts.put(MemberNames.ID_PROPERTY, new FieldFacts(ClassName.get(Long.class), null));
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
        return new DescriptorBuild(
                entityMapping(valueObject, ownerOwns, typeNameString, table, idProp, resolved), facts);
    }

    /**
     * Joins {@link DescriptorBuild#facts()} back onto the descriptor's props.
     *
     * <p>The compile-time model is a function of the descriptor plus what only the field scan could
     * see, so nothing here re-decides a mapping question — it only re-attaches the two things the
     * descriptor cannot carry. A prop the scan recorded no fact for (a to-many entity collection) is
     * one that has no column on the owner's row and therefore no {@link Property}.
     */
    private static EntityModel compileModel(TypeMirror typeMirror, ClassName typeName,
                                            DescriptorBuild build) {
        TypeMapping<?> metadata = build.metadata();
        Map<String, FieldFacts> facts = build.facts();
        Prop.Id idProp = metadata.id();
        Property id = idProp == null ? null
                : new Property(facts.get(idProp.name()).type(), idProp, null);
        List<Property> properties = new ArrayList<>();
        List<ElementCollection> collections = new ArrayList<>();
        for (Prop prop : metadata.props()) {
            if (prop instanceof Prop.EntityCollection) {
                continue;
            }
            FieldFacts fact = facts.get(prop.name());
            if (prop instanceof Prop.ElementCollection elementCollection) {
                collections.add(new ElementCollection(fact.type(), elementCollection));
            } else {
                properties.add(new Property(fact.type(), prop, fact.embeddedModel()));
            }
        }
        return new EntityModel(typeMirror, typeName, id, properties, collections, metadata);
    }

    /**
     * What the field scan can see and the shipping descriptor cannot carry: a JavaPoet type name, and
     * — for an embedded member — the compile-time model of its value object.
     *
     * @param type          the property's JavaPoet type; for an element collection this is the list's
     *                      ELEMENT type, the only type any emitter names for it
     * @param embeddedModel the embedded target's {@link EntityModel}, else {@code null}
     */
    private record FieldFacts(TypeName type, EntityModel embeddedModel) { }

    /** One field scan: the shipping descriptor, plus the compile-time facts it cannot hold. */
    private record DescriptorBuild(TypeMapping<?> metadata, Map<String, FieldFacts> facts) { }

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
     * same reason {@link #entityModel(String)} does not retry a name that already failed.
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
                    ? new EmbeddedValueObjectMapping(fqn, List.of())
                    : entityMapping(false, ownsAggregateLifecycle(element), fqn,
                            Names.tableName(element.getSimpleName().toString()), null, List.of());
        }
        descriptorsInProgress.add(fqn);
        try {
            TypeMapping<?> descriptor = buildEntity(element).metadata();
            descriptorCache.put(fqn, descriptor);
            return descriptor;
        } finally {
            descriptorsInProgress.remove(fqn);
        }
    }

    public EntityModel entityModel(TypeElement element) {
        String fqn = element.getQualifiedName().toString();
        EntityModel cached = entityModels.get(fqn);
        if (cached == null) {
            cached = buildEntity(element);
            entityModels.put(fqn, cached);
        }
        return cached;
    }

    /**
     * The model of the mapped type NAMED {@code fqn}, or {@code null} when the name does not resolve.
     *
     * <p>The name-keyed form exists because a name is all a caller has: the descriptor carries a
     * target as an FQN {@code String}, so a generator handed a {@code Prop.Reference} must resolve
     * that name before it can reach the model. Resolving a type by name IS an environment call, so
     * it belongs in the one class that owns {@code Elements} and memoizes the result — not in every
     * generator, each of which held {@code Elements} for this and nothing else.
     */
    public EntityModel entityModel(String fqn) {
        TypeElement element = elementFor(fqn);
        return element == null ? null : entityModel(element);
    }

    /** As {@link #entityModel(String)}, for a value object. */
    public EntityModel valueObjectModel(String fqn) {
        TypeElement element = elementFor(fqn);
        return element == null ? null : valueObjectModel(element);
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

    public EntityModel valueObjectModel(TypeElement element) {
        String fqn = element.getQualifiedName().toString();
        EntityModel cached = valueObjectModels.get(fqn);
        if (cached != null) {
            return cached;
        }
        if (!valueObjectModelsInProgress.add(fqn)) {
            return null;
        }
        try {
            EntityModel model = buildEntity(element);
            valueObjectModels.put(fqn, model);
            return model;
        } finally {
            valueObjectModelsInProgress.remove(fqn);
        }
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
            return new EmbeddedValueObjectMapping(typeName, props);
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
     * The child table and its descriptor. It returns the DESCRIPTOR prop only: the child table and the
     * parent FK are both reachable from it, and the value object's JavaPoet type is a fact the scan
     * records separately.
     */
    private Prop.ElementCollection buildCollection(
            String parentTable, String field, TypeElement valueObject) {
        String childTable = Names.childTable(parentTable, field);
        String parentFk = Names.fkColumn(parentTable);
        ElementCollectionValueObjectMapping<?> target = valueObjectMetadata(valueObject, childTable, parentFk);
        return new Prop.ElementCollection(field, target, target.columns());
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

    private ElementCollectionValueObjectMapping<?> valueObjectMetadata(
            TypeElement valueObject, String childTable, String parentFk) {
        List<Prop> props = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element member : elements.getAllMembers(valueObject)) {
            if (member.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) member;
            if (field.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            String name = field.getSimpleName().toString();
            if (!seen.add(name) || field.getAnnotation(Identity.class) != null || name.equals(MemberNames.ID_PROPERTY)) {
                continue;
            }
            String fieldType = field.asType().toString();
            TypeElement nestedCollection = valueObjectElementType(field);
            if (nestedCollection != null) {
                // A value object in an element collection is ONE child table reached from its OWNER's
                // table; a collection nested inside it would need a child table of a row that has no
                // identity of its own. This used to be a NullPointerException out of the processor
                // (Names.childTable(null, ..)), surfacing as a javac stack trace that aborted the round
                // and took every other repository in it down. Reject the declaration instead: it is a
                // shape the model cannot express, not a state the generator can degrade gracefully from.
                diagnostics.error(
                        "field '" + name + "' of value object '" + valueObject.getSimpleName() + "' is a nested"
                                + " collection of '" + nestedCollection.getSimpleName() + "': a value object in an"
                                + " element collection has no table of its own, so a collection inside one has no"
                                + " child table to live in", field);
                continue;
            }
            if (field.getAnnotation(Association.class) != null) {
                TypeElement target = declaredElement(field);
                props.add(referenceProp(name, Names.fkColumn(name),
                        target != null ? target.getQualifiedName().toString() : null, false, false, target));
            } else if (embeddedTarget(field) != null) {
                // A value object nested in an element-collection value object flattens EXACTLY like the
                // same value object embedded in an entity (address_point_lat). Building it as one Scalar
                // per property is what made the two paths disagree: the child-table INSERT bound the
                // whole value object to a single column while <VO>RowMapper read the flattened leaves,
                // so the row written was never the row read back.
                props.add(new Prop.Embedded(name, embeddedOf(descriptorFor(embeddedTarget(field)))));
            } else {
                props.add(new Prop.Scalar(name, Names.columnBase(name), fieldType));
            }
        }
        return new ElementCollectionValueObjectMapping(valueObject.getQualifiedName().toString(),
                childTable, parentFk, List.copyOf(props));
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
