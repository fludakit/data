package io.github.fludakit.data.processor.generator.nav;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.metadata.ValueObjectMapping;
import io.github.fludakit.data.processor.RepositoryProcessor;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.generator.nav.NavContext;
import io.github.fludakit.data.processor.generator.nav.NavLevel;
import io.github.fludakit.data.processor.generator.nav.NavUnit;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.processor.support.Props;
import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The navigable class for an EMBEDDED member ({@code EmbeddableAttribute<owner, target>}): owner-typed
 * leaf fields for the value object's direct members, the flattened column chain as leaf columns
 * ({@code address_point_lat}) and the full dot path as leaf/attribute names
 * ({@code address.point.lat}).
 *
 * <p>The one shape that RECURSES: a nested {@link Prop.Embedded} gets its own nav class, threaded
 * through the same {@code claimed} set, so a value object nesting a value object produces one nav
 * class per level — and every one of them comes back in {@link #embeddedNav}'s single return value,
 * ordered with the outermost first.</p>
 */
public final class EmbeddedNavGenerator {

    private final AttributeTypes attributeTypes;
    private final NavSupport support;

    public EmbeddedNavGenerator(AttributeTypes attributeTypes, NavSupport support) {
        this.attributeTypes = attributeTypes;
        this.support = support;
    }

    /**
     * The nav for an EMBEDDED member and for every value object nested inside it — one unit per
     * level, THIS nav's unit first, or an empty list when its name was already claimed.
     *
     * <p>A list because the shape recurses: a value object nesting a value object produces a nav
     * per level, and returning them all is what keeps the caller from having to know how deep the
     * tree went. {@code getFirst()} is this nav's own unit, which is the type its parent embeds.</p>
     */
    public List<NavUnit> embeddedNav(NavContext ctx, NavLevel level, Prop.Embedded embedded) {
        String fqn = ctx.packageName() + "." + level.navSimple();
        if (!ctx.claimed().add(fqn)) {
            support.navCollision(ctx.origin(), fqn, level.path());
            return List.of();
        }
        ValueObjectMapping<?> target = embedded.target();
        TypeName targetType = attributeTypes.classArgOf(target == null ? null : target.typeName());
        TypeSpec.Builder builder = TypeSpec.classBuilder(level.navSimple())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(AttributeTypes.FM_EMBEDDED, ctx.owner(), targetType))
                .addAnnotation(AnnotationSpec.builder(AttributeTypes.MM_GENERATED)
                        .addMember("value", "$S", RepositoryProcessor.class.getName()).build());
        Set<String> taken = new HashSet<>();
        // Nested value objects contribute their own nav classes. They are collected here rather than
        // pushed into a caller's list, so this method's whole result comes back in one return value.
        List<NavUnit> nested = new ArrayList<>();
        if (target != null) {
            if (target.id() != null) {
                nested.addAll(addNavLeaf(builder, taken, ctx, level, target.id()));
            }
            for (Prop p : target.props()) {
                nested.addAll(addNavLeaf(builder, taken, ctx, level, p));
            }
        }
        builder.addMethod(NavSupport.overrideAccessor("name", String.class,
                CodeBlock.of("$S", NavSupport.lastPathSegment(level.path()))));
        builder.addMethod(NavSupport.overrideAccessor("path", String.class, CodeBlock.of("$S", level.path())));
        List<NavUnit> units = new ArrayList<>();
        units.add(new NavUnit(fqn, ClassName.get(ctx.packageName(), level.navSimple()),
                NavSupport.navFile(ctx.packageName(), builder)));
        units.addAll(nested);
        return units;
    }

    /**
     * Adds the nav member for one property of the embedded target, and RETURNS the navs that property
     * brought with it — one for a nested value object plus everything below it, none for a leaf.
     *
     * <p>Returning them rather than filling a caller's {@code List<NavUnit>} is what makes the
     * recursion uniform: {@link #embeddedNav} collects a whole subtree the same way it collects its
     * own unit, so no method in this class takes a list it only ever appends to.</p>
     *
     * <p>{@code builder} and {@code taken} stay separate from {@link NavContext} on purpose: they are
     * the class THIS member is being added to, so they change at every level of the recursion, while
     * the context is constant all the way down.</p>
     */
    public List<NavUnit> addNavLeaf(TypeSpec.Builder builder, Set<String> taken, NavContext ctx,
                             NavLevel parent, Prop prop) {
        NavLevel level = parent.child(prop);
        if (prop instanceof Prop.Embedded embedded) {
            List<NavUnit> child = embeddedNav(ctx, level, embedded);
            if (!child.isEmpty()) {
                ClassName nav = child.getFirst().type();
                support.addNavField(builder, taken, ctx.origin(), ctx.owner(), level.navSimple(), prop.name(), nav,
                        CodeBlock.of("new $T()", nav));
            }
            return child;
        }
        String column = Props.column(prop);
        if (column == null) {
            return List.of(); // EntityCollection / ElementCollection in a value object: no column here.
        }
        support.addLeafField(builder, taken, ctx.origin(), ctx.owner(), parent.navSimple(), prop.name(),
                level.path(), parent.columnPrefix() + column, prop);
        return List.of();
    }
}
