package io.github.fludakit.data.processor.generator.nav;

import com.squareup.javapoet.ClassName;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.analysis.TypeModelBuilder;
import io.github.fludakit.data.processor.generator.RepositorySourceWriter;
import io.github.fludakit.data.processor.generator.rowmapper.RowMapperGenerator;
import io.github.fludakit.data.processor.generator.nav.MmMember;
import io.github.fludakit.data.processor.generator.nav.NavContext;
import io.github.fludakit.data.processor.generator.nav.NavLevel;
import io.github.fludakit.data.processor.generator.nav.NavUnit;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.support.MemberNames;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Emits one navigable member class per embedded / relation / element-collection member of an entity:
 * the {@code _<Entity><Field>} top-level types that make {@code _Order.item.name} type-check instead
 * of being a stringly-typed path.
 *
 * <p>A nav class implements the FluDa attribute interface matching its member kind
 * ({@code EmbeddableAttribute} / {@code EntityAttribute} / {@code ListAttribute}) and carries one
 * owner-typed leaf field per reachable target member; a nested embedded recurses into its own nav
 * class. Names are claimed against the session-global generated-type registry: a nav whose FQN is
 * already taken degrades to a NOTE plus the plain attribute record rather than failing the user's
 * build, which is why {@link #generateNav} answers {@code null} instead of throwing.</p>
 *
 * <p>This class only DISPATCHES: one generator per member kind — {@link EmbeddedNavGenerator},
 * {@link RelationNavGenerator}, {@link ElementCollectionNavGenerator} — sharing the leaf/field
 * plumbing in {@link NavSupport}. The three differ in which members they emit and under which
 * supertype, not in how a member is spelled.</p>
 *
 * <p>Nav classes are collected as the owning {@code _Entity} interface is built and written
 * afterwards through {@link #writePending} — distinct top-level types in the same round, and the
 * interface is what the user imports. Writes go out through {@link RepositorySourceWriter}, so no
 * nav generator sees {@code Filer} — or {@code Elements}: a target is resolved by the FQN its
 * descriptor carries, through the mapping builder that owns {@code Elements} and memoizes the answer.
 * A collision is reported through {@link Diagnostics}. {@link AttributeTypes} is built once per round
 * and threaded through the three generators, so none of them carries the environment to it.</p>
 */
public final class NavigationGenerator {

    private final RepositorySourceWriter sourceWriter;
    private final EmbeddedNavGenerator embedded;
    private final RelationNavGenerator relations;
    private final ElementCollectionNavGenerator elementCollections;

    /**
     * The nav classes collected for the entity being emitted, written by {@link #writePending} once
     * its {@code _Entity} interface has been. Cleared on every flush, so it never leaks state from
     * one entity's metamodel into the next.
     */
    private final List<NavUnit> pending = new ArrayList<>();

    public NavigationGenerator(AttributeTypes attributeTypes, Diagnostics diagnostics,
                        TypeModelBuilder typeMappingBuilder,
                        RowMapperGenerator rowMapperGenerator, RepositorySourceWriter sourceWriter,
                        Set<String> managedEntities) {
        this.sourceWriter = sourceWriter;
        // One generator per member kind, sharing the leaf/field plumbing: see NavSupport.
        NavSupport support = new NavSupport(attributeTypes, diagnostics, typeMappingBuilder);
        this.embedded = new EmbeddedNavGenerator(attributeTypes, support);
        this.relations = new RelationNavGenerator(attributeTypes, typeMappingBuilder,
                rowMapperGenerator, support, managedEntities);
        this.elementCollections = new ElementCollectionNavGenerator(attributeTypes, relations, support);
    }

    /**
     * Writes every nav class collected since the last flush and forgets them. Called by
     * {@link MetamodelGenerator} right after it has written the {@code _Entity} interface the navs
     * belong to.
     */

    public void writePending(TypeElement origin) {
        for (NavUnit unit : pending) {
            sourceWriter.write(unit.fqn(), origin, unit.file());
        }
        pending.clear();
    }

    public ClassName generateNav(TypeElement origin, String packageName, ClassName owner,
                          MmMember member, Set<String> claimed) {
        String navSimple = "_" + owner.simpleName() + MemberNames.capitalize(member.prop().name());
        Prop p = member.prop();
        // Each generator is now a QUERY: it returns its nav whole, and collecting it into `pending`
        // happens here, in the one class that owns the collection and decides when it is flushed.
        List<NavUnit> units;
        if (p instanceof Prop.Embedded embeddedProp) {
            // Only the embedded shape needs a NavLevel: it is the one that recurses, so it is the one
            // that has to carry its position in the tree down to the next level.
            units = embedded.embeddedNav(new NavContext(origin, packageName, owner, claimed),
                    new NavLevel(member.path(), navSimple, member.column() + "_"), embeddedProp);
        } else if (p instanceof Prop.Reference || p instanceof Prop.EntityCollection) {
            units = relations.relationNav(origin, packageName, owner, navSimple, member, claimed);
        } else {
            units = elementCollections.elementCollectionNav(origin, packageName, owner, navSimple,
                    member.path(), (Prop.ElementCollection) p, claimed);
        }
        pending.addAll(units);
        // null, not empty, on collision: that is what the metamodel emitter has always tested for.
        return units.isEmpty() ? null : units.getFirst().type();
    }
}
