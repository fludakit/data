package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.metadata.EmbeddedValueObjectMapping;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.metadata.ValueObjectMapping;

import javax.annotation.processing.Filer;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.util.Set;

/**
 * Generates {@code XXXRowMapper} classes that implement {@code RowMapper<XXX>}.
 *
 * <p>Each generated class has a single {@code mapRow(ResultSet rs)} method that reads columns
 * by name from the {@link java.sql.ResultSet} and constructs the entity via setters.</p>
 *
 * <p>Eligibility: an entity is eligible only if all its props are JDBC-safe (scalars, enums,
 * or embedded value objects). Entities with {@link Prop.Reference}, {@link Prop.EntityCollection},
 * or {@link Prop.ElementCollection} are NOT eligible and {@link #emit(TypeMapping)} returns
 * {@code null}.</p>
 */
public final class RowMapperEmitter {

    private static final String ROW_MAPPER_FQN = "io.github.fludakit.jdbc.RowMapper";
    private static final ClassName RESULT_SET = ClassName.get("java.sql", "ResultSet");

    /** Boxed types that use {@code rs.getObject(column, Type.class)}. */
    private static final Set<String> BOXED_TYPES = Set.of(
            "java.lang.Boolean", "java.lang.Byte", "java.lang.Short",
            "java.lang.Integer", "java.lang.Long", "java.lang.Float",
            "java.lang.Double"
    );

    /** Types that use {@code rs.getObject(column, Type.class)} (non-primitive, non-String). */
    private static final Set<String> OBJECT_TYPES = Set.of(
            "java.math.BigDecimal", "java.math.BigInteger",
            "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime",
            "java.time.OffsetDateTime", "java.time.OffsetTime", "java.time.Instant",
            "java.time.ZonedDateTime",
            "java.util.UUID",
            "java.lang.Number"
    );

    private final Types types;
    private final Elements elements;
    private final Filer filer;

    public RowMapperEmitter(Types types, Elements elements, Filer filer) {
        this.types = types;
        this.elements = elements;
        this.filer = filer;
    }

    /**
     * Generates a row mapper for the given entity or value-object type mapping.
     *
     * @param mapping the type mapping to generate a row mapper for
     * @return the {@link ClassName} of the generated row mapper, or {@code null} if not eligible
     */
    public ClassName emit(TypeMapping<?> mapping) {
        if (!isEligible(mapping)) return null;

        String typeFqn = mapping.typeName();
        String packageName = packageNameOf(typeFqn);
        String simpleName = simpleNameOf(typeFqn);
        String mapperName = simpleName + "RowMapper";

        ClassName entityType = ClassName.bestGuess(typeFqn);
        ClassName rowMapperInterface = ClassName.bestGuess(ROW_MAPPER_FQN);
        ParameterizedTypeName parameterizedRowMapper =
                ParameterizedTypeName.get(rowMapperInterface, entityType);

        // Build mapRow method
        MethodSpec mapRow = buildMapRowMethod(entityType, simpleName, mapping);

        TypeSpec typeSpec = TypeSpec.classBuilder(mapperName)
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(parameterizedRowMapper)
                .addMethod(mapRow)
                .build();

        try {
            JavaFile.builder(packageName, typeSpec)
                    .build()
                    .writeTo(filer);
        } catch (IOException e) {
            // Silently skip — the filer will report the error
            return null;
        }

        return ClassName.get(packageName, mapperName);
    }

    // ── Eligibility ──────────────────────────────────────────────────────

    /**
     * An entity is eligible for row mapper generation if all its props are JDBC-safe:
     * scalars, enums, or embedded value objects. References, entity collections, and
     * element collections make the entity ineligible.
     */
    private boolean isEligible(TypeMapping<?> mapping) {
        for (Prop prop : mapping.props()) {
            if (!isPropEligible(prop)) return false;
        }
        return true;
    }

    private boolean isPropEligible(Prop prop) {
        if (prop instanceof Prop.Id) return true;
        if (prop instanceof Prop.Scalar) return true;
        if (prop instanceof Prop.Embedded embedded) {
            return isEmbeddedEligible(embedded.target());
        }
        // Reference, EntityCollection, ElementCollection are NOT eligible
        return false;
    }

    private boolean isEmbeddedEligible(ValueObjectMapping<?> voMapping) {
        if (voMapping == null) return false;
        for (Prop prop : voMapping.props()) {
            if (!isPropEligible(prop)) return false;
        }
        return true;
    }

    // ── mapRow method construction ───────────────────────────────────────

    private MethodSpec buildMapRowMethod(ClassName entityType, String entitySimpleName,
                                         TypeMapping<?> mapping) {
        String entityVar = Character.toLowerCase(entitySimpleName.charAt(0))
                + entitySimpleName.substring(1);

        MethodSpec.Builder method = MethodSpec.methodBuilder("mapRow")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(entityType)
                .addParameter(RESULT_SET, "rs")
                .addException(ClassName.get("java.sql", "SQLException"));

        // Create entity instance
        method.addStatement("$T $L = new $T()", entityType, entityVar, entityType);

        // Read id
        if (mapping.id() != null) {
            Prop.Id id = mapping.id();
            addScalarRead(method, entityVar, id.name(), id.column(), id.typeFqn(), "rs");
        }

        // Read props
        for (Prop prop : mapping.props()) {
            addPropRead(method, entityVar, prop, "", "rs");
        }

        // Return entity
        method.addStatement("return $L", entityVar);

        return method.build();
    }

    // ── Prop reading ─────────────────────────────────────────────────────

    private void addPropRead(MethodSpec.Builder method, String entityVar,
                             Prop prop, String columnPrefix, String rsVar) {
        if (prop instanceof Prop.Scalar scalar) {
            String column = columnPrefix + scalar.column();
            addScalarRead(method, entityVar, scalar.name(), column, scalar.typeFqn(), rsVar);
        } else if (prop instanceof Prop.Embedded embedded) {
            addEmbeddedRead(method, entityVar, embedded.name(),
                    columnPrefix, embedded.target(), rsVar);
        }
        // Id is handled separately; Reference/EntityCollection/ElementCollection are ineligible
    }

    // ── Scalar reading ───────────────────────────────────────────────────

    private void addScalarRead(MethodSpec.Builder method, String entityVar,
                               String fieldName, String column, String typeFqn, String rsVar) {
        CodeBlock readExpr = buildReadExpression(column, typeFqn, rsVar);
        method.addStatement("$L.$L($L)", entityVar, setterName(fieldName), readExpr);
    }

    /**
     * Builds the code expression for reading a single column value from the ResultSet.
     */
    private CodeBlock buildReadExpression(String column, String typeFqn, String rsVar) {
        // Primitive types
        switch (typeFqn) {
            case "boolean":
                return CodeBlock.of("$L.getBoolean($S)", rsVar, column);
            case "byte":
                return CodeBlock.of("$L.getByte($S)", rsVar, column);
            case "short":
                return CodeBlock.of("$L.getShort($S)", rsVar, column);
            case "int":
                return CodeBlock.of("$L.getInt($S)", rsVar, column);
            case "long":
                return CodeBlock.of("$L.getLong($S)", rsVar, column);
            case "float":
                return CodeBlock.of("$L.getFloat($S)", rsVar, column);
            case "double":
                return CodeBlock.of("$L.getDouble($S)", rsVar, column);
            case "char":
                // char is not directly supported by ResultSet; read as String and take first char
                return CodeBlock.of("$L.getString($S).charAt(0)", rsVar, column);
        }

        // String
        if ("java.lang.String".equals(typeFqn)) {
            return CodeBlock.of("$L.getString($S)", rsVar, column);
        }

        // byte[]
        if ("byte[]".equals(typeFqn)) {
            return CodeBlock.of("$L.getBytes($S)", rsVar, column);
        }

        // Enum types
        if (isEnum(typeFqn)) {
            ClassName enumType = ClassName.bestGuess(typeFqn);
            return CodeBlock.of("$T.valueOf($T.class, $L.getString($S))",
                    Enum.class, enumType, rsVar, column);
        }

        // Boxed types and well-known object types → getObject(column, Type.class)
        if (BOXED_TYPES.contains(typeFqn) || OBJECT_TYPES.contains(typeFqn)) {
            ClassName typeClass = ClassName.bestGuess(typeFqn);
            return CodeBlock.of("$L.getObject($S, $T.class)", rsVar, column, typeClass);
        }

        // Default: getObject with the type class (covers any other JDBC-compatible type)
        ClassName typeClass = ClassName.bestGuess(typeFqn);
        return CodeBlock.of("$L.getObject($S, $T.class)", rsVar, column, typeClass);
    }

    // ── Embedded VO reading ──────────────────────────────────────────────

    /**
     * Generates code to read an embedded value object's fields from the ResultSet.
     * The VO's columns are flattened into the same ResultSet with a prefix derived from
     * the embedded field name.
     */
    private void addEmbeddedRead(MethodSpec.Builder method, String entityVar,
                                 String fieldName, String columnPrefix,
                                 ValueObjectMapping<?> voMapping, String rsVar) {
        if (voMapping == null) return;

        String voTypeFqn = voMapping.typeName();
        ClassName voType = ClassName.bestGuess(voTypeFqn);
        String voVar = fieldName;
        String newPrefix = columnPrefix + io.github.fludakit.data.Names.columnBase(fieldName) + "_";

        // Create VO instance
        method.addStatement("$T $L = new $T()", voType, voVar, voType);

        // Read VO props
        for (Prop prop : voMapping.props()) {
            if (prop instanceof Prop.Scalar scalar) {
                String column = newPrefix + scalar.column();
                addScalarRead(method, voVar, scalar.name(), column, scalar.typeFqn(), rsVar);
            } else if (prop instanceof Prop.Embedded embedded) {
                addEmbeddedRead(method, voVar, embedded.name(),
                        newPrefix, embedded.target(), rsVar);
            }
        }

        // Set VO on entity
        method.addStatement("$L.$L($L)", entityVar, setterName(fieldName), voVar);
    }

    // ── Type utilities ───────────────────────────────────────────────────

    /**
     * Checks whether the given type FQN refers to an enum type.
     */
    private boolean isEnum(String typeFqn) {
        TypeElement typeElement = elements.getTypeElement(typeFqn);
        return typeElement != null && typeElement.getKind() == ElementKind.ENUM;
    }

    /**
     * Derives the setter method name from a field name: {@code firstName → setFirstName}.
     */
    private static String setterName(String fieldName) {
        return "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    /**
     * Extracts the package name from a fully-qualified type name.
     */
    private static String packageNameOf(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot > 0 ? fqn.substring(0, lastDot) : "";
    }

    /**
     * Extracts the simple name from a fully-qualified type name.
     */
    private static String simpleNameOf(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot > 0 ? fqn.substring(lastDot + 1) : fqn;
    }
}
