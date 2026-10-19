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
 * <p>Two-pass approach: the first pass builds all definitions with {@code target=null} for unresolved
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

    /**
     * Pending relation target FQNs for the resolution pass.
     * Key: owner FQN, Value: (prop name → target FQN).
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
        String fqn = type.getQualifiedName().toString();
        if (cache.containsKey(fqn)) return cache.get(fqn);
        if (!inProgress.add(fqn)) return null; // cycle

        boolean isAggregate = type.getAnnotation(AggregateRoot.class) != null;
        boolean isEntity = isAggregate || type.getAnnotation(Entity.class) != null;
        boolean isVO = type.getAnnotation(ValueObject.class) != null;

        String tableName = isEntity ? Names.tableName(type.getSimpleName().toString()) : null;
        String ownerSingular = type.getSimpleName().toString();

        // Collect props by category
        List<TypeDefinition.Prop.Scalar> scalars = new ArrayList<>();
        List<TypeDefinition.Prop.Embedded> embedded = new ArrayList<>();
        List<TypeDefinition.Prop.ToOne> toOneEntities = new ArrayList<>();
        List<TypeDefinition.Prop.ToMany> toManyEntities = new ArrayList<>();
        List<TypeDefinition.Prop.ElementCollection> elementCollections = new ArrayList<>();
        List<TypeDefinition.Prop.Composition> compositions = new ArrayList<>();
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
            } else if (isVO) {
                classifyVOField(field, fieldName, fieldType, scalars, embedded, elementCollections);
            } else {
                classifyEntityField(field, fieldName, fieldType, tableName, ownerSingular,
                        isAggregate, fqn, scalars, embedded, toOneEntities, toManyEntities,
                        elementCollections, compositions);
            }
        }

        inProgress.remove(fqn);

        // Build appropriate TypeDefinition variant
        TypeDefinition definition;
        if (isAggregate) {
            definition = new TypeDefinition.Aggregate(
                    fqn, tableName, idProp, versionProp,
                    scalars, embedded, toOneEntities, toManyEntities,
                    elementCollections, compositions);
        } else if (isEntity) {
            definition = new TypeDefinition.Entity(
                    fqn, tableName, idProp, versionProp,
                    scalars, embedded, toOneEntities, toManyEntities,
                    elementCollections);
        } else {
            definition = new TypeDefinition.ValueObject(fqn, scalars, embedded, elementCollections);
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
        return cache.get(fqn);
    }

    /** Returns the set of entity and aggregate-root FQNs (excludes value objects). */
    Set<String> managedEntities() {
        return Collections.unmodifiableSet(entityFqns);
    }

    // ── Field classification — entities / aggregate roots ────────────────

    private void classifyEntityField(VariableElement field, String fieldName,
                                     TypeMirror fieldType, String ownerTable,
                                     String ownerSingular, boolean isAggregate,
                                     String ownerFqn,
                                     List<TypeDefinition.Prop.Scalar> scalars,
                                     List<TypeDefinition.Prop.Embedded> embedded,
                                     List<TypeDefinition.Prop.ToOne> toOneEntities,
                                     List<TypeDefinition.Prop.ToMany> toManyEntities,
                                     List<TypeDefinition.Prop.ElementCollection> elementCollections,
                                     List<TypeDefinition.Prop.Composition> compositions) {
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
                buildEntityCollection(fieldName, elementTE, ownerSingular, ownerFqn, toManyEntities);
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
                TypeDefinition target = cache.get(targetFqn);
                if (target instanceof TypeDefinition.Entity entityTarget) {
                    toOneEntities.add(new TypeDefinition.Prop.ToOne(fieldName,
                            targetFqn, entityTarget, Names.fkColumn(fieldName), false));
                }
                return;
            }
            if (isAggregate && isEntityOrAggregate(fieldTypeElement)) {
                String targetFqn = fieldTypeElement.getQualifiedName().toString();
                TypeDefinition target = cache.get(targetFqn);
                if (target instanceof TypeDefinition.Aggregate aggregateTarget) {
                    compositions.add(new TypeDefinition.Prop.Composition(fieldName,
                            targetFqn, aggregateTarget,
                            TypeDefinition.Prop.Composition.Cascade.ALL));
                }
                return;
            }
            if (!isAggregate && isEntityOrAggregate(fieldTypeElement)) {
                String targetFqn = fieldTypeElement.getQualifiedName().toString();
                TypeDefinition target = cache.get(targetFqn);
                if (target instanceof TypeDefinition.Entity entityTarget) {
                    toOneEntities.add(new TypeDefinition.Prop.ToOne(fieldName,
                            targetFqn, entityTarget, Names.fkColumn(fieldName), false));
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
                                 List<TypeDefinition.Prop.ElementCollection> elementCollections) {
        TypeElement fieldTypeElement = asTypeElement(fieldType);
        if (fieldTypeElement != null && isVO(fieldTypeElement)) {
            TypeDefinition voDef = buildEmbeddedVO(fieldTypeElement);
            embedded.add(new TypeDefinition.Prop.Embedded(fieldName,
                    fieldTypeElement.getQualifiedName().toString(),
                    (TypeDefinition.ValueObject) voDef, null));
            return;
        }
        if (fieldTypeElement != null && field.getAnnotation(Association.class) != null) {
            String targetFqn = fieldTypeElement.getQualifiedName().toString();
            TypeDefinition target = cache.get(targetFqn);
            if (target instanceof TypeDefinition.Entity entityTarget) {
                // VO shouldn't have ToOne to entities normally, but handle gracefully
            }
            return;
        }
        scalars.add(new TypeDefinition.Prop.Scalar(fieldName, typeFqn(fieldType),
                Names.toSnakeCase(fieldName), true));
    }

    // ── VO builders ──────────────────────────────────────────────────────

    private TypeDefinition buildEmbeddedVO(TypeElement voType) {
        String voFqn = voType.getQualifiedName().toString();
        if (!inProgress.add(voFqn)) {
            diagnostics.note("Cycle detected in VO scanning: " + voFqn + " — skipping embedded fields", voType);
            return new TypeDefinition.ValueObject(voFqn, List.of(), List.of(), List.of());
        }
        try {
            List<TypeDefinition.Prop.Scalar> scalars = new ArrayList<>();
            List<TypeDefinition.Prop.Embedded> embedded = new ArrayList<>();
            List<TypeDefinition.Prop.ElementCollection> elementCollections = new ArrayList<>();
            scanVOProps(voType, scalars, embedded, elementCollections);
            return new TypeDefinition.ValueObject(voFqn, scalars, embedded, elementCollections);
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

        elementCollections.add(new TypeDefinition.Prop.ElementCollection(fieldName,
                voFqn, childTable, parentFkCol));
    }

    private void scanVOProps(TypeElement voType, List<TypeDefinition.Prop.Scalar> scalars,
                             List<TypeDefinition.Prop.Embedded> embedded,
                             List<TypeDefinition.Prop.ElementCollection> elementCollections) {
        for (Element member : elements.getAllMembers(voType)) {
            if (member.getKind() != ElementKind.FIELD) continue;
            if (member.getModifiers().contains(Modifier.STATIC)) continue;

            VariableElement field = (VariableElement) member;
            if (field.getAnnotation(Identity.class) != null) continue;

            String fieldName = field.getSimpleName().toString();
            TypeMirror fieldType = field.asType();
            classifyVOField(field, fieldName, fieldType, scalars, embedded, elementCollections);
        }
    }

    // ── Entity collection builder ────────────────────────────────────────

    private void buildEntityCollection(String fieldName, TypeElement elementTE,
                                       String ownerSingular, String ownerFqn,
                                       List<TypeDefinition.Prop.ToMany> toManyEntities) {
        String targetFqn = elementTE.getQualifiedName().toString();
        TypeDefinition target = cache.get(targetFqn);
        String inverseFkColumn = Names.fkColumn(ownerSingular);

        if (target == null) {
            pendingTargets
                    .computeIfAbsent(ownerFqn, k -> new HashMap<>())
                    .put(fieldName, targetFqn);
        }

        if (target instanceof TypeDefinition.Entity entityTarget) {
            toManyEntities.add(new TypeDefinition.Prop.ToMany(fieldName,
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

        // Resolve ToOne targets
        if (definition instanceof TypeDefinition.Entity entity) {
            List<TypeDefinition.Prop.ToOne> resolvedToOne = new ArrayList<>();
            for (TypeDefinition.Prop.ToOne toOne : entity.toOneEntities()) {
                TypeDefinition.Prop.ToOne resolved = resolveToOne(toOne, ownerFqn);
                resolvedToOne.add(resolved);
                if (resolved != toOne) changed = true;
            }

            List<TypeDefinition.Prop.ToMany> resolvedToMany = new ArrayList<>();
            for (TypeDefinition.Prop.ToMany toMany : entity.toManyEntities()) {
                TypeDefinition.Prop.ToMany resolved = resolveToMany(toMany, ownerFqn);
                resolvedToMany.add(resolved);
                if (resolved != toMany) changed = true;
            }

            if (changed) {
                return new TypeDefinition.Entity(
                        entity.typeName(), entity.tableName(), entity.id(), entity.version(),
                        entity.scalars(), entity.embedded(), resolvedToOne, resolvedToMany,
                        entity.elementCollections());
            }
        } else if (definition instanceof TypeDefinition.Aggregate aggregate) {
            List<TypeDefinition.Prop.ToOne> resolvedToOne = new ArrayList<>();
            for (TypeDefinition.Prop.ToOne toOne : aggregate.toOneEntities()) {
                TypeDefinition.Prop.ToOne resolved = resolveToOne(toOne, ownerFqn);
                resolvedToOne.add(resolved);
                if (resolved != toOne) changed = true;
            }

            List<TypeDefinition.Prop.ToMany> resolvedToMany = new ArrayList<>();
            for (TypeDefinition.Prop.ToMany toMany : aggregate.toManyEntities()) {
                TypeDefinition.Prop.ToMany resolved = resolveToMany(toMany, ownerFqn);
                resolvedToMany.add(resolved);
                if (resolved != toMany) changed = true;
            }

            if (changed) {
                return new TypeDefinition.Aggregate(
                        aggregate.typeName(), aggregate.tableName(), aggregate.id(),
                        aggregate.version(), aggregate.scalars(), aggregate.embedded(),
                        resolvedToOne, resolvedToMany, aggregate.elementCollections(),
                        aggregate.compositions());
            }
        }

        return definition;
    }

    private TypeDefinition.Prop.ToOne resolveToOne(TypeDefinition.Prop.ToOne toOne, String ownerFqn) {
        if (toOne.target() != null) return toOne;

        TypeDefinition target = cache.get(toOne.target().typeName());
        if (target == null) {
            Map<String, String> pending = pendingTargets.get(ownerFqn);
            if (pending != null) {
                String targetFqn = pending.get(toOne.name());
                if (targetFqn != null) {
                    target = cache.get(targetFqn);
                }
            }
        }

        if (target instanceof TypeDefinition.Entity entityTarget) {
            return new TypeDefinition.Prop.ToOne(toOne.name(), toOne.javaType(),
                    entityTarget, toOne.fkColumn(), toOne.backReference());
        }

        return toOne;
    }

    private TypeDefinition.Prop.ToMany resolveToMany(TypeDefinition.Prop.ToMany toMany, String ownerFqn) {
        if (toMany.target() != null) return toMany;

        TypeDefinition target = cache.get(toMany.target().typeName());
        if (target == null) {
            Map<String, String> pending = pendingTargets.get(ownerFqn);
            if (pending != null) {
                String targetFqn = pending.get(toMany.name());
                if (targetFqn != null) {
                    target = cache.get(targetFqn);
                }
            }
        }

        if (target instanceof TypeDefinition.Entity entityTarget) {
            return new TypeDefinition.Prop.ToMany(toMany.name(), toMany.javaType(),
                    entityTarget, toMany.fkColumn(), toMany.mappedBy());
        }

        return toMany;
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
        return typeArgs.get(0);
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
