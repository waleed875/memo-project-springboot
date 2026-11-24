package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.NoteReviewConflict;
import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import io.github.waleed875.memo.modules.notes.domain.NoteApprovalPolicy;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveCandidate;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import io.github.waleed875.memo.modules.notes.domain.RequiredNoteField;
import io.github.waleed875.memo.modules.notes.application.NoteReviewTransitionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each transition, history event and JSONB audit is an indivisible transaction.
 * Database actor role is rechecked at write time. Row-level locking serializes
 * concurrent reviewers and makes failures side-effect-free.
 */
@Repository
public class JdbcNoteReviewTransitionRepository implements NoteReviewTransitionRepository {
    private record State(String status, UUID reviewerId, UUID createdBy,
                         LocalDateTime updatedAt, LocalDateTime reviewStartedAt,
                         String returnReason, UUID approvedBy, LocalDateTime approvedAt) {}

    private record ApprovalSnapshot(UUID noteTypeId, UUID companyId, String noteNumber,
                                    LocalDate writingDate, LocalDate arrivalAt) {}

    private final JdbcTemplate jdbc;

    public JdbcNoteReviewTransitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void start(UUID noteId, UUID actorId, LocalDateTime expectedUpdatedAt) {
        requireActiveRole(actorId, false);
        State prior = lock(noteId, expectedUpdatedAt);
        if (actorId.equals(prior.createdBy()) || prior.reviewerId() != null
            || !List.of("COMPLETE", "HAS_ERRORS", "NEEDS_REVIEW").contains(prior.status())) {
            throw new NoteReviewConflict(
                "تعذر بدء التدقيق لأن المذكرة حُجزت أو تغيرت حالتها بواسطة مستخدم آخر.");
        }
        int count = jdbc.update("""
            update core.notes
               set status = 'UNDER_REVIEW', reviewer_id = ?, review_started_at = now(),
                   return_reason = null, updated_by = ?
             where id = ? and not is_deleted
               and (created_by is null or created_by <> ?)
               and reviewer_id is null
               and status in ('COMPLETE', 'HAS_ERRORS', 'NEEDS_REVIEW')
            """, actorId, actorId, noteId, actorId);
        ensureSingle(count);
        record(noteId, actorId, "START_REVIEW", null, "بدء تدقيق مذكرة", prior);
    }

    @Override
    @Transactional
    public void returnForCorrection(UUID noteId, UUID actorId, String reason,
                                    LocalDateTime expectedUpdatedAt) {
        requireActiveRole(actorId, false);
        State prior = lock(noteId, expectedUpdatedAt);
        if (!"UNDER_REVIEW".equals(prior.status()) || !actorId.equals(prior.reviewerId())) {
            throw new NoteReviewConflict("لا يمكن إعادة المذكرة لأنها ليست محجوزة للتدقيق من قبلك.");
        }
        int count = jdbc.update("""
            update core.notes
               set status = 'RETURNED_FOR_CORRECTION', reviewer_id = null,
                   review_started_at = null, return_reason = ?, updated_by = ?
             where id = ? and not is_deleted
               and status = 'UNDER_REVIEW' and reviewer_id = ?
            """, reason, actorId, noteId, actorId);
        ensureSingle(count);
        record(noteId, actorId, "RETURN_FOR_CORRECTION", reason, reason, prior);
    }

    @Override
    @Transactional
    public void release(UUID noteId, UUID actorId, String reason) {
        requireActiveRole(actorId, true);
        State prior = lock(noteId, null);
        if (!"UNDER_REVIEW".equals(prior.status())) {
            throw new NoteReviewConflict("المذكرة ليست قيد مراجعة حالياً.");
        }
        int count = jdbc.update("""
            update core.notes
               set status = 'NEEDS_REVIEW', reviewer_id = null,
                   review_started_at = null, updated_by = ?
             where id = ? and not is_deleted and status = 'UNDER_REVIEW'
            """, actorId, noteId);
        ensureSingle(count);
        // The original ADMIN_RELEASE_REVIEW preserves the return_reason column.
        record(noteId, actorId, "ADMIN_RELEASE_REVIEW", reason, reason, prior);
    }


    @Override
    @Transactional
    public void approve(UUID noteId, UUID actorId, LocalDateTime expectedUpdatedAt) {
        requireActiveRole(actorId, false);
        State before = lock(noteId, expectedUpdatedAt);
        // Security decisions happen before loading indicator values, but the
        // final domain policy checks those decisions again with persisted data.
        if (actorId.equals(before.createdBy())) {
            throw new NoteSaveValidationException(
                "لا يمكن اعتماد المذكرة من المستخدم الذي أدخلها.");
        }
        if (!"UNDER_REVIEW".equals(before.status()) || !actorId.equals(before.reviewerId())) {
            throw new NoteReviewConflict(
                "يجب أن تكون المذكرة محجوزة للتدقيق من قبلك قبل اعتمادها.");
        }

        ApprovalSnapshot snapshot = jdbc.queryForObject("""
            select note_type_id, company_id, note_number, writing_date, arrival_at
              from core.notes
             where id = ? and not is_deleted
            """, (rs, row) -> new ApprovalSnapshot(
                rs.getObject("note_type_id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("note_number"),
                rs.getObject("writing_date", LocalDate.class),
                rs.getObject("arrival_at", LocalDate.class)
            ), noteId);
        if (snapshot == null) throw new NoteReviewConflict("المذكرة غير موجودة أو حُذفت.");

        // Block admin changes to effective definitions while validation and the
        // approval transaction are in flight. Soft-deleted rows never participate.
        List<RequiredNoteField> active = jdbc.query("""
            select nf.id, nf.name_ar, ntf.is_required
              from core.note_type_fields ntf
              join core.note_fields nf on nf.id = ntf.field_id
             where ntf.note_type_id = ?
               and ntf.is_active and nf.is_active
               and not ntf.is_deleted and not nf.is_deleted
             order by ntf.sort_order, nf.id
             for share of ntf, nf
            """, (rs, row) -> new RequiredNoteField(
                rs.getObject("id", UUID.class),
                rs.getString("name_ar"), rs.getBoolean("is_required"), true),
            snapshot.noteTypeId());

        // Validate all stored values, including inactive/obsolete field IDs:
        // original C# NoteValidator checks enum validity for every value.
        List<NoteSaveCandidate.IndicatorInput> indicators = jdbc.query("""
            select field_id, field_value
              from core.note_field_values
             where note_id = ?
             order by field_id
             for share
            """, (rs, row) -> {
                try {
                    return new NoteSaveCandidate.IndicatorInput(
                        rs.getObject("field_id", UUID.class),
                        IndicatorValue.fromDatabase(rs.getShort("field_value")));
                } catch (IllegalArgumentException invalidCode) {
                    throw new NoteSaveValidationException("قيمة البند غير صحيحة.");
                }
            }, noteId);

        NoteSaveCandidate persisted = new NoteSaveCandidate(
            snapshot.noteNumber(), snapshot.companyId(), snapshot.noteTypeId(),
            null, null, snapshot.writingDate(), snapshot.arrivalAt(),
            false, false, indicators);
        NoteApprovalPolicy.validate(
            actorId, before.createdBy(), before.reviewerId(),
            NoteStatus.valueOf(before.status()), persisted, active,
            LocalDate.now(ZoneId.of("Asia/Damascus")));

        int updated = jdbc.update("""
            update core.notes
               set status = 'APPROVED', approved_by = ?, approved_at = now(),
                   updated_by = ?, reviewer_id = null, return_reason = null
             where id = ? and not is_deleted
               and status = 'UNDER_REVIEW' and reviewer_id = ?
               and (created_by is null or created_by <> ?)
            """, actorId, actorId, noteId, actorId, actorId);
        ensureSingle(updated);
        // Keep review_started_at: migration 033 defines it as the approval cycle.
        record(noteId, actorId, "APPROVE", "APPROVE_NOTE", null, "اعتماد مذكرة", before);
    }

    private void requireActiveRole(UUID actorId, boolean adminOnly) {
        List<String> roles = jdbc.query("""
            select r.code
              from auth.users u join auth.roles r on r.id = u.role_id
             where u.id = ? and u.is_active and not u.is_deleted and r.is_active
            for share of u, r
            """, (rs, row) -> rs.getString(1), actorId);
        if (roles.size() != 1 || !(adminOnly
            ? "ADMIN".equals(roles.getFirst())
            : "ADMIN".equals(roles.getFirst()) || "REVIEWER".equals(roles.getFirst()))) {
            throw new MasterDataForbiddenException();
        }
    }

    private State lock(UUID noteId, LocalDateTime expectedUpdatedAt) {
        List<State> states = jdbc.query("""
            select status, reviewer_id, created_by, updated_at, review_started_at, return_reason,
                   approved_by, approved_at
              from core.notes
             where id = ? and not is_deleted
             for update
            """, (rs, row) -> new State(
                rs.getString("status"),
                rs.getObject("reviewer_id", UUID.class),
                rs.getObject("created_by", UUID.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("review_started_at", LocalDateTime.class),
                rs.getString("return_reason"),
                rs.getObject("approved_by", UUID.class),
                rs.getObject("approved_at", LocalDateTime.class)
            ), noteId);
        if (states.isEmpty()) {
            throw new NoteReviewConflict("المذكرة غير موجودة أو حُذفت.");
        }
        State state = states.getFirst();
        if (expectedUpdatedAt != null && !expectedUpdatedAt.equals(state.updatedAt())) {
            throw new NoteReviewConflict("تغيرت المذكرة منذ آخر تحميل. أعد تحميلها ثم حاول مجدداً.");
        }
        return state;
    }

    private void ensureSingle(int updated) {
        if (updated != 1) {
            throw new NoteReviewConflict("تغيرت حالة المذكرة أثناء المراجعة.");
        }
    }

    private void record(UUID noteId, UUID actorId, String action, String reason,
                        String description, State before) {
        record(noteId, actorId, action, action, reason, description, before);
    }

    private void record(UUID noteId, UUID actorId, String action, String auditAction,
                        String reason, String description, State before) {
        jdbc.update("""
            insert into core.note_review_history(note_id, action, reason, user_id)
            values (?, ?, ?, ?)
            """, noteId, action, reason, actorId);

        // Select after the UPDATE in the same transaction; the original database
        // updated_at trigger and other triggers are reflected in the audit.
        State after = lock(noteId, null);
        jdbc.update("""
            insert into audit.logs (
                user_id, action_type, schema_name, table_name, record_id,
                description, old_value, new_value
            ) values (
                ?, ?, 'core', 'notes', ?, ?,
                jsonb_build_object(
                    'status', ?, 'reviewerId', cast(? as text),
                    'reviewStartedAt', cast(? as timestamp),
                    'returnReason', cast(? as text), 'updatedAt', cast(? as timestamp),
                    'approvedBy', cast(? as text), 'approvedAt', cast(? as timestamp)
                ),
                jsonb_build_object(
                    'status', ?, 'reviewerId', cast(? as text),
                    'reviewStartedAt', cast(? as timestamp),
                    'returnReason', cast(? as text), 'updatedAt', cast(? as timestamp),
                    'approvedBy', cast(? as text), 'approvedAt', cast(? as timestamp)
                )
            )
            """,
            actorId, auditAction, noteId, description,
            before.status(), asString(before.reviewerId()),
            before.reviewStartedAt(), before.returnReason(), before.updatedAt(),
            asString(before.approvedBy()), before.approvedAt(),
            after.status(), asString(after.reviewerId()),
            after.reviewStartedAt(), after.returnReason(), after.updatedAt(),
            asString(after.approvedBy()), after.approvedAt());
    }

    private String asString(UUID id) {
        return id == null ? null : id.toString();
    }
}
