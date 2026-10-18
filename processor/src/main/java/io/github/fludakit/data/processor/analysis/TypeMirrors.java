package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.metadata.TypeMapping;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The type questions the analysis and emit layers ask of a raw {@link TypeMirror}, answered with the
 * raw compiler services ({@link Types}/{@link Elements}). Replaces the retired {@code TypeShapes}
 * wrapper: {@code TypeModelBuilder}, {@code RepositoryTypeResolver} and {@code AttributeTypes} all take
 * the raw pair, so these questions stay consistent with the rest of the package instead of bundling
 * them behind a grab-bag holder.
 */
public final class TypeMirrors {

    private static final String LIST = "java.util.List";

    private TypeMirrors() {
    }

    /**
     * The type a sequence parameter carries one of: an array's component, or a {@code List}'s single
     * type argument. {@code null} for anything else, which is how a caller says "not a sequence" and
     * lets the single-value path carry on.
     *
     * <p>A {@code List} with no type argument is not a sequence: there is no element type to read, so
     * a raw {@code List} falls through to {@code null} rather than being treated as a list of
     * {@code Object}.
     */
    public static TypeMirror sequenceElementType(TypeMirror mirror) {
        if (mirror.getKind() == TypeKind.ARRAY) {
            return ((ArrayType) mirror).getComponentType();
        }
        if (mirror instanceof DeclaredType declared
                && LIST.equals(declared.asElement().toString())
                && declared.getTypeArguments().size() == 1) {
            return declared.getTypeArguments().getFirst();
        }
        return null;
    }

    /**
     * Whether {@code mirror} is the entity {@code entity} describes, or a subtype of it.
     *
     * <p>This is what separates "hand the saved instance back" from "report the affected-row count",
     * and what makes {@code save(Book)} a write while {@code save(long)} is not. It asks about the
     * erasure because a repository method declares the entity, never a parameterisation of it.
     */
    public static boolean isEntity(Types types, Elements elements, TypeMirror mirror, TypeMapping<?> entity) {
        TypeElement element = elements.getTypeElement(entity.typeName());
        return element != null
                && types.isAssignable(types.erasure(mirror), types.erasure(element.asType()));
    }
}
