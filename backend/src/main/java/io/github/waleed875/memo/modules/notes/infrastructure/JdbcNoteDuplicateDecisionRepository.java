package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.NoteCreateConflict;
import io.github.waleed875.memo.modules.notes.application.NoteDuplicateClassification;
import io.github.waleed875.memo.modules.notes.application.NoteDuplicateDecisionCommand;
import io.github.waleed875.memo.modules.notes.application.NoteDuplicateDecisionRepository;
import io.github.waleed875.memo.modules.notes.domain.NoteNumberNormalizer;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.sql.Types;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Original C# ResolveDuplicateAsync no-create classifications. A previous
 * duplicate search result is NEVER trusted: the same current numeric/year
 * invoice-aware match is queried under the original advisory lock.
 */
@Repository
public class JdbcNoteDuplicateDecisionRepository implements NoteDuplicateDecisionRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public JdbcNoteDuplicateDecisionRepository(
        JdbcTemplate jdbc, NamedParameterJdbcTemplate named
    ) {
        this.jdbc = jdbc;
        this.named = named;
    }

    @Override
    @Transactional
    public void record(NoteDuplicateDecisionCommand command, UUID actorId) {
        if (command == null || command.classification() == null
            || command.classification() == NoteDuplicateClassification.COMPANY_DUPLICATE_NUMBER
            || command.matchedNoteId() == null || command.companyId() == null
            || command.noteTypeId() == null || command.noteNumber() == null
            || (command.productionLineId() != null && command.factoryId() == null)) {
            throw new NoteSaveValidationException("قرار التكرار غير صالح.");
        }
        List<String> roles = jdbc.query("""
            select r.code from auth.users u join auth.roles r on r.id=u.role_id
            where u.id=? and u.is_active and not u.is_deleted and r.is_active
            for share of u,r
            """, (rs,i)->rs.getString(1), actorId);
        if (roles.size()!=1 || (!"ADMIN".equals(roles.getFirst())
            && !"DATA_ENTRY".equals(roles.getFirst()))) {
            throw new MasterDataForbiddenException();
        }
        String normalized = NoteNumberNormalizer.normalize(command.noteNumber());
        if (normalized.isBlank()) {
            throw new NoteSaveValidationException("رقم المذكرة غير صالح.");
        }
        OptionalLong numeric = NoteNumberNormalizer.positiveNumericValue(command.noteNumber());
        Integer year = command.writingDate() == null
            ? null : command.writingDate().getYear();
        String dateKey = command.isInvoice()
            ? (command.writingDate() == null
                ? "invoicedate_none"
                : command.writingDate().format(
                    java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
            : (year == null ? "legacy" : Integer.toString(year));
        String lockKey = command.companyId().toString().replace("-", "")
            + "|" + command.noteTypeId().toString().replace("-", "")
            + "|" + dateKey + "|"
            + (numeric.isPresent() ? Long.toString(numeric.getAsLong()) : normalized);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,127))",
            (rs,i)->rs.getObject(1), lockKey);
        var params = new MapSqlParameterSource()
            .addValue("matched", command.matchedNoteId())
            .addValue("company", command.companyId())
            .addValue("type", command.noteTypeId())
            .addValue("numeric", numeric.isPresent() ? numeric.getAsLong() : null, Types.BIGINT)
            .addValue("normalized", normalized)
            .addValue("year", year, Types.INTEGER)
            .addValue("invoice", command.isInvoice())
            .addValue("writing", command.writingDate(), Types.DATE);
        Boolean match = named.queryForObject("""
            select exists(
                select 1 from core.notes n
                where n.id=:matched and not n.is_deleted
                  and n.company_id=:company and n.note_type_id=:type
                  and (
                      (:numeric is not null and n.note_number_value=:numeric
                       and (:year is null or n.note_year=:year))
                      or (n.note_number_value is null and
                          n.note_number_normalized=:normalized and
                          (:year is null or extract(year from n.writing_date)::int=:year))
                  )
                  and (:invoice=false or n.writing_date is not distinct from :writing)
            )
            """, params, Boolean.class);
        if (!Boolean.TRUE.equals(match)) {
            throw new NoteCreateConflict(
                "المذكرة الأصلية لم تعد مطابقة؛ أعد البحث قبل اتخاذ القرار.");
        }
        var classification = command.classification();
        String payload = jdbc.queryForObject("""
            select jsonb_build_object(
                'noteNumber', ?, 'normalizedNumber', ?,
                'CompanyId', cast(? as text), 'FactoryId', cast(? as text),
                'ProductionLineId', cast(? as text),
                'NoteTypeId', cast(? as text),
                'WritingDate', cast(? as date),
                'matchedNoteId', cast(? as text))::text
            """, String.class, command.noteNumber(), normalized,
            command.companyId().toString(), text(command.factoryId()),
            text(command.productionLineId()), command.noteTypeId().toString(),
            command.writingDate(), command.matchedNoteId().toString());

        if (classification == NoteDuplicateClassification.COMPANY_RESEND) {
            jdbc.update("""
                insert into core.duplicate_incidents(
                    classification,matched_note_id,created_note_id,company_id,
                    factory_id,production_line_id,note_type_id,note_number,
                    note_number_normalized,writing_date,decided_by,metadata)
                values ('COMPANY_RESEND',?,null,?,?,?,?,?,?,?,?,cast(? as jsonb))
                """, command.matchedNoteId(), command.companyId(),
                command.factoryId(), command.productionLineId(),
                command.noteTypeId(), command.noteNumber(), normalized,
                command.writingDate(), actorId, payload);
        }
        jdbc.update("""
            insert into audit.logs(
                user_id,action_type,schema_name,table_name,record_id,
                description,new_value)
            values (?,?,'core',?,?,
                'تصنيف محاولة إدخال رقم مذكرة مكرر',
                cast(? as jsonb))
            """, actorId, classification.name(),
            classification == NoteDuplicateClassification.COMPANY_RESEND
                ? "duplicate_incidents" : "notes",
            command.matchedNoteId(), payload);
    }

    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }
}
