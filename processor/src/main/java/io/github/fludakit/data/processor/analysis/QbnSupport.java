package io.github.fludakit.data.processor.analysis;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The query-by-method-name parser is an OPTIONAL compile dependency; every question about it —
 * whether a name is a query-by-method name, whether the parser is on the classpath, and what it
 * returns or rejects — is answered here, through fully-qualified names and a classpath probe, so
 * no other class reaches for {@code io.github.fludakit.data.qbn} directly.
 */
final class QbnSupport {

    private static final String QBN_PARSER = "io.github.fludakit.data.qbn.QueryByMethodNameParser";
    private static final List<String> QUERY_METHOD_PREFIXES = List.of("find", "count", "delete", "exists");
    private static final Pattern BARE_QUERY_NAME = Pattern.compile("(?:find|count|delete|exists)By?");
    private static Boolean available;

    static boolean isQueryMethodName(String name) {
        for (String prefix : QUERY_METHOD_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static boolean isBareQueryName(String name) {
        return BARE_QUERY_NAME.matcher(name).matches();
    }

    static boolean isAvailable() {
        if (available == null) {
            try {
                Class.forName(QBN_PARSER);
                available = true;
            } catch (ClassNotFoundException | LinkageError absent) {
                available = false;
            }
        }
        return available;
    }

    static Object parseOrNull(String name) {
        try {
            return new io.github.fludakit.data.qbn.QueryByMethodNameParser().parse(name);
        } catch (RuntimeException | LinkageError rejected) {
            return null;
        }
    }

    static String rejectionOf(String name) {
        try {
            new io.github.fludakit.data.qbn.QueryByMethodNameParser().parse(name);
            return null;
        } catch (RuntimeException | LinkageError rejected) {
            return rejected.getMessage();
        }
    }
}
