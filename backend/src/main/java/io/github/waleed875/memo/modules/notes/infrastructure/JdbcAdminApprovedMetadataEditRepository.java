package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.AdminApprovedMetadataEditCommand;
import io.github.waleed875.memo.modules.notes.application.AdminApprovedMetadataEditRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.application.NoteDraftMetadata;
import io.github.waleed875.memo.modules.notes.domain.NoteNumberNormalizer;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * A deliberately narrow subset of the legacy ADMIN SaveNoteAsync:
 * approved note number/dates/factory/line only. Existing company/type must
 * remain the same; reclassifying linked/source histories is a separate use case.
 * Reviewer, approval, workflow status, source, indicators, links, flags and
 * media are never updated. All checks and audit are in one transaction.
 */
@Repository
public class JdbcAdminApprovedMetadataEditRepository
    implements AdminApprovedMetadataEditRepository {

    private record Prior(
        String number, UUID company, UUID type, UUID factory, UUID line,
        LocalDate writing, LocalDate arrival, LocalDateTime version,
        String status, UUID reviewer, LocalDateTime reviewStarted,
        UUID approvedBy, LocalDateTime approvedAt
    ) {}

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public JdbcAdminApprovedMetadataEditRepository(
        JdbcTemplate jdbc, NamedParameterJdbcTemplate named
    ) {
        this.jdbc = jdbc;
        this.named = named;
    }

    @Override
    @Transactional
    public LocalDateTime edit(
        AdminApprovedMetadataEditCommand command, UUID actorId, LocalDate today
    ) {
        requireCurrentAdmin(actorId);
        // Cooperative order: shared cross-writer lock before notes FOR UPDATE.
        NoteLinkWriteLock.acquire(jdbc);
        Prior old = lockApproved(command.noteId());
        if (!old.version().equals(command.expectedUpdatedAt())) {
            throw new NoteDraftEditConflict(
                "تغيرت المذكرة؛ أعد تحميلها قبل التعديل الإداري.");
        }

        NoteDraftMetadata change = command.metadata();
        // Fail closed: existing approved indicators, links and sources refer
        // to the established company/type; never reinterpret them implicitly.
        if (!old.company().equals(change.companyId())
            || !old.type().equals(change.noteTypeId())) {
            throw new NoteSaveValidationException(
                "تغيير الشركة أو نوع مذكرة معتمدة يحتاج مسار نقل ومراجعة مستقل.");
        }
        String normalized = NoteNumberNormalizer.normalize(change.noteNumber());
        OptionalLong number = NoteNumberNormalizer.positiveNumericValue(
            change.noteNumber());
        if (normalized.isEmpty() || number.isEmpty()) {
            throw new NoteSaveValidationException("رقم المذكرة يجب أن يكون عددًا موجبًا.");
        }
        if (change.writingDate() != null
            && change.arrivalAt().isBefore(change.writingDate())) {
            throw new NoteSaveValidationException(
                "تاريخ الوصول لا يمكن أن يسبق تاريخ الكتابة.");
        }
        if (change.arrivalAt().isAfter(today)) {
            throw new NoteSaveValidationException(
                "تاريخ الوصول لا يمكن أن يكون في المستقبل.");
        }
        validateCatalog(old, change);
        Integer year = change.writingDate() == null
            ? null : change.writingDate().getYear();
        String key = change.companyId().toString().replace("-", "")
            + "|" + change.noteTypeId().toString().replace("-", "")
            + "|" + (year == null ? "legacy" : year) + "|" + number.getAsLong();
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 127))",
            (rs, index) -> rs.getObject(1), key);
        Integer duplicates = named.queryForObject("""
            select count(*) from core.notes
            where id <> :noteId and not is_deleted and company_id = :company
              and note_type_id = :type
              and ((note_number_value = :numeric
                  and (:year is null or note_year = :year))
                or (note_number_value is null and note_number_normalized = :normalized
                  and (:year is null
                    or extract(year from writing_date)::int = :year)))
            """, new MapSqlParameterSource()
                .addValue("noteId", command.noteId())
                .addValue("company", change.companyId())
                .addValue("type", change.noteTypeId())
                .addValue("numeric", number.getAsLong(), Types.BIGINT)
                .addValue("normalized", normalized)
                .addValue("year", year, Types.INTEGER), Integer.class);
        if (duplicates == null || duplicates > 0) {
            throw new NoteSaveValidationException(
                "رقم المذكرة مكرر؛ ينبغي معالجة التكرار قبل الحفظ.");
        }

        int changed = jdbc.update("""
            update core.notes
            set note_number = ?, note_number_normalized = ?,
                note_number_value = ?, note_year = ?,
                factory_id = ?, production_line_id = ?,
                writing_date = ?, arrival_at = ?, updated_by = ?
            where id = ? and not is_deleted and status = 'APPROVED'
              and updated_at = ?
            """, change.noteNumber(), normalized, number.getAsLong(), year,
            change.factoryId(), change.productionLineId(),
            change.writingDate(), change.arrivalAt(), actorId,
            command.noteId(), command.expectedUpdatedAt());
        if (changed != 1) {
            throw new NoteDraftEditConflict("تغيرت المذكرة أثناء التعديل الإداري.");
        }
        LocalDateTime next = jdbc.queryForObject("""
            select updated_at from core.notes where id = ?
            """, LocalDateTime.class, command.noteId());
        jdbc.update("""
            insert into audit.logs(
                user_id, action_type, schema_name, table_name, record_id,
                description, old_value, new_value)
            values (?, 'ADMIN_EDIT_NOTE', 'core', 'notes', ?,
                'تعديل بيانات مذكرة معتمدة مع الحفاظ على الاعتماد',
                jsonb_build_object('number', ?, 'companyId', ?,
                    'typeId', ?, 'factoryId', cast(? as text),
                    'lineId', cast(? as text), 'writingDate', cast(? as date),
                    'arrivalAt', cast(? as date), 'status', ?,
                    'reviewerId', cast(? as text),
                    'reviewStartedAt', cast(? as timestamp),
                    'approvedBy', cast(? as text),
                    'approvedAt', cast(? as timestamp)),
                jsonb_build_object('number', ?, 'companyId', ?,
                    'typeId', ?, 'factoryId', cast(? as text),
                    'lineId', cast(? as text), 'writingDate', cast(? as date),
                    'arrivalAt', cast(? as date), 'status', ?,
                    'reviewerId', cast(? as text),
                    'reviewStartedAt', cast(? as timestamp),
                    'approvedBy', cast(? as text),
                    'approvedAt', cast(? as timestamp)))
            """, actorId, command.noteId(),
            old.number(), old.company().toString(), old.type().toString(),
            text(old.factory()), text(old.line()), old.writing(), old.arrival(),
            old.status(), text(old.reviewer()), old.reviewStarted(),
            text(old.approvedBy()), old.approvedAt(),
            change.noteNumber(), old.company().toString(), old.type().toString(),
            text(change.factoryId()), text(change.productionLineId()),
            change.writingDate(), change.arrivalAt(),
            old.status(), text(old.reviewer()), old.reviewStarted(),
            text(old.approvedBy()), old.approvedAt());
        return next;
    }

    private void validateCatalog(Prior old, NoteDraftMetadata meta) {
        Boolean active = jdbc.queryForObject("""
            select exists(select 1 from core.companies
                          where id = ? and not is_deleted)
               and exists(select 1 from core.note_types
                          where id = ? and not is_deleted)
            """, Boolean.class, old.company(), old.type());
        if (!Boolean.TRUE.equals(active)) {
            throw new NoteSaveValidationException(
                "الشركة أو نوع المذكرة السابق غير موجود.");
        }
        if (meta.factoryId() == null && meta.productionLineId() != null) {
            throw new NoteSaveValidationException("لا يوجد خط إنتاج دون مصنع.");
        }
        if (meta.factoryId() != null && !Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from core.factories
              where id = ? and company_id = ? and not is_deleted
              and (is_active or ?))
            """, Boolean.class, meta.factoryId(), old.company(),
            meta.factoryId().equals(old.factory())))) {
            throw new NoteSaveValidationException("المصنع المحدد غير صالح.");
        }
        if (meta.productionLineId() != null
            && !Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from core.production_lines
                  where id = ? and factory_id = ? and not is_deleted
                  and (is_active or ?))
                """, Boolean.class, meta.productionLineId(), meta.factoryId(),
                meta.productionLineId().equals(old.line())
                    && meta.factoryId().equals(old.factory())))) {
            throw new NoteSaveValidationException("خط الإنتاج المحدد غير صالح.");
        }
    }

    private void requireCurrentAdmin(UUID actor) {
        List<String> roles = jdbc.query("""
            select r.code from auth.users u join auth.roles r on r.id = u.role_id
            where u.id = ? and u.is_active and not u.is_deleted and r.is_active
            for share of u, r
            """, (rs, index) -> rs.getString(1), actor);
        if (roles.size() != 1 || !"ADMIN".equals(roles.getFirst())) {
            throw new MasterDataForbiddenException();
        }
    }

    private Prior lockApproved(UUID noteId) {
        List<Prior> rows = jdbc.query("""
            select note_number, company_id, note_type_id, factory_id,
                   production_line_id, writing_date, arrival_at, updated_at,
                   status, reviewer_id, review_started_at, approved_by, approved_at
            from core.notes where id = ? and not is_deleted for update
            """, (rs, index) -> new Prior(
                rs.getString("note_number"),
                rs.getObject("company_id", UUID.class),
                rs.getObject("note_type_id", UUID.class),
                rs.getObject("factory_id", UUID.class),
                rs.getObject("production_line_id", UUID.class),
                rs.getObject("writing_date", LocalDate.class),
                rs.getObject("arrival_at", LocalDate.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getString("status"),
                rs.getObject("reviewer_id", UUID.class),
                rs.getObject("review_started_at", LocalDateTime.class),
                rs.getObject("approved_by", UUID.class),
                rs.getObject("approved_at", LocalDateTime.class)
            ), noteId);
        if (rows.isEmpty() || !"APPROVED".equals(rows.getFirst().status())) {
            throw new NoteDraftEditConflict(
                "التعديل الإداري لهذه الوظيفة متاح للمذكرات المعتمدة فقط.");
        }
        return rows.getFirst();
    }

    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }
}
