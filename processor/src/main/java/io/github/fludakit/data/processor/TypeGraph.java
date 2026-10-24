package io.github.fludakit.data.processor;

import io.github.fludakit.data.metadata.TypeDefinition;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory registry of every mapped type discovered across the processing round.
 *
 * <p>This is the single source of truth for the type graph the processor builds and the emitters
 * read from. It replaces the scattered caches the visitor used to keep (definition cache,
 * managed-entity FQNs, element-collection value objects, and deferred relation targets) with one
 * named structure, so the graph — not a generated per-type descriptor class — is the descriptor.</p>
 */
final class TypeGraph {

    /** Every mapped type, keyed by fully-qualified type name, in discovery order. */
    private final Map<String, TypeDefinition> nodes = new LinkedHashMap<>();

    /** FQNs of entity and aggregate-root nodes (not value objects). */
    private final Set<String> managedEntities = new LinkedHashSet<>();

    /** FQNs of value objects used as element collection element types. */
    private final Set<String> elementCollectionValueObjects = new LinkedHashSet<>();

    /** Deferred relation targets: owner FQN → (property name → target FQN). */
    private final Map<String, Map<String, String>> pendingTargets = new HashMap<>();

    /** Registers (or replaces) a node and records whether it is a managed entity. */
    void put(String fqn, TypeDefinition definition) {
        nodes.put(fqn, definition);
        if (definition instanceof TypeDefinition.Entity
                || definition instanceof TypeDefinition.Aggregate) {
            managedEntities.add(fqn);
        }
    }

    /** Looks up a node by fully-qualified type name, or {@code null}. */
    TypeDefinition get(String fqn) {
        return nodes.get(fqn);
    }

    /** Whether a node is registered under {@code fqn}. */
    boolean contains(String fqn) {
        return nodes.containsKey(fqn);
    }

    /** Every registered node FQN, in discovery order. */
    Set<String> nodes() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    /** The entity and aggregate-root node FQNs. */
    Set<String> managedEntities() {
        return Collections.unmodifiableSet(managedEntities);
    }

    /** The value objects used as element collection element types. */
    Set<String> elementCollectionValueObjects() {
        return Collections.unmodifiableSet(elementCollectionValueObjects);
    }

    /** Records a value object as an element collection element type. */
    void markElementCollectionValueObject(String fqn) {
        elementCollectionValueObjects.add(fqn);
    }

    /** Records a relation target to resolve once its definition is available. */
    void deferTarget(String ownerFqn, String propName, String targetFqn) {
        pendingTargets.computeIfAbsent(ownerFqn, k -> new HashMap<>()).put(propName, targetFqn);
    }

    /** Looks up a deferred target for one owner property, or {@code null}. */
    String pendingTarget(String ownerFqn, String propName) {
        Map<String, String> pending = pendingTargets.get(ownerFqn);
        return pending != null ? pending.get(propName) : null;
    }

    /** Every value-object node in the graph. */
    Set<String> valueObjects() {
        Set<String> vos = new LinkedHashSet<>();
        for (Map.Entry<String, TypeDefinition> entry : nodes.entrySet()) {
            if (entry.getValue() instanceof TypeDefinition.ValueObject) {
                vos.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(vos);
    }
}
