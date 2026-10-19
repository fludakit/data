package io.github.fludakit.data.processor;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Association;
import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;
import io.github.fludakit.data.annotation.ValueObject;
import io.github.fludakit.data.metadata.ColumnMapping;
import io.github.fludakit.data.metadata.RelationKind;
import io.github.fludakit.data.metadata.RelationMapping;
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

        List<ColumnMapping> columns = new ArrayList<>();
        List<RelationMapping> relations = new ArrayList<>();

        for (Element member : elements.getAllMembers(type)) {
            if (member.getKind() != ElementKind.FIELD) continue;
            if (member.getModifiers().contains(Modifier.STATIC)) continue;

            VariableElement field = (VariableElement) member;
            String fieldName = field.getSimpleName().toString();
            TypeMirror fieldType = field.asType();

            if (field.getAnnotation(Identity.class) != null) {
                columns.add(new ColumnMapping(fieldName, Names.toSnakeCase(fieldName),
                        typeFqn(fieldType), true, false));
            } else if (isVO) {
                classifyVOField(field, fieldName, fieldType, columns, relations);
            } else {
                classifyEntityField(field, fieldName, fieldType, tableName, ownerSingular,
                        isAggregate, fqn, columns, relations);
            }
        }

        inProgress.remove(fqn);

        TypeDefinition definition = new TypeDefinition(fqn, tableName,
                findIdColumn(columns), isAggregate, columns, relations);

        if (isEntity) {
            entityFqns.add(fqn);
        }

        cache.put(fqn, definition);
        return definition;
    }

    private static String findIdColumn(List<ColumnMapping> columns) {
        for (ColumnMapping col : columns) {
            if (col.isId()) return col.columnName();
        }
        return null;
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
                                     List<ColumnMapping> columns,
                                     List<RelationMapping> relations) {
        TypeElement fieldTypeElement = asTypeElement(fieldType);

        // List<X> — element collection or entity collection
        if (isList(fieldType)) {
            TypeMirror elementType = elementTypeOf(fieldType);
            if (elementType == null) return;
            TypeElement elementTE = asTypeElement(elementType);
            if (elementTE == null) return;

            if (isVO(elementTE)) {
                buildElementCollection(fieldName, elementType, elementTE,
                        ownerTable, ownerSingular, relations);
                return;
            }
            if (isEntityOrAggregate(elementTE)) {
                buildEntityCollection(fieldName, elementTE, ownerSingular, ownerFqn, relations);
                return;
            }
            return;
        }

        // Single-valued field
        if (fieldTypeElement != null) {
            if (isVO(fieldTypeElement)) {
                TypeDefinition voDef = buildEmbeddedVO(fieldTypeElement);
                relations.add(new RelationMapping(fieldName, RelationKind.EMBEDDED,
                        fieldTypeElement.getQualifiedName().toString(), null, null,
                        false, true, voDef));
                return;
            }
            if (field.getAnnotation(Association.class) != null) {
                String targetFqn = fieldTypeElement.getQualifiedName().toString();
                TypeDefinition target = cache.get(targetFqn);
                relations.add(new RelationMapping(fieldName, RelationKind.TO_ONE,
                        targetFqn, Names.fkColumn(fieldName), null, false, false, target));
                return;
            }
            if (isAggregate && isEntityOrAggregate(fieldTypeElement)) {
                String targetFqn = fieldTypeElement.getQualifiedName().toString();
                TypeDefinition target = cache.get(targetFqn);
                relations.add(new RelationMapping(fieldName, RelationKind.TO_ONE,
                        targetFqn, Names.fkColumn(fieldName), null, true, false, target));
                return;
            }
            if (!isAggregate && isEntityOrAggregate(fieldTypeElement)) {
                String targetFqn = fieldTypeElement.getQualifiedName().toString();
                TypeDefinition target = cache.get(targetFqn);
                relations.add(new RelationMapping(fieldName, RelationKind.TO_ONE,
                        targetFqn, Names.fkColumn(fieldName), null, false, false, target));
                return;
            }
        }

        // Default: scalar
        columns.add(new ColumnMapping(fieldName, Names.toSnakeCase(fieldName),
                typeFqn(fieldType), false, false));
    }

    // ── Field classification — value objects ─────────────────────────────

    private void classifyVOField(VariableElement field, String fieldName, TypeMirror fieldType,
                                 List<ColumnMapping> columns, List<RelationMapping> relations) {
        TypeElement fieldTypeElement = asTypeElement(fieldType);
        if (fieldTypeElement != null && isVO(fieldTypeElement)) {
            TypeDefinition voDef = buildEmbeddedVO(fieldTypeElement);
            relations.add(new RelationMapping(fieldName, RelationKind.EMBEDDED,
                    fieldTypeElement.getQualifiedName().toString(), null, null,
                    false, true, voDef));
            return;
        }
        if (fieldTypeElement != null && field.getAnnotation(Association.class) != null) {
            String targetFqn = fieldTypeElement.getQualifiedName().toString();
            TypeDefinition target = cache.get(targetFqn);
            relations.add(new RelationMapping(fieldName, RelationKind.TO_ONE,
                    targetFqn, Names.fkColumn(fieldName), null, false, false, target));
            return;
        }
        columns.add(new ColumnMapping(fieldName, Names.toSnakeCase(fieldName),
                typeFqn(fieldType), false, false));
    }

    // ── VO builders ──────────────────────────────────────────────────────

    private TypeDefinition buildEmbeddedVO(TypeElement voType) {
        String voFqn = voType.getQualifiedName().toString();
        if (!inProgress.add(voFqn)) {
            diagnostics.note("Cycle detected in VO scanning: " + voFqn + " — skipping embedded fields", voType);
            return new TypeDefinition(voFqn, null, null, false, List.of(), List.of());
        }
        try {
            List<ColumnMapping> cols = new ArrayList<>();
            List<RelationMapping> rels = new ArrayList<>();
            scanVOProps(voType, cols, rels);
            return new TypeDefinition(voFqn, null, null, false, cols, rels);
        } finally {
            inProgress.remove(voFqn);
        }
    }

    private void buildElementCollection(String fieldName, TypeMirror elementType,
                                        TypeElement elementTE, String ownerTable,
                                        String ownerSingular,
                                        List<RelationMapping> relations) {
        String voFqn = elementTE.getQualifiedName().toString();
        String childTable = Names.childTable(ownerTable, fieldName);
        String parentFkCol = Names.fkColumn(ownerSingular);

        List<ColumnMapping> cols = new ArrayList<>();
        List<RelationMapping> rels = new ArrayList<>();
        scanVOProps(elementTE, cols, rels);

        TypeDefinition elementDef = new TypeDefinition(voFqn, childTable, null, false, cols, rels);

        relations.add(new RelationMapping(fieldName, RelationKind.ELEMENT_COLLECTION,
                voFqn, parentFkCol, childTable, false, false, elementDef));
    }

    private void scanVOProps(TypeElement voType, List<ColumnMapping> columns,
                             List<RelationMapping> relations) {
        for (Element member : elements.getAllMembers(voType)) {
            if (member.getKind() != ElementKind.FIELD) continue;
            if (member.getModifiers().contains(Modifier.STATIC)) continue;

            VariableElement field = (VariableElement) member;
            if (field.getAnnotation(Identity.class) != null) continue;

            String fieldName = field.getSimpleName().toString();
            TypeMirror fieldType = field.asType();
            classifyVOField(field, fieldName, fieldType, columns, relations);
        }
    }

    // ── Entity collection builder ────────────────────────────────────────

    private void buildEntityCollection(String fieldName, TypeElement elementTE,
                                       String ownerSingular, String ownerFqn,
                                       List<RelationMapping> relations) {
        String targetFqn = elementTE.getQualifiedName().toString();
        TypeDefinition target = cache.get(targetFqn);
        String inverseFkColumn = Names.fkColumn(ownerSingular);

        if (target == null) {
            pendingTargets
                    .computeIfAbsent(ownerFqn, k -> new HashMap<>())
                    .put(fieldName, targetFqn);
        }

        relations.add(new RelationMapping(fieldName, RelationKind.TO_MANY,
                targetFqn, inverseFkColumn, null, false, false, target));
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
        List<RelationMapping> resolvedRelations = new ArrayList<>();
        boolean changed = false;

        for (RelationMapping rel : definition.relationMappings()) {
            RelationMapping resolved = resolveRelation(rel, ownerFqn);
            resolvedRelations.add(resolved);
            if (resolved != rel) changed = true;
        }

        if (!changed) return definition;

        return new TypeDefinition(definition.typeName(), definition.tableName(),
                definition.idColumn(), definition.isAggregate(),
                definition.columnMappings(), resolvedRelations);
    }

    private RelationMapping resolveRelation(RelationMapping rel, String ownerFqn) {
        if (rel.target() != null) return rel;

        TypeDefinition target = cache.get(rel.targetTypeName());

        if (target == null) {
            Map<String, String> pending = pendingTargets.get(ownerFqn);
            if (pending != null) {
                String targetFqn = pending.get(rel.propertyName());
                if (targetFqn != null) {
                    target = cache.get(targetFqn);
                }
            }
        }

        if (target != null) {
            return new RelationMapping(rel.propertyName(), rel.kind(), rel.targetTypeName(),
                    rel.fkColumn(), rel.childTable(), rel.isComposition(), rel.isEmbedded(), target);
        }

        return rel;
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
