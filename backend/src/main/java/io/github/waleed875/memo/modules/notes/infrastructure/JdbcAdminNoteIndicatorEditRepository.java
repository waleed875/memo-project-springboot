package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.AdminNoteIndicatorEditCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteIndicatorEditRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveCandidate;
import io.github.waleed875.memo.modules.notes.domain.NoteSavePolicy;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import io.github.waleed875.memo.modules.notes.domain.RequiredNoteField;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic and restricted C# administrator indicator-edit subset.
 *
 * Never deletes inactive/historical field values and never modifies workflow
 * status, reviewers, approvals, attachments, sources or counterpart links.
 * Requires the currently active ADMIN role and an exact note revision.
 */
@Repository
public class JdbcAdminNoteIndicatorEditRepository implements AdminNoteIndicatorEditRepository {
    private record Existing(
        UUID noteTypeId, UUID companyId, String noteNumber, LocalDate writingDate,
        LocalDate arrivalAt, NoteStatus status, UUID reviewerId,
        LocalDateTime reviewStartedAt, UUID approvedBy, LocalDateTime approvedAt,
        LocalDateTime updatedAt
    ) {}

    private final JdbcTemplate jdbc;

    public JdbcAdminNoteIndicatorEditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public LocalDateTime updateIndicators(
        AdminNoteIndicatorEditCommand command, UUID actorId, LocalDate operationalToday
    ) {
        requireActiveAdmin(actorId);
        Existing before = lockNote(command.noteId());
        if (!command.expectedUpdatedAt().equals(before.updatedAt())) {
            throw new NoteDraftEditConflict(
                "تعذر الحفظ لأن حالة المذكرة تغيّرت. أعد تحميلها ثم حاول مجدداً.");
        }
        // The existing C# path keeps status unchanged; when saveAsDraft=false
        // it rejects an existing DRAFT status. This internal command models
        // only that non-draft admin indicator update.
        if (before.status() == NoteStatus.DRAFT) {
            throw new NoteSaveValidationException(
                "تعديل مؤشرات المسودة الإدارية غير متاح بهذا الأمر المحدود.");
        }

        // Shared transaction-scoped lock from the legacy C# NoteTypeService and
        // our JdbcNoteDefinitionWriter, seed 712. Note row is always locked first.
        String lockKey = "note-type-fields:" +
            before.noteTypeId().toString().replace("-", "");
        jdbc.queryForObject(
            "select pg_advisory_xact_lock(hashtextextended(?, 712))",
            (rs, row) -> rs.getObject(1), lockKey);

        List<RequiredNoteField> definitions = jdbc.query("""
            select nf.id, nf.name_ar, ntf.is_required,
                   (ntf.is_active and nf.is_active) as effective_active
              from core.note_type_fields ntf
              join core.note_fields nf on nf.id = ntf.field_id
             where ntf.note_type_id = ?
               and not ntf.is_deleted and not nf.is_deleted
             order by ntf.sort_order, nf.id
             for share of ntf, nf
            """, (rs, row) -> new RequiredNoteField(
                rs.getObject("id", UUID.class), rs.getString("name_ar"),
                rs.getBoolean("is_required"), rs.getBoolean("effective_active")
            ), before.noteTypeId());
        Set<UUID> activeIds = new HashSet<>();
        for (RequiredNoteField definition : definitions) {
            if (definition.isActive()) activeIds.add(definition.fieldId());
        }

        // Matching C# SaveNoteAsync: filter client values to effective fields,
        // validate required fields against that submitted active subset, but
        // DO NOT delete historical values that are no longer active.
        List<AdminNoteIndicatorEditCommand.IndicatorInput> activeValues =
            command.fieldValues().stream()
                .filter(item -> item != null && activeIds.contains(item.fieldId()))
                .toList();
        List<NoteSaveCandidate.IndicatorInput> validationValues =
            activeValues.stream()
                .map(item -> new NoteSaveCandidate.IndicatorInput(
                    item.fieldId(), item.value()))
                .toList();
        var candidate = new NoteSaveCandidate(
            before.noteNumber(), before.companyId(), before.noteTypeId(),
            null, null, before.writingDate(), before.arrivalAt(), false,
            command.markAllIndicatorsCorrect(), validationValues);
        NoteSavePolicy.validate(candidate, definitions, operationalToday);

        // A note is already locked, but explicitly lock rows for snapshot
        // consistency and orderly writes. Original C# preserves obsolete rows.
        jdbc.query("""
            select field_id from core.note_field_values
             where note_id = ? order by field_id for update
            """, (rs, row) -> rs.getObject(1, UUID.class), command.noteId());
        String previousIndicators = indicatorSnapshot(command.noteId());

        int count = jdbc.update("""
            update core.notes
               set updated_by = ?
             where id = ? and not is_deleted and updated_at = ?
            """, actorId, command.noteId(), command.expectedUpdatedAt());
        if (count != 1) throw new NoteDraftEditConflict("تغيرت المذكرة أثناء الحفظ.");

        for (var item : activeValues) {
            jdbc.update("""
                insert into core.note_field_values (
                    id, note_id, field_id, field_value,
                    inactive_reason, created_by, updated_by
                ) values (?, ?, ?, ?, ?, ?, ?)
                on conflict (note_id, field_id) do update set
                    field_value = excluded.field_value,
                    inactive_reason = excluded.inactive_reason,
                    updated_by = excluded.updated_by, updated_at = now()
                """, UUID.randomUUID(), command.noteId(), item.fieldId(),
                (short) item.value().ordinal(), item.inactiveReason(), actorId, actorId);
        }

        String currentIndicators = indicatorSnapshot(command.noteId());
        LocalDateTime nextVersion = jdbc.queryForObject(
            "select updated_at from core.notes where id = ?",
            LocalDateTime.class, command.noteId());

        // Audit metadata demonstrates the admin edit has preserved the review
        // and approval state. Review history remains unchanged.
        jdbc.update("""
            insert into audit.logs(
                user_id, action_type, schema_name, table_name, record_id,
                description, old_value, new_value
            ) values (
                ?, 'ADMIN_EDIT_NOTE', 'core', 'notes', ?,
                'تعديل إداري للمؤشرات مع الحفاظ على سير العمل',
                jsonb_build_object(
                    'status', ?, 'reviewerId', cast(? as text),
                    'reviewStartedAt', cast(? as timestamp),
                    'approvedBy', cast(? as text), 'approvedAt', cast(? as timestamp),
                    'updatedAt', cast(? as timestamp), 'indicators', cast(? as jsonb)),
                jsonb_build_object(
                    'status', ?, 'reviewerId', cast(? as text),
                    'reviewStartedAt', cast(? as timestamp),
                    'approvedBy', cast(? as text), 'approvedAt', cast(? as timestamp),
                    'updatedAt', cast(? as timestamp), 'indicators', cast(? as jsonb))
            )
            """, actorId, command.noteId(),
            before.status().name(), asText(before.reviewerId()),
            before.reviewStartedAt(), asText(before.approvedBy()),
            before.approvedAt(), before.updatedAt(), previousIndicators,
            before.status().name(), asText(before.reviewerId()),
            before.reviewStartedAt(), asText(before.approvedBy()),
            before.approvedAt(), nextVersion, currentIndicators);
        jdbc.update("""
            insert into audit.logs(
                user_id, action_type, schema_name, table_name,
                record_id, description, old_value, new_value
            ) values (
                ?, 'SAVE_INDICATORS', 'core', 'note_field_values', ?,
                'تحديث مؤشرات المذكرة بواسطة المدير',
                cast(? as jsonb), cast(? as jsonb))
            """, actorId, command.noteId(), previousIndicators, currentIndicators);
        if (command.markAllIndicatorsCorrect()) {
            jdbc.update("""
                insert into audit.logs(
                    user_id, action_type, schema_name, table_name,
                    record_id, description
                ) values (?, 'MARK_ALL_INDICATORS_CORRECT', 'core',
                          'note_field_values', ?, 'اعتبار جميع المؤشرات صحيحة')
                """, actorId, command.noteId());
        }
        return nextVersion;
    }

    private Existing lockNote(UUID noteId) {
        List<Existing> rows = jdbc.query("""
            select note_type_id, company_id, note_number, writing_date, arrival_at,
                   status, reviewer_id, review_started_at, approved_by, approved_at,
                   updated_at
              from core.notes
             where id = ? and not is_deleted for update
            """, (rs, row) -> new Existing(
                rs.getObject("note_type_id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("note_number"),
                rs.getObject("writing_date", LocalDate.class),
                rs.getObject("arrival_at", LocalDate.class),
                NoteStatus.valueOf(rs.getString("status")),
                rs.getObject("reviewer_id", UUID.class),
                rs.getObject("review_started_at", LocalDateTime.class),
                rs.getObject("approved_by", UUID.class),
                rs.getObject("approved_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
            ), noteId);
        if (rows.isEmpty()) throw new NoteDraftEditConflict("المذكرة غير موجودة أو حُذفت.");
        return rows.getFirst();
    }

    private void requireActiveAdmin(UUID actorId) {
        List<String> roles = jdbc.query("""
            select r.code
              from auth.users u join auth.roles r on r.id = u.role_id
             where u.id = ? and u.is_active and not u.is_deleted and r.is_active
             for share of u, r
            """, (rs, row) -> rs.getString(1), actorId);
        if (roles.size() != 1 || !"ADMIN".equals(roles.getFirst())) {
            throw new MasterDataForbiddenException();
        }
    }

    private String indicatorSnapshot(UUID noteId) {
        return jdbc.queryForObject("""
            select coalesce(jsonb_object_agg(
                field_id::text, jsonb_build_object(
                    'value', field_value, 'inactiveReason', inactive_reason)
            ), '{}'::jsonb)::text
            from core.note_field_values where note_id = ?
            """, String.class, noteId);
    }

    private static String asText(UUID value) {
        return value == null ? null : value.toString();
    }
}
