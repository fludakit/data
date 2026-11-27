package io.github.fludakit.data.processor;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Association;
import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;
import io.github.fludakit.data.annotation.ValueObject;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.support.Diagnostics;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Scans {@code @Entity}, {@code @AggregateRoot}, and {@code @ValueObject} annotated types and
 * builds {@link TypeDefinition} instances into the {@link TypeGraph} the processor owns. All
 * building happens here, during the early scan — consumers downstream read the populated graph
 * and never trigger a build.
 *
 * <p>Two-pass approach: the first pass builds all definitions with {@code definition=null} for unresolved
 * cross-references; the second pass fills in targets from the cache.
 */
final class TypeDefinitionScanner {

    private final Types types;
    private final Elements elements;
    private final Diagnostics diagnostics;

    /** The whole type graph: definitions, managed entities, and deferred targets. */
    private final TypeGraph graph;

    /** FQNs currently being built — cycle detection. */
    private final Set<String> inProgress = new HashSet<>();

    /** The second pass, which re-resolves relation targets once every type has been scanned. */
    private final ReferenceResolver references;

    TypeDefinitionScanner(Types types, Elements elements, Diagnostics diagnostics, TypeGraph graph) {
        this.types = types;
        this.elements = elements;
        this.diagnostics = diagnostics;
        this.graph = graph;
        this.references = new ReferenceResolver(graph, this::get);
    }

    // ── Scanning ─────────────────────────────────────────────────────────

    /**
     * Scans the given {@code @Entity}, {@code @AggregateRoot}, and {@code @ValueObject} types,
     * then resolves cross-references in a second pass.
     *
     * <p>A failing type is reported as a diagnostic located on that type and skipped; the remaining
     * types are still scanned and the resolution pass runs for everything that succeeded.</p>
     */
    void scanTypes(List<TypeElement> types) {
        for (TypeElement type : types) {
            try {
                scanType(type, false);
            } catch (RuntimeException e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                diagnostics.error("fluda-data: " + msg, type);
            }
        }

        try {
            references.resolveAllReferences();
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            diagnostics.error("fluda-data: " + msg, null);
        }
    }

    /**
     * Builds the {@link TypeDefinition} for a single type, optionally forcing entity treatment.
     * Returns the cached definition if already scanned, or {@code null} if a cycle is detected.
     */
    private TypeDefinition scanType(TypeElement type, boolean forceEntity) {
        String fqn = type.getQualifiedName().toString();
        if (graph.contains(fqn)) return graph.get(fqn);
        if (!inProgress.add(fqn)) return null; // cycle
        try {
            return buildDefinition(type, fqn, forceEntity);
        } finally {
            inProgress.remove(fqn);
        }
    }

    private TypeDefinition buildDefinition(TypeElement type, String fqn, boolean forceEntity) {
        boolean hasAggregateAnnotation = type.getAnnotation(AggregateRoot.class) != null;
        boolean hasEntityAnnotation = type.getAnnotation(Entity.class) != null;
        boolean hasVOAnnotation = type.getAnnotation(ValueObject.class) != null;
        
        // @ValueObject always wins — never treat VOs as entities even when forceEntity=true
        boolean isAggregate = !hasVOAnnotation && hasAggregateAnnotation;
        boolean isEntity = !hasVOAnnotation && (forceEntity || hasAggregateAnnotation || hasEntityAnnotation);
        boolean isVO = hasVOAnnotation;

        String tableName = isEntity ? Names.tableName(type.getSimpleName().toString()) : null;
        String ownerSingular = type.getSimpleName().toString();

        // Collect props by category
        Props props = Props.create();
        TypeDefinition.Prop.Id idProp = null;
        String annotatedIdName = null;

        for (Element member : elements.getAllMembers(type)) {
            if (member.getKind() != ElementKind.FIELD) continue;
            if (member.getModifiers().contains(Modifier.STATIC)) continue;

            VariableElement field = (VariableElement) member;
            String fieldName = field.getSimpleName().toString();
            TypeMirror fieldType = field.asType();

            if (hasIdentityAnnotation(field)) {
                if (annotatedIdName != null) {
                    throw new IllegalArgumentException("Entity " + fqn + " declares multiple @Identity fields ('"
                            + annotatedIdName + "', '" + fieldName + "'); keep exactly one");
                }
                annotatedIdName = fieldName;
                // @Identity wins over the name fallback regardless of field order
                idProp = buildIdProp(fieldName, fieldType);
            } else if (idProp == null && isNamedId(field)) {
                idProp = buildIdProp(fieldName, fieldType);
            } else if (isVO) {
                classifyVOField(field, props);
            } else {
                classifyEntityField(field, ownerSingular, isAggregate, fqn, props);
            }
        }

        // Build appropriate TypeDefinition variant
        TypeDefinition definition;
        if (isAggregate) {
            if (idProp == null) {
                throw new IllegalArgumentException("Entity " + fqn
                        + " declares no identifier property; annotate a field with @Identity");
            }
            definition = new TypeDefinition.Aggregate(
                    fqn, tableName, idProp, null,
                    props.scalars(), props.embedded(), props.associations(), props.entityCollections(),
                    props.elementCollections());
        } else if (isEntity) {
            if (idProp == null) {
                throw new IllegalArgumentException("Entity " + fqn
                        + " declares no identifier property; annotate a field with @Identity");
            }
            definition = new TypeDefinition.Entity(
                    fqn, tableName, idProp, null,
                    props.scalars(), props.embedded(), props.associations(), props.entityCollections(),
                    props.elementCollections());
        } else {
            definition = props.toValueObject(fqn);
        }

        graph.put(fqn, definition);
        return definition;
    }

    /**
     * Resolves a type by name while scanning: a cached definition when the graph already holds
     * one, otherwise an on-demand build with forced entity treatment — the rule that makes a
     * relation target an entity because it is referenced. Scan-internal only; repository entity
     * types must carry their own annotation, and consumers read the populated graph exclusively.
     */
    private TypeDefinition get(String fqn) {
        TypeDefinition def = graph.get(fqn);
        if (def == null) {
            TypeElement te = elements.getTypeElement(fqn);
            if (te != null && !inProgress.contains(te.getQualifiedName().toString())) {
                scanType(te, true);
                def = graph.get(fqn);
            }
        }
        return def;
    }

    private TypeDefinition.Prop.Id buildIdProp(String fieldName, TypeMirror fieldType) {
        String typeFqn = typeFqn(fieldType);
        String columnName = Names.toSnakeCase(fieldName);
        return new TypeDefinition.Prop.Id(fieldName, typeFqn, columnName);
    }

    private static boolean hasIdentityAnnotation(VariableElement field) {
        return field.getAnnotation(Identity.class) != null;
    }

    private static boolean isNamedId(VariableElement field) {
        return field.getSimpleName().toString().equalsIgnoreCase("id");
    }

    /** The scanner's identifier rule: {@code @Identity} or a field named {@code id}. */
    private static boolean isIdentifierField(VariableElement field) {
        return hasIdentityAnnotation(field) || isNamedId(field);
    }

    private boolean declaresIdentifier(TypeElement type) {
        return elements.getAllMembers(type).stream()
                .filter(m -> m.getKind() == ElementKind.FIELD)
                .filter(m -> !m.getModifiers().contains(Modifier.STATIC))
                .map(m -> (VariableElement) m)
                .anyMatch(TypeDefinitionScanner::isIdentifierField);
    }

    // ── Property accumulators ────────────────────────────────────────────

    /**
     * The property lists of one type under construction. Fields are classified in declaration order
     * and appended here, so the list order is the property order of the resulting definition.
     */
    private record Props(List<TypeDefinition.Prop.Scalar> scalars,
                         List<TypeDefinition.Prop.Embedded> embedded,
                         List<TypeDefinition.Prop.Association> associations,
                         List<TypeDefinition.Prop.EntityCollection> entityCollections,
                         List<TypeDefinition.Prop.ElementCollection> elementCollections) {

        static Props create() {
            return new Props(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>());
        }

        TypeDefinition.ValueObject toValueObject(String fqn) {
            return new TypeDefinition.ValueObject(fqn, scalars, embedded, associations, entityCollections,
                    elementCollections);
        }
    }

    // ── Field classification — entities / aggregate roots ────────────────

    private void classifyEntityField(VariableElement field, String ownerSingular, boolean isAggregate,
                                     String ownerFqn, Props props) {
        String fieldName = field.getSimpleName().toString();
        TypeMirror fieldType = field.asType();
        TypeElement fieldTypeElement = asTypeElement(fieldType);

        // List<X> — element collection or entity collection
        if (isList(fieldType)) {
            TypeMirror elementType = elementTypeOf(fieldType);
            if (elementType == null) return;
            TypeElement elementTE = asTypeElement(elementType);
            if (elementTE == null) return;

            if (isVO(elementTE)) {
                buildElementCollection(fieldName, elementTE, ownerSingular, props.elementCollections());
                return;
            }
            if (isEntityOrAggregate(elementTE)) {
                buildEntityCollection(fieldName, elementTE, ownerSingular, ownerFqn, props.entityCollections());
                return;
            }
            return;
        }

        // Single-valued field
        if (fieldTypeElement != null) {
            if (isVO(fieldTypeElement)) {
                TypeDefinition voDef = buildEmbeddedVO(fieldTypeElement);
                props.embedded().add(new TypeDefinition.Prop.Embedded(fieldName,
                        fieldTypeElement.getQualifiedName().toString(),
                        (TypeDefinition.ValueObject) voDef, null));
                return;
            }
            if (field.getAnnotation(Association.class) != null) {
                String targetFqn = fieldTypeElement.getQualifiedName().toString();
                addAssociation(props.associations(), fieldName, targetFqn, get(targetFqn), false);
                return;
            }
            if (isAggregate && isEntityOrAggregate(fieldTypeElement)) {
                String targetFqn = fieldTypeElement.getQualifiedName().toString();
                // Aggregate-to-aggregate/entity reference: treat as COMPOSITION by default
                addAssociation(props.associations(), fieldName, targetFqn, get(targetFqn), true);
                return;
            }
            // Bare entity/aggregate reference on non-aggregate owner - treat as association
            if (!isAggregate && isEntityOrAggregate(fieldTypeElement)) {
                String targetFqn = fieldTypeElement.getQualifiedName().toString();
                TypeDefinition target = get(targetFqn);
                if (target instanceof TypeDefinition.Entity entityTarget) {
                    props.associations().add(new TypeDefinition.Prop.Association(fieldName,
                            targetFqn, entityTarget, Names.fkColumn(fieldName), false, false, null));
                } else if (target instanceof TypeDefinition.Aggregate aggregateTarget) {
                    // Detect back-reference: aggregate has EntityCollection pointing to owner
                    boolean isBackRef = aggregateTarget.entityCollections().stream()
                            .anyMatch(ec -> ec.javaType().equals(ownerFqn));

                    // Treat aggregate target as entity for FK purposes
                    // Create a synthetic Entity view of the Aggregate for the association
                    TypeDefinition.Entity entityView = aggregateTarget.asEntity();
                    props.associations().add(new TypeDefinition.Prop.Association(fieldName,
                            targetFqn, entityView, Names.fkColumn(fieldName), isBackRef, false, null));
                } else if (target == null) {
                    // Target not available yet - add placeholder with null target for resolution pass
                    props.associations().add(new TypeDefinition.Prop.Association(fieldName,
                            targetFqn, null, Names.fkColumn(fieldName), false, false, null));
                    // Track for resolution pass
                    graph.deferTarget(ownerFqn, fieldName, targetFqn);
                }
                return;
            }
        }

        // Default: scalar
        props.scalars().add(new TypeDefinition.Prop.Scalar(fieldName, typeFqn(fieldType),
                Names.toSnakeCase(fieldName), true));
    }

    // ── Field classification — value objects ─────────────────────────────

    private void classifyVOField(VariableElement field, Props props) {
        String fieldName = field.getSimpleName().toString();
        TypeMirror fieldType = field.asType();
        TypeElement fieldTypeElement = asTypeElement(fieldType);
        if (fieldTypeElement != null && isVO(fieldTypeElement)) {
            TypeDefinition voDef = buildEmbeddedVO(fieldTypeElement);
            props.embedded().add(new TypeDefinition.Prop.Embedded(fieldName,
                    fieldTypeElement.getQualifiedName().toString(),
                    (TypeDefinition.ValueObject) voDef, null));
            return;
        }
        
        // Handle List<X> in VOs - could be ElementCollection or EntityCollection
        if (isList(fieldType)) {
            TypeMirror elementType = elementTypeOf(fieldType);
            if (elementType != null) {
                TypeElement elementTE = asTypeElement(elementType);
                if (elementTE != null) {
                    if (isEntityOrAggregate(elementTE)) {
                        // EntityCollection within a VO
                        String targetFqn = elementTE.getQualifiedName().toString();
                        TypeDefinition target = get(targetFqn);
                        if (target instanceof TypeDefinition.Entity entityTarget) {
                            props.entityCollections().add(new TypeDefinition.Prop.EntityCollection(fieldName,
                                    targetFqn, entityTarget, Names.fkColumn(fieldName), null));
                        }
                        return;
                    }
                    // Non-entity list elements: only @ValueObject elements have a child-table shape.
                    String elementFqn = elementTE.getQualifiedName().toString();
                    if (elementTE.getKind() == ElementKind.ENUM
                            || elementFqn.startsWith("java.") || elementFqn.startsWith("javax.")) {
                        diagnostics.error("fluda-data: unsupported element-collection element type "
                                + elementFqn + " for field '" + fieldName + "'", field);
                        return;
                    }
                    if (!isVO(elementTE)) {
                        if (elementTE.getKind() == ElementKind.RECORD) {
                            diagnostics.error("fluda-data: record element type " + elementFqn
                                    + " is not yet supported as an implicit value object (issue #4)", field);
                        } else if (declaresIdentifier(elementTE)) {
                            diagnostics.error("fluda-data: element type " + elementFqn + " of field '"
                                    + fieldName + "' declares an identifier; annotate it with @Entity", field);
                        } else {
                            diagnostics.error("fluda-data: element type " + elementFqn + " of field '"
                                    + fieldName + "' is not annotated; annotate it with @ValueObject", field);
                        }
                        return;
                    }

                    graph.markElementCollectionValueObject(elementFqn);
                    TypeDefinition targetType = graph.get(elementFqn);
                    if (targetType == null && !inProgress.contains(elementFqn)) {
                        scanType(elementTE, false);
                        targetType = graph.get(elementFqn);
                    }
                    if (targetType instanceof TypeDefinition.ValueObject vo) {
                        props.elementCollections().add(new TypeDefinition.Prop.ElementCollection(fieldName,
                                elementFqn, vo, Names.childTable(elementTE.getSimpleName().toString()),
                                Names.fkColumn(fieldName)));
                    } else {
                        diagnostics.error("fluda-data: element type " + elementFqn + " of field '"
                                + fieldName + "' could not be resolved as a value object", field);
                    }
                    return;
                }
            }
        }
        
        // Handle single-valued entity/aggregate references in VOs
        if (fieldTypeElement != null && isEntityOrAggregate(fieldTypeElement)) {
            String targetFqn = fieldTypeElement.getQualifiedName().toString();
            addAssociation(props.associations(), fieldName, targetFqn, get(targetFqn), false);
            return;
        }
        
        // Default: scalar
        props.scalars().add(new TypeDefinition.Prop.Scalar(fieldName, typeFqn(fieldType),
                Names.toSnakeCase(fieldName), true));
    }

    /**
     * Adds a to-one association for a resolved target, viewed as an entity (an aggregate target is
     * flattened to its FK-only entity view). No-op when the target is unresolved.
     */
    private void addAssociation(List<TypeDefinition.Prop.Association> associations, String fieldName,
                                String targetFqn, TypeDefinition target, boolean composition) {
        TypeDefinition.Entity entityTarget = target != null ? target.asEntity() : null;
        if (entityTarget != null) {
            associations.add(new TypeDefinition.Prop.Association(fieldName, targetFqn, entityTarget,
                    Names.fkColumn(fieldName), false, composition, null));
        }
    }

    // ── VO builders ──────────────────────────────────────────────────────

    private TypeDefinition buildEmbeddedVO(TypeElement voType) {
        String voFqn = voType.getQualifiedName().toString();
        if (!inProgress.add(voFqn)) {
            diagnostics.note("Cycle detected in VO scanning: " + voFqn + " — skipping embedded fields", voType);
            return new TypeDefinition.ValueObject(voFqn, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        try {
            Props props = Props.create();
            scanVOProps(voType, props);
            return props.toValueObject(voFqn);
        } finally {
            inProgress.remove(voFqn);
        }
    }

    private void buildElementCollection(String fieldName, TypeElement elementTE, String ownerSingular,
                                        List<TypeDefinition.Prop.ElementCollection> elementCollections) {
        String voFqn = elementTE.getQualifiedName().toString();
        String elementTypeName = elementTE.getSimpleName().toString();
        String childTable = Names.childTable(elementTypeName);
        String parentFkCol = Names.fkColumn(ownerSingular);

        // Track this VO as used in an element collection
        graph.markElementCollectionValueObject(voFqn);

        // Look up the element type's TypeDefinition from the graph
        TypeDefinition targetType = graph.get(voFqn);
        if (targetType == null) {
            // Element type not scanned yet - scan it now
            if (!inProgress.contains(voFqn)) {
                scanType(elementTE, true);
                targetType = graph.get(voFqn);
            }
        }

        elementCollections.add(new TypeDefinition.Prop.ElementCollection(fieldName,
                voFqn, (TypeDefinition.ValueObject) targetType, childTable, parentFkCol));
    }

    private void scanVOProps(TypeElement voType, Props props) {
        for (Element member : elements.getAllMembers(voType)) {
            if (member.getKind() != ElementKind.FIELD) continue;
            if (member.getModifiers().contains(Modifier.STATIC)) continue;

            VariableElement field = (VariableElement) member;
            if (field.getAnnotation(Identity.class) != null) continue;

            classifyVOField(field, props);
        }
    }

    // ── Entity collection builder ────────────────────────────────────────

    private void buildEntityCollection(String fieldName, TypeElement elementTE,
                                       String ownerSingular, String ownerFqn,
                                       List<TypeDefinition.Prop.EntityCollection> entityCollections) {
        String targetFqn = elementTE.getQualifiedName().toString();
        TypeDefinition target = get(targetFqn);
        String inverseFkColumn = Names.fkColumn(ownerSingular);

        if (target == null) {
            // Target not available yet - defer to resolution pass
            graph.deferTarget(ownerFqn, fieldName, targetFqn);
            // Still add a placeholder with null target for now
            entityCollections.add(new TypeDefinition.Prop.EntityCollection(fieldName,
                    targetFqn, null, inverseFkColumn, null));
        } else if (target instanceof TypeDefinition.Entity entityTarget) {
            entityCollections.add(new TypeDefinition.Prop.EntityCollection(fieldName,
                    targetFqn, entityTarget, inverseFkColumn, null));
        }
    }

    // ── Resolution pass ──────────────────────────────────────────────────

    /**
     * The second pass: rebuilds every entity and aggregate whose to-one or to-many relation targets
     * can be resolved against the graph now that all types are scanned. Targets are looked up through
     * the scanner's {@code get}, which can still scan a type on demand and so shares its cycle
     * detection.
     */
    private static final class ReferenceResolver {

        private final TypeGraph graph;
        private final Function<String, TypeDefinition> lookup;

        ReferenceResolver(TypeGraph graph, Function<String, TypeDefinition> lookup) {
            this.graph = graph;
            this.lookup = lookup;
        }

        void resolveAllReferences() {
            List<String> keys = new ArrayList<>(graph.nodes());
            for (String fqn : keys) {
                TypeDefinition definition = graph.get(fqn);
                TypeDefinition resolved = resolveDefinition(definition, fqn);
                if (resolved != definition) {
                    graph.put(fqn, resolved);
                }
            }
        }

        private TypeDefinition resolveDefinition(TypeDefinition definition, String ownerFqn) {
            List<TypeDefinition.Prop.Association> associations;
            List<TypeDefinition.Prop.EntityCollection> entityCollections;
            if (definition instanceof TypeDefinition.Entity entity) {
                associations = entity.associations();
                entityCollections = entity.entityCollections();
            } else if (definition instanceof TypeDefinition.Aggregate aggregate) {
                associations = aggregate.associations();
                entityCollections = aggregate.entityCollections();
            } else {
                return definition;
            }

            List<TypeDefinition.Prop.Association> resolvedAssociations = new ArrayList<>();
            boolean changed = false;
            for (TypeDefinition.Prop.Association assoc : associations) {
                TypeDefinition.Prop.Association resolved = resolveAssociation(assoc, ownerFqn);
                resolvedAssociations.add(resolved);
                if (resolved != assoc) changed = true;
            }

            List<TypeDefinition.Prop.EntityCollection> resolvedEntityCollections = new ArrayList<>();
            for (TypeDefinition.Prop.EntityCollection ec : entityCollections) {
                TypeDefinition.Prop.EntityCollection resolved = resolveEntityCollection(ec, ownerFqn);
                resolvedEntityCollections.add(resolved);
                if (resolved != ec) changed = true;
            }

            if (!changed) {
                return definition;
            }

            return definition.accept(new TypeDefinition.Visitor<>() {
                @Override
                public TypeDefinition visitEntity(TypeDefinition.Entity entity) {
                    return new TypeDefinition.Entity(
                            entity.typeName(), entity.tableName(), entity.id(), entity.version(),
                            entity.scalars(), entity.embedded(), resolvedAssociations, resolvedEntityCollections,
                            entity.elementCollections());
                }

                @Override
                public TypeDefinition visitAggregate(TypeDefinition.Aggregate aggregate) {
                    return new TypeDefinition.Aggregate(
                            aggregate.typeName(), aggregate.tableName(), aggregate.id(),
                            aggregate.version(), aggregate.scalars(), aggregate.embedded(),
                            resolvedAssociations, resolvedEntityCollections, aggregate.elementCollections());
                }

                @Override
                public TypeDefinition visitValueObject(TypeDefinition.ValueObject valueObject) {
                    return valueObject;
                }
            });
        }

        private TypeDefinition.Prop.Association resolveAssociation(TypeDefinition.Prop.Association assoc,
                                                                   String ownerFqn) {
            // Get the original target type from javaType (not the synthetic Entity view)
            TypeDefinition originalTarget = lookup.apply(assoc.javaType());

            if (originalTarget == null) {
                String targetFqn = graph.pendingTarget(ownerFqn, assoc.name());
                if (targetFqn != null) {
                    originalTarget = lookup.apply(targetFqn);
                }
            }

            // Detect back-reference: if original target is Aggregate with EntityCollection pointing to owner
            boolean isBackRef = assoc.backReference();
            if (!isBackRef && originalTarget instanceof TypeDefinition.Aggregate aggregateTarget) {
                isBackRef = aggregateTarget.entityCollections().stream()
                        .anyMatch(ec -> ec.javaType().equals(ownerFqn));
            }

            if (originalTarget instanceof TypeDefinition.Entity entityTarget) {
                return new TypeDefinition.Prop.Association(assoc.name(), assoc.javaType(),
                        entityTarget, assoc.fkColumn(), isBackRef, assoc.composition(), assoc.cascade());
            } else if (originalTarget instanceof TypeDefinition.Aggregate aggregateTarget) {
                // Create synthetic Entity view if needed
                TypeDefinition.Entity entityView;
                if (assoc.definition() != null) {
                    entityView = assoc.definition();
                } else {
                    entityView = aggregateTarget.asEntity();
                }
                return new TypeDefinition.Prop.Association(assoc.name(), assoc.javaType(),
                        entityView, assoc.fkColumn(), isBackRef, assoc.composition(), assoc.cascade());
            }

            return assoc;
        }

        private TypeDefinition.Prop.EntityCollection resolveEntityCollection(TypeDefinition.Prop.EntityCollection ec,
                                                                             String ownerFqn) {
            if (ec.definition() != null) return ec;

            // Try to get target from javaType (fully qualified class name)
            TypeDefinition target = lookup.apply(ec.javaType());
            if (target == null) {
                String targetFqn = graph.pendingTarget(ownerFqn, ec.name());
                if (targetFqn != null) {
                    target = lookup.apply(targetFqn);
                }
            }

            if (target instanceof TypeDefinition.Entity entityTarget) {
                return new TypeDefinition.Prop.EntityCollection(ec.name(), ec.javaType(),
                        entityTarget, ec.fkColumn(), ec.mappedBy());
            }

            return ec;
        }
    }

    // ── Type utilities ───────────────────────────────────────────────────

    private static TypeElement asTypeElement(TypeMirror type) {
        if (type == null) return null;
        if (type.getKind() == TypeKind.NONE) return null;
        TypeMirror unwrapped = type;
        if (unwrapped.getKind() == TypeKind.ARRAY) return null;
        if (unwrapped.getKind() != TypeKind.DECLARED) return null;
        Element element = ((DeclaredType) unwrapped).asElement();
        return element instanceof TypeElement te ? te : null;
    }

    private static boolean isEntityOrAggregate(TypeElement te) {
        return te.getAnnotation(Entity.class) != null
                || te.getAnnotation(AggregateRoot.class) != null;
    }

    private static boolean isVO(TypeElement te) {
        return te.getAnnotation(ValueObject.class) != null;
    }

    private static boolean isList(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        DeclaredType dt = (DeclaredType) type;
        TypeElement te = (TypeElement) dt.asElement();
        return te.getQualifiedName().contentEquals("java.util.List");
    }

    private static TypeMirror elementTypeOf(TypeMirror listType) {
        DeclaredType dt = (DeclaredType) listType;
        List<? extends TypeMirror> typeArgs = dt.getTypeArguments();
        if (typeArgs.isEmpty()) return null;
        return typeArgs.getFirst();
    }

    private String typeFqn(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return type.toString();
        }
        Element element = types.asElement(type);
        if (element instanceof TypeElement te) {
            return te.getQualifiedName().toString();
        }
        return type.toString();
    }
}
