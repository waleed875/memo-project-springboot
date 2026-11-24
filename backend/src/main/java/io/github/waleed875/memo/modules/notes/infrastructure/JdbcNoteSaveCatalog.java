package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.notes.application.NoteSaveCatalog;
import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import io.github.waleed875.memo.modules.notes.domain.RequiredNoteField;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only adapter for preflight checks; never opens a write transaction.
 * Uses existing core.note_type_fields and core.notes without schema changes.
 */
@Repository
public class JdbcNoteSaveCatalog implements NoteSaveCatalog {
    private final JdbcTemplate jdbc;

    public JdbcNoteSaveCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RequiredNoteField> activeFields(UUID noteTypeId) {
        if (noteTypeId == null) return List.of();
        return jdbc.query("""
            select nf.id as field_id, nf.name_ar as field_name_ar,
                   ntf.is_required,
                   (ntf.is_active and nf.is_active) as is_active
            from core.note_type_fields ntf
            join core.note_fields nf on nf.id = ntf.field_id
            where ntf.note_type_id = ? and not ntf.is_deleted and not nf.is_deleted
            order by ntf.sort_order, nf.id
            """, (rs, row) -> new RequiredNoteField(
            rs.getObject("field_id", UUID.class),
            rs.getString("field_name_ar"),
            rs.getBoolean("is_required"),
            rs.getBoolean("is_active")
        ), noteTypeId);
    }

    @Override
    public Optional<ExistingNote> existing(UUID noteId) {
        return jdbc.query("""
            select id, created_by, status, updated_at
            from core.notes where id = ? and not is_deleted
            """, (rs, row) -> new ExistingNote(
            rs.getObject("id", UUID.class),
            rs.getObject("created_by", UUID.class),
            NoteStatus.valueOf(rs.getString("status")),
            rs.getObject("updated_at", java.time.LocalDateTime.class)
        ), noteId).stream().findFirst();
    }
}
