package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinEdge;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

import io.github.fludakit.data.processor.RepositoryProcessor;
import io.github.fludakit.data.processor.model.PathStrictness;
import io.github.fludakit.data.processor.model.PathResolution;
import io.github.fludakit.data.processor.support.Diagnostics;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * One question, asked of the compile-time model: <em>which physical column does this declared
 * property name mean, and how do I reach it?</em>
 *
 * <p>This was {@code RepositoryProcessor}'s path-resolution block. It is a handler rather than a
 * region of the processor because it is the only part that answers the question, and every emitter
 * (predicates, sorts, EXISTS conjuncts, delete criteria, cursor keys) asks it the same way. It
 * touches the javac environment only to report NOTEs — not even to look up a target type by name,
 * which is why it holds no {@code Elements}: it emits nothing, claims no generated type name and
 * writes no file, so it can be read, and tested, on its own.</p>
 *
 * <p>The lookup of a target's descriptor is injected, and is keyed by NAME rather than by
 * {@link TypeElement}: a path's target reaches this class as the FQN {@code Prop.Reference} carries,
 * so making the function name-keyed is what lets the caller stop resolving that name itself. The
 * mapping builder owns both the descriptor cache and the name-to-element resolution behind it, and
 * a handler that silently shared either would hide the one piece of mutable state this class
 * depends on.</p>
 */
public final class PathResolver {

    private final Diagnostics diagnostics;
    private final Function<String, TypeMapping<?>> descriptorOf;

    public PathResolver(Diagnostics diagnostics, Function<String, TypeMapping<?>> descriptorOf) {
        this.diagnostics = diagnostics;
        this.descriptorOf = descriptorOf;
    }

    /**
     * Resolves every property in {@code paths} against {@code mapping}, reporting the multi-hop and
     * unresolved-relation NOTEs on {@code method} and falling back to the legacy unqualified column
     * (identical to {@code SqlBuilder}'s old {@code Names.columnBase} rendering) whenever the descriptor
     * cannot resolve the path. The returned resolutions line up index-for-index with {@code paths}.
     *
     * <p>What happens to a path that matches NOTHING is decided by {@code origin}, not by a flag:
     * see {@link PathStrictness}.</p>
     */
    public List<PathResolution> resolveCompilePaths(TypeMapping<?> mapping, List<String> paths,
                                             ExecutableElement method, PathStrictness strictness) {
        List<PathResolution> out = new ArrayList<>();
        for (String path : paths) {
            PathResolution resolution = resolveCompilePath(mapping, path);
            if (resolution instanceof PathResolution.MultiHop) {
                diagnostics.note("path '" + path + "' traverses more than one relation; not supported", method);
                out.add(snakeCased(path));
            } else if (resolution instanceof PathResolution.UnresolvedRelation) {
                diagnostics.note("path '" + path + "' traverses a relation whose target could not be resolved; the predicate was left unqualified", method);
                out.add(snakeCased(path));
            } else if (resolution instanceof PathResolution.Resolved r) {
                out.add(r);
            } else {
                if (strictness.failsOnUnresolved()) {
                    diagnostics.error(
                            "property '" + path + "' of the query-by-method name does not resolve on entity '"
                                    + mapping.typeName() + "'; correct the method name or annotate it with @Query",
                            method);
                    out.add(new PathResolution.Unknown());
                } else {
                    out.add(snakeCased(path));
                }
            }
        }
        return out;
    }

    /** The legacy fully-local ref: physical column via {@link Names#columnBase}, no join, no table. */
    public static ColumnRef snakeCasedLocalColumnRef(String property) {
        return new ColumnRef.Simple(Names.columnBase(property), null);
    }

    /** A placement on {@code leaf} with no edges to cross. */
    public static ColumnPlacement placementOf(ColumnRef leaf) {
        return new ColumnPlacement(leaf);
    }

    /**
     * Resolves a single method-name property to a {@link ColumnRef} through the compile model, using the
     * same algorithm as the runtime {@code TypeMapping.columnRef} but driven by the concatenated
     * camel path the {@code qbn} parser emits (which carries NO dots). So a head/remainder split happens
     * at camel-hump (and any literal {@code '.'}) boundaries rather than at a single dot. Order:
     * <ol>
     *   <li>local scan — a dots-stripped, case-insensitive match against each entity-rooted mapping key
     *       (this keeps flat scalars and flattened embedded leaves byte-identical to today);</li>
     *   <li>relation descent — the shortest head that is a {@link Prop.Reference}/
     *       {@link Prop.EntityCollection}/{@link Prop.ElementCollection}, with the remainder resolved as a LEAF in the
     *       target (or against the element columns), contributing exactly one join.</li>
     * </ol>
     * A remainder that would itself need a relation yields {@link PathResolution.MultiHop}; a head whose
     * target cannot be resolved yields {@link PathResolution.UnresolvedRelation}.
     */
    public PathResolution resolveCompilePath(TypeMapping<?> mapping, String path) {
        Map.Entry<String, ColumnRef> local = localColumnEntry(mapping.columnRefs(), path);
        if (local != null) {
            return new PathResolution.Resolved(placementOf(local.getValue()), local.getKey(), false);
        }
        for (String[] head : candidateHeads(path)) {
            Prop prop = propOrNull(mapping, head[0]);
            if (!isTraversable(prop)) {
                continue;
            }
            PathResolution inner = resolveRelationHead(mapping, prop, head[1]);
            if (inner instanceof PathResolution.Resolved r) {
                // The head is a DECLARED property name; the leaf is resolved inside the target below, so
                // the authoritative path is only known once the leaf resolves. Prefix it with the head.
                return r.at(prop.name() + "." + r.attributePath());
            }
            if (inner instanceof PathResolution.MultiHop || inner instanceof PathResolution.UnresolvedRelation) {
                return inner;
            }
            // Unknown: try the next candidate head.
        }
        return new PathResolution.Unknown();
    }

    /**
     * Resolves {@code remainder} as a leaf under a relation {@code prop} and attaches the join edge the
     * traversal contributes. The alias is exactly the property name. To-one {@link Prop.Reference}
     * chains traverse transitively (each hop prepends its edge); a to-many head is always an
     * existential leaf and never recurses deeper.
     */
    public PathResolution resolveRelationHead(TypeMapping<?> mapping, Prop prop, String remainder) {
        return resolveRelationHead(mapping, prop, remainder, new HashSet<>());
    }

    public PathResolution resolveRelationHead(TypeMapping<?> mapping, Prop prop, String remainder, Set<String> visited) {
        if (prop instanceof Prop.ElementCollection elementCollection) {
            Map.Entry<String, ColumnRef> leaf =
                    localColumnEntry(elementCollection.target().columnRefs(), remainder);
            if (leaf == null) {
                return new PathResolution.Unknown();
            }
            JoinEdge edge = TypeMapping.relationEdge(mapping, elementCollection);
            if (edge == null) {
                return new PathResolution.UnresolvedRelation();
            }
            return new PathResolution.Resolved(new ColumnPlacement(leaf.getValue(), List.of(edge)),
                    leaf.getKey(), true);
        }
        String targetFqn = prop instanceof Prop.Reference reference
                ? reference.targetTypeName() : ((Prop.EntityCollection) prop).targetTypeName();
        if (!visited.add(targetFqn)) {
            return new PathResolution.UnresolvedRelation(); // cyclic relationship: degrade, don't recurse forever
        }
        TypeMapping<?> target = descriptorByFqn(targetFqn);
        if (target == null) {
            return new PathResolution.UnresolvedRelation();
        }
        JoinEdge edge = TypeMapping.relationEdge(mapping, prop);
        if (edge == null) {
            return new PathResolution.UnresolvedRelation();
        }
        Map.Entry<String, ColumnRef> leaf = localColumnEntry(target.columnRefs(), remainder);
        if (leaf != null) {
            return new PathResolution.Resolved(new ColumnPlacement(leaf.getValue(), List.of(edge)),
                    leaf.getKey(), prop instanceof Prop.EntityCollection);
        }
        // The remainder names another relation in the target: a to-one Reference continues the
        // traversal (multi-hop); a to-many head nested under a to-one stays out of scope.
        for (String[] deeper : candidateHeads(remainder)) {
            Prop deeperProp = propOrNull(target, deeper[0]);
            if (deeperProp instanceof Prop.Reference) {
                PathResolution inner = resolveRelationHead(target, deeperProp, deeper[1], visited);
                if (!(inner instanceof PathResolution.Resolved r)) {
                    return inner; // multi-hop, unresolved relation, or unknown — propagate
                }
                List<JoinEdge> chainedEdges = new ArrayList<>();
                chainedEdges.add(edge);
                chainedEdges.addAll(r.placement().edges());
                ColumnPlacement chained = new ColumnPlacement(r.placement().leaf(), chainedEdges);
                return new PathResolution.Resolved(chained, deeperProp.name() + "." + r.attributePath(),
                        prop instanceof Prop.EntityCollection);
            }
            if (isTraversable(deeperProp)) {
                return new PathResolution.MultiHop();
            }
        }
        return new PathResolution.Unknown();
    }

    /**
     * The descriptor of a relationship target named by its fully-qualified type, or {@code null}.
     *
     * <p>Three different things collapse into the same {@code null}: the name is absent, the type does
     * not resolve in this compilation, or the type resolves but building its descriptor failed. Only
     * the third is a failure rather than a legitimate absence, so it is reported — every caller treats
     * {@code null} as "not traversable" and would otherwise drop the path in silence.
     */
    public TypeMapping<?> descriptorByFqn(String fqn) {
        if (fqn == null) {
            return null;
        }
        String raw = stripGenerics(fqn);
        try {
            return descriptorOf.apply(raw);
        } catch (RuntimeException e) {
            diagnostics.note(
                    "the descriptor of '" + raw + "' could not be built (" + e
                            + "), so the relationship through it is not traversable");
            return null;
        }
    }

    /**
     * The mapping entry a concatenated camel path names, or {@code null}. The KEY is the authoritative
     * attribute path as declared ({@code "address.city"}, {@code "quantity"}); the query-by-method-name
     * token that selected it is only a lookup key ({@code "addressCity"}, {@code "Quantity"}). Callers
     * that must publish or re-resolve the path need the key, not the token.
     */
    public static Map.Entry<String, ColumnRef> localColumnEntry(Map<String, ColumnRef> mappings, String path) {
        String flat = path.replace(".", "");
        for (Map.Entry<String, ColumnRef> entry : mappings.entrySet()) {
            if (entry.getKey().replace(".", "").equalsIgnoreCase(flat)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * All (head, remainder) splits at camel-hump and literal-{@code '.'} boundaries, shortest head
     * first. Each entry is {@code {head, remainder}}.
     */
    public static List<String[]> candidateHeads(String path) {
        List<String[]> out = new ArrayList<>();
        for (int i = 1; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                out.add(new String[]{path.substring(0, i), path.substring(i + 1)});
            } else if (Character.isUpperCase(c)) {
                out.add(new String[]{path.substring(0, i), path.substring(i)});
            }
        }
        return out;
    }

    public static boolean isTraversable(Prop prop) {
        return prop instanceof Prop.Reference || prop instanceof Prop.EntityCollection || prop instanceof Prop.ElementCollection;
    }

    /**
     * {@link TypeMapping#prop(String)} without the throw: {@code null} for an unknown name. A
     * mid-path camel segment keeps its leading capital (e.g. {@code "Country"} in
     * {@code "customerCountryCode"}), so the decapitalized form is matched as a fallback — mirroring how
     * the {@code qbn} parser only decapitalizes the FIRST hump of the concatenated property string.
     */
    public static Prop propOrNull(TypeMapping<?> mapping, String name) {
        Prop exact = propByExactName(mapping, name);
        if (exact != null) {
            return exact;
        }
        if (name.isEmpty() || !Character.isUpperCase(name.charAt(0))) {
            return null;
        }
        return propByExactName(mapping, Character.toLowerCase(name.charAt(0)) + name.substring(1));
    }

    public static Prop propByExactName(TypeMapping<?> mapping, String name) {
        if (mapping.id() != null && mapping.id().name().equals(name)) {
            return mapping.id();
        }
        for (Prop p : mapping.props()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** A {@code List<Foo<Bar>>} FQN is not a resolvable type name: drop the type arguments. */
    public static String stripGenerics(String fqn) {
        int angle = fqn.indexOf('<');
        return angle < 0 ? fqn : fqn.substring(0, angle);
    }

    // ------------------------------------------------- PathResolution factory

    /** The legacy fallback resolution: a fully-local snake-cased column with no attribute path. */
    private static PathResolution.Resolved snakeCased(String path) {
        return new PathResolution.Resolved(placementOf(snakeCasedLocalColumnRef(path)), null, false);
    }
}
