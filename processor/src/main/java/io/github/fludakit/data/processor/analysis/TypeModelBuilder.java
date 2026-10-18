package io.github.fludakit.data.processor.analysis;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.annotation.Association;
import io.github.fludakit.data.annotation.Identity;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.metadata.ValueObjectMapping;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.support.MemberNames;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scans the fields of a mapped type and produces its compile-time {@link TypeMapping}: which shape
 * each field is (identifier, scalar, reference, embedded, entity collection, element collection),
 * which column it takes, and which target it points at.
 *
 * <p>This is the only pass that makes a mapping decision. The memoization of its results — one
 * descriptor per type, one element collection per placement, one member type per member — lives in
 * {@link TypeModelRegistry}, which this class owns and drives through {@code buildDescriptor}.
 */
public final class TypeModelBuilder {

    private final Types types;
    private final Elements elements;
    private final Diagnostics diagnostics;
    private final TypeModelRegistry registry;

    public TypeModelBuilder(Types types, Elements elements, Diagnostics diagnostics) {
        this.types = types;
        this.elements = elements;
        this.diagnostics = diagnostics;
        this.registry = new TypeModelRegistry(types, elements, this::buildDescriptor);
    }

    /** The mapping of one declared type, built and memoized on first use. */
    public TypeMapping<?> buildEntity(TypeElement element) {
        return registry.descriptorFor(element);
    }

    // ------------------------------------------------------------ memoized lookups

    /** The resolved models this builder scans into: the memoized cache the generators consume. */
    public TypeModelRegistry registry() {
        return registry;
    }

    // ------------------------------------------------------------------------- the field scan

    /**
     * Scans the fields ONCE and produces the shipping {@link TypeMapping} — the one printed into the
     * generated {@code <Entity>Metadata}. The value-object/root/table context is derived here from the
     * annotations, so the registry's memoization can drive this as a plain {@code TypeElement} function.
     */
    private TypeMapping<?> buildDescriptor(TypeElement element) {
        boolean valueObject = registry.isValueObjectType(element);
        boolean ownerOwns = registry.ownsAggregateLifecycle(element);
        String table = valueObject ? null : Names.tableName(element.getSimpleName().toString());
        String typeNameString = element.getQualifiedName().toString();
        Prop.Id idProp = null;
        List<Prop> props = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element member : elements.getAllMembers(element)) {
            if (member.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) member;
            if (field.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            String name = field.getSimpleName().toString();
            if (!seen.add(name)) {
                continue;
            }
            String fieldType = field.asType().toString();
            // Read for every field, not only the ones recorded below: it REPORTS a type JavaPoet
            // cannot represent, and that report must not depend on which shape the field turned out
            // to be.
            TypeName fieldTypeName = resolveFieldTypeName(field);
            boolean identityAnnotated = field.getAnnotation(Identity.class) != null;

            if (valueObject && identityAnnotated) {
                // Value objects have no identity; keep the field scalar and report the ignored marker.
                diagnostics.note(
                        "value object '" + element.getSimpleName() + "' declares '" + name
                                + "'; ignored — value objects have no identity", element);
            }
            if (!valueObject && (identityAnnotated || name.equals(MemberNames.ID_PROPERTY))) {
                idProp = new Prop.Id(name, Names.columnBase(name), fieldType);
                registry.recordMemberType(typeNameString, name, fieldTypeName);

            } else if (field.getAnnotation(Association.class) != null) {
                TypeElement target = declaredElement(field);
                props.add(referenceProp(name, Names.fkColumn(name),
                        target != null ? target.getQualifiedName().toString() : null, false, false, target));
                registry.recordMemberType(typeNameString, name, fieldTypeName);

            } else if (embeddedTarget(field) != null) {
                TypeElement vo = embeddedTarget(field);
                props.add(new Prop.Embedded(name, embeddedOf(registry.descriptorFor(vo))));
                registry.recordMemberType(typeNameString, name, fieldTypeName);

            } else if (isEntityType(declaredElement(field))) {
                TypeElement target = declaredElement(field);
                // Bare entity references compose only when owned by an aggregate root; on a non-root
                // entity they are the inverse FK back to the root — a write-only back-reference.
                boolean composition = ownerOwns;
                props.add(referenceProp(name, Names.fkColumn(name),
                        target.getQualifiedName().toString(), composition, !composition, target));
                registry.recordMemberType(typeNameString, name, fieldTypeName);

            } else if (valueObjectElementType(field) != null) {
                TypeElement vo = valueObjectElementType(field);
                if (table == null) {
                    // A value object has no table of its own, so a collection nested inside one has
                    // no parent table to derive a child table and parent FK from.
                    diagnostics.error(
                            "field '" + name + "' of value object '" + element.getSimpleName() + "' is a nested"
                                    + " collection of '" + vo.getSimpleName() + "': a value object in an"
                                    + " element collection has no table of its own, so a collection inside one has no"
                                    + " child table to live in", field);
                    continue;
                }
                props.add(registry.buildCollection(table, name, vo));

            } else if (entityTypeElement(field) != null) {
                TypeElement child = entityTypeElement(field);
                props.add(new Prop.EntityCollection(name, registry.descriptorFor(child), null, null));

            } else {
                props.add(new Prop.Scalar(name, Names.columnBase(name), fieldType));
                registry.recordMemberType(typeNameString, name, fieldTypeName);
            }
        }
        if (!valueObject && idProp == null) {
            diagnostics.error(
                    "entity '" + element.getSimpleName() + "' declares no identifier; annotate one field with"
                            + " @Identity (or name it 'id')", element);
            idProp = new Prop.Id(MemberNames.ID_PROPERTY, Names.columnBase(MemberNames.ID_PROPERTY), "java.lang.Long");
            registry.recordMemberType(typeNameString, MemberNames.ID_PROPERTY, ClassName.get(Long.class));
        }

        // Resolve one-to-many inverse references after collecting all parent properties.
        List<Prop> resolved = new ArrayList<>(props.size());
        for (Prop prop : props) {
            if (prop instanceof Prop.EntityCollection entityCollection && entityCollection.targetTypeName() != null) {
                TypeElement childElement = elements.getTypeElement(entityCollection.targetTypeName());
                TypeMapping<?> child = childElement != null
                        ? registry.descriptorFor(childElement) : null;
                String inverse = child != null ? findInverseName(typeNameString, child) : null;
                resolved.add(new Prop.EntityCollection(entityCollection.name(), entityCollection.target(),
                        inverse, inverse == null ? null : Names.fkColumn(inverse)));
            } else {
                resolved.add(prop);
            }
        }
        return TypeModelRegistry.entityMapping(valueObject, ownerOwns, typeNameString, table, idProp, resolved);
    }

    private Prop.Reference referenceProp(String name, String fkColumn, String targetTypeName,
                                         boolean composition, boolean backReference, TypeElement target) {
        TypeMapping<?> targetModel = target == null ? null : registry.descriptorFor(target);
        return new Prop.Reference(name, fkColumn, targetTypeName, targetModel, composition, backReference);
    }

    private TypeName resolveFieldTypeName(VariableElement field) {
        try {
            return TypeName.get(field.asType());
        } catch (IllegalArgumentException unmappable) {
            diagnostics.error(
                    "field '" + field.getSimpleName() + "' has type '" + field.asType()
                            + "' that cannot be mapped to a generated row-mapper type", field);
            return TypeName.OBJECT;
        }
    }

    // ------------------------------------------------------------------ field classification

    private boolean isEntityType(TypeElement element) {
        return element != null
                && (element.getAnnotation(io.github.fludakit.data.annotation.Entity.class) != null
                || element.getAnnotation(io.github.fludakit.data.annotation.AggregateRoot.class) != null);
    }

    private TypeElement embeddedTarget(VariableElement field) {
        if (field.getAnnotation(Association.class) != null
                || !(field.asType() instanceof DeclaredType declared)
                || !(declared.asElement() instanceof TypeElement element)) {
            return null;
        }
        return registry.isValueObjectType(element) ? element : null;
    }

    private TypeElement listElementType(VariableElement field,
                                        java.util.function.Predicate<TypeElement> elementMatcher) {
        if (field.getAnnotation(Association.class) != null
                || !(field.asType() instanceof DeclaredType declared)
                || declared.getTypeArguments().size() != 1
                || !"java.util.List".equals(types.erasure(declared).toString())) {
            return null;
        }
        TypeMirror argument = declared.getTypeArguments().getFirst();
        if (!(argument instanceof DeclaredType element)
                || !(element.asElement() instanceof TypeElement typeElement)) {
            return null;
        }
        return elementMatcher.test(typeElement) ? typeElement : null;
    }

    private TypeElement entityTypeElement(VariableElement field) {
        return listElementType(field, this::isEntityType);
    }

    private TypeElement valueObjectElementType(VariableElement field) {
        return listElementType(field, registry::isValueObjectType);
    }

    private static TypeElement declaredElement(VariableElement field) {
        if (field.asType() instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement element) {
            return element;
        }
        return null;
    }

    private static ValueObjectMapping<?> embeddedOf(TypeMapping<?> model) {
        return model instanceof ValueObjectMapping<?> vo ? vo : null;
    }

    private static String findInverseName(String parentTypeName, TypeMapping<?> child) {
        for (Prop prop : child.props()) {
            if (prop instanceof Prop.Reference reference
                    && parentTypeName.equals(reference.targetTypeName())) {
                return reference.name();
            }
        }
        return null;
    }
}
