package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.NoteReviewConflict;
import io.github.waleed875.memo.modules.notes.application.NoteReviewTransitionRepository;
import java.time.LocalDateTime;
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
                         String returnReason) {}

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
            select status, reviewer_id, created_by, updated_at, review_started_at, return_reason
              from core.notes
             where id = ? and not is_deleted
             for update
            """, (rs, row) -> new State(
                rs.getString("status"),
                rs.getObject("reviewer_id", UUID.class),
                rs.getObject("created_by", UUID.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("review_started_at", LocalDateTime.class),
                rs.getString("return_reason")
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
                    'returnReason', cast(? as text), 'updatedAt', cast(? as timestamp)
                ),
                jsonb_build_object(
                    'status', ?, 'reviewerId', cast(? as text),
                    'reviewStartedAt', cast(? as timestamp),
                    'returnReason', cast(? as text), 'updatedAt', cast(? as timestamp)
                )
            )
            """,
            actorId, action, noteId, description,
            before.status(), asString(before.reviewerId()),
            before.reviewStartedAt(), before.returnReason(), before.updatedAt(),
            after.status(), asString(after.reviewerId()),
            after.reviewStartedAt(), after.returnReason(), after.updatedAt());
    }

    private String asString(UUID id) {
        return id == null ? null : id.toString();
    }
}

