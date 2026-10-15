package io.github.fludakit.data.processor.generator.nav;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.support.MemberNames;

/**
 * Where in the value-object tree one navigable class sits: its dot path, its own simple class name,
 * and the column prefix its flattened leaf columns carry.
 *
 * <p>One record because the three only ever move together — a nested value object derives all three
 * from its parent's triple and the property that names it, so {@link #child} is the only way a level
 * is ever produced. That is the recursion made explicit: before this, each level rebuilt the same
 * three strings at the call site, and a caller that changed one of them out of step with the other
 * two would still compile.</p>
 */
public record NavLevel(String path, String navSimple, String columnPrefix) {

    /** The level BELOW this one, for the value object reached through {@code p}. */
    public NavLevel child(Prop prop) {
        return new NavLevel(path + "." + prop.name(), navSimple + MemberNames.capitalize(prop.name()),
                columnPrefix + Names.columnBase(prop.name()) + "_");
    }
}
