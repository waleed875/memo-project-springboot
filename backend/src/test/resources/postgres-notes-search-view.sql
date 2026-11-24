-- TEST ONLY projection of reporting.vw_note_details from legacy migration 030.
-- The production query uses its already-migrated view; never apply this
-- fixture to production or treat it as a full 001-070 schema replay.
create schema if not exists reporting;

create view reporting.vw_note_details as
select n.id as note_id, n.note_number,
       nt.id as note_type_id, nt.name_ar as note_type_name,
       c.id as company_id, c.name as company_name,
       n.factory_id, f.name as factory_name,
       n.production_line_id, pl.name as production_line_name,
       n.writing_date, n.arrival_at, n.status, n.image_path,
       n.created_at, n.created_by, creator.full_name as created_by_name,
       n.review_started_at, n.approved_at, n.approved_by,
       approver.full_name as approved_by_name,
       count(ntf.field_id)::int as total_fields,
       count(nfv.field_id) filter (where nfv.field_value = 1)::int as active_count,
       count(nfv.field_id) filter (where nfv.field_value between 2 and 6)::int as inactive_count,
       count(nfv.field_id) filter (where nfv.field_value = 0)::int as unknown_count,
       coalesce(round(
           count(nfv.field_id) filter (where nfv.field_value = 1)::numeric * 100 /
           nullif(count(nfv.field_id) filter (where nfv.field_value <> 0), 0), 2
       ), 0) as accuracy_percentage,
       coalesce(round(
           count(nfv.field_id) filter (where nfv.field_value between 2 and 6)::numeric * 100 /
           nullif(count(nfv.field_id) filter (where nfv.field_value <> 0), 0), 2
       ), 0) as error_percentage
from core.notes n
join core.note_types nt on nt.id = n.note_type_id
join core.companies c on c.id = n.company_id
left join core.factories f on f.id = n.factory_id
left join core.production_lines pl on pl.id = n.production_line_id
left join auth.users creator on creator.id = n.created_by
left join auth.users approver on approver.id = n.approved_by
join core.note_type_fields ntf on ntf.note_type_id = n.note_type_id
    and ntf.is_active and not ntf.is_deleted
join core.note_fields nf on nf.id = ntf.field_id and nf.is_active and not nf.is_deleted
left join core.note_field_values nfv on nfv.note_id = n.id and nfv.field_id = ntf.field_id
where not n.is_deleted
group by n.id, nt.id, nt.name_ar, c.id, c.name, f.id, f.name, pl.id, pl.name,
         creator.full_name, approver.full_name;
