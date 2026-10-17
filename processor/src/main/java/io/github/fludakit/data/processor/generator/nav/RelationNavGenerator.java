package io.github.fludakit.data.processor.generator.nav;

import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.processor.generator.metadata.MmMember;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.support.Props;
import javax.lang.model.element.TypeElement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The navigable class for a MANY_TO_ONE ({@code EntityAttribute}) or ONE_TO_MANY
 * ({@code ListAttribute}) member.
 */
public final class RelationNavGenerator {

    private final NavSupport support;

    public RelationNavGenerator(NavSupport support) {
        this.support = support;
    }

    /**
     * The nav for a {@code REFERENCE} or {@code ENTITY_COLLECTION} member: this nav's unit, or an empty
     * list when its name was already claimed (the collision is reported, not thrown).
     */
    public List<NavUnit> relationNav(TypeElement origin, String packageName, TypeName owner,
                              String navSimple, MmMember member, Set<String> claimed) {
        Prop p = member.prop();
        boolean fk = p instanceof Prop.Reference;
        String targetFqn = Props.targetFqn(p);
        NavDraft draft = support.beginNav(origin, packageName, owner, navSimple, member.path(),
                fk ? AttributeTypes.FM_FK : AttributeTypes.FM_OTM, targetFqn, claimed);
        if (draft == null) {
            return List.of();
        }
        Set<String> taken = new HashSet<>();
        // The gate resolved this same model by this same name, so this is a cache hit.
        TypeMapping<?> target = support.relationTarget(targetFqn, false);
        if (target != null) {
            if (target.id() != null) {
                support.addRelationLeaf(draft.builder(), taken, origin, owner, member.path(), "", target.id());
            }
            for (Prop tp : target.props()) {
                support.addRelationLeaf(draft.builder(), taken, origin, owner, member.path(), "", tp);
            }
        }
        return List.of(support.finishNav(draft, member.path(), member.prop().name()));
    }
}
