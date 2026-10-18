package io.github.fludakit.data.processor.generator.metadata;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.metadata.UnknownAttributePathException;
import io.github.fludakit.data.processor.analysis.TypeModelResolver;
import io.github.fludakit.data.processor.analysis.PathResolver;
import io.github.fludakit.data.processor.generator.nav.NavCase;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.support.MemberNames;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Emits the reflection-free {@code ValueAccess.value(String path, T root)} override of a generated
 * {@code <Entity>Metadata} descriptor: a {@code switch} over exactly the dot paths the descriptor
 * resolves — the flattened column view and the one-hop leaves of every relation.
 *
 * <p>Split from {@link TypeMappingGenerator} so the descriptor's structure and its navigation are two
 * single-responsibility classes. The path list is computed once by {@link TypeMappingGenerator} (the
 * {@link NavCase} enumeration both surfaces consume) and handed in, so the two can never disagree
 * about which paths exist. Bodies are JavaPoet getter chains, method references and stream lambdas
 * only (never reflection), each hop null-guarded and each to-many container guarded to an EMPTY list.
 */
public final class ValueAccessGenerator {

    private final Diagnostics diagnostics;
    private final TypeModelResolver typeMappingBuilder;

    public ValueAccessGenerator(Diagnostics diagnostics, TypeModelResolver typeMappingBuilder) {
        this.diagnostics = diagnostics;
        this.typeMappingBuilder = typeMappingBuilder;
    }

    /**
     * Emits the {@code value(...)} override, or {@code null} when it could not be built — the
     * descriptor then keeps the interface default (which fails fast) instead of failing the round.
     */
    public MethodSpec valueMethod(TypeElement entityElement, TypeMapping<?> entity, List<NavCase> navs) {
        try {
            return valueSwitch(entityElement, entity, navs);
        } catch (RuntimeException e) {
            diagnostics.note(
                    "runtime path navigation could not be generated for " + entityElement.getSimpleName()
                            + " (" + e + "); the navigation switch is omitted", entityElement);
            return null;
        }
    }

    private MethodSpec valueSwitch(TypeElement entityElement, TypeMapping<?> entity, List<NavCase> navs) {
        TypeMapping<?> mapping = entity;
        Map<String, CodeBlock> cases = new LinkedHashMap<>();

        // (1) The flattened column view this descriptor publishes: identifier, scalars, to-one heads
        // (whose path is the property name, mapping to the fk column) and embedded leaves.
        for (Map.Entry<String, ColumnRef> entry : mapping.columnRefs().entrySet()) {
            putValueCase(cases, entityElement, entry.getKey(),
                    valueChain(mapping, CodeBlock.of("e"), entry.getKey(), entityElement));
        }
        // (2) Depth 1 through every relationship the descriptor publishes — the SHARED navCases()
        // enumeration (columnRefs() consumes the identical list).
        for (NavCase nav : navs) {
            String leafPath = nav.path().substring(nav.path().indexOf(".") + 1);
            CodeBlock body = nav.toOne()
                    ? guardedValueChain(nav.targetElement(), nav.target(), nav.head(), leafPath)
                    : toManyValue(nav.targetElement(), nav.target(), nav.head(), leafPath);
            putValueCase(cases, entityElement, nav.path(), body);
        }

        TypeName entityType = typeMappingBuilder.classNameFor(entity.typeName());
        MethodSpec.Builder navigateMethod = MethodSpec.methodBuilder("value")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(Object.class)
                .addParameter(String.class, "path")
                .addParameter(entityType, "root")
                .addStatement("$T e = ($T) root", entityType, entityType);
        navigateMethod.beginControlFlow("switch (path)");
        for (Map.Entry<String, CodeBlock> entry : cases.entrySet()) {
            navigateMethod.addStatement("case $S: return $L", entry.getKey(), entry.getValue());
        }
        navigateMethod.addStatement("default: throw new $T(path, $S)", UnknownAttributePathException.class,
                entityElement.getQualifiedName().toString());
        navigateMethod.endControlFlow();
        return navigateMethod.build();
    }

    /** Records one generated case, NOTE-degrading the path when its expression could not be built. */
    private void putValueCase(Map<String, CodeBlock> cases, TypeElement origin, String path, CodeBlock value) {
        if (value == null) {
            diagnostics.note(
                    "runtime path navigation is not available for path '" + path
                            + "': its accessor could not be resolved; the path is left out of the"
                            + " value switch", origin);
            return;
        }
        cases.putIfAbsent(path, value);
    }

    /**
     * The null-safe getter chain for one dot path walked from {@code recv} through {@code mapping}
     * (declared on {@code element}), or {@code null} when a hop is unknown, is not an embedded value
     * object, or has no accessor. Only {@link Prop.Embedded} may carry a further segment.
     */
    private CodeBlock valueChain(TypeMapping<?> mapping, CodeBlock recv, String path, TypeElement element) {
        int dot = path.indexOf('.');
        String head = dot < 0 ? path : path.substring(0, dot);
        Prop p = PathResolver.propOrNull(mapping, head);
        if (p == null || element == null || !typeMappingBuilder.hasMethod(element, MemberNames.getter(head))) {
            return null;
        }
        CodeBlock chain = CodeBlock.of("$L.$L()", recv, MemberNames.getter(head));
        if (dot < 0) {
            return chain;
        }
        if (!(p instanceof Prop.Embedded embedded) || embedded.target() == null) {
            return null;
        }
        TypeElement voElement = typeMappingBuilder.elementFor(PathResolver.stripGenerics(embedded.target().typeName()));
        CodeBlock body = valueChain(embedded.target(), chain, path.substring(dot + 1), voElement);
        return body == null ? null : CodeBlock.of("$L == null ? null : $L", chain, body);
    }

    /**
     * A to-one leaf: the target's own chain, guarded on the reference itself —
     * {@code e.getCustomer() == null ? null : e.getCustomer().getName()}.
     */
    private CodeBlock guardedValueChain(TypeElement targetElement, TypeMapping<?> target,
                                        CodeBlock head, String leafPath) {
        CodeBlock body = valueChain(target, head, leafPath, targetElement);
        return body == null ? null : CodeBlock.of("$L == null ? null : $L", head, body);
    }

    /**
     * A to-many leaf: the child values of the whole collection, mapped by a method reference (single
     * segment) or a null-safe chain lambda (embedded leaf) and collected to a list. A null container
     * reads as an empty list, never null, so the cursor reducer always gets a list.
     */
    private CodeBlock toManyValue(TypeElement childElement, TypeMapping<?> childModel,
                                  CodeBlock collection, String leafPath) {
        CodeBlock mapper;
        if (leafPath.indexOf('.') < 0) {
            if (!typeMappingBuilder.hasMethod(childElement, MemberNames.getter(leafPath))) {
                return null;
            }
            mapper = CodeBlock.of("$T::$L", ClassName.get(childElement), MemberNames.getter(leafPath));
        } else {
            CodeBlock body = valueChain(childModel, CodeBlock.of("child"), leafPath, childElement);
            mapper = body == null ? null : CodeBlock.of("child -> $L", body);
        }
        if (mapper == null) {
            return null;
        }
        return CodeBlock.of("$L == null ? $T.of() : $L.stream().map($L).collect($T.toList())",
                collection, ClassName.get(java.util.List.class), collection, mapper,
                ClassName.get("java.util.stream", "Collectors"));
    }
}
