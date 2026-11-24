package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.notes.application.NoteAccessDenied;
import io.github.waleed875.memo.modules.notes.application.NoteCreateConflict;
import io.github.waleed875.memo.modules.notes.application.NoteSourceStore;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachmentPolicy;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceDraft;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceType;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceView;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restricted source write/read adapter against migration 063.
 * Access is rechecked under an exclusive note-row lock before a mutation.
 * Sources, exit refs/invoices/loot, note loot flag and JSONB audit are one
 * transaction. The DB's original source/ref triggers remain authoritative.
 */
@Repository
public class JdbcNoteSourceStore implements NoteSourceStore {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public JdbcNoteSourceStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    private record NoteScope(UUID companyId, String type, UUID owner) {}

    private NoteScope note(UUID id, UUID requester, boolean ownOnly, boolean lock) {
        String query = """
            select n.company_id, nt.code, n.created_by
            from core.notes n join core.note_types nt on nt.id = n.note_type_id
            where n.id = ? and not n.is_deleted
            """ + (lock ? " for update of n" : "");
        List<NoteScope> rows = jdbc.query(query,
            (rs, row) -> new NoteScope(
                rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, UUID.class)), id);
        if (rows.isEmpty()) throw new NoteCreateConflict("المذكرة غير موجودة أو محذوفة.");
        NoteScope scope = rows.get(0);
        if (ownOnly && !requester.equals(scope.owner())) throw new NoteAccessDenied();
        return scope;
    }

    private UUID sourceId(UUID noteId) {
        List<UUID> ids = jdbc.query(
            "select id from core.note_sources where note_id = ?",
            (rs, row) -> rs.getObject(1, UUID.class), noteId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Safe structured audit snapshot; never embeds invoice attachment bytes. */
    private String snapshot(UUID sourceId) {
        if (sourceId == null) return null;
        List<String> values = jdbc.query("""
            select jsonb_build_object(
                'id', s.id, 'noteId', s.note_id, 'sourceType', s.source_type,
                'otherDescription', s.other_description,
                'lootDescription', (
                    select l.description from core.note_source_loot l
                    where l.source_id = s.id),
                'exitReferences', (
                    select coalesce(jsonb_agg(
                        jsonb_build_object('exitNoteId', r.exit_note_id,
                                           'referenceOrder', r.reference_order)
                        order by r.reference_order, r.exit_note_id), '[]'::jsonb)
                    from core.note_source_exit_refs r where r.source_id = s.id),
                'invoices', (
                    select coalesce(jsonb_agg(
                        jsonb_build_object('id', i.id,
                            'invoiceNumber', i.invoice_number, 'invoiceDate', i.invoice_date,
                            'destination', i.destination, 'attachmentFileName', i.attachment_file_name,
                            'attachmentContentType', i.attachment_content_type,
                            'attachmentBytes', coalesce(octet_length(i.attachment_data), 0))
                        order by i.invoice_date nulls last, i.invoice_number, i.id
                    ), '[]'::jsonb)
                    from core.note_source_invoices i where i.source_id = s.id))
                ::text
            from core.note_sources s where s.id = ?
            """, (rs, row) -> rs.getString(1), sourceId);
        return values.isEmpty() ? null : values.get(0);
    }

    private void audit(UUID noteId, UUID actor, String action,
                       String before, String after) {
        jdbc.update("""
            insert into audit.logs(
                user_id, action_type, schema_name, table_name, record_id,
                description, old_value, new_value)
            values (?, ?, 'core', 'note_sources', ?,
                'تعديل مصدر مذكرة الإدخال مع أثر تدقيق كامل',
                cast(? as jsonb), cast(? as jsonb))
            """, actor, action, noteId, before, after);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<NoteSourceView> read(UUID noteId, UUID actor, boolean ownOnly) {
        NoteScope scope = note(noteId, actor, ownOnly, false);
        if (!"ENTRY".equals(scope.type())) return Optional.empty();
        var rows = jdbc.query("""
            select id, note_id, source_type, other_description, updated_at
            from core.note_sources where note_id = ?
            """, (rs, row) -> new Object[]{
                rs.getObject("id", UUID.class),
                rs.getObject("note_id", UUID.class),
                rs.getString("source_type"), rs.getString("other_description"),
                rs.getObject("updated_at", LocalDateTime.class)
            }, noteId);
        if (rows.isEmpty()) return Optional.empty();
        Object[] raw = rows.get(0);
        UUID source = (UUID) raw[0];
        List<NoteSourceView.ExitReferenceView> refs = jdbc.query("""
            select r.exit_note_id, r.reference_order, n.note_number
            from core.note_source_exit_refs r
            left join core.notes n on n.id = r.exit_note_id
            where r.source_id = ? order by r.reference_order, r.id
            """, (rs, row) -> new NoteSourceView.ExitReferenceView(
                rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3)), source);
        List<NoteSourceView.InvoiceView> invoices = jdbc.query("""
            select id, invoice_number, invoice_date, destination, attachment_file_name
            from core.note_source_invoices where source_id = ?
            order by invoice_date nulls last, invoice_number, id
            """, (rs, row) -> new NoteSourceView.InvoiceView(
                rs.getObject(1, UUID.class), rs.getString(2),
                rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate(),
                rs.getString(4), rs.getString(5),
                rs.getString(5) != null && !rs.getString(5).isBlank()), source);
        List<String> loot = jdbc.query(
            "select description from core.note_source_loot where source_id = ?",
            (rs, row) -> rs.getString(1), source);
        return Optional.of(new NoteSourceView(
            source, (UUID) raw[1], NoteSourceType.valueOf(
                ((String) raw[2]).substring(0, 1)
                + ((String) raw[2]).substring(1).toLowerCase(java.util.Locale.ROOT)),
            (String) raw[3], loot.isEmpty() ? null : loot.get(0),
            (LocalDateTime) raw[4], refs, invoices));
    }

    @Override
    @Transactional
    public void save(UUID noteId, UUID actor, boolean ownOnly, NoteSourceDraft draft) {
        // This row lock serializes all source updates on the same note.
        NoteScope scope = note(noteId, actor, ownOnly, true);
        if (!"ENTRY".equals(scope.type())) {
            throw new NoteCreateConflict("يمكن تعيين مصادر لمذكرات الإدخال فقط.");
        }
        if (draft.sourceType() == NoteSourceType.Exit) {
            List<UUID> ids = draft.exitReferences().stream()
                .map(NoteSourceDraft.ExitReference::exitNoteId).toList();
            var args = new MapSqlParameterSource()
                .addValue("ids", ids).addValue("company", scope.companyId());
            Integer count = named.queryForObject("""
                select count(*) from core.notes n
                join core.note_types nt on nt.id = n.note_type_id
                where n.id in (:ids) and not n.is_deleted
                  and n.company_id = :company and nt.code = 'EXIT'
                """, args, Integer.class);
            if (count == null || count != ids.size()) {
                throw new NoteCreateConflict(
                    "مذكرات الإخراج المرجعية يجب أن تكون فعالة ومن الشركة نفسها.");
            }
        }

        UUID oldId = sourceId(noteId);
        String before = snapshot(oldId);
        UUID source = jdbc.queryForObject("""
            insert into core.note_sources(
                note_id, source_type, other_description, created_by)
            values (?, ?, ?, ?)
            on conflict (note_id) do update set
                source_type = excluded.source_type,
                other_description = excluded.other_description,
                updated_at = now()
            returning id
            """, UUID.class, noteId, draft.sourceType().name().toUpperCase(
                java.util.Locale.ROOT),
            draft.sourceType() == NoteSourceType.Other
                ? draft.otherDescription().trim() : null,
            actor);

        jdbc.update("delete from core.note_source_exit_refs where source_id = ?", source);
        jdbc.update("delete from core.note_source_invoices where source_id = ?", source);
        jdbc.update("delete from core.note_source_loot where source_id = ?", source);

        if (draft.sourceType() == NoteSourceType.Exit) {
            for (int i = 0; i < draft.exitReferences().size(); i++) {
                jdbc.update("""
                    insert into core.note_source_exit_refs(
                        source_id, exit_note_id, reference_order)
                    values (?, ?, ?)
                    """, source, draft.exitReferences().get(i).exitNoteId(), i + 1);
            }
        } else if (draft.sourceType() == NoteSourceType.Invoice) {
            for (var invoice : draft.invoices()) {
                NoteAttachment attachment = invoice.attachment();
                String type = NoteAttachmentPolicy.validate(attachment);
                jdbc.update("""
                    insert into core.note_source_invoices(
                        source_id, invoice_number, invoice_date, destination,
                        attachment_file_name, attachment_content_type, attachment_data)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, source, invoice.invoiceNumber().trim(), invoice.invoiceDate(),
                    invoice.destination() == null ? null : invoice.destination().trim(),
                    attachment == null ? null : attachment.fileName(),
                    type, attachment == null ? null : attachment.bytes());
            }
        } else if (draft.sourceType() == NoteSourceType.Loot) {
            jdbc.update("""
                insert into core.note_source_loot(source_id, description)
                values (?, ?)
                """, source, draft.lootDescription().trim());
        }

        jdbc.update("update core.notes set is_loot = ? where id = ?",
            draft.sourceType() == NoteSourceType.Loot, noteId);
        audit(noteId, actor, "UPDATE_NOTE_SOURCE", before, snapshot(source));
    }

    @Override
    @Transactional
    public void remove(UUID noteId, UUID actor, boolean ownOnly) {
        NoteScope scope = note(noteId, actor, ownOnly, true);
        if (!"ENTRY".equals(scope.type())) {
            throw new NoteCreateConflict("يمكن إزالة مصادر مذكرات الإدخال فقط.");
        }
        UUID source = sourceId(noteId);
        if (source == null) throw new NoteCreateConflict("مصدر المذكرة غير موجود.");
        String before = snapshot(source);
        jdbc.update("delete from core.note_sources where id = ?", source);
        jdbc.update("update core.notes set is_loot = false where id = ?", noteId);
        audit(noteId, actor, "REMOVE_NOTE_SOURCE", before, null);
    }
}
