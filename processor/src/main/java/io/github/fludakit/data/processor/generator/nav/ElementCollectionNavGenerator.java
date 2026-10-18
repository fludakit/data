package io.github.fludakit.data.processor.generator.nav;

import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
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

    private final NavSupport support;

    public ElementCollectionNavGenerator(NavSupport support) {
        this.support = support;
    }

    /** As {@link RelationNavGenerator#relationNav}: this nav's unit, or empty when name-claimed. */
    public List<NavUnit> elementCollectionNav(TypeElement origin, String packageName, TypeName owner,
                                       String navSimple, String path,
                                       Prop.ElementCollection elementCollection,
                                       Set<String> claimed) {
        String targetFqn = elementCollection.elementTypeName();
        TypeSpec.Builder builder = support.beginNav(origin, packageName, owner, navSimple, path,
                AttributeTypes.FM_EC, targetFqn, claimed);
        if (builder == null) {
            return List.of();
        }
        Set<String> taken = new HashSet<>();
        if (support.relationTarget(targetFqn, true) != null) {
            for (Prop ep : elementCollection.target().props()) {
                support.addRelationLeaf(builder, taken, origin, owner, path, "", ep);
            }
        }
        return List.of(support.finishNav(packageName, navSimple, builder, path, NavSupport.lastPathSegment(path)));
    }
}
