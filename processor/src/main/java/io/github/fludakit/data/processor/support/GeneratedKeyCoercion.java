package io.github.fludakit.data.processor.support;

import com.squareup.javapoet.TypeName;

/**
 * How a database-generated key must be coerced to a target's declared id type.
 *
 * <p>Neither an analysis nor a generation concern: it answers a fact about a declared id {@link TypeName}
 * that both the write cascade planner (to reject a non-coercible target) and the row writer (to bind the
 * returned key) need to agree on. {@code Long}/{@code Integer} are the only id types a
 * {@code GeneratedKeyHolder} key can be read back as; anything else is application-assigned.</p>
 */
public final class GeneratedKeyCoercion {

    private GeneratedKeyCoercion() {
    }

    /**
     * The {@code GeneratedKeyHolder} accessor name for {@code idType} ({@code longValue} /
     * {@code intValue}), or {@code null} when the id type cannot be produced from a generated key.
     */
    public static String forIdType(TypeName idType) {
        return switch (idType.box().toString()) {
            case "java.lang.Long" -> "longValue";
            case "java.lang.Integer" -> "intValue";
            default -> null;
        };
    }
}
