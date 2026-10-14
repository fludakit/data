package io.github.fludakit.data.qbn;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.misc.ParseCancellationException;

/**
 * Turns a repository method name into the query that name states.
 *
 * <p>Two rejections leave {@link #parse(String)} and they are NOT bundled into one message. A name
 * the GRAMMAR cannot read is wrapped as {@code "Not a query method name"}. A name the grammar
 * accepts but the {@link QbnMethodNameVisitor} refuses — a {@code Not} on an operator the spec does
 * not negate, a {@code Distinct} on an action that neither selects nor counts — propagates
 * UNCHANGED, carrying the reason the visitor worked out.</p>
 *
 * <p>They used to be one: the {@code try} wrapped the visit as well as the parse, so the visitor's
 * reason became the CAUSE of a generic wrapper. Every caller then read only the wrapper, so
 * {@code findByPriceNotLessThan} was rejected as "not a query method name" when the parser had
 * already determined that {@code Not} cannot be combined with {@code LESS_THAN}. Catching the
 * parse-specific failure instead of {@code RuntimeException} is what keeps the two apart.</p>
 */
public final class QueryByMethodNameParser {

    /**
     * Parses {@code name} into the action, predicates, sorts and limit it states.
     *
     * @throws IllegalArgumentException the name is not one the grammar reads, or it is one the
     *         visitor refuses — in the latter case the message is the reason the visitor gave
     */
    public ParsedMethodName parse(String name) {
        QbnParser.MethodNameContext ctx;
        try {
            QbnLexer lexer = new QbnLexer(CharStreams.fromString(name));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            QbnParser parser = new QbnParser(tokens);
            parser.setErrorHandler(new BailErrorStrategy());
            ctx = parser.methodName();
        } catch (ParseCancellationException malformed) {
            // The one failure the lexer and the parser signal for a name they cannot read. Caught
            // HERE rather than around the whole method, which is what leaves the visitor's own
            // rejections intact: those are a different claim — the name parsed, and the query it
            // states is the thing that is wrong.
            throw new IllegalArgumentException("Not a query method name: " + name, malformed);
        }
        return new QbnMethodNameVisitor().visit(ctx);
    }
}
