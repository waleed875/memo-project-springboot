package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.notes.application.NoteCreateCommand;
import io.github.waleed875.memo.modules.notes.application.NoteCreateConflict;
import io.github.waleed875.memo.modules.notes.application.NoteCreator;
import io.github.waleed875.memo.modules.notes.domain.NoteNumberNormalizer;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachmentPolicy;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveCandidate;
import io.github.waleed875.memo.modules.notes.domain.NoteSavePolicy;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.modules.notes.domain.RequiredNoteField;
import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restricted create core; DO NOT expose as the legacy POST route.
 *
 * The entire operation (lock, idempotency reservation, catalog/field checks,
 * duplicate check, note + values, review history and two audit events) is
 * transaction-scoped. A failed write/audit always rolls back the reservation.
 */
@Repository
public class JdbcNoteCreator implements NoteCreator {
    private static final UUID ZERO = new UUID(0, 0);
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public JdbcNoteCreator(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    private void lock(String key, int seed) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, ?))",
            (rs, row) -> rs.getObject(1), key, seed);
    }

    private record ExistingOperation(UUID operationId, UUID noteId,
                                     UUID requester, String fingerprint) {}

    private ExistingOperation existingOperation(UUID op, UUID noteId) {
        List<ExistingOperation> matches = jdbc.query("""
            select operation_id, note_id, requested_by, fingerprint
            from core.note_create_operations
            where operation_id = ? or note_id = ?
            order by case when operation_id = ? then 0 else 1 end
            limit 1
            """, (rs, row) -> new ExistingOperation(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            rs.getObject(3, UUID.class), rs.getString(4)),
            op, noteId, op);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private UUID verifyReplay(ExistingOperation record, UUID operationId,
                              UUID noteId, UUID user, String fingerprint) {
        if (!record.operationId().equals(operationId) || !record.noteId().equals(noteId)
            || !record.requester().equals(user) || !record.fingerprint().equals(fingerprint)) {
            throw new NoteCreateConflict(
                "لا يمكن إعادة استخدام معرّف عملية إنشاء المذكرة مع مستخدم أو مذكرة أو بيانات مختلفة.");
        }
        return record.noteId();
    }

    /**
     * Deliberately versioned, deterministic SHA-256 for this restricted Java
     * command. Not asserted byte-identical to the full C# v2 fingerprint.
     * A cross-runtime replay therefore conflicts instead of being trusted.
     */
    private String fingerprint(NoteCreateCommand command) {
        NoteSaveCandidate n = command.candidate();
        try {
            // Length-prefixed canonical UTF-8 values: no delimiter ambiguity.
            // Independent of JSON library implementation and field order.
            var buffer = new ByteArrayOutputStream();
            var data = new DataOutputStream(buffer);
            put(data, "note-create-core-2");
            put(data, n.noteNumber());
            put(data, n.companyId());
            put(data, n.noteTypeId());
            put(data, n.factoryId());
            put(data, n.productionLineId());
            put(data, n.writingDate());
            put(data, n.arrivalAt());
            data.writeBoolean(n.saveAsDraft());
            data.writeBoolean(n.markAllIndicatorsCorrect());
            put(data, command.notes());
            NoteAttachment attachment = command.attachment();
            if (attachment == null) {
                data.writeBoolean(false);
            } else {
                data.writeBoolean(true);
                put(data, attachment.fileName());
                byte[] dataBytes = attachment.bytes();
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(dataBytes);
                data.writeInt(dataBytes.length);
                data.writeInt(digest.length);
                data.write(digest);
            }
            data.writeInt(command.relatedNoteIds().size());
            command.relatedNoteIds().stream()
                .map(UUID::toString)
                .sorted()
                .forEachOrdered(x -> {
                    try { put(data, x); }
                    catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
                });
            if (n.fieldValues() == null) {
                data.writeInt(-1);
            } else {
                data.writeInt(n.fieldValues().size());
                n.fieldValues().stream()
                    .sorted(java.util.Comparator.comparing(x ->
                        x.fieldId() == null ? "" : x.fieldId().toString()))
                    .forEachOrdered(v -> {
                        try {
                            put(data, v.fieldId());
                            data.writeInt(v.value() == null ? -1 : v.value().ordinal());
                        } catch (IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                    });
            }
            data.flush();
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot fingerprint note creation", ex);
        }
    }

    private static void put(DataOutputStream data, Object value) throws IOException {
        if (value == null) {
            data.writeInt(-1);
        } else {
            byte[] utf8 = value.toString().getBytes(StandardCharsets.UTF_8);
            data.writeInt(utf8.length);
            data.write(utf8);
        }
    }

    private void requireActiveCatalog(NoteSaveCandidate c) {
        Boolean valid = jdbc.queryForObject("""
            select (
                exists(select 1 from core.companies
                       where id = ? and is_active and not is_deleted)
                and exists(select 1 from core.note_types
                       where id = ? and is_active and not is_deleted))
            """, Boolean.class, c.companyId(), c.noteTypeId());
        if (!Boolean.TRUE.equals(valid)) {
            throw new NoteCreateConflict("الشركة أو نوع المذكرة غير موجود أو غير فعال.");
        }
        if (c.factoryId() == null && c.productionLineId() != null) {
            throw new NoteCreateConflict("لا يمكن اختيار خط إنتاج دون تحديد مصنع.");
        }
        if (c.factoryId() != null) {
            Boolean factory = jdbc.queryForObject("""
                select exists(select 1 from core.factories
                    where id = ? and company_id = ? and is_active and not is_deleted)
                """, Boolean.class, c.factoryId(), c.companyId());
            if (!Boolean.TRUE.equals(factory)) {
                throw new NoteCreateConflict("المصنع المحدد لا يتبع للشركة المختارة.");
            }
        }
        if (c.productionLineId() != null) {
            Boolean line = jdbc.queryForObject("""
                select exists(select 1 from core.production_lines
                    where id = ? and factory_id = ? and is_active and not is_deleted)
                """, Boolean.class, c.productionLineId(), c.factoryId());
            if (!Boolean.TRUE.equals(line)) {
                throw new NoteCreateConflict("خط الإنتاج المحدد لا يتبع للمصنع المختار أو أنه غير فعال.");
            }
        }
    }

    /**
     * Reject malformed, foreign, deleted, duplicate, and self-links before
     * writing any link row. The legacy migrated DB must retain its own
     * validation triggers to protect concurrent operations and other clients.
     */
    private void validateLinks(UUID noteId, NoteSaveCandidate candidate,
                               List<UUID> relatedIds) {
        if (relatedIds.size() > 100) {
            throw new NoteSaveValidationException("عدد المذكرات المرتبطة يتجاوز الحد.");
        }
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        String currentType = jdbc.queryForObject(
            "select code from core.note_types where id = ?",
            String.class, candidate.noteTypeId());
        if (!"ENTRY".equals(currentType) && !"EXIT".equals(currentType)) {
            if (!relatedIds.isEmpty())
                throw new NoteCreateConflict("الربط متاح فقط بين مذكرات الإدخال والإخراج.");
            return;
        }
        for (UUID related : relatedIds) {
            if (related == null || ZERO.equals(related) || noteId.equals(related)
                || !seen.add(related)) {
                throw new NoteCreateConflict("الربط يحتوي على معرف غير صالح أو مكرر.");
            }
        }
        if (relatedIds.isEmpty()) return;
        var params = new MapSqlParameterSource()
            .addValue("ids", relatedIds).addValue("company", candidate.companyId())
            .addValue("expected", "ENTRY".equals(currentType) ? "EXIT" : "ENTRY");
        Integer eligible = named.queryForObject("""
            select count(*) from core.notes n
            join core.note_types nt on nt.id = n.note_type_id
            where n.id in (:ids) and not n.is_deleted
              and n.company_id = :company and nt.code = :expected
            """, params, Integer.class);
        if (eligible == null || eligible != relatedIds.size()) {
            throw new NoteCreateConflict(
                "المذكرات المرتبطة يجب أن تكون موجودة وفعالة ومن الشركة نفسها وبالنوع المقابل.");
        }
    }

    private List<RequiredNoteField> definitions(UUID typeId) {
        return jdbc.query("""
            select nf.id as field_id, nf.name_ar as field_name,
                   ntf.is_required, (ntf.is_active and nf.is_active) as active
            from core.note_type_fields ntf
            join core.note_fields nf on nf.id = ntf.field_id
            where ntf.note_type_id = ? and not ntf.is_deleted and not nf.is_deleted
            order by ntf.sort_order, nf.id
            """, (rs, row) -> new RequiredNoteField(
            rs.getObject("field_id", UUID.class),
            rs.getString("field_name"), rs.getBoolean("is_required"),
            rs.getBoolean("active")), typeId);
    }

    private void ensureNoDuplicate(NoteCreateCommand command, String normalized,
                                   long numericValue) {
        var candidate = command.candidate();
        Integer year = candidate.writingDate() == null
            ? null : candidate.writingDate().getYear();
        // Same transaction-scoped key format / seed as C# AcquireDuplicateLockAsync.
        String datePart = year == null ? "legacy" : Integer.toString(year);
        String key = candidate.companyId().toString().replace("-", "")
            + "|" + candidate.noteTypeId().toString().replace("-", "")
            + "|" + datePart + "|" + numericValue;
        lock(key, 127);

        var params = new MapSqlParameterSource()
            .addValue("company", candidate.companyId())
            .addValue("type", candidate.noteTypeId())
            .addValue("numeric", numericValue, Types.BIGINT)
            .addValue("normalized", normalized)
            .addValue("year", year, Types.INTEGER);
        List<UUID> duplicateIds = named.query("""
            select id from core.notes
            where not is_deleted and company_id = :company and note_type_id = :type
              and ((note_number_value = :numeric
                   and (:year is null or note_year = :year))
                or (note_number_value is null and note_number_normalized = :normalized
                   and (:year is null or extract(year from writing_date)::int = :year)))
            limit 1
            """, params, (rs, row) -> rs.getObject(1, UUID.class));
        if (!duplicateIds.isEmpty()) {
            throw new NoteCreateConflict(
                "توجد مذكرة مطابقة. يجب اختيار التصنيف والمذكرة الأصلية من شاشة معالجة التكرار.");
        }
    }

    @Override
    @Transactional
    public UUID create(NoteCreateCommand command, UUID actor, LocalDate operationalToday) {
        if (command == null || command.candidate() == null || actor == null
            || command.operationId() == null || ZERO.equals(command.operationId())
            || command.noteId() == null || ZERO.equals(command.noteId())) {
            throw new NoteSaveValidationException("عملية إنشاء المذكرة غير صالحة.");
        }
        // Validate bytes before any DB locks or reservations. Fingerprint
        // includes the full content digest and link IDs, including on replay.
        String attachmentType = NoteAttachmentPolicy.validate(command.attachment());
        String fingerprint = fingerprint(command);
        UUID operationId = command.operationId();
        UUID noteId = command.noteId();

        // Same operation-ID lock and acquisition order as original C#.
        lock(operationId.toString().replace("-", ""), 571);
        ExistingOperation existing = existingOperation(operationId, noteId);
        if (existing != null) {
            return verifyReplay(existing, operationId, noteId, actor, fingerprint);
        }
        int reserved = jdbc.update("""
            insert into core.note_create_operations(
                operation_id, note_id, requested_by, fingerprint)
            values (?, ?, ?, ?)
            on conflict do nothing
            """, operationId, noteId, actor, fingerprint);
        if (reserved != 1) {
            ExistingOperation winner = existingOperation(operationId, noteId);
            if (winner == null) throw new NoteCreateConflict("تعارض متزامن في إنشاء المذكرة.");
            return verifyReplay(winner, operationId, noteId, actor, fingerprint);
        }

        if (Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from core.notes where id = ?)", Boolean.class, noteId))) {
            throw new NoteCreateConflict("معرّف المذكرة المثبّت مستخدم مسبقاً في عملية أخرى.");
        }
        NoteSaveCandidate c = command.candidate();
        if (c.noteTypeId() == null || ZERO.equals(c.noteTypeId())
            || c.companyId() == null || ZERO.equals(c.companyId())) {
            throw new NoteSaveValidationException("الشركة ونوع المذكرة مطلوبان.");
        }

        // Serialize changes to type definitions while capturing a consistent
        // validation set; no stale preflight metadata is trusted.
        lock("note-type-fields:" + c.noteTypeId().toString().replace("-", ""), 712);
        requireActiveCatalog(c);
        List<RequiredNoteField> definitions = definitions(c.noteTypeId());
        HashSet<UUID> activeIds = new HashSet<>();
        definitions.stream().filter(RequiredNoteField::isActive)
            .forEach(field -> activeIds.add(field.fieldId()));
        List<NoteSaveCandidate.IndicatorInput> submitted = c.fieldValues() == null
            ? null : c.fieldValues().stream()
                .filter(v -> v != null && activeIds.contains(v.fieldId())).toList();
        NoteSaveCandidate filtered = new NoteSaveCandidate(
            c.noteNumber(), c.companyId(), c.noteTypeId(),
            c.factoryId(), c.productionLineId(), c.writingDate(), c.arrivalAt(),
            c.saveAsDraft(), c.markAllIndicatorsCorrect(), submitted);
        NoteSavePolicy.validate(filtered, definitions, operationalToday);
        String normalized = NoteNumberNormalizer.normalize(c.noteNumber());
        OptionalLong numeric = NoteNumberNormalizer.positiveNumericValue(c.noteNumber());
        if (normalized.isEmpty() || numeric.isEmpty()) {
            throw new NoteSaveValidationException(
                "رقم المذكرة يجب أن يكون عددًا صحيحًا موجبًا، ويمكن إدخال الأرقام العربية أو اللاتينية فقط.");
        }
        NoteStatus status = NoteSavePolicy.calculateStatus(
            definitions, filtered.fieldValues(), c.saveAsDraft());
        if (!c.saveAsDraft() && status == NoteStatus.DRAFT) {
            throw new NoteSaveValidationException(
                "لا يمكن إرسال المذكرة قبل مراجعة جميع البنود الإلزامية.");
        }

        ensureNoDuplicate(command, normalized, numeric.getAsLong());
        validateLinks(noteId, c, command.relatedNoteIds());

        jdbc.update("""
            insert into core.notes(
                id, note_number, note_type_id, company_id,
                factory_id, production_line_id, writing_date, arrival_at,
                note_number_normalized, note_number_value, note_year,
                notes, status, created_by, updated_by,
                attachment_file_name, attachment_content_type, attachment_data,
                is_loot, is_invoice)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, false)
            """, noteId, c.noteNumber(), c.noteTypeId(), c.companyId(),
            c.factoryId(), c.productionLineId(), c.writingDate(), c.arrivalAt(),
            normalized, numeric.getAsLong(),
            c.writingDate() == null ? null : c.writingDate().getYear(),
            command.notes(), status.name(), actor, actor,
            command.attachment() == null ? null : command.attachment().fileName(),
            attachmentType, command.attachment() == null ? null : command.attachment().bytes());

        for (var value : filtered.fieldValues()) {
            jdbc.update("""
                insert into core.note_field_values(
                    id, note_id, field_id, field_value, inactive_reason)
                values (?, ?, ?, ?, null)
                """, UUID.randomUUID(), noteId, value.fieldId(),
                (short) value.value().ordinal());
        }

        if (command.attachment() != null) {
            byte[] bytes = command.attachment().bytes();
            jdbc.update("""
                insert into core.note_attachments(
                    note_id, file_name, content_type, file_data, file_size, created_by)
                values (?, ?, ?, ?, ?, ?)
                """, noteId, command.attachment().fileName(), attachmentType,
                bytes, (long) bytes.length, actor);
        }

        for (UUID related : command.relatedNoteIds()) {
            // In the full migration 063/070 schema, insert triggers also
            // maintain compatible source references; never bypass them.
            jdbc.update("""
                insert into core.note_links(
                    source_note_id, related_note_id, created_by)
                values (?, ?, ?)
                """, noteId, related, actor);
            jdbc.update("""
                insert into audit.logs(
                    user_id, action_type, schema_name, table_name,
                    record_id, description)
                values (?, 'LINK_NOTES', 'core', 'note_links',
                        ?, 'ربط المذكرة بمذكرة مقابلة أثناء الإنشاء')
                """, actor, noteId);
        }

        String action = c.saveAsDraft() ? "CREATE_DRAFT" : "SUBMIT_FOR_REVIEW";
        jdbc.update("""
            insert into core.note_review_history(note_id, action, user_id)
            values (?, ?, ?)
            """, noteId, action, actor);

        jdbc.update("""
            insert into audit.logs(
                user_id, action_type, schema_name, table_name, record_id,
                description, old_value, new_value)
            values (?, 'CREATE_NOTE', 'core', 'notes', ?, 'إنشاء مذكرة',
                    'null'::jsonb,
                    jsonb_build_object('noteNumber', ?, 'normalizedNumber', ?,
                        'status', ?, 'operationId', cast(? as text)))
            """, actor, noteId, c.noteNumber(), normalized, status.name(),
            operationId.toString());
        jdbc.update("""
            insert into audit.logs(
                user_id, action_type, schema_name, table_name, record_id,
                description, new_value)
            values (?, 'SAVE_INDICATORS', 'core', 'note_field_values', ?,
                    'حفظ مؤشرات المذكرة',
                    jsonb_build_object('indicatorCount', ?))
            """, actor, noteId, filtered.fieldValues().size());
        if (c.markAllIndicatorsCorrect()) {
            jdbc.update("""
                insert into audit.logs(
                    user_id, action_type, schema_name, table_name, record_id,
                    description)
                values (?, 'MARK_ALL_INDICATORS_CORRECT', 'core',
                        'note_field_values', ?, 'اعتبار المؤشرات صحيحة')
                """, actor, noteId);
        }
        return noteId;
    }
}
