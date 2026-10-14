package io.github.fludakit.data.processor.emit;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.analysis.MappedTypeBuilder;
import io.github.fludakit.data.processor.support.Diagnostics;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.Set;

/**
 * What the three navigable-class generators share: how a member of a navigable class is declared
 * (a leaf field for a column, a nav field for a nested class), how a name is claimed and a collision
 * reported, and how the finished class becomes a {@link JavaFile}.
 *
 * <p>Shared because the three shapes differ in WHICH members they emit and under which supertype —
 * an embedded attribute recurses into its own nested nav class, a relation attributes the target's
 * local paths, an element collection attributes the element's columns — but they must agree on how a
 * member is spelled. Two copies of {@code addLeafField} would let the same logical attribute be
 * published once as a {@code SortableAttribute} and once as a plain {@code Attribute}, which is
 * exactly the kind of drift a static metamodel cannot survive.</p>
 */
public final class NavSupport {

    private final AttributeTypes attributeTypes;
    private final Diagnostics diagnostics;
    private final MappedTypeBuilder typeMappingBuilder;

    /**
     * No {@code Elements} and no {@code Types}: the one compile-time lookup this class performs is
     * {@link AttributeTypes#kindOf}, and it takes the wired {@link AttributeTypes} instead.
     */
    public NavSupport(AttributeTypes attributeTypes, Diagnostics diagnostics,
               MappedTypeBuilder typeMappingBuilder) {
        this.attributeTypes = attributeTypes;
        this.diagnostics = diagnostics;
        this.typeMappingBuilder = typeMappingBuilder;
    }

    public void addLeafField(TypeSpec.Builder builder, Set<String> taken, TypeElement origin,
                              TypeName owner, String navSimple, String identifier,
                              String path, String column, Prop prop) {
        ClassName attrType;
        ClassName recordType;
        // Java 17 safety: instanceof chain, never a pattern switch.
        if (prop instanceof Prop.Id) {
            attrType = AttributeTypes.FM_ID;
            recordType = AttributeTypes.FM_ID_RECORD;
        } else if (prop instanceof Prop.Scalar scalar) {
            switch (attributeTypes.kindOf(scalar.typeFqn())) {
                case TEXT -> {
                    attrType = AttributeTypes.FM_TEXT;
                    recordType = AttributeTypes.FM_TEXT_RECORD;
                }
                case SORTABLE -> {
                    attrType = AttributeTypes.FM_SORTABLE;
                    recordType = AttributeTypes.FM_SORTABLE_RECORD;
                }
                default -> {
                    attrType = AttributeTypes.FM_COLUMN;
                    recordType = AttributeTypes.FM_COLUMN_RECORD;
                }
            }
        } else {
            attrType = AttributeTypes.FM_COLUMN; // relation/embedded foreign keys surface as plain column leaves
            recordType = AttributeTypes.FM_COLUMN_RECORD;
        }
        addNavField(builder, taken, origin, owner, navSimple, identifier,
                ParameterizedTypeName.get(attrType, owner),
                CodeBlock.of("new $T<>($S, $S)", recordType, prop.name(), path));
    }

    public void addNavField(TypeSpec.Builder builder, Set<String> taken, TypeElement origin,
                             TypeName owner, String navSimple, String identifier,
                             TypeName declaredType, CodeBlock initializer) {
        if (!taken.add(navSimple + "." + identifier)) {
            diagnostics.note(
                    "static metamodel collision in '" + navSimple + "': leaf '" + identifier
                            + "' already emitted; this member is skipped", origin);
            return;
        }
        builder.addField(FieldSpec.builder(declaredType, identifier, Modifier.PUBLIC, Modifier.FINAL)
                .initializer(initializer).build());
    }

    public void navCollision(TypeElement origin, String fqn, String path) {
        diagnostics.note(
                "static metamodel collision on navigable member '" + path + "': type '" + fqn
                        + "' already claimed; emitting a plain attribute record instead", origin);
    }

    public static JavaFile navFile(String packageName, TypeSpec.Builder builder) {
        return JavaFile.builder(packageName, builder.build()).skipJavaLangImports(true).build();
    }

    public static MethodSpec overrideAccessor(String name, TypeName returnType, CodeBlock body) {
        return MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class).returns(returnType)
                .addStatement("return $L", body).build();
    }

    public static MethodSpec overrideAccessor(String name, Class<?> returnType, CodeBlock body) {
        return overrideAccessor(name, ClassName.get(returnType), body);
    }

    public static String lastPathSegment(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }
}
