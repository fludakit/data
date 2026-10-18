package io.github.fludakit.data.processor.generator.nav;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.metadata.ValueObjectMapping;
import io.github.fludakit.data.processor.RepositoryProcessor;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.analysis.PathResolver;
import io.github.fludakit.data.processor.analysis.TypeModelRegistry;
import io.github.fludakit.data.processor.generator.rowmapper.RowMapperGenerator;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.support.Props;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.Set;

/**
 * The hub the three navigable-class generators share: how a navigable class is begun and finished
 * (claim its name, build the {@code TypeSpec} with the superinterface and the {@code @Generated}
 * marker, append the {@code name()}/{@code path()} accessors), how a member of a navigable class is
 * declared (a leaf field for a column, a nav field for a nested class), how a relation target is
 * flattened into leaf fields, and how a name collision is reported.
 *
 * <p>Shared because the three shapes differ in WHICH members they emit and under which supertype —
 * an embedded attribute recurses into its own nested nav class, a relation attributes the target's
 * local paths, an element collection attributes the element's columns — but they must agree on how a
 * member is spelled. Two copies of {@code addLeafField} would let the same logical attribute be
 * published once as a {@code SortableAttribute} and once as a plain {@code Attribute}, which is
 * exactly the kind of drift a static metamodel cannot survive.
 */
public final class NavSupport {

    private final AttributeTypes attributeTypes;
    private final Diagnostics diagnostics;
    private final TypeModelRegistry typeMappingBuilder;
    private final RowMapperGenerator rowMapperGenerator;
    private final Set<String> managedEntities;

    public NavSupport(AttributeTypes attributeTypes, Diagnostics diagnostics,
               TypeModelRegistry typeMappingBuilder, RowMapperGenerator rowMapperGenerator,
               Set<String> managedEntities) {
        this.attributeTypes = attributeTypes;
        this.diagnostics = diagnostics;
        this.typeMappingBuilder = typeMappingBuilder;
        this.rowMapperGenerator = rowMapperGenerator;
        this.managedEntities = managedEntities;
    }

    // ------------------------------------------------------------ the nav-class skeleton

    /**
     * Claims the nav-class name and builds its {@link TypeSpec} — public final, the given attribute
     * superinterface parameterised by owner and target, and the {@code @Generated} marker. Returns
     * {@code null} when the name was already claimed (the collision is reported).
     */
    public TypeSpec.Builder beginNav(TypeElement origin, String packageName, TypeName owner, String navSimple,
                                     String path, ClassName attrInterface, String targetFqn, Set<String> claimed) {
        String fqn = packageName + "." + navSimple;
        if (!claimed.add(fqn)) {
            navCollision(origin, fqn, path);
            return null;
        }
        TypeName targetType = attributeTypes.classArgOf(targetFqn);
        return TypeSpec.classBuilder(navSimple)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(attrInterface, owner, targetType))
                .addAnnotation(AnnotationSpec.builder(AttributeTypes.MM_GENERATED)
                        .addMember("value", "$S", RepositoryProcessor.class.getName()).build());
    }

    /** Appends the {@code name()}/{@code path()} accessors and returns the finished class. */
    public NavUnit finishNav(String packageName, String navSimple, TypeSpec.Builder builder,
                             String path, String name) {
        builder.addMethod(overrideAccessor("name", String.class, CodeBlock.of("$S", name)));
        builder.addMethod(overrideAccessor("path", String.class, CodeBlock.of("$S", path)));
        return new NavUnit(packageName + "." + navSimple, ClassName.get(packageName, navSimple),
                navFile(packageName, builder));
    }

    // ------------------------------------------------------------ member declaration

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
            attrType = attributeTypes.scalarAttributeType(scalar.typeFqn());
            recordType = attributeTypes.scalarRecordType(scalar.typeFqn());
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

    /**
     * Adds the nav leaves for every flattened column of a relation target (or an element collection's
     * element): the identifier first, then each property, recursing into embedded value objects with a
     * growing column prefix. Shared by the relation and element-collection shapes, which attribute the
     * same target columns.
     */
    public void addRelationLeaf(TypeSpec.Builder builder, Set<String> taken, TypeElement origin,
                                 TypeName owner, String parentPath, String columnPrefix, Prop prop) {
        String path = parentPath + "." + prop.name();
        if (prop instanceof Prop.Embedded embedded) {
            String prefix = columnPrefix + Names.columnBase(embedded.name()) + "_";
            ValueObjectMapping<?> vo = embedded.target();
            if (vo != null && vo.id() != null) {
                addRelationLeaf(builder, taken, origin, owner, path, prefix, vo.id());
            }
            if (vo != null && !vo.props().isEmpty()) {
                for (Prop vp : vo.props()) {
                    addRelationLeaf(builder, taken, origin, owner, path, prefix, vp);
                }
            } else {
                // unresolved (or empty) cycle target: one plain leaf at the container column
                addLeafField(builder, taken, origin, owner, parentPath, embedded.name(), path,
                        columnPrefix + Names.columnBase(embedded.name()),
                        new Prop.Scalar(embedded.name(), Names.columnBase(embedded.name()), null));
            }
            return;
        }
        String column = Props.column(prop);
        if (column == null) {
            return;
        }
        addLeafField(builder, taken, origin, owner, parentPath, prop.name(), path,
                columnPrefix + column, prop);
    }

    /**
     * The resolved target model of a relation, or {@code null} when it cannot be attributed: the model
     * resolves, a reflection-free row mapper can be generated for it, and — for a relation (not an
     * element collection) — it is managed in this round.
     */
    public TypeMapping<?> relationTarget(String targetFqn, boolean elementCollection) {
        if (targetFqn == null) {
            return null;
        }
        String rawName = PathResolver.stripGenerics(targetFqn);
        TypeMapping<?> targetModel = typeMappingBuilder.descriptorFor(rawName);
        if (targetModel == null || !rowMapperGenerator.canGenerateRowMapper(targetModel)) {
            return null;
        }
        return elementCollection || managedEntities.contains(rawName) ? targetModel : null;
    }

    // ------------------------------------------------------------ diagnostics / literals

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
