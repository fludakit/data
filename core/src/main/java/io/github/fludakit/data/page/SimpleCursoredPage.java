package io.github.fludakit.data.page;

import jakarta.data.page.CursoredPage;
import jakarta.data.page.PageRequest;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public final class SimpleCursoredPage<T> implements CursoredPage<T> {
    private final PageRequest pageRequest;
    private final List<T> content;
    private final List<PageRequest.Cursor> cursors;
    private final long totalElements;

    public SimpleCursoredPage(PageRequest pageRequest, List<T> content, List<PageRequest.Cursor> cursors, long totalElements) {
        this.pageRequest = pageRequest;
        this.content = content;
        this.cursors = cursors;
        this.totalElements = totalElements;
    }

    @Override public List<T> content() { return content; }
    @Override public boolean hasContent() { return !content.isEmpty(); }
    @Override public int numberOfElements() { return content.size(); }
    @Override public boolean hasNext() { return content.size() == pageRequest.size(); }
    @Override public boolean hasPrevious() { return pageRequest.cursor().isPresent(); }
    @Override public PageRequest pageRequest() { return pageRequest; }
    @Override public PageRequest.Cursor cursor(int index) { return cursors.get(index); }
    @Override public PageRequest nextPageRequest() {
        if (!hasNext()) throw new NoSuchElementException();
        return pageRequest.afterCursor(cursors.get(cursors.size() - 1));
    }
    @Override public PageRequest previousPageRequest() {
        if (!hasPrevious()) throw new NoSuchElementException();
        return pageRequest.beforeCursor(cursors.get(0));
    }
    @Override public boolean hasTotals() { return pageRequest.requestTotal(); }
    @Override public long totalElements() {
        if (!hasTotals()) throw new IllegalStateException("totals not requested");
        return totalElements;
    }
    @Override public long totalPages() { return 0; }
    @Override public Iterator<T> iterator() { return content.iterator(); }
}
