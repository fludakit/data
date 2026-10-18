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
        if (prop instanceof Prop.Reference ref) {
            // Non-back-reference references are eligible (read FK + id-only partial)
            return !ref.backReference();
        }
        // EntityCollection, ElementCollection are NOT eligible
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
                .addParameter(int.class, "rowNum")
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
        } else if (prop instanceof Prop.Reference ref) {
            if (!ref.backReference()) {
                addReferenceRead(method, entityVar, ref, rsVar);
            }
        }
        // Id is handled separately; EntityCollection/ElementCollection are ineligible
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

        // Enum types — null-safe: return null when column is NULL
        if (isEnum(typeFqn)) {
            ClassName enumType = ClassName.bestGuess(typeFqn);
            return CodeBlock.of("$L.getString($S) == null ? null : $T.valueOf($T.class, $L.getString($S))",
                    rsVar, column, Enum.class, enumType, rsVar, column);
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
     * the embedded field name. The VO is only set on the parent when at least one leaf
     * value is non-null (presence rule).
     */
    private void addEmbeddedRead(MethodSpec.Builder method, String entityVar,
                                 String fieldName, String columnPrefix,
                                 ValueObjectMapping<?> voMapping, String rsVar) {
        if (voMapping == null) return;

        String voTypeFqn = voMapping.typeName();
        ClassName voType = ClassName.bestGuess(voTypeFqn);
        String voVar = fieldName;
        String newPrefix = columnPrefix + io.github.fludakit.data.Names.columnBase(fieldName) + "_";

        java.util.List<String> nullCheckVars = new java.util.ArrayList<>();
        java.util.List<Runnable> setters = new java.util.ArrayList<>();

        for (Prop prop : voMapping.props()) {
            if (prop instanceof Prop.Scalar scalar) {
                String column = newPrefix + scalar.column();
                String tempVar = voVar + "_" + scalar.name();
                addScalarReadToVar(method, tempVar, column, scalar.typeFqn(), rsVar);
                nullCheckVars.add(tempVar);
                setters.add(() -> method.addStatement("$L.$L($L)", voVar, setterName(scalar.name()), tempVar));
            } else if (prop instanceof Prop.Embedded embedded) {
                String nestedVar = voVar + "_" + embedded.name();
                addEmbeddedReadReturning(method, nestedVar, embedded.name(),
                        newPrefix, embedded.target(), rsVar);
                nullCheckVars.add(nestedVar);
                setters.add(() -> method.addStatement("$L.$L($L)", voVar, setterName(embedded.name()), nestedVar));
            }
        }

        method.beginControlFlow("if ($L)", buildPresenceCheck(nullCheckVars));
        method.addStatement("$T $L = new $T()", voType, voVar, voType);
        for (Runnable setter : setters) {
            setter.run();
        }
        method.addStatement("$L.$L($L)", entityVar, setterName(fieldName), voVar);
        method.nextControlFlow("else");
        method.addStatement("$T $L = null", voType, voVar);
        method.endControlFlow();
    }

    /**
     * Like {@link #addEmbeddedRead} but guarantees the nested VO variable is always declared
     * (either to a new instance or null), so the parent can reference it in its presence check.
     */
    private void addEmbeddedReadReturning(MethodSpec.Builder method, String voVar,
                                           String fieldName, String columnPrefix,
                                           ValueObjectMapping<?> voMapping, String rsVar) {
        if (voMapping == null) {
            method.addStatement("$T $L = null", ClassName.get(Object.class), voVar);
            return;
        }

        String voTypeFqn = voMapping.typeName();
        ClassName voType = ClassName.bestGuess(voTypeFqn);
        String newPrefix = columnPrefix + io.github.fludakit.data.Names.columnBase(fieldName) + "_";

        java.util.List<String> nullCheckVars = new java.util.ArrayList<>();
        java.util.List<Runnable> setters = new java.util.ArrayList<>();

        for (Prop prop : voMapping.props()) {
            if (prop instanceof Prop.Scalar scalar) {
                String column = newPrefix + scalar.column();
                String tempVar = voVar + "_" + scalar.name();
                addScalarReadToVar(method, tempVar, column, scalar.typeFqn(), rsVar);
                nullCheckVars.add(tempVar);
                setters.add(() -> method.addStatement("$L.$L($L)", voVar, setterName(scalar.name()), tempVar));
            } else if (prop instanceof Prop.Embedded embedded) {
                String nestedVar = voVar + "_" + embedded.name();
                addEmbeddedReadReturning(method, nestedVar, embedded.name(),
                        newPrefix, embedded.target(), rsVar);
                nullCheckVars.add(nestedVar);
                setters.add(() -> method.addStatement("$L.$L($L)", voVar, setterName(embedded.name()), nestedVar));
            }
        }

        method.addStatement("$T $L", voType, voVar);
        method.beginControlFlow("if ($L)", buildPresenceCheck(nullCheckVars));
        method.addStatement("$L = new $T()", voVar, voType);
        for (Runnable setter : setters) {
            setter.run();
        }
        method.nextControlFlow("else");
        method.addStatement("$L = null", voVar);
        method.endControlFlow();
    }
    private void addScalarReadToVar(MethodSpec.Builder method, String varName,
                                     String column, String typeFqn, String rsVar) {
        CodeBlock readExpr = buildReadExpression(column, typeFqn, rsVar);
        TypeName javaType = boxType(typeFqn);
        method.addStatement("$T $L = $L", javaType, varName, readExpr);
    }

    /**
     * Builds a presence-check expression: {@code true} if the list is empty (no leaves to check),
     * otherwise {@code var1 != null || var2 != null || ...}.
     */
    private static String buildPresenceCheck(java.util.List<String> nullCheckVars) {
        if (nullCheckVars.isEmpty()) return "false";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nullCheckVars.size(); i++) {
            if (i > 0) sb.append(" || ");
            sb.append(nullCheckVars.get(i)).append(" != null");
        }
        return sb.toString();
    }

    // ── Reference reading ────────────────────────────────────────────────

    /**
     * Generates code to read a to-one reference from the ResultSet.
     * Reads the FK column and creates an id-only partial of the target type.
     * If the FK is null, the reference is set to null.
     */
    private void addReferenceRead(MethodSpec.Builder method, String entityVar,
                                  Prop.Reference ref, String rsVar) {
        String targetFqn = ref.targetTypeName();
        if (targetFqn == null) return;

        ClassName targetType = ClassName.bestGuess(targetFqn);
        String fkColumn = ref.fkColumn();
        String fkVar = ref.name() + "_fk";

        // Determine the ID type from the target's id
        String idTypeFqn = "java.lang.Long"; // default
        if (ref.target() != null && ref.target().id() != null) {
            idTypeFqn = ref.target().id().typeFqn();
        }
        String idColumn = ref.targetIdColumn() != null ? ref.targetIdColumn() : "id";

        // Read FK value
        CodeBlock fkRead = buildReadExpression(fkColumn, idTypeFqn, rsVar);
        method.addStatement("$T $L = $L", boxType(idTypeFqn), fkVar, fkRead);

        // If FK is not null, create id-only partial
        method.beginControlFlow("if ($L != null)", fkVar);
        method.addStatement("$T $L = new $T()", targetType, ref.name(), targetType);
        method.addStatement("$L.$L($L)", ref.name(), setterName("id"), fkVar);
        method.addStatement("$L.$L($L)", entityVar, setterName(ref.name()), ref.name());
        method.endControlFlow();
    }

    /**
     * Returns the boxed type for a primitive type, or the type itself if already boxed.
     */
    private static TypeName boxType(String typeFqn) {
        return switch (typeFqn) {
            case "boolean" -> ClassName.get(Boolean.class);
            case "byte" -> ClassName.get(Byte.class);
            case "short" -> ClassName.get(Short.class);
            case "int" -> ClassName.get(Integer.class);
            case "long" -> ClassName.get(Long.class);
            case "float" -> ClassName.get(Float.class);
            case "double" -> ClassName.get(Double.class);
            case "char" -> ClassName.get(Character.class);
            default -> ClassName.bestGuess(typeFqn);
        };
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
