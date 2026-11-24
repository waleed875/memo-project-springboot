package io.github.waleed875.memo.modules.masterdata.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.NoteDefinitionReader;
import io.github.waleed875.memo.modules.masterdata.domain.NoteType;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeField;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeFieldImpactPreview;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Query-only adapter matching original NoteTypeService SQL.
 * No Hibernate DDL or changes to existing database schema.
 */
@Repository
public class JdbcNoteDefinitionReader implements NoteDefinitionReader {
    private final JdbcTemplate jdbc;

    public JdbcNoteDefinitionReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<NoteType> noteTypes() {
        return jdbc.query("""
            select id, code, name_ar, description, is_active
            from core.note_types
            where is_deleted = false
            order by code
            """, (rs, row) -> new NoteType(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name_ar"),
                rs.getString("description"),
                rs.getBoolean("is_active")));
    }

    @Override
    public List<NoteTypeField> fieldsForType(UUID noteTypeId) {
        return jdbc.query("""
            select ntf.id, ntf.note_type_id, nf.id as field_id, nf.code as field_code,
                   nf.name_ar as field_name_ar, ntf.sort_order, ntf.is_required,
                   (ntf.is_active and nf.is_active) as is_active
            from core.note_type_fields ntf
            join core.note_fields nf on nf.id = ntf.field_id
            where ntf.note_type_id = ?
              and ntf.is_deleted = false
              and nf.is_deleted = false
            order by ntf.sort_order, nf.id
            """, (rs, row) -> new NoteTypeField(
                rs.getObject("id", UUID.class),
                rs.getObject("note_type_id", UUID.class),
                rs.getObject("field_id", UUID.class),
                rs.getString("field_code"),
                rs.getString("field_name_ar"),
                rs.getInt("sort_order"),
                rs.getBoolean("is_required"),
                rs.getBoolean("is_active")
            ), noteTypeId);
    }

    @Override
    public NoteTypeFieldImpactPreview preview(UUID noteTypeId, UUID fieldId) {
        return jdbc.queryForObject("""
            select count(*)::int as total_notes,
                   count(nfv.id)::int as existing_values,
                   count(*) filter (where nfv.id is null or nfv.field_value = 0)::int
                       as missing_or_unreviewed
            from core.notes n
            left join core.note_field_values nfv
              on nfv.note_id = n.id and nfv.field_id = cast(? as uuid)
            where n.note_type_id = ? and not n.is_deleted
            """, (rs, row) -> {
                int missing = rs.getInt("missing_or_unreviewed");
                // Match legacy's actual payload, not the misleading property name.
                return new NoteTypeFieldImpactPreview(
                    noteTypeId, fieldId, missing,
                    rs.getInt("existing_values"), missing, fieldId == null);
            }, fieldId, noteTypeId);
    }
}
