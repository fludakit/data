package io.github.fludakit.data.processor.context;

import com.squareup.javapoet.MethodSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable emission scratch for one repository: SQL constants and generated {@link MethodSpec}s.
 *
 * <p>Filled by emitters after {@link RepositoryContext} is frozen; consumed by
 * {@code RepositoryWriter}.</p>
 */
public final class EmissionContext {

    private final Map<String, String> constants = new LinkedHashMap<>();
    private final List<MethodSpec> auxMethods = new ArrayList<>();

    public Map<String, String> constants() {
        return constants;
    }

    public List<MethodSpec> auxMethods() {
        return auxMethods;
    }

    /** Register a SQL constant, deduplicating by content. Returns the constant name. */
    public String registerConstant(String base, String sql) {
        String name = base;
        int suffix = 2;
        while (constants.containsKey(name) && !constants.get(name).equals(sql)) {
            name = base + "_" + suffix++;
        }
        constants.put(name, sql);
        return name;
    }
}
