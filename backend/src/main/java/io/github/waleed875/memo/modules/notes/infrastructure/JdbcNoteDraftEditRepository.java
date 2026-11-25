package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.NoteAccessDenied;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditCommand;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditRepository;
import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveCandidate;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachmentPolicy;
import io.github.waleed875.memo.modules.notes.domain.NoteSavePolicy;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import io.github.waleed875.memo.modules.notes.domain.RequiredNoteField;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restricted, atomic DATA_ENTRY draft/resubmission core (internal only).
 * No number, catalog, attachment, source, flag or link columns are mutated.
 * All status, indicator, review-history and audit changes succeed or roll back.
 */
@Repository
public class JdbcNoteDraftEditRepository implements NoteDraftEditRepository {
    private record Existing(
        String number, UUID companyId, UUID typeId, UUID factoryId, UUID lineId,
        LocalDate writingDate, LocalDate arrivalAt,
        String notes, NoteStatus status, UUID createdBy, LocalDateTime updatedAt
    ) {}

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public JdbcNoteDraftEditRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    @Override
    @Transactional
    public EditResult edit(NoteDraftEditCommand command, UUID actor, LocalDate today) {
        requireActiveDataEntry(actor);
        // Every Spring note/link/source mutation takes this lock before notes FOR UPDATE.
        NoteLinkWriteLock.acquire(jdbc);
        Existing existing = lock(command.noteId());
        if (!actor.equals(existing.createdBy())) throw new NoteAccessDenied();
        if (!command.expectedUpdatedAt().equals(existing.updatedAt())) {
            throw new NoteDraftEditConflict(
                "تعذر الحفظ لأن حالة المذكرة تغيّرت. أعد تحميلها ثم حاول مجدداً.");
        }
        if (existing.status() != NoteStatus.DRAFT
            && existing.status() != NoteStatus.RETURNED_FOR_CORRECTION) {
            throw new NoteDraftEditConflict(
                "لا يمكن تعديل المذكرة بعد إرسالها للتدقيق إلا بعد إعادتها للتصحيح.");
        }

        // Verify that the submitted link snapshot still matches the current
        // persisted set. Source-side trigger updates need not touch notes.updated_at.
        List<UUID> originalLinks = command.synchronizesLinks()
            ? counterpartIds(command.noteId()) : List.of();
        if (command.synchronizesLinks()
            && !new HashSet<>(originalLinks).equals(
                new HashSet<>(command.expectedRelatedNoteIds()))) {
            throw new NoteDraftEditConflict(
                "تغيرت الروابط منذ آخر تحميل؛ أعد تحميل المذكرة قبل حفظها.");
        }
        if (command.synchronizesLinks()) {
            validateRelatedNotes(command.noteId(), existing,
                command.relatedNoteIds());
        }
        // Lock against concurrent master-data definition changes with the
        // exact existing key + seed from JdbcNoteDefinitionWriter and JdbcNoteCreator.
        String key = "note-type-fields:" + existing.typeId().toString().replace("-", "");
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 712))",
            (rs, row) -> rs.getObject(1), key);

        List<RequiredNoteField> definitions = jdbc.query("""
            select nf.id, nf.name_ar, ntf.is_required,
                   (ntf.is_active and nf.is_active) as effective_active
              from core.note_type_fields ntf
              join core.note_fields nf on nf.id = ntf.field_id
             where ntf.note_type_id = ? and not ntf.is_deleted and not nf.is_deleted
             order by ntf.sort_order, nf.id
             for share of ntf, nf
            """, (rs, row) -> new RequiredNoteField(
                rs.getObject("id", UUID.class), rs.getString("name_ar"),
                rs.getBoolean("is_required"), rs.getBoolean("effective_active")
            ), existing.typeId());

        Set<UUID> active = new HashSet<>();
        for (var definition : definitions) {
            if (definition.isActive()) active.add(definition.fieldId());
        }
        // C# SaveNote filters incoming indicators by currently effective fields.
        List<NoteDraftEditCommand.IndicatorInput> values = command.fieldValues().stream()
            .filter(v -> v != null && active.contains(v.fieldId())).toList();
        var baseIndicators = values.stream()
            .map(v -> new NoteSaveCandidate.IndicatorInput(v.fieldId(), v.value())).toList();
        var candidate = new NoteSaveCandidate(
            existing.number(), existing.companyId(), existing.typeId(),
            existing.factoryId(), existing.lineId(), existing.writingDate(),
            existing.arrivalAt(), command.saveAsDraft(), command.markAllIndicatorsCorrect(),
            baseIndicators);
        NoteSavePolicy.validate(candidate, definitions, today);
        NoteStatus status = NoteSavePolicy.calculateStatus(
            definitions, baseIndicators, command.saveAsDraft());
        if (!command.saveAsDraft() && status == NoteStatus.DRAFT) {
            throw new NoteSaveValidationException(
                "لا يمكن إرسال المذكرة قبل مراجعة جميع البنود الإلزامية.");
        }

        // Lock rows before replacing values to serialize with concurrent
        // indicator edits. Obsolete field values are removed, matching legacy
        // non-admin SaveNote behavior.
        jdbc.query("""
            select field_id from core.note_field_values
            where note_id = ? order by field_id for update
            """, (rs, row) -> rs.getObject(1, UUID.class), command.noteId());
        String beforeIndicators = indicatorSnapshot(command.noteId());
        int updated = jdbc.update("""
            update core.notes
               set notes = ?, status = ?, reviewer_id = null,
                   review_started_at = null, return_reason = null, updated_by = ?
             where id = ? and not is_deleted and created_by = ?
               and updated_at = ?
               and status in ('DRAFT', 'RETURNED_FOR_CORRECTION')
            """, command.notes(), status.name(), actor, command.noteId(), actor,
            command.expectedUpdatedAt());
        if (updated != 1) {
            throw new NoteDraftEditConflict("تغيرت حالة المذكرة أثناء الحفظ.");
        }

        if (values.isEmpty()) {
            jdbc.update("delete from core.note_field_values where note_id = ?", command.noteId());
        } else {
            var parameters = new MapSqlParameterSource()
                .addValue("noteId", command.noteId())
                .addValue("fieldIds", values.stream().map(NoteDraftEditCommand.IndicatorInput::fieldId).toList());
            named.update("""
                delete from core.note_field_values
                 where note_id = :noteId and field_id not in (:fieldIds)
                """, parameters);
        }
        for (var v : values) {
            jdbc.update("""
                insert into core.note_field_values(
                    id, note_id, field_id, field_value, inactive_reason,
                    created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (note_id, field_id) do update set
                    field_value = excluded.field_value,
                    inactive_reason = excluded.inactive_reason,
                    updated_by = excluded.updated_by, updated_at = now()
                """, UUID.randomUUID(), command.noteId(), v.fieldId(),
                (short) v.value().ordinal(), v.inactiveReason(), actor, actor);
        }

        String afterIndicators = indicatorSnapshot(command.noteId());

        // Save new owner attachment without deleting historical attachments.
        // All bytes, primary fields and metadata audit belong to this same TX.
        if (command.attachment() != null) {
            String mime = NoteAttachmentPolicy.validate(command.attachment());
            byte[] bytes = command.attachment().bytes();
            UUID attachmentId = UUID.randomUUID();
            jdbc.update("""
                insert into core.note_attachments(
                    id, note_id, file_name, content_type, file_data,
                    file_size, created_by)
                values (?, ?, ?, ?, ?, ?, ?)
                """, attachmentId, command.noteId(), command.attachment().fileName(),
                mime, bytes, (long) bytes.length, actor);
            jdbc.update("""
                update core.notes
                set attachment_file_name = ?, attachment_content_type = ?,
                    attachment_data = ?, updated_by = ?
                where id = ? and not is_deleted
                """, command.attachment().fileName(), mime, bytes, actor,
                command.noteId());
            jdbc.update("""
                insert into audit.logs(
                    user_id, action_type, schema_name, table_name, record_id,
                    description, new_value)
                values (?, 'NOTE_ATTACHMENT_UPLOADED', 'core', 'note_attachments',
                    ?, 'إضافة مرفق ضمن حفظ مسودة المذكرة',
                    jsonb_build_object('noteId', cast(? as text),
                      'attachmentId', cast(? as text),
                      'fileName', ?, 'contentType', ?, 'fileSize', ?))
                """, actor, attachmentId, command.noteId().toString(),
                attachmentId.toString(), command.attachment().fileName(), mime,
                (long) bytes.length);
        }

        if (command.synchronizesLinks()) {
            Set<UUID> previous = new HashSet<>(originalLinks);
            Set<UUID> desired = new HashSet<>(command.relatedNoteIds());
            for (UUID target : originalLinks) {
                if (desired.contains(target)) continue;
                int removed = jdbc.update("""
                    delete from core.note_links
                    where (source_note_id = ? and related_note_id = ?)
                       or (related_note_id = ? and source_note_id = ?)
                    """, command.noteId(), target, command.noteId(), target);
                if (removed != 1) {
                    throw new NoteDraftEditConflict(
                        "تغيرت روابط المذكرة أثناء حذف الرابط.");
                }
                auditLink(command.noteId(), target, actor, "UNLINK_NOTES");
            }
            for (UUID target : new TreeSet<>(desired)) {
                if (previous.contains(target)) continue;
                int inserted = jdbc.update("""
                    insert into core.note_links(
                        source_note_id, related_note_id, created_by)
                    values (?, ?, ?) on conflict do nothing
                    """, command.noteId(), target, actor);
                if (inserted != 1) {
                    throw new NoteDraftEditConflict(
                        "تعارض في إضافة رابط مذكرة موجود بالفعل.");
                }
                auditLink(command.noteId(), target, actor, "LINK_NOTES");
            }
            if (!new HashSet<>(counterpartIds(command.noteId())).equals(desired)) {
                throw new NoteDraftEditConflict("تعذر مزامنة روابط المذكرة.");
            }
        }
        String historyAction = command.saveAsDraft()
            ? "SAVE_DRAFT"
            : existing.status() == NoteStatus.RETURNED_FOR_CORRECTION
                ? "RESUBMIT_FOR_REVIEW" : "SUBMIT_FOR_REVIEW";
        jdbc.update("""
            insert into core.note_review_history(note_id, action, user_id)
            values (?, ?, ?)
            """, command.noteId(), historyAction, actor);

        // Historical media/source/link metadata are intentionally unchanged.
        jdbc.update("""
            insert into audit.logs (
                user_id, action_type, schema_name, table_name, record_id,
                description, old_value, new_value
            ) values (
                ?, 'UPDATE_NOTE', 'core', 'notes', ?, 'تعديل نص المذكرة وحالة التدقيق',
                jsonb_build_object('status', ?, 'notes', cast(? as text), 'updatedAt', cast(? as timestamp)),
                jsonb_build_object('status', ?, 'notes', cast(? as text), 'updatedAt',
                    (select updated_at from core.notes where id = ?))
            )
            """, actor, command.noteId(),
            existing.status().name(), existing.notes(), existing.updatedAt(),
            status.name(), command.notes(), command.noteId());

        jdbc.update("""
            insert into audit.logs (
                user_id, action_type, schema_name, table_name, record_id,
                description, old_value, new_value
            ) values (
                ?, 'SAVE_INDICATORS', 'core', 'note_field_values', ?,
                'تحديث مؤشرات المذكرة',
                cast(? as jsonb), cast(? as jsonb)
            )
            """, actor, command.noteId(), beforeIndicators, afterIndicators);
        if (command.markAllIndicatorsCorrect()) {
            jdbc.update("""
                insert into audit.logs (
                    user_id, action_type, schema_name, table_name, record_id, description
                ) values (?, 'MARK_ALL_INDICATORS_CORRECT',
                          'core', 'note_field_values', ?, 'اعتبار جميع المؤشرات صحيحة')
                """, actor, command.noteId());
        }
        LocalDateTime newVersion = jdbc.queryForObject(
            "select updated_at from core.notes where id = ?",
            LocalDateTime.class, command.noteId());
        return new EditResult(command.noteId(), status, newVersion);
    }


    private List<UUID> counterpartIds(UUID noteId) {
        List<UUID> results = jdbc.query("""
            select case when source_note_id = ? then related_note_id
                        else source_note_id end as counterpart
            from core.note_links
            where source_note_id = ? or related_note_id = ?
            order by counterpart
            """, (rs, row) -> rs.getObject(1, UUID.class),
            noteId, noteId, noteId);
        if (new HashSet<>(results).size() != results.size()) {
            throw new NoteDraftEditConflict("روابط المذكرة الحالية غير متسقة.");
        }
        return results;
    }

    private void validateRelatedNotes(
        UUID noteId, Existing note, List<UUID> targets
    ) {
        if (targets.size() > 100) {
            throw new NoteSaveValidationException("عدد المذكرات المرتبطة يتجاوز الحد.");
        }
        if (targets.isEmpty()) return;
        List<String> types = jdbc.query("""
            select code from core.note_types where id = ?
            """, (rs, i) -> rs.getString(1), note.typeId());
        if (types.size() != 1 || !List.of("ENTRY", "EXIT").contains(types.getFirst())) {
            throw new NoteSaveValidationException(
                "الربط متاح فقط بين مذكرات الإدخال والإخراج.");
        }
        String otherType = types.getFirst().equals("ENTRY") ? "EXIT" : "ENTRY";
        // Lock affected targets in a deterministic order within the shared lock.
        var all = new TreeSet<UUID>(targets);
        var params = new MapSqlParameterSource("ids", new ArrayList<>(all));
        List<UUID> valid = named.query("""
            select n.id from core.notes n
              join core.note_types nt on nt.id = n.note_type_id
            where n.id in (:ids) and not n.is_deleted
              and n.company_id = :company and nt.code = :type
              and not (nt.code = 'ENTRY' and n.is_loot)
            order by n.id for share of n
            """, params.addValue("company", note.companyId())
                .addValue("type", otherType),
            (rs, i) -> rs.getObject(1, UUID.class));
        if (valid.size() != all.size()) {
            throw new NoteSaveValidationException(
                "الروابط يجب أن تكون مع مذكرات غير محذوفة من الشركة نفسها والنوع المقابل.");
        }
        Boolean loot = jdbc.queryForObject(
            "select is_loot from core.notes where id = ?", Boolean.class, noteId);
        if (Boolean.TRUE.equals(loot)) {
            throw new NoteSaveValidationException("لا يمكن ربط مذكرة غنائم بمذكرة إخراج.");
        }
    }

    private void auditLink(UUID noteId, UUID target, UUID actor, String action) {
        jdbc.update("""
            insert into audit.logs(
                user_id, action_type, schema_name, table_name, record_id,
                description, new_value)
            values (?, ?, 'core', 'note_links', ?,
                'مزامنة روابط المذكرة ضمن إعادة الحفظ',
                jsonb_build_object('noteId', cast(? as text),
                                   'relatedNoteId', cast(? as text),
                                   'operation', ?))
            """, actor, action, noteId, noteId.toString(), target.toString(), action);
    }

    private void requireActiveDataEntry(UUID actor) {
        List<String> roles = jdbc.query("""
            select r.code from auth.users u
            join auth.roles r on r.id = u.role_id
            where u.id = ? and u.is_active and not u.is_deleted and r.is_active
            for share of u, r
            """, (rs, row) -> rs.getString(1), actor);
        if (roles.size() != 1 || !"DATA_ENTRY".equals(roles.getFirst())) {
            throw new MasterDataForbiddenException();
        }
    }

    private Existing lock(UUID id) {
        List<Existing> notes = jdbc.query("""
            select note_number, company_id, note_type_id, factory_id, production_line_id,
                   writing_date, arrival_at, notes, status, created_by, updated_at
              from core.notes where id = ? and not is_deleted for update
            """, (rs, row) -> new Existing(
                rs.getString("note_number"),
                rs.getObject("company_id", UUID.class),
                rs.getObject("note_type_id", UUID.class),
                rs.getObject("factory_id", UUID.class),
                rs.getObject("production_line_id", UUID.class),
                rs.getObject("writing_date", LocalDate.class),
                rs.getObject("arrival_at", LocalDate.class),
                rs.getString("notes"),
                NoteStatus.valueOf(rs.getString("status")),
                rs.getObject("created_by", UUID.class),
                rs.getObject("updated_at", LocalDateTime.class)
            ), id);
        if (notes.isEmpty()) throw new NoteDraftEditConflict("المذكرة غير موجودة أو حُذفت.");
        return notes.getFirst();
    }

    private String indicatorSnapshot(UUID noteId) {
        return jdbc.queryForObject("""
            select coalesce(jsonb_object_agg(
                field_id::text,
                jsonb_build_object('value', field_value, 'inactiveReason', inactive_reason)
            ), '{}'::jsonb)::text
            from core.note_field_values
            where note_id = ?
            """, String.class, noteId);
    }
}
