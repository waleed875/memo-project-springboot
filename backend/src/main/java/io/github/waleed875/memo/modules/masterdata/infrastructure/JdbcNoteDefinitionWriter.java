package io.github.waleed875.memo.modules.masterdata.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.NoteDefinitionWriter;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeFieldDefinition;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL implementation of the original NoteTypeService:
 * advisory lock, optional field reuse, note_type_fields upsert and JSONB audit.
 *
 * Requires transaction proxy. No schema modification or implicit migration.
 */
@Repository
public class JdbcNoteDefinitionWriter implements NoteDefinitionWriter {
    private static final UUID EMPTY = new UUID(0L, 0L);
    private final JdbcTemplate jdbc;

    public JdbcNoteDefinitionWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void typeLock(UUID noteTypeId) {
        // This advisory lock is transaction scoped. The lock-key format and seed
        // are identical to the legacy Npgsql service.
        String key = "note-type-fields:" + noteTypeId.toString().replace("-", "");
        jdbc.queryForObject(
            "select pg_advisory_xact_lock(hashtextextended(?, 712))",
            (rs, rowNum) -> rs.getObject(1), key);
    }

    private String snapshot(UUID definitionId, UUID noteTypeId) {
        List<String> values = jdbc.query("""
            select jsonb_build_object(
                'fieldCode', nf.code, 'fieldNameAr', nf.name_ar,
                'sortOrder', ntf.sort_order, 'isRequired', ntf.is_required,
                'isActive', ntf.is_active
            )::text
            from core.note_type_fields ntf
            join core.note_fields nf on nf.id = ntf.field_id
            where ntf.id = ? and ntf.note_type_id = ? and not ntf.is_deleted
            for update of ntf, nf
            """, (rs, row) -> rs.getString(1), definitionId, noteTypeId);
        if (values.isEmpty()) throw new MasterDataOperationException("تعريف البند غير موجود.");
        return values.get(0);
    }

    private UUID attachedField(UUID definitionId, UUID noteTypeId) {
        List<UUID> result = jdbc.query("""
            select field_id from core.note_type_fields
            where id = ? and note_type_id = ? and not is_deleted
            """, (rs, row) -> rs.getObject(1, UUID.class), definitionId, noteTypeId);
        if (result.isEmpty()) throw new MasterDataOperationException("تعريف البند غير موجود.");
        return result.get(0);
    }

    private void audit(UUID actor, String action, UUID id, String before, String after) {
        jdbc.update("""
            insert into audit.logs(
                user_id, action_type, schema_name, table_name,
                record_id, description, old_value, new_value)
            values (?, ?, 'core', 'note_type_fields', ?,
                'تغيير تعريف بند مع تسجيل الحالة السابقة واللاحقة',
                cast(? as jsonb), cast(? as jsonb))
            """, actor, action, id, before == null ? "null" : before, after);
    }

    @Override
    @Transactional
    public NoteTypeFieldDefinition save(NoteTypeFieldDefinition value, UUID actor) {
        UUID noteType = value.noteTypeId();
        typeLock(noteType);
        UUID definitionId = value.id() == null || EMPTY.equals(value.id()) ? null : value.id();
        String before = null;
        UUID previousField = null;
        if (definitionId != null) {
            before = snapshot(definitionId, noteType);
            previousField = attachedField(definitionId, noteType);
        }

        UUID fieldId = previousField == null ? value.fieldId() : previousField;
        if (previousField != null && value.fieldId() != null
            && !previousField.equals(value.fieldId())) {
            throw new MasterDataOperationException(
                "لا يمكن تغيير معرّف البند المرتبط؛ أنشئ تعريفًا جديدًا بدلًا منه.");
        }

        if (fieldId == null) {
            List<UUID> found = jdbc.query("""
                select id from core.note_fields where lower(code) = lower(?) for update
                """, (rs, row) -> rs.getObject(1, UUID.class), value.fieldCode());
            if (!found.isEmpty()) {
                fieldId = found.get(0);
            } else {
                fieldId = UUID.randomUUID();
                jdbc.update("""
                    insert into core.note_fields(
                        id, code, name_ar, description, default_sort_order,
                        is_active, is_deleted)
                    values (?, ?, ?, ?, ?, true, false)
                    """, fieldId, value.fieldCode(), value.fieldNameAr(),
                    value.description(), value.sortOrder());
            }
        }

        int updated = jdbc.update("""
            update core.note_fields
            set name_ar = ?, description = ?, default_sort_order = ?,
                is_active = true, is_deleted = false, updated_at = now()
            where id = ?
            """, value.fieldNameAr(), value.description(), value.sortOrder(), fieldId);
        if (updated != 1) throw new MasterDataOperationException("تعريف البند غير موجود.");

        UUID generated = definitionId == null ? UUID.randomUUID() : definitionId;
        UUID saved;
        try {
            saved = jdbc.queryForObject("""
                insert into core.note_type_fields(
                    id, note_type_id, field_id, sort_order,
                    is_required, is_active, is_deleted)
                values (?, ?, ?, ?, ?, ?, false)
                on conflict (note_type_id, field_id) do update set
                    sort_order = excluded.sort_order,
                    is_required = excluded.is_required,
                    is_active = excluded.is_active,
                    is_deleted = false, updated_at = now()
                returning id
                """, UUID.class, generated, noteType, fieldId, value.sortOrder(),
                    value.isRequired(), value.isActive());
        } catch (DataIntegrityViolationException ex) {
            throw new MasterDataOperationException("نوع المذكرة أو البند غير موجود أو غير صالح.");
        }

        String after = jdbc.queryForObject("""
            select jsonb_build_object(
                'fieldId', cast(? as uuid), 'fieldCode', ?,
                'fieldNameAr', ?, 'sortOrder', ?,
                'isRequired', ?, 'isActive', ?)::text
            """, String.class, fieldId, value.fieldCode(), value.fieldNameAr(),
                value.sortOrder(), value.isRequired(), value.isActive());

        audit(actor, definitionId == null ? "CREATE_NOTE_TYPE_FIELD" :
            "UPDATE_NOTE_TYPE_FIELD", saved, before, after);
        return new NoteTypeFieldDefinition(saved, noteType, fieldId,
            value.fieldCode(), value.fieldNameAr(), value.description(),
            value.sortOrder(), value.isRequired(), value.isActive());
    }

    @Override
    @Transactional
    public void deactivate(UUID definitionId, UUID actor) {
        List<UUID> found = jdbc.query("""
            select note_type_id from core.note_type_fields
            where id = ? and not is_deleted
            """, (rs, row) -> rs.getObject(1, UUID.class), definitionId);
        if (found.isEmpty()) throw new MasterDataOperationException("تعريف البند غير موجود.");
        UUID typeId = found.get(0);
        // All mutating commands lock in this order: type advisory lock, row lock.
        // This prevents save-vs-deactivate inversion deadlocks.
        typeLock(typeId);
        List<UUID> locked = jdbc.query("""
            select note_type_id from core.note_type_fields
            where id = ? and not is_deleted for update
            """, (rs, row) -> rs.getObject(1, UUID.class), definitionId);
        if (locked.isEmpty() || !typeId.equals(locked.get(0))) {
            throw new MasterDataOperationException("تعريف البند غير موجود.");
        }
        String before = jdbc.queryForObject("""
            select jsonb_build_object(
                'isActive', is_active, 'isDeleted', is_deleted)::text
            from core.note_type_fields where id = ?
            """, String.class, definitionId);
        UUID fieldId = jdbc.queryForObject(
            "select field_id from core.note_type_fields where id = ?",
            UUID.class, definitionId);
        jdbc.update("""
            update core.note_type_fields
            set is_active = false, is_deleted = false, updated_at = now()
            where id = ?
            """, definitionId);
        String after = jdbc.queryForObject("""
            select jsonb_build_object(
                'isActive', false, 'isDeleted', false, 'fieldId', cast(? as uuid))::text
            """, String.class, fieldId);
        audit(actor, "DEACTIVATE_NOTE_TYPE_FIELD", definitionId, before, after);
    }
}
