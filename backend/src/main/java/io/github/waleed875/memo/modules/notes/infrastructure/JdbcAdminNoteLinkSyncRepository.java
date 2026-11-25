package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncRepository;
import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncService;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrative legacy note_links set synchronization.
 *
 * The C# original migration 063/070 triggers are authoritative for
 * synchronizing note_links with note_source_exit_refs. Do not mirror those
 * trigger side effects in Java (double insertion risks recursion/duplicates).
 *
 * A global transaction advisory lock serializes this NEW internal operation,
 * while ordered row locks, optimistic revision, symmetric unique index and
 * original database validation triggers protect actual note relationships.
 * Until all legacy writes share a lock protocol, full-migration concurrency
 * testing remains a required release gate.
 */
@Repository
public class JdbcAdminNoteLinkSyncRepository implements AdminNoteLinkSyncRepository {
    private static final String LOCK_NAME = "memo:admin-note-link-sync";
    private static final int LOCK_SEED = 23070;

    private record Scope(
        UUID id, UUID companyId, String type, String number,
        boolean loot, LocalDateTime updatedAt
    ) {}

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public JdbcAdminNoteLinkSyncRepository(
        JdbcTemplate jdbc, NamedParameterJdbcTemplate named
    ) {
        this.jdbc = jdbc;
        this.named = named;
    }

    @Override
    @Transactional
    public SyncResult sync(AdminNoteLinkSyncCommand command, UUID actorId) {
        // Validate again to avoid bypass by a repository-only caller.
        AdminNoteLinkSyncService.validate(command);
        requireAdmin(actorId);

        // All calls to this new internal command use the same advisory lock.
        // This is intentionally coarse until the full schema/trigger races
        // with the original source writer have been golden-master tested.
        jdbc.queryForObject(
            "select pg_advisory_xact_lock(hashtextextended(?, ?))",
            (rs, row) -> rs.getObject(1), LOCK_NAME, LOCK_SEED);

        List<UUID> before = counterpartIds(command.noteId());
        var lockedIds = new TreeSet<UUID>();
        lockedIds.add(command.noteId());
        lockedIds.addAll(command.relatedNoteIds());
        lockedIds.addAll(before);

        // Order all note locks deterministically, including removed targets.
        List<Scope> rows = named.query("""
            select n.id, n.company_id, nt.code as type_code,
                   n.note_number, n.is_loot, n.updated_at
              from core.notes n
              join core.note_types nt on nt.id = n.note_type_id
             where n.id in (:ids) and not n.is_deleted
             order by n.id
             for update of n
            """, new MapSqlParameterSource("ids", List.copyOf(lockedIds)),
            (rs, row) -> new Scope(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("type_code"),
                rs.getString("note_number"),
                rs.getBoolean("is_loot"),
                rs.getObject("updated_at", LocalDateTime.class)));
        if (rows.size() != lockedIds.size()) {
            throw new NoteSaveValidationException(
                "إحدى المذكرات المراد ربطها غير موجودة أو محذوفة.");
        }
        Map<UUID, Scope> scopes = rows.stream().collect(
            java.util.stream.Collectors.toMap(Scope::id, value -> value));
        Scope current = scopes.get(command.noteId());
        if (!command.expectedUpdatedAt().equals(current.updatedAt())) {
            throw new NoteDraftEditConflict(
                "تغيرت المذكرة منذ آخر تحميل؛ أعد تحميلها قبل تعديل الروابط.");
        }

        // If another legacy writer changed the pairs during lock acquisition,
        // fail closed rather than applying a set based on a stale snapshot.
        List<UUID> settled = counterpartIds(command.noteId());
        if (!before.equals(settled)) {
            throw new NoteDraftEditConflict(
                "تغيرت روابط المذكرة أثناء التعديل؛ أعد تحميل المذكرة.");
        }
        validateRelations(current, command.relatedNoteIds(), scopes);

        Set<UUID> oldSet = new HashSet<>(before);
        Set<UUID> newSet = new HashSet<>(command.relatedNoteIds());
        List<UUID> removed = before.stream().filter(x -> !newSet.contains(x)).toList();
        List<UUID> added = command.relatedNoteIds().stream()
            .filter(x -> !oldSet.contains(x)).sorted().toList();

        if (removed.isEmpty() && added.isEmpty()) {
            return new SyncResult(command.noteId(), before, 0, 0, current.updatedAt());
        }

        for (UUID target : removed) {
            int count = jdbc.update("""
                delete from core.note_links
                 where (source_note_id = ? and related_note_id = ?)
                    or (source_note_id = ? and related_note_id = ?)
                """, command.noteId(), target, target, command.noteId());
            if (count != 1) {
                throw new NoteDraftEditConflict(
                    "تغيرت روابط المذكرة أثناء إلغاء الربط.");
            }
            audit(command.noteId(), target, actorId, "UNLINK_NOTES");
        }

        for (UUID target : added) {
            int count = jdbc.update("""
                insert into core.note_links(source_note_id, related_note_id, created_by)
                values (?, ?, ?)
                on conflict do nothing
                """, command.noteId(), target, actorId);
            if (count != 1) {
                throw new NoteDraftEditConflict(
                    "الرابط موجود بالفعل أو أُنشئ بواسطة عملية متزامنة.");
            }
            audit(command.noteId(), target, actorId, "LINK_NOTES");
        }

        int updated = jdbc.update("""
            update core.notes set updated_by = ?
             where id = ? and not is_deleted and updated_at = ?
            """, actorId, command.noteId(), current.updatedAt());
        if (updated != 1) {
            throw new NoteDraftEditConflict(
                "تغيرت المذكرة أثناء حفظ الروابط.");
        }
        LocalDateTime revision = jdbc.queryForObject(
            "select updated_at from core.notes where id = ?",
            LocalDateTime.class, command.noteId());
        List<UUID> finalIds = counterpartIds(command.noteId());
        if (!new HashSet<>(finalIds).equals(newSet)) {
            throw new NoteDraftEditConflict(
                "لم تتم مزامنة روابط المذكرة بصورة متسقة.");
        }
        return new SyncResult(
            command.noteId(), finalIds, added.size(), removed.size(), revision);
    }

    private List<UUID> counterpartIds(UUID noteId) {
        List<UUID> ids = jdbc.query("""
            select case when source_note_id = ? then related_note_id
                        else source_note_id end as counterpart_id
              from core.note_links
             where source_note_id = ? or related_note_id = ?
             order by counterpart_id
            """, (rs, row) -> rs.getObject(1, UUID.class), noteId, noteId, noteId);
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new NoteDraftEditConflict(
                "روابط المذكرة الحالية غير متسقة أو تحتوي على تكرار.");
        }
        return ids;
    }

    private void validateRelations(Scope source, List<UUID> targets,
                                   Map<UUID, Scope> scopes) {
        if (!targets.isEmpty() && !List.of("ENTRY", "EXIT").contains(source.type())) {
            throw new NoteSaveValidationException(
                "الربط متاح فقط بين مذكرات الإدخال والإخراج.");
        }
        if (source.loot() && !targets.isEmpty()) {
            throw new NoteSaveValidationException(
                "لا يمكن ربط مذكرة الغنائم بمذكرات إخراج.");
        }
        String counterpart = "ENTRY".equals(source.type()) ? "EXIT" : "ENTRY";
        for (UUID id : targets) {
            Scope target = scopes.get(id);
            if (target == null || !source.companyId().equals(target.companyId())
                || !counterpart.equals(target.type())
                || ("ENTRY".equals(target.type()) && target.loot())) {
                throw new NoteSaveValidationException(
                    "المذكرات المرتبطة يجب أن تكون من الشركة نفسها وبالنوع المقابل وغير محذوفة.");
            }
        }
    }

    private void requireAdmin(UUID actorId) {
        List<String> roles = jdbc.query("""
            select r.code from auth.users u
            join auth.roles r on r.id = u.role_id
            where u.id = ? and u.is_active and not u.is_deleted and r.is_active
            for share of u, r
            """, (rs, row) -> rs.getString(1), actorId);
        if (roles.size() != 1 || !"ADMIN".equals(roles.getFirst())) {
            throw new MasterDataForbiddenException();
        }
    }

    private void audit(UUID noteId, UUID relatedId, UUID actorId, String type) {
        jdbc.update("""
            insert into audit.logs(
                user_id, action_type, schema_name, table_name,
                record_id, description, new_value)
            values (?, ?, 'core', 'note_links', ?,
                'مزامنة روابط مذكرات الإدخال والإخراج',
                jsonb_build_object(
                    'noteId', cast(? as text),
                    'relatedNoteId', cast(? as text),
                    'operation', ?))
            """, actorId, type, noteId, noteId.toString(), relatedId.toString(), type);
    }
}
