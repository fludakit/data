package io.github.fludakit.data.processor.generator.nav;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.metadata.ValueObjectMapping;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.support.Props;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The navigable class for an EMBEDDED member ({@code EmbeddableAttribute<owner, target>}): owner-typed
 * leaf fields for the value object's direct members, the flattened column chain as leaf columns
 * ({@code address_point_lat}) and the full dot path as leaf/attribute names ({@code address.point.lat}).
 *
 * <p>The one shape that RECURSES: a nested {@link Prop.Embedded} gets its own nav class, threaded
 * through the same {@code claimed} set, so a value object nesting a value object produces one nav
 * class per level — and every one of them comes back in {@link #embeddedNav}'s single return value,
 * ordered with the outermost first.</p>
 */
public final class EmbeddedNavGenerator {

    private final NavSupport support;

    public EmbeddedNavGenerator(NavSupport support) {
        this.support = support;
    }

    /**
     * The nav for an EMBEDDED member and for every value object nested inside it — one unit per level,
     * THIS nav's unit first, or an empty list when its name was already claimed.
     */
    public List<NavUnit> embeddedNav(NavContext ctx, NavLevel level, Prop.Embedded embedded) {
        ValueObjectMapping<?> target = embedded.target();
        TypeSpec.Builder builder = support.beginNav(ctx.origin(), ctx.packageName(), ctx.owner(), level.navSimple(),
                level.path(), AttributeTypes.FM_EMBEDDED, target == null ? null : target.typeName(), ctx.claimed());
        if (builder == null) {
            return List.of();
        }
        Set<String> taken = new HashSet<>();
        // Nested value objects contribute their own nav classes, collected here rather than pushed
        // into a caller's list, so this method's whole result comes back in one return value.
        List<NavUnit> nested = new ArrayList<>();
        if (target != null) {
            if (target.id() != null) {
                nested.addAll(addNavLeaf(builder, taken, ctx, level, target.id()));
            }
            for (Prop p : target.props()) {
                nested.addAll(addNavLeaf(builder, taken, ctx, level, p));
            }
        }
        List<NavUnit> units = new ArrayList<>();
        units.add(support.finishNav(ctx.packageName(), level.navSimple(), builder, level.path(),
                NavSupport.lastPathSegment(level.path())));
        units.addAll(nested);
        return units;
    }

    /**
     * Adds the nav member for one property of the embedded target, and RETURNS the navs that property
     * brought with it — one for a nested value object plus everything below it, none for a leaf.
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
