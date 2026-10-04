package io.github.fludakit.data.page;

import jakarta.data.page.PageRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SimplePageTest {
    @Test
    void reportsTotals() {
        PageRequest req = PageRequest.ofSize(2);
        SimplePage<String> p = new SimplePage<>(req, List.of("a", "b"), 5L);
        assertThat(p.content()).hasSize(2);
        assertThat(p.numberOfElements()).isEqualTo(2);
        assertThat(p.hasTotals()).isTrue();
        assertThat(p.totalElements()).isEqualTo(5L);
        assertThat(p.totalPages()).isEqualTo(3L);
        assertThat(p.hasNext()).isTrue();
        assertThat(p.hasPrevious()).isFalse();
    }
}
