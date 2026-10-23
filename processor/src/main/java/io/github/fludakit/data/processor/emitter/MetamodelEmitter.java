package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.support.Diagnostics;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.Set;

/**
 * Generates the Jakarta Data static metamodel for each entity type:
 * an {@code _Entity} interface with {@code @StaticMetamodel(Entity.class)}, String constants
 * for each property name, and typed attribute fields.
 *
 * <p>Navigable attributes (associations, embeddables, collections) are emitted as plain
 * {@code EntityAttribute}/{@code EmbeddableAttribute}/{@code ListAttribute} record fields.
 * Path composition uses the {@code get()} pattern: {@code _Order.customer.get(_Customer.name)}
 * produces a path-scoped attribute with combined path {@code "customer.name"}.</p>
 */
public final class MetamodelEmitter {

    private static final String METAMODEL_PACKAGE = "io.github.fludakit.data.metamodel";
    private static final String PROCESSOR_FQN = "io.github.fludakit.data.processor.RepositoryProcessor";

    private static final ClassName STATIC_METAMODEL =
            ClassName.bestGuess("jakarta.data.metamodel.StaticMetamodel");
    private static final ClassName GENERATED =
            ClassName.bestGuess("jakarta.annotation.Generated");

    private static final ClassName ID_ATTRIBUTE =
            ClassName.get(METAMODEL_PACKAGE, "IdAttribute");
    private static final ClassName TEXT_SCALAR_ATTRIBUTE =
            ClassName.get(METAMODEL_PACKAGE, "TextScalarAttribute");
    private static final ClassName SORTABLE_SCALAR_ATTRIBUTE =
            ClassName.get(METAMODEL_PACKAGE, "SortableScalarAttribute");
    private static final ClassName SCALAR_ATTRIBUTE =
            ClassName.get(METAMODEL_PACKAGE, "ScalarAttribute");
    private static final ClassName EMBEDDABLE_ATTRIBUTE =
            ClassName.get(METAMODEL_PACKAGE, "EmbeddableAttribute");
    private static final ClassName ENTITY_ATTRIBUTE =
            ClassName.get(METAMODEL_PACKAGE, "EntityAttribute");
    private static final ClassName LIST_ATTRIBUTE =
            ClassName.get(METAMODEL_PACKAGE, "ListAttribute");

    private static final ClassName ID_ATTRIBUTE_RECORD =
            ClassName.get(METAMODEL_PACKAGE, "IdAttributeRecord");
    private static final ClassName TEXT_SCALAR_ATTRIBUTE_RECORD =
            ClassName.get(METAMODEL_PACKAGE, "TextScalarAttributeRecord");
    private static final ClassName SORTABLE_SCALAR_ATTRIBUTE_RECORD =
            ClassName.get(METAMODEL_PACKAGE, "SortableScalarAttributeRecord");
    private static final ClassName SCALAR_ATTRIBUTE_RECORD =
            ClassName.get(METAMODEL_PACKAGE, "ScalarAttributeRecord");
    private static final ClassName ENTITY_ATTRIBUTE_RECORD =
            ClassName.get(METAMODEL_PACKAGE, "EntityAttributeRecord");
    private static final ClassName EMBEDDABLE_ATTRIBUTE_RECORD =
            ClassName.get(METAMODEL_PACKAGE, "EmbeddableAttributeRecord");
    private static final ClassName LIST_ATTRIBUTE_RECORD =
            ClassName.get(METAMODEL_PACKAGE, "ListAttributeRecord");

    private static final Set<String> TEXT_TYPES = Set.of(
            "java.lang.String", "char"
    );

    private static final Set<String> SORTABLE_TYPES = Set.of(
            "boolean", "byte", "short", "int", "long", "float", "double",
            "java.lang.Boolean", "java.lang.Byte", "java.lang.Short",
            "java.lang.Integer", "java.lang.Long", "java.lang.Float",
            "java.lang.Double",
            "java.math.BigDecimal", "java.math.BigInteger",
            "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime",
            "java.time.OffsetDateTime", "java.time.OffsetTime", "java.time.Instant",
            "java.time.ZonedDateTime",
            "java.util.UUID"
    );

    private final Filer filer;
    private final Diagnostics diagnostics;

    public MetamodelEmitter(Filer filer, Diagnostics diagnostics) {
        this.filer = filer;
        this.diagnostics = diagnostics;
    }

    public void emit(TypeDefinition entity) {
        String typeFqn = entity.typeName();
        String packageName = packageNameOf(typeFqn);
        String simpleName = simpleNameOf(typeFqn);
        String metamodelName = "_" + simpleName;

        ClassName entityType = ClassName.bestGuess(typeFqn);

        TypeSpec.Builder interfaceBuilder = TypeSpec.interfaceBuilder(metamodelName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(STATIC_METAMODEL)
                        .addMember("value", "$T.class", entityType)
                        .build())
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build());

        TypeDefinition.Prop.Id idProp = idProp(entity);
        if (idProp != null) {
            String constName = toScreamingSnakeCase(idProp.name());
            addStringConstant(interfaceBuilder, constName, idProp.name());

            TypeName fieldType = ParameterizedTypeName.get(ID_ATTRIBUTE, entityType);
            TypeName recordType = ParameterizedTypeName.get(ID_ATTRIBUTE_RECORD, entityType);
            interfaceBuilder.addField(FieldSpec.builder(fieldType, idProp.name(),
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T($S, $L)", recordType, idProp.name(), constName)
                    .build());
        }

        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.Scalar s) {
                addScalarToInterface(interfaceBuilder, s, entityType);
            } else if (prop instanceof TypeDefinition.Prop.Embedded embedded) {
                addEmbeddedToInterface(interfaceBuilder, embedded, entityType);
            } else if (prop instanceof TypeDefinition.Prop.Association assoc) {
                addAssociationToInterface(interfaceBuilder, assoc, entityType);
            } else if (prop instanceof TypeDefinition.Prop.EntityCollection ec) {
                addEntityCollectionToInterface(interfaceBuilder, ec, entityType);
            } else if (prop instanceof TypeDefinition.Prop.ElementCollection elemCol) {
                addElementCollectionToInterface(interfaceBuilder, elemCol, entityType);
            }
        }

        ClassName metamodelType = ClassName.get(packageName, metamodelName);
        writeJavaFile(packageName, interfaceBuilder.build(), metamodelType);
    }

    private void addScalarToInterface(TypeSpec.Builder builder, TypeDefinition.Prop.Scalar col,
                                       ClassName entityType) {
        String constName = toScreamingSnakeCase(col.name());
        addStringConstant(builder, constName, col.name());

        ClassName attributeInterface = scalarAttributeInterface(col.javaType());
        ClassName attributeRecord = scalarAttributeRecord(col.javaType());

        TypeName fieldType = ParameterizedTypeName.get(attributeInterface, entityType);
        TypeName recordType = ParameterizedTypeName.get(attributeRecord, entityType);

        builder.addField(FieldSpec.builder(fieldType, col.name(),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T($S, $L)", recordType, col.name(), constName)
                .build());
    }

    private void addEmbeddedToInterface(TypeSpec.Builder builder, TypeDefinition.Prop.Embedded embedded,
                                         ClassName entityType) {
        String constName = toScreamingSnakeCase(embedded.name());
        addStringConstant(builder, constName, embedded.name());

        ClassName voType = embedded.definition() != null
                ? ClassName.bestGuess(embedded.definition().typeName())
                : ClassName.get("java.lang", "Object");
        TypeName fieldType = ParameterizedTypeName.get(EMBEDDABLE_ATTRIBUTE, entityType, voType);
        TypeName recordType = ParameterizedTypeName.get(EMBEDDABLE_ATTRIBUTE_RECORD, entityType, voType);

        builder.addField(FieldSpec.builder(fieldType, embedded.name(),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T($S, $L)", recordType, embedded.name(), constName)
                .build());
    }

    private void addAssociationToInterface(TypeSpec.Builder builder, TypeDefinition.Prop.Association assoc,
                                            ClassName entityType) {
        String constName = toScreamingSnakeCase(assoc.name());
        addStringConstant(builder, constName, assoc.name());

        if (!(assoc.definition() instanceof TypeDefinition.Entity targetEntity)) {
            return;
        }
        ClassName targetType = ClassName.bestGuess(targetEntity.typeName());
        TypeName fieldType = ParameterizedTypeName.get(ENTITY_ATTRIBUTE, entityType, targetType);
        TypeName recordType = ParameterizedTypeName.get(ENTITY_ATTRIBUTE_RECORD, entityType, targetType);

        builder.addField(FieldSpec.builder(fieldType, assoc.name(),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T($S, $L)", recordType, assoc.name(), constName)
                .build());
    }

    private void addEntityCollectionToInterface(TypeSpec.Builder builder, TypeDefinition.Prop.EntityCollection ec,
                                                 ClassName entityType) {
        String constName = toScreamingSnakeCase(ec.name());
        addStringConstant(builder, constName, ec.name());

        if (!(ec.definition() instanceof TypeDefinition.Entity targetEntity)) {
            return;
        }
        ClassName elementType = ClassName.bestGuess(targetEntity.typeName());
        TypeName fieldType = ParameterizedTypeName.get(LIST_ATTRIBUTE, entityType, elementType);
        TypeName recordType = ParameterizedTypeName.get(LIST_ATTRIBUTE_RECORD, entityType, elementType);

        builder.addField(FieldSpec.builder(fieldType, ec.name(),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T($S, $L)", recordType, ec.name(), constName)
                .build());
    }

    private void addElementCollectionToInterface(TypeSpec.Builder builder, TypeDefinition.Prop.ElementCollection ec,
                                                  ClassName entityType) {
        String constName = toScreamingSnakeCase(ec.name());
        addStringConstant(builder, constName, ec.name());

        ClassName elementType = ClassName.bestGuess(ec.javaType());
        TypeName fieldType = ParameterizedTypeName.get(LIST_ATTRIBUTE, entityType, elementType);
        TypeName recordType = ParameterizedTypeName.get(LIST_ATTRIBUTE_RECORD, entityType, elementType);

        builder.addField(FieldSpec.builder(fieldType, ec.name(),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T($S, $L)", recordType, ec.name(), constName)
                .build());
    }

    private static ClassName scalarAttributeInterface(String typeFqn) {
        if (TEXT_TYPES.contains(typeFqn)) return TEXT_SCALAR_ATTRIBUTE;
        if (SORTABLE_TYPES.contains(typeFqn)) return SORTABLE_SCALAR_ATTRIBUTE;
        return SCALAR_ATTRIBUTE;
    }

    private static ClassName scalarAttributeRecord(String typeFqn) {
        if (TEXT_TYPES.contains(typeFqn)) return TEXT_SCALAR_ATTRIBUTE_RECORD;
        if (SORTABLE_TYPES.contains(typeFqn)) return SORTABLE_SCALAR_ATTRIBUTE_RECORD;
        return SCALAR_ATTRIBUTE_RECORD;
    }

    private static void addStringConstant(TypeSpec.Builder builder, String constName, String value) {
        builder.addField(FieldSpec.builder(String.class, constName,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", value)
                .build());
    }

    private void writeJavaFile(String packageName, TypeSpec typeSpec, ClassName expectedName) {
        try {
            JavaFile.builder(packageName, typeSpec)
                    .build()
                    .writeTo(filer);
        } catch (IOException e) {
            // Filer will report the error; silently skip
        }
    }

    static String toScreamingSnakeCase(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && !Character.isUpperCase(name.charAt(i - 1))) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    private static TypeDefinition.Prop.Id idProp(TypeDefinition def) {
        return switch (def) {
            case TypeDefinition.Entity e -> e.id();
            case TypeDefinition.Aggregate a -> a.id();
            default -> null;
        };
    }

    private static String packageNameOf(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot > 0 ? fqn.substring(0, lastDot) : "";
    }

    private static String simpleNameOf(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot > 0 ? fqn.substring(lastDot + 1) : fqn;
    }
}
