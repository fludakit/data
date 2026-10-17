package io.github.fludakit.data.processor.generator.nav;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
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
import io.github.fludakit.data.processor.generator.rowmapper.RowMapperGenerator;
import io.github.fludakit.data.processor.analysis.TypeModelBuilder;
import io.github.fludakit.data.processor.generator.nav.MmMember;
import io.github.fludakit.data.processor.generator.nav.NavUnit;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.processor.support.Props;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The navigable class for a MANY_TO_ONE ({@code EntityAttribute}) or ONE_TO_MANY
 * ({@code ListAttribute}) member.
 *
 * <p>Also owns the two pieces the ELEMENT-COLLECTION generator borrows: an element collection's
 * leaves are built exactly like a relation's (one owner-typed leaf per flattened column), so
 * {@link ElementCollectionNavGenerator} asks this class rather than growing a second copy.</p>
 */
public final class RelationNavGenerator {

    private final AttributeTypes attributeTypes;
    private final TypeModelBuilder typeMappingBuilder;
    private final RowMapperGenerator rowMapperGenerator;
    private final NavSupport support;
    private final Set<String> managedEntities;

    public RelationNavGenerator(AttributeTypes attributeTypes,
                         TypeModelBuilder typeMappingBuilder,
                         RowMapperGenerator rowMapperGenerator, NavSupport support,
                         Set<String> managedEntities) {
        this.attributeTypes = attributeTypes;
        this.typeMappingBuilder = typeMappingBuilder;
        this.rowMapperGenerator = rowMapperGenerator;
        this.support = support;
        this.managedEntities = managedEntities;
    }

    /**
     * The nav for a {@code REFERENCE} or {@code ENTITY_COLLECTION} member: this nav's unit, or an
     * empty list when its name was already claimed (the collision is reported, not thrown).
     *
     * <p>A LIST rather than a single unit because {@link EmbeddedNavGenerator} nests: one call there
     * produces a nav per level, and a uniform return is what lets the caller collect without
     * special-casing the recursive shape.</p>
     */
    public List<NavUnit> relationNav(TypeElement origin, String packageName, TypeName owner,
                              String navSimple, MmMember member, Set<String> claimed) {
        Prop p = member.prop();
        boolean fk = p instanceof Prop.Reference;
        String targetFqn = Props.targetFqn(p);
        String fqn = packageName + "." + navSimple;
        if (!claimed.add(fqn)) {
            support.navCollision(origin, fqn, member.path());
            return List.of();
        }
        TypeName targetType = attributeTypes.classArgOf(targetFqn);
        TypeSpec.Builder builder = TypeSpec.classBuilder(navSimple)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(
                        fk ? AttributeTypes.FM_FK : AttributeTypes.FM_OTM, owner, targetType))
                .addAnnotation(AnnotationSpec.builder(AttributeTypes.MM_GENERATED)
                        .addMember("value", "$S", RepositoryProcessor.class.getName()).build());
        Set<String> taken = new HashSet<>();
        if (relationTargetResolved(targetFqn, false)) {
            // Non-null is guaranteed by the gate above, which resolved this same model by this same
            // name; the FQN-keyed form is a cache hit, so the model is built once either way.
            TypeMapping<?> target = typeMappingBuilder.descriptorFor(PathResolver.stripGenerics(targetFqn));
            if (target.id() != null) {
                addRelationLeaf(builder, taken, origin, owner, member.path(), "", target.id());
            }
            for (Prop tp : target.props()) {
                addRelationLeaf(builder, taken, origin, owner, member.path(), "", tp);
            }
        }
        builder.addMethod(NavSupport.overrideAccessor("name", String.class,
                CodeBlock.of("$S", member.prop().name())));
        builder.addMethod(NavSupport.overrideAccessor("path", String.class, CodeBlock.of("$S", member.path())));
        return List.of(new NavUnit(fqn, ClassName.get(packageName, navSimple),
                NavSupport.navFile(packageName, builder)));
    }

    public void addRelationLeaf(TypeSpec.Builder builder, Set<String> taken, TypeElement origin,
                                 TypeName owner, String parentPath, String columnPrefix, Prop prop) {
        String path = parentPath + "." + prop.name();
        // Java 17 safety: instanceof chain, never a pattern switch.
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
                support.addLeafField(builder, taken, origin, owner, parentPath, embedded.name(), path,
                        columnPrefix + Names.columnBase(embedded.name()),
                        new Prop.Scalar(embedded.name(), Names.columnBase(embedded.name()), null));
            }
            return;
        }
        String column = Props.column(prop);
        if (column == null) {
            return;
        }
        support.addLeafField(builder, taken, origin, owner, parentPath, prop.name(), path,
                columnPrefix + column, prop);
    }

    public boolean relationTargetResolved(String targetFqn, boolean elementCollection) {
        if (targetFqn == null) {
            return false;
        }
        String rawName = PathResolver.stripGenerics(targetFqn);
        TypeMapping<?> targetModel = typeMappingBuilder.descriptorFor(rawName);
        if (targetModel == null || !rowMapperGenerator.canGenerateRowMapper(targetModel)) {
            return false;
        }
        return elementCollection || managedEntities.contains(rawName);
    }
}
