package io.github.fludakit.data.processor;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Association;
import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;
import io.github.fludakit.data.annotation.ValueObject;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.support.Diagnostics;

import javax.annotation.processing.RoundEnvironment;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans {@code @Entity}, {@code @AggregateRoot}, and {@code @ValueObject} annotated types from a
 * processing round and builds {@link TypeDefinition} instances cached by fully-qualified type name.
 *
 * <p>Two-pass approach: the first pass builds all definitions with {@code definition=null} for unresolved
 * cross-references; the second pass fills in targets from the cache.
 */
final class TypeMappingVisitor {

    private final Types types;
    private final Elements elements;
    private final Diagnostics diagnostics;

    /** Cache of scanned type definitions keyed by fully-qualified type name. */
    private final Map<String, TypeDefinition> cache = new LinkedHashMap<>();

    /** FQNs currently being built — cycle detection. */
    private final Set<String> inProgress = new HashSet<>();

    /** FQNs of entities and aggregate roots (not value objects). */
    private final Set<String> entityFqns = new LinkedHashSet<>();

    /** FQNs of value objects used as element collection element types. */
    private final Set<String> elementCollectionValueObjects = new LinkedHashSet<>();

    /**
     * Pending relation definition FQNs for the resolution pass.
     * Key: owner FQN, Value: (prop name → definition FQN).
     */
    private final Map<String, Map<String, String>> pendingTargets = new HashMap<>();

    TypeMappingVisitor(Types types, Elements elements, Diagnostics diagnostics) {
        this.types = types;
        this.elements = elements;
        this.diagnostics = diagnostics;
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Scans all {@code @Entity}, {@code @AggregateRoot}, and {@code @ValueObject} types in the
     * given round, then resolves cross-references in a second pass.
     */
    void scanRound(RoundEnvironment roundEnv) {
        List<TypeElement> allTypes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        collectAnnotated(roundEnv, Entity.class, allTypes, seen);
        collectAnnotated(roundEnv, AggregateRoot.class, allTypes, seen);
        collectAnnotated(roundEnv, ValueObject.class, allTypes, seen);

        for (TypeElement te : allTypes) {
            visitType(te);
        }

        resolveAllReferences();
    }

    /**
     * Builds the {@link TypeDefinition} for a single type. Returns the cached definition if already
     * scanned, or {@code null} if a cycle is detected.
     */
    TypeDefinition visitType(TypeElement type) {
        return visitType(type, false);
    }
    
    /**
     * Builds the {@link TypeDefinition} for a single type, optionally forcing entity treatment.
     */
    TypeDefinition visitType(TypeElement type, boolean forceEntity) {
        String fqn = type.getQualifiedName().toString();
        if (cache.containsKey(fqn)) return cache.get(fqn);
        if (!inProgress.add(fqn)) return null; // cycle

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
        List<TypeDefinition.Prop.Scalar> scalars = new ArrayList<>();
        List<TypeDefinition.Prop.Embedded> embedded = new ArrayList<>();
        List<TypeDefinition.Prop.Association> associations = new ArrayList<>();
        List<TypeDefinition.Prop.EntityCollection> entityCollections = new ArrayList<>();
        List<TypeDefinition.Prop.ElementCollection> elementCollections = new ArrayList<>();
        TypeDefinition.Prop.Id idProp = null;
        TypeDefinition.Prop.Version versionProp = null;

        for (Element member : elements.getAllMembers(type)) {
            if (member.getKind() != ElementKind.FIELD) continue;
            if (member.getModifiers().contains(Modifier.STATIC)) continue;

            VariableElement field = (VariableElement) member;
            String fieldName = field.getSimpleName().toString();
            TypeMirror fieldType = field.asType();

            if (field.getAnnotation(Identity.class) != null) {
                idProp = buildIdProp(fieldName, fieldType, fqn);
            } else if (idProp == null && fieldName.equalsIgnoreCase("id")) {
                // Fallback: treat field named "id" as identifier when no @Identity annotation present
                idProp = buildIdProp(fieldName, fieldType, fqn);
            } else if (isVO) {
                classifyVOField(field, fieldName, fieldType, scalars, embedded, associations, entityCollections, elementCollections);
            } else {
                classifyEntityField(field, fieldName, fieldType, tableName, ownerSingular,
                        isAggregate, fqn, scalars, embedded, associations, entityCollections,
                        elementCollections);
            }
        }

        inProgress.remove(fqn);

        // Build appropriate TypeDefinition variant
        TypeDefinition definition;
        if (isAggregate) {
            if (idProp == null) {
                throw new IllegalArgumentException("Entity " + fqn + " declares no identifier property; annotate a field with @Identity");
            }
            definition = new TypeDefinition.Aggregate(
                    fqn, tableName, idProp, versionProp,
                    scalars, embedded, associations, entityCollections,
                    elementCollections);
        } else if (isEntity) {
            if (idProp == null) {
                throw new IllegalArgumentException("Entity " + fqn + " declares no identifier property; annotate a field with @Identity");
            }
            definition = new TypeDefinition.Entity(
                    fqn, tableName, idProp, versionProp,
                    scalars, embedded, associations, entityCollections,
                    elementCollections);
        } else {
            definition = new TypeDefinition.ValueObject(fqn, scalars, embedded, associations, entityCollections, elementCollections);
        }

        if (isEntity) {
            entityFqns.add(fqn);
        }

        cache.put(fqn, definition);
        return definition;
    }

    private TypeDefinition.Prop.Id buildIdProp(String fieldName, TypeMirror fieldType, String ownerFqn) {
        String typeFqn = typeFqn(fieldType);
        String columnName = Names.toSnakeCase(fieldName);
        // Default to NONE - could be enhanced to detect @GeneratedValue
        return new TypeDefinition.Prop.Id(fieldName, typeFqn, columnName,
                TypeDefinition.Prop.Id.GeneratedType.NONE);
    }

    /** Cache lookup by fully-qualified type name. */
    TypeDefinition get(String fqn) {
        TypeDefinition def = cache.get(fqn);
        if (def == null) {
            // Try to scan this type on-demand if it exists
            TypeElement te = elements.getTypeElement(fqn);
            if (te != null && !inProgress.contains(te.getQualifiedName().toString())) {
                // Force entity treatment for types referenced by repositories
                visitType(te, true);
                def = cache.get(fqn);
            }
        }
        return def;
    }

    /** Returns the set of entity and aggregate-root FQNs (excludes value objects). */
    Set<String> managedEntities() {
        return Collections.unmodifiableSet(entityFqns);
    }

    /** Returns the set of value object FQNs used as element collection element types. */
    Set<String> elementCollectionValueObjects() {
        return Collections.unmodifiableSet(elementCollectionValueObjects);
    }

    // ── Field classification — entities / aggregate roots ────────────────

    private void classifyEntityField(VariableElement field, String fieldName,
                                     TypeMirror fieldType, String ownerTable,
                                     String ownerSingular, boolean isAggregate,
                                     String ownerFqn,
                                     List<TypeDefinition.Prop.Scalar> scalars,
                                     List<TypeDefinition.Prop.Embedded> embedded,
                                     List<TypeDefinition.Prop.Association> associations,
                                     List<TypeDefinition.Prop.EntityCollection> entityCollections,
                                     List<TypeDefinition.Prop.ElementCollection> elementCollections) {
        TypeElement fieldTypeElement = asTypeElement(fieldType);

        // List<X> — element collection or entity collection
        if (isList(fieldType)) {
            TypeMirror elementType = elementTypeOf(fieldType);
            if (elementType == null) return;
            TypeElement elementTE = asTypeElement(elementType);
            if (elementTE == null) return;

            if (isVO(elementTE)) {
                buildElementCollection(fieldName, elementType, elementTE,
                        ownerTable, ownerSingular, elementCollections);
                return;
            }
            if (isEntityOrAggregate(elementTE)) {
                buildEntityCollection(fieldName, elementTE, ownerSingular, ownerFqn, entityCollections);
                return;
            }
            return;
        }

        // Single-valued field
        if (fieldTypeElement != null) {
            if (isVO(fieldTypeElement)) {
                TypeDefinition voDef = buildEmbeddedVO(fieldTypeElement);
                embedded.add(new TypeDefinition.Prop.Embedded(fieldName,
                        fieldTypeElement.getQualifiedName().toString(),
                        (TypeDefinition.ValueObject) voDef, null));
                return;
            }
            if (field.getAnnotation(Association.class) != null) {
                String targetFqn = fieldTypeElement.getQualifiedName().toString();
                TypeDefinition target = get(targetFqn);
                if (target instanceof TypeDefinition.Entity entityTarget) {
                    associations.add(new TypeDefinition.Prop.Association(fieldName,
                            targetFqn, entityTarget, Names.fkColumn(fieldName), false, false, null));
                }
                return;
            }
            if (isAggregate && isEntityOrAggregate(fieldTypeElement)) {
                String targetFqn = fieldTypeElement.getQualifiedName().toString();
                TypeDefinition target = get(targetFqn);
                // Aggregate-to-aggregate/entity reference: treat as COMPOSITION by default
                if (target instanceof TypeDefinition.Entity entityTarget) {
                    associations.add(new TypeDefinition.Prop.Association(fieldName,
                            targetFqn, entityTarget, Names.fkColumn(fieldName), false, true, null));
                } else if (target instanceof TypeDefinition.Aggregate aggregateTarget) {
                    // Create a synthetic Entity view of the Aggregate for the FK reference
                    TypeDefinition.Entity entityView = new TypeDefinition.Entity(
                        aggregateTarget.typeName(),
                        aggregateTarget.tableName(),
                        aggregateTarget.id(),
                        aggregateTarget.version(),
                        aggregateTarget.scalars(),
                        aggregateTarget.embedded(),
                        List.of(),
                        List.of(),
                        List.of()
                    );
                    associations.add(new TypeDefinition.Prop.Association(fieldName,
                            targetFqn, entityView, Names.fkColumn(fieldName), false, true, null));
                }
                return;
            }
            // Bare entity/aggregate reference on non-aggregate owner - treat as association
            if (!isAggregate && isEntityOrAggregate(fieldTypeElement)) {
                String targetFqn = fieldTypeElement.getQualifiedName().toString();
                TypeDefinition target = get(targetFqn);
                if (target instanceof TypeDefinition.Entity entityTarget) {
                    associations.add(new TypeDefinition.Prop.Association(fieldName,
                            targetFqn, entityTarget, Names.fkColumn(fieldName), false, false, null));
                } else if (target instanceof TypeDefinition.Aggregate aggregateTarget) {
                    // Treat aggregate target as entity for FK purposes
                    // Create a synthetic Entity view of the Aggregate for the association
                    TypeDefinition.Entity entityView = new TypeDefinition.Entity(
                        aggregateTarget.typeName(),
                        aggregateTarget.tableName(),
                        aggregateTarget.id(),
                        aggregateTarget.version(),
                        aggregateTarget.scalars(),
                        aggregateTarget.embedded(),
                        List.of(), // No nested associations in the FK reference
                        List.of(), // No collections in the FK reference
                        List.of()  // No element collections in the FK reference
                    );
                    associations.add(new TypeDefinition.Prop.Association(fieldName,
                            targetFqn, entityView, Names.fkColumn(fieldName), false, false, null));
                } else if (target == null) {
                    // Target not available yet - add placeholder with null target for resolution pass
                    associations.add(new TypeDefinition.Prop.Association(fieldName,
                            targetFqn, null, Names.fkColumn(fieldName), false, false, null));
                    // Track for resolution pass
                    pendingTargets
                            .computeIfAbsent(ownerFqn, k -> new HashMap<>())
                            .put(fieldName, targetFqn);
                }
                return;
            }
        }

        // Default: scalar
        scalars.add(new TypeDefinition.Prop.Scalar(fieldName, typeFqn(fieldType),
                Names.toSnakeCase(fieldName), true));
    }

    // ── Field classification — value objects ─────────────────────────────

    private void classifyVOField(VariableElement field, String fieldName, TypeMirror fieldType,
                                 List<TypeDefinition.Prop.Scalar> scalars,
                                 List<TypeDefinition.Prop.Embedded> embedded,
                                 List<TypeDefinition.Prop.Association> associations,
                                 List<TypeDefinition.Prop.EntityCollection> entityCollections,
                                 List<TypeDefinition.Prop.ElementCollection> elementCollections) {
        TypeElement fieldTypeElement = asTypeElement(fieldType);
        if (fieldTypeElement != null && isVO(fieldTypeElement)) {
            TypeDefinition voDef = buildEmbeddedVO(fieldTypeElement);
            embedded.add(new TypeDefinition.Prop.Embedded(fieldName,
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
                            entityCollections.add(new TypeDefinition.Prop.EntityCollection(fieldName,
                                    targetFqn, entityTarget, Names.fkColumn(fieldName), null));
                        }
                        return;
                    }
                    // For non-entity list elements in VOs, treat as ElementCollection
                    String elementFqn = typeFqn(elementType);
                    String ownerTable = "unknown"; // VOs don't have tables, this will be set by parent
                    String childTable = Names.childTable("vo", fieldName);
                    String parentFkCol = Names.fkColumn(fieldName);
                    
                    // Look up the element type's TypeDefinition from cache
                    TypeDefinition targetType = cache.get(elementFqn);
                    if (targetType == null && elementTE != null && !inProgress.contains(elementFqn)) {
                        visitType(elementTE, true);
                        targetType = cache.get(elementFqn);
                    }
                    
                    elementCollections.add(new TypeDefinition.Prop.ElementCollection(fieldName,
                            elementFqn, (TypeDefinition.ValueObject) targetType, childTable, parentFkCol));
                    return;
                }
            }
        }
        
        // Handle single-valued entity/aggregate references in VOs
        if (fieldTypeElement != null && isEntityOrAggregate(fieldTypeElement)) {
            String targetFqn = fieldTypeElement.getQualifiedName().toString();
            TypeDefinition target = get(targetFqn);
            if (target instanceof TypeDefinition.Entity entityTarget) {
                associations.add(new TypeDefinition.Prop.Association(fieldName,
                        targetFqn, entityTarget, Names.fkColumn(fieldName), false, false, null));
            } else if (target instanceof TypeDefinition.Aggregate aggregateTarget) {
                // Create synthetic Entity view for association
                TypeDefinition.Entity entityView = new TypeDefinition.Entity(
                    aggregateTarget.typeName(),
                    aggregateTarget.tableName(),
                    aggregateTarget.id(),
                    aggregateTarget.version(),
                    aggregateTarget.scalars(),
                    aggregateTarget.embedded(),
                    List.of(),
                    List.of(),
                    List.of()
                );
                associations.add(new TypeDefinition.Prop.Association(fieldName,
                        targetFqn, entityView, Names.fkColumn(fieldName), false, false, null));
            }
            return;
        }
        
        // Default: scalar
        scalars.add(new TypeDefinition.Prop.Scalar(fieldName, typeFqn(fieldType),
                Names.toSnakeCase(fieldName), true));
    }

    // ── VO builders ──────────────────────────────────────────────────────

    private TypeDefinition buildEmbeddedVO(TypeElement voType) {
        String voFqn = voType.getQualifiedName().toString();
        if (!inProgress.add(voFqn)) {
            diagnostics.note("Cycle detected in VO scanning: " + voFqn + " — skipping embedded fields", voType);
            return new TypeDefinition.ValueObject(voFqn, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        try {
            List<TypeDefinition.Prop.Scalar> scalars = new ArrayList<>();
            List<TypeDefinition.Prop.Embedded> embedded = new ArrayList<>();
            List<TypeDefinition.Prop.Association> associations = new ArrayList<>();
            List<TypeDefinition.Prop.EntityCollection> entityCollections = new ArrayList<>();
            List<TypeDefinition.Prop.ElementCollection> elementCollections = new ArrayList<>();
            scanVOProps(voType, scalars, embedded, associations, entityCollections, elementCollections);
            return new TypeDefinition.ValueObject(voFqn, scalars, embedded, associations, entityCollections, elementCollections);
        } finally {
            inProgress.remove(voFqn);
        }
    }

    private void buildElementCollection(String fieldName, TypeMirror elementType,
                                        TypeElement elementTE, String ownerTable,
                                        String ownerSingular,
                                        List<TypeDefinition.Prop.ElementCollection> elementCollections) {
        String voFqn = elementTE.getQualifiedName().toString();
        String childTable = Names.childTable(ownerTable, fieldName);
        String parentFkCol = Names.fkColumn(ownerSingular);

        // Track this VO as used in an element collection
        elementCollectionValueObjects.add(voFqn);

        // Look up the element type's TypeDefinition from cache
        TypeDefinition targetType = cache.get(voFqn);
        if (targetType == null) {
            // Element type not scanned yet - scan it now
            if (!inProgress.contains(voFqn)) {
                visitType(elementTE, true);
                targetType = cache.get(voFqn);
            }
        }

        elementCollections.add(new TypeDefinition.Prop.ElementCollection(fieldName,
                voFqn, (TypeDefinition.ValueObject) targetType, childTable, parentFkCol));
    }

    private void scanVOProps(TypeElement voType, List<TypeDefinition.Prop.Scalar> scalars,
                             List<TypeDefinition.Prop.Embedded> embedded,
                             List<TypeDefinition.Prop.Association> associations,
                             List<TypeDefinition.Prop.EntityCollection> entityCollections,
                             List<TypeDefinition.Prop.ElementCollection> elementCollections) {
        for (Element member : elements.getAllMembers(voType)) {
            if (member.getKind() != ElementKind.FIELD) continue;
            if (member.getModifiers().contains(Modifier.STATIC)) continue;

            VariableElement field = (VariableElement) member;
            if (field.getAnnotation(Identity.class) != null) continue;

            String fieldName = field.getSimpleName().toString();
            TypeMirror fieldType = field.asType();
            classifyVOField(field, fieldName, fieldType, scalars, embedded, associations, entityCollections, elementCollections);
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
            pendingTargets
                    .computeIfAbsent(ownerFqn, k -> new HashMap<>())
                    .put(fieldName, targetFqn);
            // Still add a placeholder with null target for now
            entityCollections.add(new TypeDefinition.Prop.EntityCollection(fieldName,
                    targetFqn, null, inverseFkColumn, null));
        } else if (target instanceof TypeDefinition.Entity entityTarget) {
            entityCollections.add(new TypeDefinition.Prop.EntityCollection(fieldName,
                    targetFqn, entityTarget, inverseFkColumn, null));
        }
    }

    // ── Resolution pass ──────────────────────────────────────────────────

    private void resolveAllReferences() {
        List<String> keys = new ArrayList<>(cache.keySet());
        for (String fqn : keys) {
            TypeDefinition definition = cache.get(fqn);
            TypeDefinition resolved = resolveDefinition(definition, fqn);
            if (resolved != definition) {
                cache.put(fqn, resolved);
            }
        }
    }

    private TypeDefinition resolveDefinition(TypeDefinition definition, String ownerFqn) {
        boolean changed = false;

        // Resolve Association targets
        if (definition instanceof TypeDefinition.Entity entity) {
            List<TypeDefinition.Prop.Association> resolvedAssociations = new ArrayList<>();
            for (TypeDefinition.Prop.Association assoc : entity.associations()) {
                TypeDefinition.Prop.Association resolved = resolveAssociation(assoc, ownerFqn);
                resolvedAssociations.add(resolved);
                if (resolved != assoc) changed = true;
            }

            List<TypeDefinition.Prop.EntityCollection> resolvedEntityCollections = new ArrayList<>();
            for (TypeDefinition.Prop.EntityCollection ec : entity.entityCollections()) {
                TypeDefinition.Prop.EntityCollection resolved = resolveEntityCollection(ec, ownerFqn);
                resolvedEntityCollections.add(resolved);
                if (resolved != ec) changed = true;
            }

            if (changed) {
                return new TypeDefinition.Entity(
                        entity.typeName(), entity.tableName(), entity.id(), entity.version(),
                        entity.scalars(), entity.embedded(), resolvedAssociations, resolvedEntityCollections,
                        entity.elementCollections());
            }
        } else if (definition instanceof TypeDefinition.Aggregate aggregate) {
            List<TypeDefinition.Prop.Association> resolvedAssociations = new ArrayList<>();
            for (TypeDefinition.Prop.Association assoc : aggregate.associations()) {
                TypeDefinition.Prop.Association resolved = resolveAssociation(assoc, ownerFqn);
                resolvedAssociations.add(resolved);
                if (resolved != assoc) changed = true;
            }

            List<TypeDefinition.Prop.EntityCollection> resolvedEntityCollections = new ArrayList<>();
            for (TypeDefinition.Prop.EntityCollection ec : aggregate.entityCollections()) {
                TypeDefinition.Prop.EntityCollection resolved = resolveEntityCollection(ec, ownerFqn);
                resolvedEntityCollections.add(resolved);
                if (resolved != ec) changed = true;
            }

            if (changed) {
                return new TypeDefinition.Aggregate(
                        aggregate.typeName(), aggregate.tableName(), aggregate.id(),
                        aggregate.version(), aggregate.scalars(), aggregate.embedded(),
                        resolvedAssociations, resolvedEntityCollections, aggregate.elementCollections());
            }
        }

        return definition;
    }

    private TypeDefinition.Prop.Association resolveAssociation(TypeDefinition.Prop.Association assoc, String ownerFqn) {
        if (assoc.definition() != null) return assoc;

        // Try to get target from javaType (fully qualified class name)
        TypeDefinition target = get(assoc.javaType());
        
        if (target == null) {
            Map<String, String> pending = pendingTargets.get(ownerFqn);
            if (pending != null) {
                String targetFqn = pending.get(assoc.name());
                if (targetFqn != null) {
                    target = get(targetFqn);
                }
            }
        }

        if (target instanceof TypeDefinition.Entity entityTarget) {
            return new TypeDefinition.Prop.Association(assoc.name(), assoc.javaType(),
                    entityTarget, assoc.fkColumn(), assoc.backReference(), assoc.composition(), assoc.cascade());
        } else if (target instanceof TypeDefinition.Aggregate aggregateTarget) {
            // Create synthetic Entity view for association
            TypeDefinition.Entity entityView = new TypeDefinition.Entity(
                aggregateTarget.typeName(),
                aggregateTarget.tableName(),
                aggregateTarget.id(),
                aggregateTarget.version(),
                aggregateTarget.scalars(),
                aggregateTarget.embedded(),
                List.of(),
                List.of(),
                List.of()
            );
            return new TypeDefinition.Prop.Association(assoc.name(), assoc.javaType(),
                    entityView, assoc.fkColumn(), assoc.backReference(), assoc.composition(), assoc.cascade());
        }

        return assoc;
    }

    private TypeDefinition.Prop.EntityCollection resolveEntityCollection(TypeDefinition.Prop.EntityCollection ec, String ownerFqn) {
        if (ec.definition() != null) return ec;

        // Try to get target from javaType (fully qualified class name)
        TypeDefinition target = get(ec.javaType());
        if (target == null) {
            Map<String, String> pending = pendingTargets.get(ownerFqn);
            if (pending != null) {
                String targetFqn = pending.get(ec.name());
                if (targetFqn != null) {
                    target = get(targetFqn);
                }
            }
        }

        if (target instanceof TypeDefinition.Entity entityTarget) {
            return new TypeDefinition.Prop.EntityCollection(ec.name(), ec.javaType(),
                    entityTarget, ec.fkColumn(), ec.mappedBy());
        }

        return ec;
    }

    // ── Annotation collection helper ─────────────────────────────────────

    private void collectAnnotated(RoundEnvironment roundEnv,
                                  Class<? extends java.lang.annotation.Annotation> annotation,
                                  List<TypeElement> out, Set<String> seen) {
        for (Element e : roundEnv.getElementsAnnotatedWith(annotation)) {
            if (e.getKind() != ElementKind.CLASS) continue;
            TypeElement te = (TypeElement) e;
            String fqn = te.getQualifiedName().toString();
            if (seen.add(fqn)) {
                out.add(te);
            }
        }
    }

    // ── Type utilities ───────────────────────────────────────────────────

    private TypeElement asTypeElement(TypeMirror type) {
        if (type == null) return null;
        if (type.getKind() == TypeKind.NONE) return null;
        TypeMirror unwrapped = type;
        if (unwrapped.getKind() == TypeKind.ARRAY) return null;
        if (unwrapped.getKind() != TypeKind.DECLARED) return null;
        Element element = ((DeclaredType) unwrapped).asElement();
        return element instanceof TypeElement te ? te : null;
    }

    private boolean isEntityOrAggregate(TypeElement te) {
        return te.getAnnotation(Entity.class) != null
                || te.getAnnotation(AggregateRoot.class) != null;
    }

    private boolean isVO(TypeElement te) {
        return te.getAnnotation(ValueObject.class) != null;
    }

    private boolean isList(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        DeclaredType dt = (DeclaredType) type;
        TypeElement te = (TypeElement) dt.asElement();
        return te.getQualifiedName().contentEquals("java.util.List");
    }

    private TypeMirror elementTypeOf(TypeMirror listType) {
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
