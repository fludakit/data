package io.github.fludakit.data.page;

import jakarta.data.page.Page;
import jakarta.data.page.PageRequest;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public final class SimplePage<T> implements Page<T> {
    private final PageRequest pageRequest;
    private final List<T> content;
    private final long totalElements;

    public SimplePage(PageRequest pageRequest, List<T> content, long totalElements) {
        this.pageRequest = pageRequest;
        this.content = content;
        this.totalElements = totalElements;
    }

    @Override public List<T> content() { return content; }
    @Override public boolean hasContent() { return !content.isEmpty(); }
    @Override public int numberOfElements() { return content.size(); }
    @Override public boolean hasNext() {
        return pageRequest.mode() == PageRequest.Mode.OFFSET
                ? pageRequest.page() * (long) pageRequest.size() < totalElements
                : content.size() == pageRequest.size();
    }
    @Override public boolean hasPrevious() {
        return pageRequest.mode() == PageRequest.Mode.OFFSET && pageRequest.page() > 1;
    }
    @Override public PageRequest pageRequest() { return pageRequest; }
    @Override public PageRequest nextPageRequest() {
        if (!hasNext()) throw new NoSuchElementException();
        return pageRequest.mode() == PageRequest.Mode.OFFSET
                ? PageRequest.ofPage(pageRequest.page() + 1, pageRequest.size(), pageRequest.requestTotal())
                : pageRequest;
    }
    @Override public PageRequest previousPageRequest() {
        if (!hasPrevious()) throw new NoSuchElementException();
        return PageRequest.ofPage(pageRequest.page() - 1, pageRequest.size(), pageRequest.requestTotal());
    }
    @Override public boolean hasTotals() { return pageRequest.requestTotal(); }
    @Override public long totalElements() {
        if (!hasTotals()) throw new IllegalStateException("totals not requested");
        return totalElements;
    }
    @Override public long totalPages() {
        long t = totalElements();
        return t == 0 ? 0 : (t + pageRequest.size() - 1) / pageRequest.size();
    }
    @Override public Iterator<T> iterator() { return content.iterator(); }
}
