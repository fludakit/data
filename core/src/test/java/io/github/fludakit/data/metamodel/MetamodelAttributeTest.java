package io.github.fludakit.data.metamodel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.data.Sort;

import org.junit.jupiter.api.Test;

/**
 * The static-metamodel attribute records and the {@link Sort} terms they publish.
 *
 * <p>What is worth pinning is the PATH, not the name: {@code asc()}/{@code desc()} delegate to
 * {@link Sort} with {@code path()}, and for a navigable attribute the path is multi-segment
 * ({@code "address.city"}) while the name is only its last segment ({@code "city"}). A delegation
 * that used {@code name()} would compile, and would sort by a property that does not resolve.
 * {@link NavigableAttribute#path()} is re-declared abstract for exactly this reason, so the two are
 * pinned together here.</p>
 */
class MetamodelAttributeTest {

    private static final Sort<String> ASC = new Sort<>("address.city", true, false);
    private static final Sort<String> DESC = new Sort<>("address.city", false, false);
    private static final Sort<String> ASC_IGNORED = new Sort<>("address.city", true, true);
    private static final Sort<String> DESC_IGNORED = new Sort<>("address.city", false, true);

    @Test
    void aSortableScalarSortsByItsPathNotItsName() {
        SortableScalarAttributeRecord<String> city =
                new SortableScalarAttributeRecord<>("city", "address.city");
        assertEquals("city", city.name());
        assertEquals(ASC, city.asc());
        assertEquals(DESC, city.desc());
    }

    @Test
    void anIdAttributeSortsByItsPath() {
        IdAttributeRecord<String> id = new IdAttributeRecord<>("id", "id");
        assertEquals(new Sort<>("id", true, false), id.asc());
        assertEquals(new Sort<>("id", false, false), id.desc());
    }

    @Test
    void aTextAttributeSortsByItsPathWithAndWithoutCase() {
        TextScalarAttributeRecord<String> title = new TextScalarAttributeRecord<>("title", "title");
        assertEquals(new Sort<>("title", true, false), title.asc());
        assertEquals(new Sort<>("title", false, false), title.desc());
        assertEquals(new Sort<>("title", true, true), title.ascIgnoreCase());
        assertEquals(new Sort<>("title", false, true), title.descIgnoreCase());
    }

    @Test
    void aTextAttributeSortsCaseInsensitivelyByItsPathNotItsName() {
        TextScalarAttributeRecord<String> city =
                new TextScalarAttributeRecord<>("city", "address.city");
        assertEquals(ASC, city.asc());
        assertEquals(ASC_IGNORED, city.ascIgnoreCase());
        assertEquals(DESC, city.desc());
        assertEquals(DESC_IGNORED, city.descIgnoreCase());
    }

    @Test
    void aSingleSegmentAttributeDefaultsItsPathToItsName() {
        SortableScalarAttributeRecord<String> title = new SortableScalarAttributeRecord<>("title");
        assertEquals("title", title.path());
        assertEquals(new Sort<>("title", true, false), title.asc());
    }

    /** {@link PathAttribute#path()} defaults to {@code name()}; a navigable attribute may not. */
    @Test
    void aPathOnlyAttributeKeepsTheInheritedNameDefaultForPath() {
        PathAttributeRecord<String> leaf = new PathAttributeRecord<>("city", "address.city");
        assertEquals("city", leaf.name());
        assertEquals("address.city", leaf.path());
    }
}
