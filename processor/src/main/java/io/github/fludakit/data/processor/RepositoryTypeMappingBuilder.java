package io.github.fludakit.data.processor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.annotation.Association;
import io.github.fludakit.data.annotation.Identity;
import io.github.fludakit.data.metadata.TypeMapping.Kind;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.metadata.EmbeddedValueObjectMapping;
import io.github.fludakit.data.metadata.EntityTypeMapping;
import io.github.fludakit.data.metadata.ElementCollectionValueObjectMapping;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds and memoizes the compile-time entity and value-object models used by repository generation. */
final class RepositoryTypeMappingBuilder {

    private final Types types;
    private final Elements elements;
    private final Messager messager;
    private final Map<String, TypeMapping> descriptorCache = new HashMap<>();
    private final Set<String> descriptorsInProgress = new HashSet<>();
    private final Map<String, RepositoryProcessor.EntityModel> entityModels = new HashMap<>();
    private final Map<String, RepositoryProcessor.EntityModel> valueObjectModels = new HashMap<>();
    private final Set<String> valueObjectModelsInProgress = new HashSet<>();

    RepositoryTypeMappingBuilder(Types types, Elements elements, Messager messager) {
        this.types = types;
        this.elements = elements;
        this.messager = messager;
    }

    RepositoryProcessor.EntityModel buildEntity(TypeElement element) {
        boolean valueObject = isValueObjectType(element);
        Kind ownerKind = kindOf(element);
        TypeMirror typeMirror = types.erasure(element.asType());
        TypeName typeName = TypeName.get(typeMirror);
        String table = valueObject ? null : Names.tableName(element.getSimpleName().toString());
        String typeNameString = element.getQualifiedName().toString();
        RepositoryProcessor.Property id = null;
        Prop.Id idProp = null;
        List<RepositoryProcessor.Property> properties = new ArrayList<>();
        List<Prop> props = new ArrayList<>();
        List<RepositoryProcessor.ElementCollection> collections = new ArrayList<>();
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
            TypeName fieldTypeName = resolveFieldTypeName(field);
            boolean identityAnnotated = field.getAnnotation(Identity.class) != null;

            if (valueObject && identityAnnotated) {
                // Value objects have no identity; keep the field scalar and report the ignored marker.
                messager.printMessage(Diagnostic.Kind.NOTE,
                        "value object '" + element.getSimpleName() + "' declares '" + name
                                + "'; ignored — value objects have no identity", element);
            }
            if (!valueObject && (identityAnnotated || name.equals("id"))) {
                idProp = new Prop.Id(name, Names.columnName(name), fieldType);
                id = new RepositoryProcessor.Property(name, idProp.column(), fieldTypeName, idProp, null);

            } else if (field.getAnnotation(Association.class) != null) {
                TypeElement target = declaredElement(field);
                Prop.Reference ref = referenceProp(name, Names.fkColumn(name),
                        target != null ? target.getQualifiedName().toString() : null, target, false);
                properties.add(new RepositoryProcessor.Property(name, ref.fkColumn(), fieldTypeName, ref, null));
                props.add(ref);

            } else if (embeddedTarget(field) != null) {
                TypeElement vo = embeddedTarget(field);
                Prop.Embedded embedded = new Prop.Embedded(name, embeddedOf(descriptorFor(vo)));
                properties.add(new RepositoryProcessor.Property(name, Names.columnName(name), fieldTypeName,
                        embedded, valueObjectModel(vo)));
                props.add(embedded);

            } else if (isEntityType(declaredElement(field))) {
                TypeElement target = declaredElement(field);
                // Bare entity references compose only when owned by an aggregate root.
                boolean composition = ownerKind == Kind.AGGREGATE_ROOT;
                Prop.Reference ref = referenceProp(name, Names.fkColumn(name),
                        target.getQualifiedName().toString(), target, composition);
                properties.add(new RepositoryProcessor.Property(name, ref.fkColumn(), fieldTypeName, ref, null));
                props.add(ref);

            } else if (valueObjectElementType(field) != null) {
                TypeElement vo = valueObjectElementType(field);
                RepositoryProcessor.ElementCollection collection = buildCollection(table, name, vo);
                collections.add(collection);
                props.add(collection.element());

            } else if (entityTypeElement(field) != null) {
                TypeElement child = entityTypeElement(field);
                props.add(new Prop.EntityCollection(name, entityOf(descriptorFor(child)), null, null));

            } else {
                Prop.Scalar scalar = new Prop.Scalar(name, Names.columnName(name), fieldType);
                properties.add(new RepositoryProcessor.Property(name, scalar.column(), fieldTypeName, scalar, null));
                props.add(scalar);
            }
        }
        if (!valueObject && id == null) {
            // Inventing an identifier silently produced a row mapper calling setId(...) on an entity
            // that has no such method — an uncompilable generated source with no hint of the cause.
            // Report it; the placeholder below only keeps this round from failing with an NPE.
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "entity '" + element.getSimpleName() + "' declares no identifier; annotate one field with"
                            + " @Identity (or name it 'id')", element);
            idProp = new Prop.Id("id", Names.columnName("id"), "java.lang.Long");
            id = new RepositoryProcessor.Property("id", idProp.column(),
                    ClassName.get(Long.class), idProp, null);
        }

        // Resolve one-to-many inverse references after collecting all parent properties.
        TypeMapping<?> self = valueObject ? new EmbeddedValueObjectMapping(typeNameString, props)
                : new EntityTypeMapping(ownerKind, typeNameString, table, idProp, props);
        List<Prop> resolved = new ArrayList<>(props.size());
        for (Prop prop : props) {
            if (prop instanceof Prop.EntityCollection entityCollection && entityCollection.targetTypeName() != null) {
                TypeElement childElement = elements.getTypeElement(entityCollection.targetTypeName());
                EntityTypeMapping<?> child = childElement != null
                        ? entityOf(descriptorFor(childElement)) : null;
                String inverse = child != null ? findInverseName(self, child) : null;
                resolved.add(new Prop.EntityCollection(entityCollection.name(), entityCollection.target(),
                        inverse, inverse == null ? null : Names.fkColumn(inverse)));
            } else {
                resolved.add(prop);
            }
        }
        TypeMapping<?> metadata = valueObject ? new EmbeddedValueObjectMapping(typeNameString, resolved)
                : new EntityTypeMapping(ownerKind, typeNameString, table, idProp, resolved);
        return new RepositoryProcessor.EntityModel(typeMirror, typeName, id, properties, collections, metadata);
    }

    TypeMapping<?> descriptorFor(TypeElement element) {
        String fqn = element.getQualifiedName().toString();
        TypeMapping<?> cached = descriptorCache.get(fqn);
        if (cached != null) {
            return cached;
        }
        if (descriptorsInProgress.contains(fqn)) {
            Kind stereotype = kindOf(element);
            return stereotype == Kind.VALUE_OBJECT
                    ? new EmbeddedValueObjectMapping(fqn, List.of())
                    : new EntityTypeMapping(stereotype, fqn, Names.tableName(element.getSimpleName().toString()),
                            null, List.of());
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

    RepositoryProcessor.EntityModel entityModel(TypeElement element) {
        String fqn = element.getQualifiedName().toString();
        RepositoryProcessor.EntityModel cached = entityModels.get(fqn);
        if (cached == null) {
            cached = buildEntity(element);
            entityModels.put(fqn, cached);
        }
        return cached;
    }

    RepositoryProcessor.EntityModel valueObjectModel(TypeElement element) {
        String fqn = element.getQualifiedName().toString();
        RepositoryProcessor.EntityModel cached = valueObjectModels.get(fqn);
        if (cached != null) {
            return cached;
        }
        if (!valueObjectModelsInProgress.add(fqn)) {
            return null;
        }
        try {
            RepositoryProcessor.EntityModel model = buildEntity(element);
            valueObjectModels.put(fqn, model);
            return model;
        } finally {
            valueObjectModelsInProgress.remove(fqn);
        }
    }

    boolean isValueObjectType(TypeElement element) {
        return element != null
                && element.getAnnotation(io.github.fludakit.data.annotation.ValueObject.class) != null;
    }

    Kind kindOf(TypeElement element) {
        if (isValueObjectType(element)) {
            return Kind.VALUE_OBJECT;
        }
        return element.getAnnotation(io.github.fludakit.data.annotation.AggregateRoot.class) != null
                ? Kind.AGGREGATE_ROOT : Kind.ENTITY;
    }

    private Prop.Reference referenceProp(String name, String fkColumn, String targetTypeName,
                                         TypeElement target, boolean composition) {
        TypeMapping<?> targetModel = target == null ? null : descriptorFor(target);
        return new Prop.Reference(name, fkColumn, targetTypeName, entityOf(targetModel), composition);
    }

    private RepositoryProcessor.ElementCollection buildCollection(
            String parentTable, String field, TypeElement valueObject) {
        String childTable = Names.childTable(parentTable, field);
        String parentFk = Names.fkColumn(parentTable);
        ElementCollectionValueObjectMapping<?> target = valueObjectMetadata(valueObject, childTable, parentFk);
        Prop.ElementCollection element = new Prop.ElementCollection(field, target, target.columns());
        return new RepositoryProcessor.ElementCollection(field,
                TypeName.get(types.erasure(valueObject.asType())), element, target);
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
            messager.printMessage(Diagnostic.Kind.ERROR,
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
            if (!seen.add(name) || field.getAnnotation(Identity.class) != null || name.equals("id")) {
                continue;
            }
            String fieldType = field.asType().toString();
            if (field.getAnnotation(Association.class) != null) {
                TypeElement target = declaredElement(field);
                props.add(referenceProp(name, Names.fkColumn(name),
                        target != null ? target.getQualifiedName().toString() : null, target, false));
            } else {
                props.add(new Prop.Scalar(name, Names.columnName(name), fieldType));
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

    private static EntityTypeMapping<?> entityOf(TypeMapping<?> model) {
        return model instanceof EntityTypeMapping<?> entity ? entity : null;
    }

    private static EmbeddedValueObjectMapping<?> embeddedOf(TypeMapping<?> model) {
        return model instanceof EmbeddedValueObjectMapping<?> embedded ? embedded : null;
    }

    private static String findInverseName(TypeMapping<?> parent, TypeMapping<?> child) {
        for (Prop prop : child.props()) {
            if (prop instanceof Prop.Reference reference
                    && parent.typeName().equals(reference.targetTypeName())) {
                return reference.name();
            }
        }
        return null;
    }
}
