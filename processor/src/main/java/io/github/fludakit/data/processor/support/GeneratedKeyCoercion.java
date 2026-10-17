package io.github.fludakit.data.processor.support;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.TypeName;

/**
 * How a database-generated key must be coerced to a target's declared id type.
 *
 * <p>The generated key's runtime type is whatever the JDBC driver returns for the identity column —
 * {@code Long} for a {@code BIGINT} auto-increment, {@code Integer} for {@code INT}, {@code UUID} for
 * a UUID column, {@code BigInteger} for a large numeric, and so on. Neither an analysis nor a
 * generation concern: it answers a fact about a declared id {@link TypeName} that the write-cascade
 * planner (to reject a non-coercible target) and the row writers (to read the returned key back onto
 * the entity) must agree on.
 *
 * <p>The numeric types read through {@code Number}'s accessor, which survives a driver that hands back
 * a compatible numeric (an {@code INT} key read as {@code long}). {@code BigInteger}/{@code BigDecimal}
 * are cast directly — folding them through {@code longValue()} would silently truncate — and a
 * {@code UUID} key is cast directly. A {@code String} id (or any custom id value object) is
 * application-assigned and yields {@code null}.
 */
public final class GeneratedKeyCoercion {

    private GeneratedKeyCoercion() {
    }

    /** Whether a {@code GeneratedKeyHolder} key can be read back as the declared {@code idType}. */
    public static boolean isCoercible(TypeName idType) {
        return coercion(idType, "keys") != null;
    }

    /**
     * The expression that coerces {@code <keyVar>.getKey()} to {@code idType}, or {@code null} when the
     * id type cannot be produced from a generated key.
     */
    public static CodeBlock coercion(TypeName idType, String keyVar) {
        return switch (idType.box().toString()) {
            case "java.lang.Long" -> CodeBlock.of("(($T) $L.getKey()).longValue()", Number.class, keyVar);
            case "java.lang.Integer" -> CodeBlock.of("(($T) $L.getKey()).intValue()", Number.class, keyVar);
            case "java.lang.Short" -> CodeBlock.of("(($T) $L.getKey()).shortValue()", Number.class, keyVar);
            case "java.math.BigInteger" -> CodeBlock.of("($T) $L.getKey()", java.math.BigInteger.class, keyVar);
            case "java.math.BigDecimal" -> CodeBlock.of("($T) $L.getKey()", java.math.BigDecimal.class, keyVar);
            case "java.util.UUID" -> CodeBlock.of("($T) $L.getKey()", java.util.UUID.class, keyVar);
            default -> null;
        };
    }
}
