package io.github.fludakit.data.processor.emitter;

import java.util.ArrayList;
import java.util.List;

/**
 * The structural path to a nested property, carried as its Java property-name segments.
 *
 * <p>Flattening a value object has to produce three names from one path: the getter chain the
 * generated code calls, the flattened column, and the flattened bind parameter. The processor used
 * to carry the path as property names joined with {@code '_'} and recover the getter chain by
 * splitting that string on {@code '_'} again. That encoding is lossy — a property legally named
 * {@code home_address} re-parses as the two segments {@code home} and {@code address} — so the
 * emitter called a getter that does not exist and the generated repository did not compile.</p>
 *
 * <p>Keeping the segments means the path is never parsed back into structure: {@link #nameOf} still
 * reproduces the historical {@code a_b} spelling for columns and parameters, while the getter chain
 * walks {@link #segments()} verbatim.</p>
 *
 * <p>Package-private on purpose: only the emitters speak this language, and it stays in the
 * processor because a column name is a physical detail rather than part of the runtime metadata
 * model.</p>
 */
record PropPath(List<String> segments) {

    /** The path to a property declared directly on the root type. */
    static final PropPath ROOT = new PropPath(List.of());

    PropPath {
        segments = List.copyOf(segments);
    }

    /** This path extended by one property name — the only way a caller can grow a path. */
    PropPath child(String propertyName) {
        List<String> deeper = new ArrayList<>(segments);
        deeper.add(propertyName);
        return new PropPath(deeper);
    }

    boolean isEmpty() {
        return segments.isEmpty();
    }

    /**
     * The flattened column/parameter prefix, empty at the root ({@code address_point}).
     *
     * <p>Present because the SQL side spells an embedded path with underscores; the Java side uses
     * {@link #segments()} instead, so this is the ONLY place the two conventions meet.</p>
     */
    String prefix() {
        return String.join("_", segments);
    }

    /**
     * The flattened name of {@code leaf} under this path — used for both the physical column and the
     * named parameter, which the renderer derives from the same logical path.
     */
    String nameOf(String leaf) {
        return segments.isEmpty() ? leaf : prefix() + "_" + leaf;
    }
}
