package io.github.fludakit.data.processor.emit;

import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

import io.github.fludakit.data.processor.model.EntityModel;
import io.github.fludakit.data.processor.model.Property;
import java.util.List;

/**
 * How a generated mapper reads one row: the {@code ResultSet} getter for a primitive column, and the
 * null-presence terms that mark a value-object subtree absent.
 *
 * <p>Both are needed by two different readers of the same row — the standalone
 * {@code <Entity>RowMapper} and the joined-projection reader that inlines a parent and its relations
 * into one {@code mapRow} — so they live apart from each emitter. A getter spelled one way in one
 * reader and another way in the other is generated code that does not compile; a presence test that
 * disagrees between them is worse, because it compiles and silently builds a different object graph
 * for the same row.
 */
public final class RowReads {

    private RowReads() {
    }

    /**
     * The {@code ResultSet} getter for one primitive property type. The message names the property
     * because this throw surfaces as a bare javac stack trace: without it a reader sees only
     * "Unsupported primitive char" and has no idea which attribute of which entity is at fault.
     */
    public static String primitiveGetter(String property, TypeName type) {
        return switch (type.toString()) {
            case "boolean" -> "getBoolean";
            case "byte" -> "getByte";
            case "short" -> "getShort";
            case "int" -> "getInt";
            case "long" -> "getLong";
            case "float" -> "getFloat";
            case "double" -> "getDouble";
            default -> throw new IllegalStateException(
                    "No JDBC getter for primitive type " + type + " of property '" + property + "'");
        };
    }

    /**
     * Collects the boxed-leaf locals whose nullness marks absence within {@code vo}'s subtree rooted
     * at {@code prefix}: {@code <prefix>_<leaf> != null} per boxed leaf, recursing into nested value
     * objects. Primitives contribute nothing — a primitive leaf cannot be NULL in the database, so it
     * carries no presence signal.
     */
    public static void collectPresenceTerms(EntityModel valueObject, String prefix, List<String> terms) {
        for (Property p : valueObject.properties()) {
            if (p.prop() instanceof Prop.Reference) {
                continue;
            }
            if (p.prop() instanceof Prop.Embedded) {
                collectPresenceTerms(p.embeddedModel(), prefix + "_" + p.column(), terms);
                continue;
            }
            if (!p.type().isPrimitive()) {
                terms.add(prefix + "_" + p.column() + " != null");
            }
        }
    }
}
