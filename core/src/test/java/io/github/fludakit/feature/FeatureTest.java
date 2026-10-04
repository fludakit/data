package io.github.fludakit.feature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeatureTest {

    @Test
    void hello() {
        assertEquals("Hello from FluDa Feature!", Feature.hello());
    }
}
