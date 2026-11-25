-- TEST ONLY: exact v3 indicator fact view from original C# migration 060.
-- Never deploy this fixture in place of the original SQL migrations.
create schema if not exists reporting;
create or replace view reporting.vw_note_indicator_facts_v3 as
select
    n.id as note_id,
    n.note_number,
    n.company_id,
    n.factory_id,
    n.production_line_id,
    n.note_type_id,
    nt.code as note_type_code,
    n.status as workflow_status,
    n.created_at,
    n.writing_date,
    n.arrival_at,
    n.created_by,
    ntf.field_id,
    nf.code as field_code,
    nf.name_ar as field_name,
    (nfv.id is not null) as has_value_row,
    nfv.field_value as stored_field_value,
    coalesce(nfv.field_value, 0)::smallint as effective_field_value,
    (nfv.id is null or nfv.field_value = 0) as is_unreviewed,
    (nfv.field_value between 1 and 6) as is_reviewed,
    (nfv.field_value between 1 and 5) as is_applicable_reviewed,
    (nfv.field_value = 1) as is_correct,
    (nfv.field_value between 2 and 5) as is_error,
    (nfv.field_value = 6) as is_not_applicable
from core.notes n
join core.note_types nt
  on nt.id = n.note_type_id
join core.note_type_fields ntf
  on ntf.note_type_id = n.note_type_id
 and ntf.is_active
 and not ntf.is_deleted
join core.note_fields nf
  on nf.id = ntf.field_id
 and nf.is_active
 and not nf.is_deleted
left join core.note_field_values nfv
  on nfv.note_id = n.id
 and nfv.field_id = ntf.field_id
where not n.is_deleted;
