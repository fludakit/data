package io.github.fludakit.data.processor.emit;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.analysis.MappedTypeBuilder;
import io.github.fludakit.data.processor.model.ElementCollection;
import io.github.fludakit.data.processor.model.EntityModel;
import io.github.fludakit.data.processor.model.Property;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.support.MemberNames;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits the reflection-free row mappers: {@code <Entity>RowMapper} for an aggregate or entity, and
 * {@code <VO>RowMapper} for the value object read out of an element-collection child table.
 *
 * <p>Reflection-free means the generated {@code mapRow} is a straight sequence of {@code rs.getObject}
 * / {@code rs.getInt} calls and setter calls — no {@code Class.forName}, no field walking. That is only
 * possible for a type whose every property is JDBC-safe, an enum, a to-one FK (skipped on read) or an
 * embeddable value object that itself passes the same test, which {@link #canGenerateRowMapper} is the
 * gate for. A type that fails it gets no mapper, and the repository degrades to the reflective path.</p>
 *
 * <p>Every failure to materialise part of a row — an unresolvable {@code @Association} target, a VO
 * that fails the gate — is a NOTE plus a null field, never an abort: hydration degrades to the
 * parent's default value rather than failing the user's build.</p>
 *
 * <p>Writes go out through {@link RepositorySourceWriter}, so this emitter never sees {@code Filer},
 * and it holds no {@code Elements}: a target that is only ever named (a descriptor carries an FQN
 * {@code String}) resolves through {@link MappedTypeBuilder#elementFor(String)}, and the one question
 * that needs a member SCAN — whether the target has an id setter, which is what makes an id-only
 * partial constructible — is {@link MappedTypeBuilder#hasMethod}, which owns {@code Elements} and
 * memoizes by name. Both belong to the class that already had to own the environment for building the
 * models; neither decides whether this emitter can be constructed. The no-arg-constructor test needs
 * no environment at all — it reads {@code getEnclosedElements} off an element it already holds. A
 * degradation is reported through {@link Diagnostics}.</p>
 */
public final class RowMapperGenerator {

    private final AttributeTypes attributeTypes;
    private final Diagnostics diagnostics;
    private final MappedTypeBuilder typeMappingBuilder;
    private final MetadataGenerator metadataGenerator;
    private final RepositorySourceWriter sourceWriter;

    /**
     * The {@code <T>Metadata} name per mapped-type FQN, shared by reference with its owner: read here to
     * point a value-object mapper at the constants its leaf reads reference.
     */
    private final Map<String, ClassName> metadataTypes;

    /** Value-object FQN to generated {@code <VO>RowMapper} type, deduped across processing rounds. */
    private final Map<String, ClassName> voRowMappers = new LinkedHashMap<>();

    public RowMapperGenerator(AttributeTypes attributeTypes, Diagnostics diagnostics,
                       MappedTypeBuilder typeMappingBuilder,
                       MetadataGenerator metadataGenerator,
                       RepositorySourceWriter sourceWriter, Map<String, ClassName> metadataTypes) {
        this.attributeTypes = attributeTypes;
        this.diagnostics = diagnostics;
        this.typeMappingBuilder = typeMappingBuilder;
        this.metadataGenerator = metadataGenerator;
        this.sourceWriter = sourceWriter;
        this.metadataTypes = metadataTypes;
    }

    /**
     * True when {@code type} names an enum, which the reflection-free mapper reads via String +
     * valueOf. The lookup is {@code AttributeTypes}' single copy — the metamodel kind decision asks
     * the same question, and a row that disagreed with its own metamodel would not type-check.
     */
    private boolean isEnumType(TypeName type) {
        return attributeTypes.isEnum(type);
    }

    /**
     * Gate for the reflection-free row mapper: safe when every property is JDBC-safe, an
     * {@code ASSOCIATION}/{@code MANY_TO_ONE} fk (skipped on read), or an {@code EMBEDDED} value
     * object whose own model passes the same check. A VO's model has no id and its leaves are its
     * properties, so the recursion flattens naturally; a {@code null} embedded model (cycle guard)
     * fails closed.
     */
    public boolean canGenerateRowMapper(EntityModel entity) {
        if (entity.id() != null && !Literals.isJdbcSafe(entity.id().type()) && !isEnumType(entity.id().type())) {
            return false;
        }
        for (Property p : entity.properties()) {
            if (p.prop() instanceof Prop.Reference) {
                continue; // FK-only, or hydrated by the relationship loaders
            }
            if (Literals.isJdbcSafe(p.type()) || isEnumType(p.type())) {
                continue;
            }
            if (p.prop() instanceof Prop.Embedded && p.embeddedModel() != null
                    && canGenerateRowMapper(p.embeddedModel())) {
                continue;
            }
            return false;
        }
        return true;
    }

    /** Emits {@code <Entity>RowMapper implements io.github.fludakit.jdbc.RowMapper<Entity>}; reflection-free. */
    public ClassName generateRowMapper(TypeElement entityElement, EntityModel entity, ClassName metadataType) {
        String packageName = entity.typeName().packageName();
        String simpleName = entityElement.getSimpleName() + "RowMapper";
        TypeName entityType = entity.typeName();
        ClassName rowMapperInterface = ClassName.get("io.github.fludakit.jdbc", "RowMapper");

        MethodSpec.Builder mapRow = MethodSpec.methodBuilder("mapRow")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(entityType)
                .addParameter(ClassName.get("java.sql", "ResultSet"), "rs")
                .addParameter(int.class, "rowNum")
                .addException(ClassName.get("java.sql", "SQLException"))
                .addStatement("$T entity = new $T()", entityType, entityType);

        if (entity.id() != null) {
            appendPropertyRead(mapRow, entity.id().type(), metadataType, entity.id().name(),
                    entity.id().column(), entityElement.getSimpleName().toString());
        }
        // a non-composing @Association (composition=false) materializes at read time as an
        // id-only PARTIAL instance through a generated partial_<Target> helper. A composition
        // Reference (root-owned many-to-one) stays loader-filled: it is deliberately NOT read here.
        // One helper per distinct target simple name (deduped). A target that cannot back a partial
        // (no no-arg ctor / setId / non-JDBC-safe id) degrades to a NOTE + null field (never abort).
        Map<String, MethodSpec> partialHelpers = new LinkedHashMap<>();
        for (Property p : entity.properties()) {
            if (p.prop() instanceof Prop.Reference ref) {
                if (!ref.composition()) {
                    appendAssociationPartial(mapRow, ref, metadataType, entityElement, partialHelpers);
                }
                continue; // composition reference: FK-only on the mapper, hydrated by the loader
            }
            if (p.prop() instanceof Prop.Embedded) {
                appendEmbeddedRead(mapRow, metadataType, p, entityElement.getSimpleName().toString());
                continue;
            }
            appendPropertyRead(mapRow, p.type(), metadataType, p.name(), p.column(),
                    entityElement.getSimpleName().toString());
        }
        mapRow.addStatement("return entity");

        TypeSpec.Builder mapperBuilder = TypeSpec.classBuilder(simpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(rowMapperInterface, entityType))
                .addMethod(mapRow.build());
        partialHelpers.values().forEach(mapperBuilder::addMethod);
        TypeSpec type = mapperBuilder.build();
        sourceWriter.write(packageName + "." + simpleName, entityElement,
                JavaFile.builder(packageName, type).skipJavaLangImports(true).build());
        return ClassName.get(packageName, simpleName);
    }

    /**
     * Emits the id-only partial materialisation of one {@code @Association} field: a read of
     * the FK through the owner's metadata constant plus a {@code MemberNames.setter(fk == null ? null :
     * partial_<Target>(fk))} call. The {@code partial_<Target>} helper (new + setId, reflection-free)
     * is registered in {@code helpers} keyed by the target simple name so several associations to the
     * same target share one helper. The target's own relationships are NOT touched (id-only). Any
     * precondition failure (unresolvable target, no id, non-JDBC-safe id, missing no-arg ctor or
     * {@code setId}) records a NOTE on {@code entityElement} and leaves the field unset (null) —
     * degraded with a NOTE, never an abort.
     */
    private void appendAssociationPartial(MethodSpec.Builder mapRow, Prop.Reference reference,
                                          ClassName metadataType, TypeElement entityElement,
                                          Map<String, MethodSpec> helpers) {
        String why = associationPartialBlocked(reference, entityElement);
        if (why != null) {
            diagnostics.note(
                    "association '" + reference.name() + "' on " + entityElement.getQualifiedName() + ": " + why
                            + " — the field is left null on read (no id-only partial materialisation)",
                    entityElement);
            return;
        }
        EntityModel targetModel = typeMappingBuilder.entityModel(reference.targetTypeName());
        if (targetModel == null || targetModel.id() == null) {
            // Unreachable in practice: associationPartialBlocked already rejected both. Re-checked
            // because the model is resolved a second time here, and the gate's verdict is not carried.
            return;
        }
        TypeName idType = targetModel.id().type().box();
        TypeName targetType = targetModel.typeName();
        String targetSimple = targetSimpleName(targetModel);
        // Read the FK into a local named after its physical column (customer_id), reusing the owner's
        // metadata constant for the association property (CUSTOMER -> "customer_id").
        String fkLocal = reference.fkColumn();
        mapRow.addStatement("$T $L = $L", idType, fkLocal,
                readExpression(idType, metadataType, reference.name()));
        mapRow.addStatement("entity.$L($L == null ? null : partial_$L($L))",
                MemberNames.setter(reference.name()), fkLocal, targetSimple, fkLocal);
        helpers.computeIfAbsent(targetSimple, k -> MethodSpec.methodBuilder("partial_" + targetSimple)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(targetType)
                .addParameter(idType, MemberNames.ID_PARAM)
                .addStatement("$T c = new $T()", targetType, targetType)
                .addStatement("c.$L(id)", MemberNames.setter(targetModel.id().name()))
                .addStatement("return c")
                .build());
    }

    /**
     * The reason a {@code @Association} target cannot back an id-only partial, or {@code null} when it
     * can. Checks (in order): the target element resolves, the target carries an id, the id is JDBC-safe
     * (so the FK reads through {@code getObject}), and the target exposes a no-arg constructor and an
     * id setter. Java 17 safety: driven by {@code elements}/{@code types}, never reflection in the
     * generated code.
     */
    private String associationPartialBlocked(Prop.Reference reference, TypeElement entityElement) {
        String fqn = reference.targetTypeName();
        if (fqn == null) {
            return "the target type could not be resolved";
        }
        TypeElement target = typeMappingBuilder.elementFor(fqn);
        if (target == null) {
            return "the target " + fqn + " could not be resolved";
        }
        EntityModel targetModel = typeMappingBuilder.entityModel(target);
        if (targetModel.id() == null) {
            return "the target " + fqn + " has no identifier";
        }
        if (!Literals.isJdbcSafe(targetModel.id().type().box())) {
            return "the target " + fqn + " id type " + targetModel.id().type() + " is not JDBC-safe";
        }
        if (!hasNoArgConstructor(target)) {
            return "the target " + fqn + " has no accessible no-argument constructor";
        }
        if (!typeMappingBuilder.hasMethod(target, MemberNames.setter(targetModel.id().name()))) {
            return "the target " + fqn + " has no " + MemberNames.setter(targetModel.id().name()) + " setter";
        }
        return null;
    }

    /** True when {@code element} can be instantiated with {@code new X()} (an explicit or default ctor). */
    private static boolean hasNoArgConstructor(TypeElement element) {
        boolean hasExplicitCtor = false;
        for (Element member : element.getEnclosedElements()) {
            if (member.getKind() == ElementKind.CONSTRUCTOR) {
                hasExplicitCtor = true;
                if (((ExecutableElement) member).getParameters().isEmpty()) {
                    return true;
                }
            }
        }
        return !hasExplicitCtor; // no explicit ctor → the compiler supplies a no-arg default
    }

    /** True when {@code element} has any member method named {@code name}. */
    /**
     * Resolves (generating on first use) the {@code <VO>RowMapper} for an element-collection child VO.
     * The VO's own descriptor metadata is generated (and its transitive embedded tree) so the mapper's
     * leaf reads reference {@code <VO>Metadata} constants. A VO that fails the row-mapper gate yields
     * {@code null}: its collection loader is skipped and a NOTE is recorded, so hydration degrades to
     * the parent's default field value rather than failing the whole repository.
     */
    public ClassName voRowMapperFor(ElementCollection collection, Element origin) {
        String voFqn = collection.descriptor().typeName();
        ClassName cached = voRowMappers.get(voFqn);
        if (cached != null) {
            return cached;
        }
        TypeElement voElement = typeMappingBuilder.elementFor(voFqn);
        if (voElement == null) {
            diagnostics.note(
                    "Cannot resolve element-collection value object '" + voFqn
                            + "'; its children will not be hydrated.", origin);
            return null;
        }
        EntityModel voModel = typeMappingBuilder.valueObjectModel(voElement);
        if (!canGenerateRowMapper(voModel)) {
            diagnostics.note(
                    "Value object '" + voFqn + "' is not reflection-free mappable; its element collection"
                            + " children will not be hydrated.", origin);
            return null;
        }
        ClassName voMetadata = metadataGenerator.metadataFor(voElement, voModel);
        ClassName mapper = generateValueObjectRowMapper(voElement, voModel, voMetadata);
        voRowMappers.put(voFqn, mapper);
        return mapper;
    }

    /**
     * Emits {@code <VO>RowMapper implements RowMapper<VO>} for a value object read from an
     * element-collection child table. It is {@link #generateRowMapper} without the identifier step: a
     * {@code @ValueObject} has no id and the child table's physical columns ARE the VO's (flattened)
     * leaf columns, so the standalone mapper reads UNprefixed labels from the VO's OWN metadata. FK-only
     * children ({@code ASSOCIATION}/{@code MANY_TO_ONE}) are skipped — never hydrated in this task
     * — and {@code EMBEDDED} children are rebuilt leaf-by-leaf via
     * {@link #appendEmbeddedRead}.
     */
    private ClassName generateValueObjectRowMapper(TypeElement voElement, EntityModel voModel,
                                                  ClassName voMetadataType) {
        String packageName = voModel.typeName().packageName();
        String simpleName = voElement.getSimpleName() + "RowMapper";
        TypeName voType = voModel.typeName();
        ClassName rowMapperInterface = ClassName.get("io.github.fludakit.jdbc", "RowMapper");
        String voName = voElement.getSimpleName().toString();

        MethodSpec.Builder mapRow = MethodSpec.methodBuilder("mapRow")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(voType)
                .addParameter(ClassName.get("java.sql", "ResultSet"), "rs")
                .addParameter(int.class, "rowNum")
                .addException(ClassName.get("java.sql", "SQLException"))
                .addStatement("$T entity = new $T()", voType, voType);

        for (Property p : voModel.properties()) {
            if (p.prop() instanceof Prop.Reference) {
                continue; // FK-only reference; left null (the relationship loaders fill it)
            }
            if (p.prop() instanceof Prop.Embedded) {
                appendEmbeddedRead(mapRow, voMetadataType, p, voName);
                continue;
            }
            appendPropertyRead(mapRow, p.type(), voMetadataType, p.name(), p.column(), voName);
        }
        mapRow.addStatement("return entity");

        TypeSpec type = TypeSpec.classBuilder(simpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(rowMapperInterface, voType))
                .addMethod(mapRow.build())
                .build();
        sourceWriter.write(packageName + "." + simpleName, voElement,
                JavaFile.builder(packageName, type).skipJavaLangImports(true).build());
        return ClassName.get(packageName, simpleName);
    }

    /**
     * Emits the assignment of one mapped column into the freshly constructed {@code entity}. Boxed
     * and reference columns are read via {@code getObject} and assigned directly, since a SQL NULL
     * is a legal value for them. A primitive column cannot hold NULL, so the value is read into a
     * method-unique temporary and {@code rs.wasNull()} is checked immediately after the getter to
     * fail fast with a {@code SQLException} — mirroring the reflective mapper, which throws when a
     * NULL is assigned to a primitive field. The {@code id} read follows the same rule.
     */
    private void appendPropertyRead(MethodSpec.Builder mapRow, TypeName type, ClassName metadataType,
                                    String property, String columnName, String entityName) {
        if (!type.isPrimitive()) {
            mapRow.addStatement("entity.$L($L)", MemberNames.setter(property), readExpression(type, metadataType, property));
            return;
        }
        // Temp name is derived from the (unique) property name so it cannot collide with the
        // generated entity/rs/rowNum locals nor with another primitive property in the same mapper.
        String temp = property + "Value";
        mapRow.addStatement("$T $L = rs.$L($T.$L)", type, temp, RowReads.primitiveGetter(property, type),
                metadataType, Literals.constName(property));
        mapRow.beginControlFlow("if (rs.wasNull())");
        mapRow.addStatement("throw new $T($S)", ClassName.get("java.sql", "SQLException"),
                "NULL value in non-nullable primitive column \"" + columnName
                        + "\" mapped to " + entityName + "." + property);
        mapRow.endControlFlow();
        mapRow.addStatement("entity.$L($L)", MemberNames.setter(property), temp);
    }

    /**
     * Emits the read and construction of one {@code EMBEDDED} property: every flattened
     * leaf of the value-object tree is read into a local named after its physical column
     * ({@code <prefix>_<leaf>}, e.g. {@code address_point_lat}); the top VO is then constructed inside
     * a guard firing iff at least one boxed leaf anywhere in its subtree is non-null, and each nested
     * child VO is constructed and set iff at least one boxed leaf in <em>its own</em> subtree is
     * non-null — an all-null child subtree leaves the child null (per-subtree presence). Primitive
     * leaves follow the {@link #appendPropertyRead} temp+{@code wasNull()} rule, never join any
     * presence test, and so a VO with a primitive leaf cannot be NULL-absent in the database. The
     * whole block stays reflection-free.
     */
    private void appendEmbeddedRead(MethodSpec.Builder mapRow, ClassName ownerMetadata,
                                    Property embedded, String entityName) {
        List<String> presence = new ArrayList<>();
        readEmbeddedLeaves(mapRow, ownerMetadata, embedded.embeddedModel(),
                Names.columnBase(embedded.name()), embedded.name(), presence, entityName);
        boolean guarded = !presence.isEmpty();
        if (guarded) {
            mapRow.beginControlFlow("if ($L)", String.join(" || ", presence));
        }
        buildEmbeddedValue(mapRow, embedded.embeddedModel(), Names.columnBase(embedded.name()), embedded.name());
        mapRow.addStatement("entity.$L($L)", MemberNames.setter(embedded.name()), embedded.name());
        if (guarded) {
            mapRow.endControlFlow();
        }
    }

    /** Reads each flattened leaf of {@code vo} into a column-named local, collecting the boxed-leaf presence terms. */
    private void readEmbeddedLeaves(MethodSpec.Builder mapRow, ClassName ownerMetadata, EntityModel valueObject,
                                    String prefix, String path, List<String> presence, String entityName) {
        for (Property p : valueObject.properties()) {
            if (p.prop() instanceof Prop.Reference) {
                continue; // fk-only children stay null on read, as at owner level
            }
            if (p.prop() instanceof Prop.Embedded) {
                readEmbeddedLeaves(mapRow, ownerMetadata, p.embeddedModel(),
                        prefix + "_" + p.column(), path + "." + p.name(), presence, entityName);
                continue;
            }
            String local = prefix + "_" + p.column();
            if (!p.type().isPrimitive()) {
                mapRow.addStatement("$T $L = $L", p.type(), local,
                        readExpression(p.type(), ownerMetadata, local));
                presence.add(local + " != null");
                continue;
            }
            mapRow.addStatement("$T $L = rs.$L($T.$L)", p.type(), local, RowReads.primitiveGetter(path, p.type()),
                    ownerMetadata, Literals.constName(local));
            mapRow.beginControlFlow("if (rs.wasNull())");
            mapRow.addStatement("throw new $T($S)", ClassName.get("java.sql", "SQLException"),
                    "NULL value in non-nullable primitive column \"" + local
                            + "\" mapped to " + entityName + "." + path);
            mapRow.endControlFlow();
        }
    }

    /**
     * Constructs one value-object instance from its already-read leaf locals (parent first): the VO's
     * own scalar leaves are assigned, then each nested child is constructed and set under its OWN
     * subtree presence guard (an all-null child subtree leaves the child null). A child
     * whose subtree holds no boxed leaf has no presence signal and is constructed unconditionally
     * under the parent's guard.
     */
    private void buildEmbeddedValue(MethodSpec.Builder mapRow, EntityModel valueObject, String prefix, String local) {
        mapRow.addStatement("$T $L = new $T()", valueObject.typeName(), local, valueObject.typeName());
        for (Property p : valueObject.properties()) {
            Prop pp = p.prop();
            if (pp instanceof Prop.Reference || pp instanceof Prop.Embedded) {
                continue;
            }
            mapRow.addStatement("$L.$L($L)", local, MemberNames.setter(p.name()), prefix + "_" + p.column());
        }
        for (Property p : valueObject.properties()) {
            if (!(p.prop() instanceof Prop.Embedded)) {
                continue;
            }
            String childLocal = prefix + "_" + p.column();
            List<String> terms = new ArrayList<>();
            RowReads.collectPresenceTerms(p.embeddedModel(), childLocal, terms);
            if (terms.isEmpty()) {
                buildEmbeddedValue(mapRow, p.embeddedModel(), childLocal, childLocal);
                mapRow.addStatement("$L.$L($L)", local, MemberNames.setter(p.name()), childLocal);
            } else {
                mapRow.beginControlFlow("if ($L)", String.join(" || ", terms));
                buildEmbeddedValue(mapRow, p.embeddedModel(), childLocal, childLocal);
                mapRow.addStatement("$L.$L($L)", local, MemberNames.setter(p.name()), childLocal);
                mapRow.endControlFlow();
            }
        }
    }

    /** Boxed/reference read expression; a primitive column is guarded separately in {@link #appendPropertyRead}. */
    private CodeBlock readExpression(TypeName type, ClassName metadataType, String property) {
        if (isEnumType(type)) {
            // Enums persist as their name; read the string and convert, null-safe for a nullable column.
            return CodeBlock.of("rs.getString($T.$L) == null ? null : $T.valueOf($T.class, rs.getString($T.$L))",
                    metadataType, Literals.constName(property), Enum.class, type, metadataType, Literals.constName(property));
        }
        return CodeBlock.of("rs.getObject($T.$L, $T.class)", metadataType, Literals.constName(property), type.box());
    }

    /** The simple name of a target entity model, for the {@code SQL_FIND_<TARGET>_BY_IDS} constant base. */
    private static String targetSimpleName(EntityModel targetModel) {
        String fqn = targetModel.typeName().toString();
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
