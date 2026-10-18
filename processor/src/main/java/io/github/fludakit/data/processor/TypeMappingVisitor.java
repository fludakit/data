package io.github.fludakit.data.processor;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Association;
import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;
import io.github.fludakit.data.annotation.ValueObject;
import io.github.fludakit.data.metadata.AggregateTypeMapping;
import io.github.fludakit.data.metadata.ElementCollectionValueObjectMapping;
import io.github.fludakit.data.metadata.EmbeddedValueObjectMapping;
import io.github.fludakit.data.metadata.EntityTypeMapping;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
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
 * processing round and builds {@link TypeMapping} instances cached by fully-qualified type name.
 *
 * <p>Two-pass approach: the first pass builds all mappings with {@code target=null} for unresolved
 * cross-references; the second pass fills in targets from the cache.
 */
final class TypeMappingVisitor {

    private final Types types;
    private final Elements elements;
    private final Diagnostics diagnostics;

    /** Cache of scanned type mappings keyed by fully-qualified type name. */
    private final Map<String, TypeMapping<?>> cache = new LinkedHashMap<>();

    /** FQNs currently being built — cycle detection. */
    private final Set<String> inProgress = new HashSet<>();

    /** FQNs of entities and aggregate roots (not value objects). */
    private final Set<String> entityFqns = new LinkedHashSet<>();

    /**
     * Pending entity-collection target FQNs for the resolution pass.
     * Key: owner FQN, Value: (prop name → target FQN).
     */
    private final Map<String, Map<String, String>> pendingEcTargets = new HashMap<>();

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
        // Collect annotated types (deduplicated, preserving encounter order)
        List<TypeElement> allTypes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        collectAnnotated(roundEnv, Entity.class, allTypes, seen);
        collectAnnotated(roundEnv, AggregateRoot.class, allTypes, seen);
        collectAnnotated(roundEnv, ValueObject.class, allTypes, seen);

        // First pass: build TypeMappings with target=null for unresolved references
        for (TypeElement te : allTypes) {
            visitType(te);
        }

        // Second pass: resolve cross-references
        resolveAllReferences();
    }

    /**
     * Builds the {@link TypeMapping} for a single type. Returns the cached mapping if already
     * scanned, or {@code null} if a cycle is detected.
     */
    TypeMapping<?> visitType(TypeElement type) {
        String fqn = type.getQualifiedName().toString();
        if (cache.containsKey(fqn)) return cache.get(fqn);
        if (!inProgress.add(fqn)) return null; // cycle

        boolean isAggregate = type.getAnnotation(AggregateRoot.class) != null;
        boolean isEntity = isAggregate || type.getAnnotation(Entity.class) != null;
        boolean isVO = type.getAnnotation(ValueObject.class) != null;

        String tableName = isEntity ? Names.tableName(type.getSimpleName().toString()) : null;
        String ownerSingular = type.getSimpleName().toString();

        Prop.Id id = null;
        List<Prop> props = new ArrayList<>();

        for (Element member : elements.getAllMembers(type)) {
            if (member.getKind() != ElementKind.FIELD) continue;
            if (member.getModifiers().contains(Modifier.STATIC)) continue;

            VariableElement field = (VariableElement) member;
            String fieldName = field.getSimpleName().toString();
            TypeMirror fieldType = field.asType();

            if (field.getAnnotation(Identity.class) != null) {
                id = new Prop.Id(fieldName, Names.toSnakeCase(fieldName), typeFqn(fieldType));
            } else if (isVO) {
                Prop prop = classifyVOField(field, fieldName, fieldType);
                if (prop != null) props.add(prop);
            } else {
                Prop prop = classifyEntityField(field, fieldName, fieldType,
                        tableName, ownerSingular, isAggregate, fqn);
                if (prop != null) props.add(prop);
            }
        }

        inProgress.remove(fqn);

        TypeMapping<?> mapping;
        if (isAggregate) {
            mapping = new AggregateTypeMapping<>(fqn, tableName, id, props);
            entityFqns.add(fqn);
        } else if (isEntity) {
            mapping = new EntityTypeMapping<>(fqn, tableName, id, props);
            entityFqns.add(fqn);
        } else {
            // Top-level @ValueObject scan → EmbeddedValueObjectMapping
            mapping = new EmbeddedValueObjectMapping<>(fqn, props);
        }

        cache.put(fqn, mapping);
        return mapping;
    }

    /** Cache lookup by fully-qualified type name. */
    TypeMapping<?> get(String fqn) {
        return cache.get(fqn);
    }

    /** Returns the set of entity and aggregate-root FQNs (excludes value objects). */
    Set<String> managedEntities() {
        return Collections.unmodifiableSet(entityFqns);
    }

    // ── Field classification — entities / aggregate roots ────────────────

    private Prop classifyEntityField(VariableElement field, String fieldName,
                                     TypeMirror fieldType, String ownerTable,
                                     String ownerSingular, boolean isAggregate,
                                     String ownerFqn) {
        TypeElement fieldTypeElement = asTypeElement(fieldType);

        // List<X> — element collection or entity collection
        if (isList(fieldType)) {
            TypeMirror elementType = elementTypeOf(fieldType);
            if (elementType == null) return null;
            TypeElement elementTE = asTypeElement(elementType);
            if (elementTE == null) return null;

            if (isVO(elementTE)) {
                // List<@ValueObject> → ElementCollection
                return buildElementCollection(fieldName, elementType, elementTE,
                        ownerTable, ownerSingular);
            }
            if (isAggregate && isEntityOrAggregate(elementTE)) {
                // List<@Entity> on @AggregateRoot → EntityCollection
                return buildEntityCollection(fieldName, elementType, elementTE,
                        ownerSingular, ownerFqn);
            }
            return null; // unsupported collection element type
        }

        // Single-valued field
        if (fieldTypeElement != null) {
            if (isVO(fieldTypeElement)) {
                // @ValueObject field → Embedded
                EmbeddedValueObjectMapping<?> voMapping = buildEmbeddedVO(fieldTypeElement);
                return new Prop.Embedded(fieldName, voMapping);
            }
            if (field.getAnnotation(Association.class) != null) {
                // @Association → Reference (composition=false)
                String targetFqn = fieldTypeElement.getQualifiedName().toString();
                TypeMapping<?> target = cache.get(targetFqn);
                return new Prop.Reference(fieldName, Names.fkColumn(fieldName),
                        targetFqn, target, false, false);
            }
            if (isAggregate && isEntityOrAggregate(fieldTypeElement)) {
                // Bare entity on @AggregateRoot → composition Reference
                String targetFqn = fieldTypeElement.getQualifiedName().toString();
                TypeMapping<?> target = cache.get(targetFqn);
                return new Prop.Reference(fieldName, Names.fkColumn(fieldName),
                        targetFqn, target, true, false);
            }
            if (!isAggregate && isEntityOrAggregate(fieldTypeElement)) {
                // Bare entity on plain @Entity → treat as association (composition=false)
                String targetFqn = fieldTypeElement.getQualifiedName().toString();
                TypeMapping<?> target = cache.get(targetFqn);
                return new Prop.Reference(fieldName, Names.fkColumn(fieldName),
                        targetFqn, target, false, false);
            }
        }

        // Default: scalar
        return new Prop.Scalar(fieldName, Names.toSnakeCase(fieldName), typeFqn(fieldType));
    }

    // ── Field classification — value objects ─────────────────────────────

    private Prop classifyVOField(VariableElement field, String fieldName, TypeMirror fieldType) {
        TypeElement fieldTypeElement = asTypeElement(fieldType);
        if (fieldTypeElement != null && isVO(fieldTypeElement)) {
            EmbeddedValueObjectMapping<?> voMapping = buildEmbeddedVO(fieldTypeElement);
            return new Prop.Embedded(fieldName, voMapping);
        }
        // Scalar (VOs may only contain scalars and embedded VOs)
        return new Prop.Scalar(fieldName, Names.toSnakeCase(fieldName), typeFqn(fieldType));
    }

    // ── VO builders ──────────────────────────────────────────────────────

    /**
     * Builds an {@link EmbeddedValueObjectMapping} for a VO field by scanning its declared fields
     * recursively. VOs may only contain scalars and other embedded VOs.
     *
     * <p>If a VO cycle is detected (the VO FQN is already being scanned higher in the call stack),
     * returns an empty placeholder mapping and emits a diagnostic note to avoid
     * {@link StackOverflowError}.
     */
    private EmbeddedValueObjectMapping<?> buildEmbeddedVO(TypeElement voType) {
        String voFqn = voType.getQualifiedName().toString();
        if (!inProgress.add(voFqn)) {
            diagnostics.note("Cycle detected in VO scanning: " + voFqn + " — skipping embedded fields", voType);
            return new EmbeddedValueObjectMapping<>(voFqn, Collections.emptyList());
        }
        try {
            List<Prop> voProps = scanVOProps(voType);
            return new EmbeddedValueObjectMapping<>(voFqn, voProps);
        } finally {
            inProgress.remove(voFqn);
        }
    }

    /**
     * Builds an {@link ElementCollectionValueObjectMapping} for a {@code List<@ValueObject>} field.
     */
    private Prop.ElementCollection buildElementCollection(String fieldName,
                                                          TypeMirror elementType,
                                                          TypeElement elementTE,
                                                          String ownerTable,
                                                          String ownerSingular) {
        String voFqn = elementTE.getQualifiedName().toString();
        String childTable = Names.childTable(ownerTable, fieldName);
        String parentFkCol = Names.fkColumn(ownerSingular);
        List<Prop> voProps = scanVOProps(elementTE);
        ElementCollectionValueObjectMapping<?> mapping =
                new ElementCollectionValueObjectMapping<>(voFqn, childTable, parentFkCol, voProps);
        return new Prop.ElementCollection(fieldName, mapping);
    }

    /**
     * Scans the fields of a value-object type, returning its props. VOs may only contain scalars
     * and embedded VOs — no relations, no entity collections.
     */
    private List<Prop> scanVOProps(TypeElement voType) {
        List<Prop> props = new ArrayList<>();
        for (Element member : elements.getAllMembers(voType)) {
            if (member.getKind() != ElementKind.FIELD) continue;
            if (member.getModifiers().contains(Modifier.STATIC)) continue;

            VariableElement field = (VariableElement) member;
            // Skip @Identity in VOs (VOs have no identity)
            if (field.getAnnotation(Identity.class) != null) continue;

            String fieldName = field.getSimpleName().toString();
            TypeMirror fieldType = field.asType();
            Prop prop = classifyVOField(field, fieldName, fieldType);
            if (prop != null) props.add(prop);
        }
        return props;
    }

    // ── Entity collection builder ────────────────────────────────────────

    private Prop.EntityCollection buildEntityCollection(String fieldName,
                                                        TypeMirror elementType,
                                                        TypeElement elementTE,
                                                        String ownerSingular,
                                                        String ownerFqn) {
        String targetFqn = elementTE.getQualifiedName().toString();
        TypeMapping<?> target = cache.get(targetFqn);
        String inverseName = decapitalize(ownerSingular);
        String inverseFkColumn = Names.fkColumn(ownerSingular);

        // Track for resolution pass if target is not yet available
        if (target == null) {
            pendingEcTargets
                    .computeIfAbsent(ownerFqn, k -> new HashMap<>())
                    .put(fieldName, targetFqn);
        }

        return new Prop.EntityCollection(fieldName, target, inverseName, inverseFkColumn);
    }

    // ── Resolution pass ──────────────────────────────────────────────────

    /**
     * Second pass: resolves all {@code target=null} references and entity collections by looking
     * up the cache. Rebuilds immutable records with the resolved targets.
     */
    private void resolveAllReferences() {
        List<String> keys = new ArrayList<>(cache.keySet());
        for (String fqn : keys) {
            TypeMapping<?> mapping = cache.get(fqn);
            TypeMapping<?> resolved = resolveMapping(mapping, fqn);
            if (resolved != mapping) {
                cache.put(fqn, resolved);
            }
        }
    }

    private TypeMapping<?> resolveMapping(TypeMapping<?> mapping, String ownerFqn) {
        List<Prop> resolvedProps = new ArrayList<>();
        boolean changed = false;

        for (Prop prop : mapping.props()) {
            Prop resolved = resolveProp(prop, ownerFqn);
            resolvedProps.add(resolved);
            if (resolved != prop) changed = true;
        }

        if (!changed) return mapping;

        if (mapping instanceof AggregateTypeMapping<?> atm) {
            return new AggregateTypeMapping<>(atm.typeName(), atm.tableName(), atm.id(), resolvedProps);
        } else if (mapping instanceof EntityTypeMapping<?> etm) {
            return new EntityTypeMapping<>(etm.typeName(), etm.tableName(), etm.id(), resolvedProps);
        }
        return mapping;
    }

    private Prop resolveProp(Prop prop, String ownerFqn) {
        if (prop instanceof Prop.Reference ref && ref.target() == null) {
            TypeMapping<?> target = cache.get(ref.targetTypeName());
            if (target != null) {
                return new Prop.Reference(ref.name(), ref.fkColumn(), ref.targetTypeName(),
                        target, ref.composition(), ref.backReference());
            }
        } else if (prop instanceof Prop.EntityCollection ec && ec.target() == null) {
            Map<String, String> ecMap = pendingEcTargets.get(ownerFqn);
            if (ecMap != null) {
                String targetFqn = ecMap.get(ec.name());
                if (targetFqn != null) {
                    TypeMapping<?> target = cache.get(targetFqn);
                    if (target != null) {
                        return new Prop.EntityCollection(ec.name(), target,
                                ec.inverseName(), ec.inverseFkColumn());
                    }
                }
            }
        }
        return prop;
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

    /** Returns the {@link TypeElement} for a declared type, or {@code null}. */
    private TypeElement asTypeElement(TypeMirror type) {
        if (type == null) return null;
        if (type.getKind() == TypeKind.NONE) return null;
        // Unwrap arrays (not expected but defensive)
        TypeMirror unwrapped = type;
        if (unwrapped.getKind() == TypeKind.ARRAY) return null;
        if (unwrapped.getKind() != TypeKind.DECLARED) return null;
        Element element = ((DeclaredType) unwrapped).asElement();
        return element instanceof TypeElement te ? te : null;
    }

    /** Whether the type element is annotated {@code @Entity} or {@code @AggregateRoot}. */
    private boolean isEntityOrAggregate(TypeElement te) {
        return te.getAnnotation(Entity.class) != null
                || te.getAnnotation(AggregateRoot.class) != null;
    }

    /** Whether the type element is annotated {@code @ValueObject}. */
    private boolean isVO(TypeElement te) {
        return te.getAnnotation(ValueObject.class) != null;
    }

    /** Whether the type mirror is a {@code java.util.List}. */
    private boolean isList(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        DeclaredType dt = (DeclaredType) type;
        TypeElement te = (TypeElement) dt.asElement();
        return te.getQualifiedName().contentEquals("java.util.List");
    }

    /** Extracts the first type argument of a parameterized type, or {@code null}. */
    private TypeMirror elementTypeOf(TypeMirror listType) {
        DeclaredType dt = (DeclaredType) listType;
        List<? extends TypeMirror> typeArgs = dt.getTypeArguments();
        if (typeArgs.isEmpty()) return null;
        return typeArgs.get(0);
    }

    /** Fully-qualified type name for a type mirror (handles primitives and declared types). */
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

    /** Lowercases the first character: {@code "Profile"} → {@code "profile"}. */
    private static String decapitalize(String name) {
        if (name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
