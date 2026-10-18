package io.github.fludakit.data.processor.analysis;

import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeVariableName;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.query.ResultKind;
import java.util.Set;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The declared-result-shape classification shared by the find and native-query generators.
 *
 * <p>Both {@code @Find}/query-by-method-name and {@code @Query} map a method's return type to a
 * {@link ResultKind} and to the {@link TypeName} the generated call declares, so the rules live here
 * rather than duplicated in two generators. A {@code Set} is materialised into a {@code List} by the
 * client and widened back, which is why it gets its own kind; {@code Collection}/{@code Iterable} ride
 * on {@code LIST} because a list satisfies them.
 */
public final class MethodResults {

    /** The container types a query result can be declared as. */
    private static final Set<String> CONTAINER_TYPES = Set.of(
            "java.util.List", "java.util.Set", "java.util.Collection", "java.lang.Iterable",
            "java.util.Optional", "java.util.stream.Stream");

    private final Types types;
    private final Elements elements;

    public MethodResults(Types types, Elements elements) {
        this.types = types;
        this.elements = elements;
    }

    public ResultKind resultKindOf(TypeMirror returnType) {
        if (returnType.getKind() == TypeKind.VOID) {
            return ResultKind.VOID;
        }
        if (returnType.getKind() == TypeKind.ARRAY) {
            return ResultKind.ARRAY;
        }
        return switch (types.erasure(returnType).toString()) {
            case "java.util.Set" -> ResultKind.SET;
            case "java.util.List", "java.util.Collection", "java.lang.Iterable" -> ResultKind.LIST;
            case "java.util.Optional" -> ResultKind.OPTIONAL;
            case "java.util.stream.Stream" -> ResultKind.STREAM;
            case "jakarta.data.page.Page" -> ResultKind.PAGE;
            case "jakarta.data.page.CursoredPage" -> ResultKind.CURSORED_PAGE;
            default -> ResultKind.SINGLE;
        };
    }

    public TypeName queryResultType(TypeMirror returnType, TypeName fallback) {
        if (returnType.getKind() == TypeKind.ARRAY) {
            return TypeName.get(((ArrayType) returnType).getComponentType()).box();
        }
        if (returnType instanceof DeclaredType declared && declared.getTypeArguments().size() == 1
                && CONTAINER_TYPES.contains(types.erasure(declared).toString())) {
            return concrete(TypeName.get(declared.getTypeArguments().getFirst()), fallback);
        }
        return concrete(TypeName.get(returnType).box(), fallback);
    }

    /**
     * The result shape a WRITE declares. Three shapes, and the return TYPE is what separates the
     * second from the third — the declared read shape alone cannot, because {@code long}, {@code int}
     * and the entity itself all classify as {@link ResultKind#SINGLE}.
     *
     * <ul>
     *   <li>{@code void} reports nothing.</li>
     *   <li>The entity type hands the instance back.</li>
     *   <li>Anything else — {@code long}, {@code int}, {@code boolean} — is the affected-row count.</li>
     * </ul>
     */
    public ResultKind writeResultKindOf(TypeMirror returnType, ResultKind kind, TypeMapping<?> entity) {
        if (kind == ResultKind.VOID) {
            return ResultKind.VOID;
        }
        return TypeMirrors.isEntity(types, elements, returnType, entity)
                ? ResultKind.SINGLE
                : ResultKind.AFFECTED;
    }

    private TypeName concrete(TypeName candidate, TypeName fallback) {
        return candidate instanceof TypeVariableName ? fallback : candidate;
    }
}
