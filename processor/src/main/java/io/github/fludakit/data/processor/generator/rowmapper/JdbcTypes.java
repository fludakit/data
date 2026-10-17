package io.github.fludakit.data.processor.generator.rowmapper;

import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.TypeName;

import java.util.Set;

/**
 * The types the reflection-free {@code <Entity>RowMapper} can read straight off a {@code ResultSet}.
 *
 * <p>Lives here, beside the row mapper, because the allowlist exists for one decision — whether a
 * mapped type clears the reflection-free gate ({@link RowMapperGenerator#canGenerateRowMapper}) — and
 * nothing about it is a literal-building concern.
 */
public final class JdbcTypes {

    private JdbcTypes() {
    }

    /**
     * Whether a value of this type can be read straight off a {@code ResultSet}. {@code byte[]} is the
     * one array the JDBC layer hands back as itself; everything else must be a known scalar.
     */
    public static boolean isJdbcSafe(TypeName type) {
        if (type instanceof ArrayTypeName array) {
            return array.componentType.equals(TypeName.BYTE);
        }
        return JDBC_SAFE_TYPES.contains(type.toString());
    }

    /**
     * Field types the reflection-free {@code <Entity>RowMapper} can read straight from a
     * {@code ResultSet} (via {@code getObject(...)} or a primitive getter). Any entity carrying a
     * field outside this allowlist (enums, non-embeddable types, ...) is skipped so it keeps
     * using the existing reflective mapping instead of generating code that would not compile.
     * {@code @ValueObject}-typed fields are not in this set: they pass the gate through the
     * {@code EMBEDDED} recursion in {@link RowMapperGenerator#canGenerateRowMapper} and are read leaf-by-leaf from
     * their flattened columns.
     * Note that {@code java.time.Instant}, {@code java.time.ZonedDateTime} and
     * {@code java.math.BigInteger} are read via {@code rs.getObject(col, X.class)}, whose driver
     * support beyond the types guaranteed by JDBC 4.2 ({@code LocalDate}/{@code LocalTime}/
     * {@code LocalDateTime}/{@code OffsetDateTime} and the basic types) is driver-dependent; an
     * unsupported driver fails fast at runtime with {@code SQLFeatureNotSupportedException}.
     */
    private static final Set<String> JDBC_SAFE_TYPES = Set.of(
            "java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Short",
            "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
            "java.math.BigDecimal", "java.math.BigInteger",
            "java.sql.Date", "java.sql.Time", "java.sql.Timestamp",
            "java.time.LocalDate", "java.time.LocalTime", "java.time.LocalDateTime",
            "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime",
            "java.util.UUID",
            "boolean", "byte", "short", "int", "long", "float", "double");
}
