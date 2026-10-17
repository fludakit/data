package io.github.fludakit.data.processor.generator.rowmapper;

import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.analysis.TypeModelRegistry;
import io.github.fludakit.data.processor.support.MemberNames;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * The capability gate behind the reflection-free row mapper: whether a mapped type's every property is
 * JDBC-safe (or an enum, a skipped FK, or a recursively-safe embedded value object), and whether an
 * {@code @Association} target can back an id-only partial.
 *
 * <p>Split from {@link RowMapperGenerator} so the question "can we emit" is separate from the act of
 * emitting. The gate reads only the model and the compiler services — it writes nothing, claims no
 * name and emits no code — which is why the same answer is consulted from the nav generator, the
 * artifact coordinator and the repository generator as well as from the emitter itself.
 */
public final class RowMapperEligibility {

    private final AttributeTypes attributeTypes;
    private final TypeModelRegistry typeMappingBuilder;

    public RowMapperEligibility(AttributeTypes attributeTypes, TypeModelRegistry typeMappingBuilder) {
        this.attributeTypes = attributeTypes;
        this.typeMappingBuilder = typeMappingBuilder;
    }

    /**
     * True when {@code type} names an enum, which the reflection-free mapper reads via String +
     * valueOf. The lookup is {@code AttributeTypes}' single copy — the metamodel kind decision asks
     * the same question, and a row that disagreed with its own metamodel would not type-check.
     */
    public boolean isEnumType(TypeName type) {
        return attributeTypes.isEnum(type);
    }

    /**
     * Gate for the reflection-free row mapper: safe when every property is JDBC-safe, an
     * {@code ASSOCIATION}/{@code MANY_TO_ONE} fk (skipped on read), or an {@code EMBEDDED} value
     * object whose own model passes the same check. A VO's model has no id and its leaves are its
     * properties, so the recursion flattens naturally; a {@code null} embedded model (cycle guard)
     * fails closed.
     */
    public boolean canGenerateRowMapper(TypeMapping<?> entity) {
        if (entity.id() != null && !JdbcTypes.isJdbcSafe(idType(entity)) && !isEnumType(idType(entity))) {
            return false;
        }
        for (Prop p : entity.rowProps()) {
            if (p instanceof Prop.Reference) {
                continue; // FK-only, or hydrated by the relationship loaders
            }
            if (JdbcTypes.isJdbcSafe(typeMappingBuilder.memberType(entity.typeName(), p.name())) || isEnumType(typeMappingBuilder.memberType(entity.typeName(), p.name()))) {
                continue;
            }
            if (p instanceof Prop.Embedded && ((Prop.Embedded) p).target() != null
                    && canGenerateRowMapper(((Prop.Embedded) p).target())) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * The id's JavaPoet type — the one member whose type the mapping names but does not render.
     *
     * <p>Not {@code entity.id().typeFqn()} turned back into a name: a synthesized {@code long id}
     * resolves to {@code java.lang.Long} through {@link AttributeTypes#classArgOf} only by accident,
     * and a primitive must stay primitive for {@code JdbcTypes.isJdbcSafe} and for the
     * {@code wasNull()} guard to be emitted. The scan recorded it, so the scan is asked.</p>
     */
    public TypeName idType(TypeMapping<?> entity) {
        return typeMappingBuilder.memberType(entity.typeName(), entity.id().name());
    }

    /**
     * The reason a {@code @Association} target cannot back an id-only partial, or {@code null} when it
     * can. Checks (in order): the target element resolves, the target carries an id, the id is JDBC-safe
     * (so the FK reads through {@code getObject}), and the target exposes a no-arg constructor and an
     * id setter. Java 17 safety: driven by {@code elements}/{@code types}, never reflection in the
     * generated code.
     */
    public String associationPartialBlocked(Prop.Reference reference, TypeElement entityElement) {
        String fqn = reference.targetTypeName();
        if (fqn == null) {
            return "the target type could not be resolved";
        }
        TypeElement target = typeMappingBuilder.elementFor(fqn);
        if (target == null) {
            return "the target " + fqn + " could not be resolved";
        }
        TypeMapping<?> targetModel = typeMappingBuilder.descriptorFor(target);
        if (targetModel.id() == null) {
            return "the target " + fqn + " has no identifier";
        }
        if (!JdbcTypes.isJdbcSafe(typeMappingBuilder.memberType(targetModel.typeName(),
                targetModel.id().name()).box())) {
            return "the target " + fqn + " id type "
                + typeMappingBuilder.memberType(targetModel.typeName(), targetModel.id().name())
                + " is not JDBC-safe";
        }
        if (!hasNoArgConstructor(target)) {
            return "the target " + fqn + " has no accessible no-argument constructor";
        }
        if (!typeMappingBuilder.hasMethod(target, MemberNames.setter(targetModel.id().name()))) {
            return "the target " + fqn + " has no " + MemberNames.setter(targetModel.id().name()) + " setter";
        }
        return null;
    }

    /** True when {@code element} can be instantiated with {@code new X()} (an explicit or default ctor). */
    private static boolean hasNoArgConstructor(TypeElement element) {
        boolean hasExplicitCtor = false;
        for (Element member : element.getEnclosedElements()) {
            if (member.getKind() == ElementKind.CONSTRUCTOR) {
                hasExplicitCtor = true;
                if (((ExecutableElement) member).getParameters().isEmpty()) {
                    return true;
                }
            }
        }
        return !hasExplicitCtor; // no explicit ctor → the compiler supplies a no-arg default
    }
}
