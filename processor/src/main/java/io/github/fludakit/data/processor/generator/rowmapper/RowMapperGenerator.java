package io.github.fludakit.data.processor.generator.rowmapper;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.analysis.TypeModelRegistry;
import io.github.fludakit.data.processor.generator.RepositorySourceWriter;
import io.github.fludakit.data.processor.generator.metadata.Literals;
import io.github.fludakit.data.processor.generator.metadata.TypeMappingGenerator;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.processor.support.Props;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
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
 * {@code String}) resolves through {@link TypeModelRegistry#elementFor(String)}, and the one question
 * that needs a member SCAN — whether the target has an id setter, which is what makes an id-only
 * partial constructible — is {@link TypeModelRegistry#hasMethod}, which owns {@code Elements} and
 * memoizes by name. Both belong to the class that already had to own the environment for building the
 * models; neither decides whether this emitter can be constructed. The no-arg-constructor test needs
 * no environment at all — it reads {@code getEnclosedElements} off an element it already holds. A
 * degradation is reported through {@link Diagnostics}.</p>
 */
public final class RowMapperGenerator {

    private final RowMapperEligibility eligibility;
    private final Diagnostics diagnostics;
    private final TypeModelRegistry typeMappingBuilder;
    private final TypeMappingGenerator metadataGenerator;
    private final RepositorySourceWriter sourceWriter;

    /**
     * The {@code <T>Metadata} name per mapped-type FQN, shared by reference with its owner: read here to
     * point a value-object mapper at the constants its leaf reads reference.
     */
    private final Map<String, ClassName> metadataTypes;

    /** Value-object FQN to generated {@code <VO>RowMapper} type, deduped across processing rounds. */
    private final Map<String, ClassName> voRowMappers = new LinkedHashMap<>();

    /** The flattened embedded-value-object reader, shared by the entity and VO mappers. */
    private final EmbeddedValueReader embeddedReader;

    public RowMapperGenerator(AttributeTypes attributeTypes, Diagnostics diagnostics,
                       TypeModelRegistry typeMappingBuilder,
                       TypeMappingGenerator metadataGenerator,
                       RepositorySourceWriter sourceWriter, Map<String, ClassName> metadataTypes) {
        this.eligibility = new RowMapperEligibility(attributeTypes, typeMappingBuilder);
        this.diagnostics = diagnostics;
        this.typeMappingBuilder = typeMappingBuilder;
        this.metadataGenerator = metadataGenerator;
        this.sourceWriter = sourceWriter;
        this.metadataTypes = metadataTypes;
        this.embeddedReader = new EmbeddedValueReader(typeMappingBuilder, this::readExpression);
    }

    /**
     * Whether {@code entity} can be materialised by a reflection-free row mapper. Delegates to
     * {@link RowMapperEligibility}, which owns the gate so this emitter stays about emission.
     */
    public boolean canGenerateRowMapper(TypeMapping<?> entity) {
        return eligibility.canGenerateRowMapper(entity);
    }

    /** Emits {@code <Entity>RowMapper implements io.github.fludakit.jdbc.RowMapper<Entity>}; reflection-free. */
    public ClassName generateRowMapper(TypeElement entityElement, TypeMapping<?> entity, ClassName metadataType) {
        String packageName = typeMappingBuilder.classNameFor(entity.typeName()).packageName();
        String simpleName = entityElement.getSimpleName() + "RowMapper";
        TypeName entityType = typeMappingBuilder.classNameFor(entity.typeName());
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
            appendPropertyRead(mapRow, eligibility.idType(entity), metadataType, entity.id().name(),
                    entity.id().column(), entityElement.getSimpleName().toString());
        }
        // a non-composing @Association (composition=false) materializes at read time as an
        // id-only PARTIAL instance through a generated partial_<Target> helper. A composition
        // Reference (root-owned many-to-one) stays loader-filled: it is deliberately NOT read here.
        // One helper per distinct target simple name (deduped). A target that cannot back a partial
        // (no no-arg ctor / setId / non-JDBC-safe id) degrades to a NOTE + null field (never abort).
        Map<String, MethodSpec> partialHelpers = new LinkedHashMap<>();
        for (Prop p : entity.rowProps()) {
            if (p instanceof Prop.Reference ref) {
                if (!ref.composition()) {
                    appendAssociationPartial(mapRow, ref, metadataType, entityElement, partialHelpers);
                }
                continue; // composition reference: FK-only on the mapper, hydrated by the loader
            }
            if (p instanceof Prop.Embedded embedded) {
                embeddedReader.appendEmbeddedRead(mapRow, metadataType, embedded, entityElement.getSimpleName().toString());
                continue;
            }
            appendPropertyRead(mapRow, typeMappingBuilder.memberType(entity.typeName(), p.name()), metadataType, p.name(), Props.column(p),
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
        String why = eligibility.associationPartialBlocked(reference, entityElement);
        if (why != null) {
            diagnostics.note(
                    "association '" + reference.name() + "' on " + entityElement.getQualifiedName() + ": " + why
                            + " — the field is left null on read (no id-only partial materialisation)",
                    entityElement);
            return;
        }
        TypeMapping<?> targetModel = typeMappingBuilder.descriptorFor(reference.targetTypeName());
        if (targetModel == null || targetModel.id() == null) {
            // Unreachable in practice: associationPartialBlocked already rejected both. Re-checked
            // because the model is resolved a second time here, and the gate's verdict is not carried.
            return;
        }
        TypeName idType = typeMappingBuilder.memberType(targetModel.typeName(), targetModel.id().name()).box();
        TypeName targetType = typeMappingBuilder.classNameFor(targetModel.typeName());
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
     * Resolves (generating on first use) the {@code <VO>RowMapper} for an element-collection child VO.
     * The VO's own descriptor metadata is generated (and its transitive embedded tree) so the mapper's
     * leaf reads reference {@code <VO>Metadata} constants. A VO that fails the row-mapper gate yields
     * {@code null}: its collection loader is skipped and a NOTE is recorded, so hydration degrades to
     * the parent's default field value rather than failing the whole repository.
     */
    public ClassName voRowMapperFor(Prop.ElementCollection collection, Element origin) {
        String voFqn = collection.target().typeName();
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
        TypeMapping<?> voModel = typeMappingBuilder.descriptorFor(voElement);
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
    private ClassName generateValueObjectRowMapper(TypeElement voElement, TypeMapping<?> voModel,
                                                  ClassName voMetadataType) {
        String packageName = typeMappingBuilder.classNameFor(voModel.typeName()).packageName();
        String simpleName = voElement.getSimpleName() + "RowMapper";
        TypeName voType = typeMappingBuilder.classNameFor(voModel.typeName());
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

        for (Prop p : voModel.rowProps()) {
            if (p instanceof Prop.Reference) {
                continue; // FK-only reference; left null (the relationship loaders fill it)
            }
            if (p instanceof Prop.Embedded embedded) {
                embeddedReader.appendEmbeddedRead(mapRow, voMetadataType, embedded, voName);
                continue;
            }
            appendPropertyRead(mapRow, typeMappingBuilder.memberType(voModel.typeName(), p.name()), voMetadataType, p.name(), Props.column(p), voName);
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
        RowReads.appendPrimitiveRead(mapRow, type, temp, property,
                CodeBlock.of("$T.$L", metadataType, Literals.constName(property)),
                columnName, entityName + "." + property);
        mapRow.addStatement("entity.$L($L)", MemberNames.setter(property), temp);
    }

    /** Boxed/reference read expression; a primitive column is guarded separately in {@link #appendPropertyRead}. */
    private CodeBlock readExpression(TypeName type, ClassName metadataType, String property) {
        return RowReads.boxedRead(type.box(), CodeBlock.of("$T.$L", metadataType, Literals.constName(property)),
                eligibility.isEnumType(type));
    }

    /** The simple name of a target entity model, for the {@code SQL_FIND_<TARGET>_BY_IDS} constant base. */
    private static String targetSimpleName(TypeMapping<?> targetModel) {
        String fqn = targetModel.typeName().toString();
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
