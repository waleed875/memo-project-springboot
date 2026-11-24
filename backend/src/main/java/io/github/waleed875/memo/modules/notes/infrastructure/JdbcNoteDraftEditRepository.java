package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.NoteAccessDenied;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditCommand;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditRepository;
import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveCandidate;
import io.github.waleed875.memo.modules.notes.domain.NoteSavePolicy;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import io.github.waleed875.memo.modules.notes.domain.RequiredNoteField;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
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
