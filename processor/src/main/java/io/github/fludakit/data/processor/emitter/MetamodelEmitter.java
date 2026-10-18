package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.metadata.EmbeddedValueObjectMapping;
import io.github.fludakit.data.metadata.ElementCollectionValueObjectMapping;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.metadata.ValueObjectMapping;
import io.github.fludakit.data.processor.support.Diagnostics;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Generates the Jakarta Data static metamodel for each entity type:
 * <ul>
 *   <li>{@code _Entity} interface with {@code @StaticMetamodel(Entity.class)} annotation,
 *       String constants for each property name, and typed attribute fields.</li>
 *   <li>{@code _EntityMember} classes for navigable members (embedded, reference, collections).</li>
 * </ul>
 *
 * <p>Naming: entity {@code Customer} produces {@code _Customer} (interface) and navigable member
 * classes like {@code _CustomerAddress} in the same package.</p>
 */
public final class MetamodelEmitter {

    private static final String METAMODEL_PACKAGE = "io.github.fludakit.data.metamodel";
    private static final String PROCESSOR_FQN = "io.github.fludakit.data.processor.RepositoryProcessor";

    private static final ClassName STATIC_METAMODEL =
            ClassName.bestGuess("jakarta.data.metamodel.StaticMetamodel");
    private static final ClassName GENERATED =
            ClassName.bestGuess("jakarta.annotation.Generated");

    // Attribute interfaces
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

    // Attribute record implementations
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

    /** Types whose scalar attribute is {@link TextScalarAttribute}. */
    private static final Set<String> TEXT_TYPES = Set.of(
            "java.lang.String", "char"
    );

    /** Types whose scalar attribute is {@link SortableScalarAttribute}. */
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

    /** FQNs of entity metamodel interfaces (pre-claimed names that nav classes must not collide with). */
    private final Set<String> claimedMetamodelFqns;

    public MetamodelEmitter(Filer filer, Diagnostics diagnostics) {
        this.filer = filer;
        this.diagnostics = diagnostics;
        this.claimedMetamodelFqns = new HashSet<>();
    }

    /**
     * Pre-registers entity metamodel FQNs so that nav-class collision detection works
     * across entities in the same processing round.
     */
    public void preclaimEntityMetamodel(TypeMapping<?> entity) {
        String packageName = packageNameOf(entity.typeName());
        String simpleName = simpleNameOf(entity.typeName());
        claimedMetamodelFqns.add(packageName + "._" + simpleName);
    }

    /**
     * Generates the static metamodel for the given entity type mapping.
     *
     * @param entity the entity type mapping
     */
    public void emit(TypeMapping<?> entity) {
        String typeFqn = entity.typeName();
        String packageName = packageNameOf(typeFqn);
        String simpleName = simpleNameOf(typeFqn);
        String metamodelName = "_" + simpleName;

        ClassName entityType = ClassName.bestGuess(typeFqn);
        ClassName metamodelType = ClassName.get(packageName, metamodelName);

        // Build the _Entity interface
        TypeSpec.Builder interfaceBuilder = TypeSpec.interfaceBuilder(metamodelName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(STATIC_METAMODEL)
                        .addMember("value", "$T.class", entityType)
                        .build())
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build());

        // Add ID constant + field
        if (entity.id() != null) {
            Prop.Id id = entity.id();
            String constName = toScreamingSnakeCase(id.name());
            addStringConstant(interfaceBuilder, constName, id.name());

            TypeName fieldType = ParameterizedTypeName.get(ID_ATTRIBUTE, entityType);
            TypeName recordType = ParameterizedTypeName.get(ID_ATTRIBUTE_RECORD, entityType);
            interfaceBuilder.addField(FieldSpec.builder(fieldType, id.name(),
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T($S, $L)", recordType, id.name(), constName)
                    .build());
        }

        // Add props
        for (Prop prop : entity.props()) {
            addPropToInterface(interfaceBuilder, prop, entityType, metamodelType,
                    packageName, simpleName, "");
        }

        // Write the interface
        writeJavaFile(packageName, interfaceBuilder.build(), metamodelType);
    }

    // ── Interface prop handling ──────────────────────────────────────────

    private void addPropToInterface(TypeSpec.Builder interfaceBuilder, Prop prop,
                                     ClassName entityType, ClassName metamodelType,
                                     String packageName, String entitySimpleName,
                                     String pathPrefix) {
        if (prop instanceof Prop.Scalar scalar) {
            addScalarToInterface(interfaceBuilder, scalar, entityType, pathPrefix);
        } else if (prop instanceof Prop.Embedded embedded) {
            addEmbeddedToInterface(interfaceBuilder, embedded, entityType, metamodelType,
                    packageName, entitySimpleName, pathPrefix);
        } else if (prop instanceof Prop.Reference ref) {
            addReferenceToInterface(interfaceBuilder, ref, entityType, metamodelType,
                    packageName, entitySimpleName, pathPrefix);
        } else if (prop instanceof Prop.EntityCollection ec) {
            addEntityCollectionToInterface(interfaceBuilder, ec, entityType, metamodelType,
                    packageName, entitySimpleName, pathPrefix);
        } else if (prop instanceof Prop.ElementCollection ec) {
            addElementCollectionToInterface(interfaceBuilder, ec, entityType, metamodelType,
                    packageName, entitySimpleName, pathPrefix);
        }
    }

    private void addScalarToInterface(TypeSpec.Builder builder, Prop.Scalar scalar,
                                       ClassName entityType, String pathPrefix) {
        String constName = toScreamingSnakeCase(scalar.name());
        addStringConstant(builder, constName, scalar.name());

        String typeFqn = scalar.typeFqn();
        ClassName attributeInterface = scalarAttributeInterface(typeFqn);
        ClassName attributeRecord = scalarAttributeRecord(typeFqn);

        TypeName fieldType = ParameterizedTypeName.get(attributeInterface, entityType);
        TypeName recordType = ParameterizedTypeName.get(attributeRecord, entityType);

        String path = pathPrefix.isEmpty() ? scalar.name() : pathPrefix + "." + scalar.name();
        String pathArg = pathPrefix.isEmpty() ? constName : "\"" + path + "\"";

        builder.addField(FieldSpec.builder(fieldType, scalar.name(),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T($S, $L)", recordType, scalar.name(), pathArg)
                .build());
    }

    private void addEmbeddedToInterface(TypeSpec.Builder builder, Prop.Embedded embedded,
                                         ClassName entityType, ClassName metamodelType,
                                         String packageName, String entitySimpleName,
                                         String pathPrefix) {
        String constName = toScreamingSnakeCase(embedded.name());
        addStringConstant(builder, constName, embedded.name());

        String navClassName = "_" + entitySimpleName + capitalize(embedded.name());
        String navFqn = packageName + "." + navClassName;

        if (claimedMetamodelFqns.contains(navFqn)) {
            // Collision: degrade to plain EmbeddableAttribute record field
            ClassName voType = embedded.target() != null
                    ? ClassName.bestGuess(embedded.target().typeName())
                    : ClassName.get("java.lang", "Object");
            TypeName fieldType = ParameterizedTypeName.get(EMBEDDABLE_ATTRIBUTE, entityType, voType);
            TypeName recordType = ParameterizedTypeName.get(EMBEDDABLE_ATTRIBUTE_RECORD, entityType, voType);
            builder.addField(FieldSpec.builder(fieldType, embedded.name(),
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T($S, $L)", recordType, embedded.name(), constName)
                    .build());
            diagnostics.note("static metamodel collision on navigable member '" + embedded.name()
                    + "': type '" + navFqn + "' already claimed", null);
            return;
        }

        // Add the nav field to the interface
        ClassName navType = ClassName.get(packageName, navClassName);
        builder.addField(FieldSpec.builder(navType, embedded.name(),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T()", navType)
                .build());

        // Generate the nav class
        String fullPath = pathPrefix.isEmpty() ? embedded.name() : pathPrefix + "." + embedded.name();
        emitEmbeddedNavClass(embedded, entityType, packageName, entitySimpleName,
                embedded.name(), navClassName, fullPath);
    }

    private void addReferenceToInterface(TypeSpec.Builder builder, Prop.Reference ref,
                                          ClassName entityType, ClassName metamodelType,
                                          String packageName, String entitySimpleName,
                                          String pathPrefix) {
        String constName = toScreamingSnakeCase(ref.name());
        addStringConstant(builder, constName, ref.name());

        String navClassName = "_" + entitySimpleName + capitalize(ref.name());
        String navFqn = packageName + "." + navClassName;

        if (claimedMetamodelFqns.contains(navFqn)) {
            // Collision: degrade to plain entity attribute record
            TypeName fieldType = ParameterizedTypeName.get(ENTITY_ATTRIBUTE, entityType,
                    ClassName.bestGuess(ref.targetTypeName()));
            TypeName recordType = ParameterizedTypeName.get(ENTITY_ATTRIBUTE_RECORD, entityType,
                    ClassName.bestGuess(ref.targetTypeName()));
            builder.addField(FieldSpec.builder(fieldType, ref.name(),
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T($S, $L)", recordType, ref.name(), constName)
                    .build());
            diagnostics.note("static metamodel collision on navigable member '" + ref.name()
                    + "': type '" + navFqn + "' already claimed", null);
            return;
        }

        // Add the nav field to the interface
        ClassName navType = ClassName.get(packageName, navClassName);
        builder.addField(FieldSpec.builder(navType, ref.name(),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T()", navType)
                .build());

        // Generate the nav class
        String fullPath = pathPrefix.isEmpty() ? ref.name() : pathPrefix + "." + ref.name();
        emitReferenceNavClass(ref, entityType, packageName, entitySimpleName,
                ref.name(), navClassName, fullPath);
    }

    private void addEntityCollectionToInterface(TypeSpec.Builder builder, Prop.EntityCollection ec,
                                                 ClassName entityType, ClassName metamodelType,
                                                 String packageName, String entitySimpleName,
                                                 String pathPrefix) {
        String constName = toScreamingSnakeCase(ec.name());
        addStringConstant(builder, constName, ec.name());

        String navClassName = "_" + entitySimpleName + capitalize(ec.name());
        String navFqn = packageName + "." + navClassName;

        if (claimedMetamodelFqns.contains(navFqn)) {
            diagnostics.note("static metamodel collision on navigable member '" + ec.name()
                    + "': type '" + navFqn + "' already claimed", null);
            return;
        }

        // Add the nav field to the interface
        ClassName navType = ClassName.get(packageName, navClassName);
        builder.addField(FieldSpec.builder(navType, ec.name(),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T()", navType)
                .build());

        // Generate the nav class
        String fullPath = pathPrefix.isEmpty() ? ec.name() : pathPrefix + "." + ec.name();
        if (ec.target() != null) {
            emitEntityCollectionNavClass(ec, entityType, packageName, entitySimpleName,
                    ec.name(), navClassName, fullPath);
        }
    }

    private void addElementCollectionToInterface(TypeSpec.Builder builder, Prop.ElementCollection ec,
                                                  ClassName entityType, ClassName metamodelType,
                                                  String packageName, String entitySimpleName,
                                                  String pathPrefix) {
        String constName = toScreamingSnakeCase(ec.name());
        addStringConstant(builder, constName, ec.name());

        String navClassName = "_" + entitySimpleName + capitalize(ec.name());
        String navFqn = packageName + "." + navClassName;

        if (claimedMetamodelFqns.contains(navFqn)) {
            diagnostics.note("static metamodel collision on navigable member '" + ec.name()
                    + "': type '" + navFqn + "' already claimed", null);
            return;
        }

        // Add the nav field to the interface
        ClassName navType = ClassName.get(packageName, navClassName);
        builder.addField(FieldSpec.builder(navType, ec.name(),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T()", navType)
                .build());

        // Generate the nav class
        String fullPath = pathPrefix.isEmpty() ? ec.name() : pathPrefix + "." + ec.name();
        if (ec.target() != null) {
            emitElementCollectionNavClass(ec, entityType, packageName, entitySimpleName,
                    ec.name(), navClassName, fullPath);
        }
    }

    // ── Nav class generation ─────────────────────────────────────────────

    private void emitEmbeddedNavClass(Prop.Embedded embedded, ClassName entityType,
                                       String packageName, String entitySimpleName,
                                       String fieldName, String navClassName,
                                       String fullPath) {
        if (embedded.target() == null) return;

        ClassName voType = ClassName.bestGuess(embedded.target().typeName());
        ParameterizedTypeName superInterface = ParameterizedTypeName.get(
                EMBEDDABLE_ATTRIBUTE, entityType, voType);

        TypeSpec.Builder navBuilder = TypeSpec.classBuilder(navClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(superInterface);

        // Add name() and path() methods
        addNameAndPathMethods(navBuilder, fieldName, fullPath);

        // Add leaf fields from the VO mapping
        Set<String> visitedVOs = new HashSet<>();
        visitedVOs.add(embedded.target().typeName());
        addVoPropsToNav(navBuilder, embedded.target(), entityType, packageName,
                entitySimpleName, navClassName, fullPath, visitedVOs);

        writeJavaFile(packageName, navBuilder.build(),
                ClassName.get(packageName, navClassName));
    }

    private void emitReferenceNavClass(Prop.Reference ref, ClassName entityType,
                                        String packageName, String entitySimpleName,
                                        String fieldName, String navClassName,
                                        String fullPath) {
        if (ref.target() == null) return;

        ClassName targetType = ClassName.bestGuess(ref.targetTypeName());
        ParameterizedTypeName superInterface = ParameterizedTypeName.get(
                ENTITY_ATTRIBUTE, entityType, targetType);

        TypeSpec.Builder navBuilder = TypeSpec.classBuilder(navClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(superInterface);

        // Add name() and path() methods
        addNameAndPathMethods(navBuilder, fieldName, fullPath);

        // Add leaf fields from the target entity mapping
        addEntityLeavesToNav(navBuilder, ref.target(), entityType, packageName,
                entitySimpleName, navClassName, fullPath);

        writeJavaFile(packageName, navBuilder.build(),
                ClassName.get(packageName, navClassName));
    }

    private void emitEntityCollectionNavClass(Prop.EntityCollection ec, ClassName entityType,
                                               String packageName, String entitySimpleName,
                                               String fieldName, String navClassName,
                                               String fullPath) {
        if (ec.target() == null) return;

        ClassName targetType = ClassName.bestGuess(ec.target().typeName());
        ParameterizedTypeName superInterface = ParameterizedTypeName.get(
                LIST_ATTRIBUTE, entityType, targetType);

        TypeSpec.Builder navBuilder = TypeSpec.classBuilder(navClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(superInterface);

        // Add name() and path() methods
        addNameAndPathMethods(navBuilder, fieldName, fullPath);

        // Add leaf fields from the target entity mapping
        addEntityLeavesToNav(navBuilder, ec.target(), entityType, packageName,
                entitySimpleName, navClassName, fullPath);

        writeJavaFile(packageName, navBuilder.build(),
                ClassName.get(packageName, navClassName));
    }

    private void emitElementCollectionNavClass(Prop.ElementCollection ec, ClassName entityType,
                                                String packageName, String entitySimpleName,
                                                String fieldName, String navClassName,
                                                String fullPath) {
        if (ec.target() == null) return;

        ClassName elementType = ClassName.bestGuess(ec.target().typeName());
        ParameterizedTypeName superInterface = ParameterizedTypeName.get(
                LIST_ATTRIBUTE, entityType, elementType);

        TypeSpec.Builder navBuilder = TypeSpec.classBuilder(navClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(superInterface);

        // Add name() and path() methods
        addNameAndPathMethods(navBuilder, fieldName, fullPath);

        // Add leaf fields from the element VO mapping
        Set<String> visitedVOs = new HashSet<>();
        visitedVOs.add(ec.target().typeName());
        addVoPropsToNav(navBuilder, ec.target(), entityType, packageName,
                entitySimpleName, navClassName, fullPath, visitedVOs);

        writeJavaFile(packageName, navBuilder.build(),
                ClassName.get(packageName, navClassName));
    }

    // ── Nav class leaf fields ────────────────────────────────────────────

    /**
     * Adds leaf fields from a value object mapping to a nav class. Handles embedded VOs
     * recursively with cycle detection.
     */
    private void addVoPropsToNav(TypeSpec.Builder navBuilder, ValueObjectMapping<?> voMapping,
                                  ClassName entityType, String packageName,
                                  String entitySimpleName, String ownerNavClassName,
                                  String fullPath, Set<String> visitedVOs) {
        for (Prop prop : voMapping.props()) {
            if (prop instanceof Prop.Scalar scalar) {
                addScalarToNav(navBuilder, scalar, entityType, fullPath);
            } else if (prop instanceof Prop.Embedded embedded) {
                addEmbeddedToNav(navBuilder, embedded, entityType, packageName,
                        entitySimpleName, ownerNavClassName, fullPath, visitedVOs);
            }
        }
    }

    /**
     * Adds leaf fields from an entity mapping to a nav class (for Reference/EntityCollection targets).
     * Only adds scalars and the entity's own id; does not recurse into further navigable members.
     */
    private void addEntityLeavesToNav(TypeSpec.Builder navBuilder, TypeMapping<?> target,
                                       ClassName entityType, String packageName,
                                       String entitySimpleName, String ownerNavClassName,
                                       String fullPath) {
        // Add target's id
        if (target.id() != null) {
            Prop.Id id = target.id();
            String leafPath = fullPath + "." + id.name();
            TypeName fieldType = ParameterizedTypeName.get(ID_ATTRIBUTE, entityType);
            TypeName recordType = ParameterizedTypeName.get(ID_ATTRIBUTE_RECORD, entityType);
            navBuilder.addField(FieldSpec.builder(fieldType, id.name(),
                            Modifier.PUBLIC, Modifier.FINAL)
                    .initializer("new $T($S, $S)", recordType, id.name(), leafPath)
                    .build());
        }

        // Add target's scalar props and embedded VOs
        for (Prop prop : target.props()) {
            if (prop instanceof Prop.Scalar scalar) {
                addScalarToNav(navBuilder, scalar, entityType, fullPath);
            } else if (prop instanceof Prop.Embedded embedded) {
                // Flatten embedded VOs from the target into this nav
                Set<String> visitedVOs = new HashSet<>();
                if (embedded.target() != null) {
                    visitedVOs.add(embedded.target().typeName());
                }
                addEmbeddedToNav(navBuilder, embedded, entityType, packageName,
                        entitySimpleName, ownerNavClassName, fullPath, visitedVOs);
            } else if (prop instanceof Prop.Reference ref) {
                // Reference on the target: add as a plain ScalarAttribute (non-navigable leaf)
                String leafPath = fullPath + "." + ref.name();
                TypeName fieldType = ParameterizedTypeName.get(SCALAR_ATTRIBUTE, entityType);
                TypeName recordType = ParameterizedTypeName.get(SCALAR_ATTRIBUTE_RECORD, entityType);
                navBuilder.addField(FieldSpec.builder(fieldType, ref.name(),
                                Modifier.PUBLIC, Modifier.FINAL)
                        .initializer("new $T($S, $S)", recordType, ref.name(), leafPath)
                        .build());
            }
        }
    }

    private void addScalarToNav(TypeSpec.Builder navBuilder, Prop.Scalar scalar,
                                 ClassName entityType, String ownerPath) {
        String leafPath = ownerPath + "." + scalar.name();
        String typeFqn = scalar.typeFqn();
        ClassName attributeInterface = scalarAttributeInterface(typeFqn);
        ClassName attributeRecord = scalarAttributeRecord(typeFqn);

        TypeName fieldType = ParameterizedTypeName.get(attributeInterface, entityType);
        TypeName recordType = ParameterizedTypeName.get(attributeRecord, entityType);

        navBuilder.addField(FieldSpec.builder(fieldType, scalar.name(),
                        Modifier.PUBLIC, Modifier.FINAL)
                .initializer("new $T($S, $S)", recordType, scalar.name(), leafPath)
                .build());
    }

    private void addEmbeddedToNav(TypeSpec.Builder navBuilder, Prop.Embedded embedded,
                                   ClassName entityType, String packageName,
                                   String entitySimpleName, String ownerNavClassName,
                                   String ownerPath, Set<String> visitedVOs) {
        if (embedded.target() == null) return;

        // Build the nested nav class name: _OwnerNavClassName + Capitalized(fieldName)
        String nestedNavName = ownerNavClassName + capitalize(embedded.name());
        String nestedFullPath = ownerPath + "." + embedded.name();

        // Cycle detection: if this VO is already in the visited set, emit a container-only nav
        boolean isCycle = visitedVOs.contains(embedded.target().typeName());

        // Add the nested nav field
        ClassName nestedNavType = ClassName.get(packageName, nestedNavName);
        navBuilder.addField(FieldSpec.builder(nestedNavType, embedded.name(),
                        Modifier.PUBLIC, Modifier.FINAL)
                .initializer("new $T()", nestedNavType)
                .build());

        // Generate the nested nav class
        ClassName voType = ClassName.bestGuess(embedded.target().typeName());
        ParameterizedTypeName superInterface = ParameterizedTypeName.get(
                EMBEDDABLE_ATTRIBUTE, entityType, voType);

        TypeSpec.Builder nestedNavBuilder = TypeSpec.classBuilder(nestedNavName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(superInterface);

        addNameAndPathMethods(nestedNavBuilder, embedded.name(), nestedFullPath);

        if (!isCycle) {
            // Recurse into the VO's props
            Set<String> childVisited = new HashSet<>(visitedVOs);
            addVoPropsToNav(nestedNavBuilder, embedded.target(), entityType, packageName,
                    entitySimpleName, nestedNavName, nestedFullPath, childVisited);
        }
        // If cycle: container-only nav with no leaf fields

        writeJavaFile(packageName, nestedNavBuilder.build(),
                ClassName.get(packageName, nestedNavName));
    }

    // ── Name/path methods for nav classes ────────────────────────────────

    private void addNameAndPathMethods(TypeSpec.Builder navBuilder, String name, String path) {
        navBuilder.addMethod(MethodSpec.methodBuilder("name")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S", name)
                .build());

        navBuilder.addMethod(MethodSpec.methodBuilder("path")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S", path)
                .build());
    }

    // ── Scalar type mapping ──────────────────────────────────────────────

    /**
     * Returns the attribute interface for a scalar type.
     */
    private static ClassName scalarAttributeInterface(String typeFqn) {
        if (TEXT_TYPES.contains(typeFqn)) return TEXT_SCALAR_ATTRIBUTE;
        if (SORTABLE_TYPES.contains(typeFqn)) return SORTABLE_SCALAR_ATTRIBUTE;
        return SCALAR_ATTRIBUTE;
    }

    /**
     * Returns the attribute record implementation for a scalar type.
     */
    private static ClassName scalarAttributeRecord(String typeFqn) {
        if (TEXT_TYPES.contains(typeFqn)) return TEXT_SCALAR_ATTRIBUTE_RECORD;
        if (SORTABLE_TYPES.contains(typeFqn)) return SORTABLE_SCALAR_ATTRIBUTE_RECORD;
        return SCALAR_ATTRIBUTE_RECORD;
    }

    // ── String constant helper ───────────────────────────────────────────

    private static void addStringConstant(TypeSpec.Builder builder, String constName, String value) {
        builder.addField(FieldSpec.builder(String.class, constName,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", value)
                .build());
    }

    // ── File writing ─────────────────────────────────────────────────────

    private void writeJavaFile(String packageName, TypeSpec typeSpec, ClassName expectedName) {
        try {
            JavaFile.builder(packageName, typeSpec)
                    .build()
                    .writeTo(filer);
        } catch (IOException e) {
            // Filer will report the error; silently skip
        }
    }

    // ── Naming utilities ─────────────────────────────────────────────────

    /**
     * Converts a camelCase name to SCREAMING_SNAKE_CASE.
     */
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

    private static String capitalize(String name) {
        if (name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
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
