package io.github.waleed875.memo.modules.notes.domain;

import java.util.List;

/** Original PagedResult<NoteDetailsModel> JSON shape. */
public record PagedNotes(
    List<NoteSearchItem> items,
    int totalCount,
    int pageNumber,
    int pageSize,
    int totalPages
) {
    public PagedNotes {
        items = List.copyOf(items);
    }

    public static PagedNotes of(
        List<NoteSearchItem> items, int totalCount, int pageNumber, int pageSize
    ) {
        int totalPages = totalCount == 0 ? 0 : 1 + (totalCount - 1) / pageSize;
        return new PagedNotes(items, totalCount, pageNumber, pageSize, totalPages);
    }
}
