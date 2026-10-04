package io.github.fludakit.data.query;

public record SortSpec(String property, boolean ascending, boolean ignoreCase) {
    public static SortSpec of(String property, boolean ascending, boolean ignoreCase) {
        return new SortSpec(property, ascending, ignoreCase);
    }
}
