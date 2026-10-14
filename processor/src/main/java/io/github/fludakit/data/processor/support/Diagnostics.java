package io.github.fludakit.data.processor.support;

import io.github.fludakit.data.processor.analysis.PathResolver;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

/**
 * The ONE channel every generator reports a degradation through.
 *
 * <p>Most of what the processor cannot do it does not fail on — it degrades and says so: a nav class
 * whose name a metamodel already claimed, a runtime path that could not be generated, a relationship
 * target with no row mapper, a to-many sort path that {@code IgnoreCase} cannot order. Those are the
 * NOTE-level diagnostics, and they are the reason twelve classes used to hold a {@link Messager}.</p>
 *
 * <p>{@code Messager} is a {@code ProcessingEnvironment} service. Holding it means the class cannot be
 * constructed outside an annotation-processing round, so a degradation rule that is three lines of
 * logic could only be exercised by compiling a repository and reading the diagnostic back out of a
 * javac task. {@code Diagnostics} is the seam that ends that: production wires
 * {@link #of(Messager)} once per round, and a test wires a sink that collects what was reported.</p>
 *
 * <p>This file is the only place in the package that names {@code Messager}. That is the point: one
 * adapter instead of twelve holders, and a reader can find the whole APT surface of the diagnostic
 * channel by opening one file.</p>
 *
 * <p>{@link #report} takes the {@link Element} a diagnostic is positioned at, or {@code null} when
 * there is no declaration to blame — {@code Messager} accepts {@code null} there, and one caller
 * ({@code PathResolver}'s unresolvable-descriptor NOTE) genuinely has none.</p>
 */
public interface Diagnostics {

    public void report(Diagnostic.Kind kind, String message, Element site);

    public default void note(String message) {
        report(Diagnostic.Kind.NOTE, message, null);
    }

    public default void note(String message, Element site) {
        report(Diagnostic.Kind.NOTE, message, site);
    }

    public default void error(String message, Element site) {
        report(Diagnostic.Kind.ERROR, message, site);
    }

    public default void warn(String message) {
        report(Diagnostic.Kind.WARNING, message, null);
    }

    /** The production instance: everything reported goes straight to the compiler's own channel. */
    public static Diagnostics of(Messager messager) {
        return (kind, message, at) -> messager.printMessage(kind, message, at);
    }
}
