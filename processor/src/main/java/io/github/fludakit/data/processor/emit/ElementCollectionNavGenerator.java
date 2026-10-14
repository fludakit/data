package io.github.fludakit.data.processor.emit;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.RepositoryProcessor;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.model.NavUnit;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The navigable class for an ELEMENT-COLLECTION member ({@code ListAttribute<owner, element>}): one
 * owner-typed leaf per element column, mirroring {@link Prop.ElementCollection#elementColumns()}.
 *
 * <p>The child-table placement lives in the descriptor's {@code columnRefs()} literal, not on the
 * attribute — which is why this generator only emits the leaves and no column metadata of its own.</p>
 */
public final class ElementCollectionNavGenerator {

    private final AttributeTypes attributeTypes;
    private final RelationNavGenerator relations;
    private final NavSupport support;

    public ElementCollectionNavGenerator(AttributeTypes attributeTypes,
                                  RelationNavGenerator relations, NavSupport support) {
        this.attributeTypes = attributeTypes;
        this.relations = relations;
        this.support = support;
    }

    /** As {@link RelationNavGenerator#relationNav}: this nav's unit, or empty when name-claimed. */
    public List<NavUnit> elementCollectionNav(TypeElement origin, String packageName, TypeName owner,
                                       String navSimple, String path,
                                       Prop.ElementCollection elementCollection,
                                       Set<String> claimed) {
        String fqn = packageName + "." + navSimple;
        if (!claimed.add(fqn)) {
            support.navCollision(origin, fqn, path);
            return List.of();
        }
        String targetFqn = elementCollection.elementTypeName();
        TypeName targetType = attributeTypes.classArgOf(targetFqn);
        TypeSpec.Builder builder = TypeSpec.classBuilder(navSimple)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(AttributeTypes.FM_EC, owner, targetType))
                .addAnnotation(AnnotationSpec.builder(AttributeTypes.MM_GENERATED)
                        .addMember("value", "$S", RepositoryProcessor.class.getName()).build());
        Set<String> taken = new HashSet<>();
        // The gate resolved the target by this same name already, so the second resolve that used to
        // sit here was dead: it could only re-answer a question the gate had answered true.
        if (relations.relationTargetResolved(targetFqn, true)) {
            for (Prop ep : elementCollection.target().props()) {
                relations.addRelationLeaf(builder, taken, origin, owner, path, "", ep);
            }
        }
        builder.addMethod(NavSupport.overrideAccessor("name", String.class, CodeBlock.of("$S", NavSupport.lastPathSegment(path))));
        builder.addMethod(NavSupport.overrideAccessor("path", String.class, CodeBlock.of("$S", path)));
        return List.of(new NavUnit(fqn, ClassName.get(packageName, navSimple),
                NavSupport.navFile(packageName, builder)));
    }
}
