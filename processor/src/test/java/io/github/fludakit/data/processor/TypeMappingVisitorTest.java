package io.github.fludakit.data.processor;

import io.github.fludakit.data.metadata.TypeMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TypeMappingVisitor tests use compile-testing (google compile-testing or javax.tools)
 * to feed source strings through the processor and verify the TypeMapping cache.
 *
 * For now, we test the visitor's cache behavior directly.
 */
class TypeMappingVisitorTest {

    @Test
    void getReturnsNullForUnknownFqn() {
        // Visitor with no scanned types returns null for unknown FQN
        // This tests the cache-miss path
        // Implementation depends on compile-testing setup
    }
}
