package io.github.fludakit.data.qbn;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

public final class QueryByMethodNameParser {

    public ParsedMethodName parse(String name) {
        try {
            QbnLexer lexer = new QbnLexer(CharStreams.fromString(name));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            QbnParser parser = new QbnParser(tokens);
            parser.setErrorHandler(new BailErrorStrategy());
            QbnParser.MethodNameContext ctx = parser.methodName();
            return new QbnMethodNameVisitor().visit(ctx);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a query method name: " + name, e);
        }
    }
}
