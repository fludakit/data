package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import io.github.fludakit.data.metadata.TypeDefinition;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/**
 * Shared write-emission plumbing: param bindings, generated-key apply, and naming helpers.
 *
 * <p>No identity-generation strategy — callers branch on null id at emit time. Embedded flatten
 * walks thread a column {@code prefix} (SQL names) plus a Java owner expression / null-guard
 * (getters), matching {@code TypeDefinition}'s collector without a dedicated path type.</p>
 */
final class WriteSupport {

    static final ClassName GENERATED_KEY_HOLDER =
            ClassName.get("io.github.fludakit.jdbc.support", "GeneratedKeyHolder");

    private final Elements elements;

    WriteSupport(Elements elements) {
        this.elements = elements;
    }

    /** Appends .param() calls for ALL columns (id + non-id props). */
    void appendAllBindings(CodeBlock.Builder body, TypeDefinition entity, String varName) {
        TypeDefinition.Prop.Id idProp = entity.id();
        if (idProp != null) {
            body.add(".param($S, $L.getId())", idProp.name(), varName);
        }
        appendPropBindings(body, entity, varName);
    }

    /** Appends .param() calls for non-id columns only. */
    void appendNonIdBindings(CodeBlock.Builder body, TypeDefinition entity, String varName) {
        appendPropBindings(body, entity, varName);
    }

    /**
     * Appends .param() calls for all props of a child entity in an entity collection.
     * The inverse FK (back-reference to parent) is bound to the parent's ID.
     */
    void appendChildPropBindings(CodeBlock.Builder body, TypeDefinition.Entity childEntity,
                                 String childVar, String parentIdExpr,
                                 String inverseName, String fkColumn) {
        TypeDefinition.Prop.Id idProp = childEntity.id();
        if (idProp != null) {
            body.add(".param($S, $L.getId())", idProp.name(), childVar);
        }
        for (TypeDefinition.Prop prop : childEntity.properties()) {
            appendChildPropBinding(body, prop, childVar, "", childVar, null,
                    parentIdExpr, inverseName, fkColumn);
        }
    }

    /**
     * Appends .param() calls for non-id props of a child entity in an entity collection.
     * The inverse FK (back-reference to parent) is bound to the parent's ID.
     */
    void appendChildNonIdBindings(CodeBlock.Builder body, TypeDefinition.Entity childEntity,
                                  String childVar, String parentIdExpr,
                                  String inverseName, String fkColumn) {
        for (TypeDefinition.Prop prop : childEntity.properties()) {
            appendChildPropBinding(body, prop, childVar, "", childVar, null,
                    parentIdExpr, inverseName, fkColumn);
        }
    }

    private void appendChildPropBinding(CodeBlock.Builder body, TypeDefinition.Prop prop,
                                        String rootVar, String columnPrefix, String ownerExpr,
                                        String nullGuard, String parentIdExpr,
                                        String inverseName, String fkColumn) {
        if (prop instanceof TypeDefinition.Prop.Scalar s) {
            appendColumnBinding(body, s, columnPrefix, ownerExpr, nullGuard);
        } else if (prop instanceof TypeDefinition.Prop.Association assoc
                && (assoc.name().equals(inverseName)
                || (fkColumn != null && fkColumn.equals(assoc.fkColumn())))) {
            body.add(".param($S, $L)", qualify(columnPrefix, assoc.name()), parentIdExpr);
        } else if (prop instanceof TypeDefinition.Prop.Association assoc) {
            appendRelationBinding(body, assoc, columnPrefix, ownerExpr, nullGuard);
        } else if (prop instanceof TypeDefinition.Prop.Embedded embedded) {
            appendRelationBinding(body, embedded, columnPrefix, ownerExpr, nullGuard);
        }
    }

    private void appendPropBindings(CodeBlock.Builder body, TypeDefinition entity, String varName) {
        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.Scalar s) {
                appendColumnBinding(body, s, "", varName, null);
            } else if (prop instanceof TypeDefinition.Prop.Association assoc) {
                appendRelationBinding(body, assoc, "", varName, null);
            } else if (prop instanceof TypeDefinition.Prop.Embedded embedded) {
                appendRelationBinding(body, embedded, "", varName, null);
            }
        }
    }

    /**
     * @param ownerExpr Java expression for the current owner ({@code entity} or {@code entity.getAddress()})
     * @param nullGuard accumulated null-check for enclosing embeds, or {@code null} at root
     */
    private void appendColumnBinding(CodeBlock.Builder body, TypeDefinition.Prop.Scalar col,
                                     String columnPrefix, String ownerExpr, String nullGuard) {
        String paramName = qualify(columnPrefix, col.name());
        String leafGetter = ownerExpr + "." + getter(col.name()) + "()";
        if (nullGuard == null) {
            if (isEnum(col.javaType())) {
                body.add(".param($S, $L == null ? null : $L.name())",
                        paramName, leafGetter, leafGetter);
            } else {
                body.add(".param($S, $L)", paramName, leafGetter);
            }
        } else {
            String guarded = nullGuard + " ? null : " + leafGetter;
            if (isEnum(col.javaType())) {
                body.add(".param($S, ($L) == null ? null : ($L).name())",
                        paramName, guarded, guarded);
            } else {
                body.add(".param($S, $L)", paramName, guarded);
            }
        }
    }

    private void appendRelationBinding(CodeBlock.Builder body, TypeDefinition.Prop rel,
                                       String columnPrefix, String ownerExpr, String nullGuard) {
        if (rel instanceof TypeDefinition.Prop.Association assoc && assoc.fkColumn() != null) {
            String paramName = qualify(columnPrefix, assoc.name());
            String assocGetter = ownerExpr + "." + getter(assoc.name()) + "()";
            if (nullGuard == null) {
                body.add(".param($S, $L == null ? null : $L.getId())",
                        paramName, assocGetter, assocGetter);
            } else {
                body.add(".param($S, $L || $L == null ? null : $L.getId())",
                        paramName, nullGuard, assocGetter, assocGetter);
            }
        } else if (rel instanceof TypeDefinition.Prop.Embedded embedded && embedded.definition() != null) {
            String childPrefix = qualify(columnPrefix, embedded.name());
            String childOwner = ownerExpr + "." + getter(embedded.name()) + "()";
            String childGuard = nullGuard == null
                    ? childOwner + " == null"
                    : nullGuard + " || " + childOwner + " == null";
            for (TypeDefinition.Prop childProp : embedded.definition().properties()) {
                if (childProp instanceof TypeDefinition.Prop.Scalar s) {
                    appendColumnBinding(body, s, childPrefix, childOwner, childGuard);
                } else if (childProp instanceof TypeDefinition.Prop.Association childAssoc) {
                    appendRelationBinding(body, childAssoc, childPrefix, childOwner, childGuard);
                } else if (childProp instanceof TypeDefinition.Prop.Embedded childEmbedded) {
                    appendRelationBinding(body, childEmbedded, childPrefix, childOwner, childGuard);
                }
            }
        }
    }

    /**
     * Applies a {@code GeneratedKeyHolder} key onto {@code entityVar.setId(...)}.
     * Cast details follow {@code idTypeFqn}; this is not an identity-generation strategy.
     */
    void applyGeneratedKey(CodeBlock.Builder body, String entityVar, String keysVar, String idTypeFqn) {
        body.add("if ($L.getKey() != null) {\n", keysVar);
        switch (idTypeFqn) {
            case "java.lang.Integer", "int" ->
                    body.add("    $L.setId((($T) $L.getKey()).intValue());\n",
                            entityVar, Number.class, keysVar);
            case "java.lang.Short", "short" ->
                    body.add("    $L.setId((($T) $L.getKey()).shortValue());\n",
                            entityVar, Number.class, keysVar);
            case "java.lang.Long", "long" ->
                    body.add("    $L.setId((($T) $L.getKey()).longValue());\n",
                            entityVar, Number.class, keysVar);
            default ->
                    body.add("    $L.setId(($T) $L.getKey());\n",
                            entityVar, ClassName.bestGuess(idTypeFqn), keysVar);
        }
        body.add("}\n");
    }

    /**
     * Finds the name of the TO_ONE relation on the child that targets the parent type.
     */
    static String findInverseRelationName(TypeDefinition.Entity childDef, String parentTypeName) {
        for (TypeDefinition.Prop prop : childDef.properties()) {
            if (prop instanceof TypeDefinition.Prop.Association assoc
                    && assoc.definition() != null
                    && parentTypeName.equals(assoc.definition().typeName())) {
                return assoc.name();
            }
        }
        String parentSimpleName = parentTypeName.contains(".")
                ? parentTypeName.substring(parentTypeName.lastIndexOf('.') + 1)
                : parentTypeName;
        for (TypeDefinition.Prop prop : childDef.properties()) {
            if (prop instanceof TypeDefinition.Prop.Association assoc
                    && assoc.definition() != null
                    && assoc.definition().typeName() != null) {
                String target = assoc.definition().typeName();
                String targetSimpleName = target.contains(".")
                        ? target.substring(target.lastIndexOf('.') + 1)
                        : target;
                if (parentSimpleName.equals(targetSimpleName)) {
                    return assoc.name();
                }
            }
        }
        return null;
    }

    static String qualify(String prefix, String leaf) {
        return prefix == null || prefix.isEmpty() ? leaf : prefix + "_" + leaf;
    }

    String getter(String propertyName) {
        return "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
    }

    boolean isEnum(String typeFqn) {
        TypeElement typeElement = elements.getTypeElement(typeFqn);
        return typeElement != null && typeElement.getKind() == ElementKind.ENUM;
    }

    String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
