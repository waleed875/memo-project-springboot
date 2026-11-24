package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.notes.domain.NoteSearchCriteria;
import io.github.waleed875.memo.modules.notes.domain.PagedNotes;

/** Read-only port with no Spring or persistence dependency. */
public interface NoteSearchReader {
    PagedNotes search(NoteSearchCriteria criteria);
}
