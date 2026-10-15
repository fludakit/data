package io.github.fludakit.data.processor.analysis;

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.processor.model.EntityModel;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * The questions the dispatch and emit layers ask about a type they were handed as a
 * {@link TypeMirror}: what it is called once its type arguments are dropped, whether it is a
 * {@code List}, what a {@code List}'s element is, whether it is the entity, and what a method's
 * signature looks like in the context that declares it.
 *
 * <p>Each of them had more than one home, and the copies were not merely similar — they were the
 * same three lines restated. {@code sequenceElementType} existed verbatim in
 * {@code RepositoryMethodValidator} and in {@code WriteMethodEmitter}; the
 * {@code isAssignable(erasure(a), erasure(entity.typeMirror()))} test that answers "is this the
 * entity" appeared in that validator, in the write emitter and again inline in the method compiler;
 * and {@code asMemberOf} was called by two classes that each held {@link Types} to do it. One rule
 * with three homes is three chances for a {@code saveAll} to accept a parameter the {@code save}
 * next to it rejects.</p>
 *
 * <p>Holding {@link Types} here is what lets the callers stop holding it. Four of them —
 * {@code WriteMethodEmitter}, {@code RepositoryMethodCompiler}, {@code RepositoryMethodValidator} and
 * {@code QueryPlanCompiler} — held the service for these answers and for nothing else, so after this
 * move no generator in the package holds {@code Types} at all. What remains is the deliberate
 * minority: the two wiring holders, {@link MappedTypeBuilder} (which stores an erased mirror on
 * {@link EntityModel}), {@link AttributeTypes} (which needs an erased mirror to build a
 * {@code TypeName}) and {@link RepositoryTypeResolver} (which walks supertypes).</p>
 *
 * <p>Only {@link #isEntity} and {@link #resolved} genuinely need {@link Types}. {@link #isList} and
 * {@link #sequenceElementType} are answered from {@code DeclaredType.asElement()} — the element of a
 * declared type IS its erasure, and it knows its own qualified name — and go through the service
 * anyway so that one class owns the vocabulary and a reader does not have to work out which of two
 * spellings is the one in use.</p>
 */
public final class TypeShapes {

    /** The name the JDBC column of a paging {@code List} parameter is read from the spec under. */
    private static final String LIST = "java.util.List";

    private final Types types;

    public TypeShapes(Types types) {
        this.types = types;
    }

    /**
     * The name of {@code mirror} with its type arguments dropped — {@code java.util.List} for a
     * {@code List<Book>}. This is the key every container decision is made on: the declared return
     * type of a query method, the container a result is materialised into, and the {@code Slice} a
     * paging method must not declare are all recognised by this name and not by their arguments.
     */
    public String erasedName(TypeMirror mirror) {
        return types.erasure(mirror).toString();
    }

    /** Whether {@code mirror} is a {@code List}, ignoring what it is a list of. */
    public boolean isList(TypeMirror mirror) {
        return LIST.equals(erasedName(mirror));
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
    public TypeMirror sequenceElementType(TypeMirror mirror) {
        if (mirror.getKind() == TypeKind.ARRAY) {
            return ((ArrayType) mirror).getComponentType();
        }
        if (mirror instanceof DeclaredType declared
                && isList(mirror)
                && declared.getTypeArguments().size() == 1) {
            return declared.getTypeArguments().get(0);
        }
        return null;
    }

    /** The JavaPoet type of {@code mirror}'s erasure: what generated code declares a variable as. */
    public TypeName erasedTypeName(TypeMirror mirror) {
        return TypeName.get(types.erasure(mirror));
    }

    /**
     * Whether {@code mirror} is the entity {@code entity} describes, or a subtype of it.
     *
     * <p>This is what separates "hand the saved instance back" from "report the affected-row count",
     * and what makes {@code save(Book)} a write while {@code save(long)} is not. It asks about the
     * erasure because a repository method declares the entity, never a parameterisation of it.
     */
    public boolean isEntity(TypeMirror mirror, EntityModel entity) {
        return types.isAssignable(types.erasure(mirror), types.erasure(entity.typeMirror()));
    }

    /**
     * The signature of {@code method} as seen through {@code owner}: the type arguments a generic
     * supertype was declared with, substituted in. A {@code save(T)} inherited from
     * {@code CrudRepository<Book, Long>} is a {@code save(Book)} only after this — asking the
     * declaration directly yields {@code T}.
     */
    public ExecutableType resolved(DeclaredType owner, ExecutableElement method) {
        return (ExecutableType) types.asMemberOf(owner, method);
    }

    /**
     * The {@code MethodSpec} for an implementation of {@code method}, in the same resolved shape.
     *
     * <p>JavaPoet's {@code MethodSpec.overriding} performs exactly the resolution above — it substitutes
     * the owner's type arguments into the inherited declaration — but it wants the {@link Types}
     * instance rather than an {@code ExecutableType}. Left at the call sites, that argument is a
     * service passing through a generator that had no other use for it: two classes held {@code Types}
     * to forward it to a third-party method. Taking it here keeps the one operation in one place, and
     * keeps the environment out of the two emitters that only needed the result.
     */
    public MethodSpec.Builder overriding(ExecutableElement method, DeclaredType owner) {
        return MethodSpec.overriding(method, owner, types);
    }
}
