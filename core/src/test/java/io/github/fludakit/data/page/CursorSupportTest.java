package io.github.fludakit.data.page;

import jakarta.data.page.PageRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CursorSupportTest {
    @Test
    void seekWhereDelegatesToSeekWhereTermsByteIdentical() {
        // seekWhere self-quotes bare snake_case columns and delegates to seekWhereTerms; the render
        // must be byte-identical to passing the pre-quoted terms through directly (Task 11 fix pin).
        PageRequest.Cursor cursor = PageRequest.Cursor.forKey(new Object[]{"x", 5L});
        String viaColumns = CursorSupport.seekWhere(List.of("name", "id"), List.of(true, false), cursor, true);
        String viaTerms = CursorSupport.seekWhereTerms(
                List.of("\"name\"", "\"id\""), List.of(true, false), cursor, true);
        assertThat(viaColumns)
                .describedAs("seekWhere must render exactly what seekWhereTerms renders for pre-quoted names")
                .isEqualTo(viaTerms);
    }
}
