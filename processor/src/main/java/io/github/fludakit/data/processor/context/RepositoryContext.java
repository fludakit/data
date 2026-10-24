package io.github.fludakit.data.processor.context;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.TypeDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RepositoryContext {
    private final TypeDefinition entity;
    private final ClassName rowMapperType;

    // Emission state (mutable, appended by emitters)
    private final Map<String, String> constants = new LinkedHashMap<>();
    private final List<MethodSpec> auxMethods = new ArrayList<>();

    public RepositoryContext(TypeDefinition entity, ClassName rowMapperType) {
        this.entity = entity;
        this.rowMapperType = rowMapperType;
    }

    public TypeDefinition entity() { return entity; }
    public ClassName rowMapperType() { return rowMapperType; }
    public Map<String, String> constants() { return constants; }
    public List<MethodSpec> auxMethods() { return auxMethods; }

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
