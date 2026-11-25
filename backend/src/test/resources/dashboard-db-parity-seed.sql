-- Phase 54. Disposable PostgreSQL 16 DB ONLY, after replaying pinned original migrations 001-070.
-- UUIDs and business dates are frozen to make Java and C# read the exact same rows.
\set ON_ERROR_STOP on
begin;
insert into core.companies(id,name) values
('e2000000-0000-4000-8000-000000000001','شركة المعيار أ'),
('e2000000-0000-4000-8000-000000000002','شركة المعيار ب');

insert into core.factories(id,company_id,name) values
('e2000000-0000-4000-8000-000000000003',
 'e2000000-0000-4000-8000-000000000001','مصنع المعيار');
insert into core.production_lines(id,factory_id,name) values
('e2000000-0000-4000-8000-000000000004',
 'e2000000-0000-4000-8000-000000000003','خط المعيار');

with seeded(id,note_number,type_code,company_id,writing_date,arrival_at,status,
     approved_at,created_at,is_deleted,is_loot,factory_id,line_id) as (
 values
 ('e2000000-0000-4000-8000-000000000011'::uuid,'5101','ENTRY',
  'e2000000-0000-4000-8000-000000000001'::uuid,'2026-11-05'::date,
  '2026-11-10 08:00'::timestamp,'APPROVED','2026-11-10 10:00'::timestamp,
  '2026-11-10 08:00'::timestamp,false,false,
  'e2000000-0000-4000-8000-000000000003'::uuid,
  'e2000000-0000-4000-8000-000000000004'::uuid),
 ('e2000000-0000-4000-8000-000000000012'::uuid,'5102','ENTRY',
  'e2000000-0000-4000-8000-000000000001'::uuid,'2026-11-10'::date,
  '2026-11-17 09:00'::timestamp,'HAS_ERRORS',null::timestamp,
  '2026-11-17 09:00'::timestamp,false,false,
  'e2000000-0000-4000-8000-000000000003'::uuid,
  'e2000000-0000-4000-8000-000000000004'::uuid),
 ('e2000000-0000-4000-8000-000000000013'::uuid,'5103','ENTRY',
  'e2000000-0000-4000-8000-000000000001'::uuid,null::date,
  '2026-11-19 10:00'::timestamp,'NEEDS_REVIEW',null::timestamp,
  '2026-11-19 10:00'::timestamp,false,false,
  null::uuid,null::uuid),
 ('e2000000-0000-4000-8000-000000000014'::uuid,'5104','EXIT',
  'e2000000-0000-4000-8000-000000000001'::uuid,'2026-10-29'::date,
  '2026-11-04 08:00'::timestamp,'APPROVED','2026-11-05 09:00'::timestamp,
  '2026-11-04 08:00'::timestamp,false,false,
  null::uuid,null::uuid),
 ('e2000000-0000-4000-8000-000000000015'::uuid,'5105','ENTRY',
  'e2000000-0000-4000-8000-000000000001'::uuid,'2026-11-12'::date,
  '2026-11-13 08:00'::timestamp,'COMPLETE',null::timestamp,
  '2026-11-13 08:00'::timestamp,false,true,
  null::uuid,null::uuid),
 ('e2000000-0000-4000-8000-000000000016'::uuid,'5106','ENTRY',
  'e2000000-0000-4000-8000-000000000002'::uuid,'2026-11-14'::date,
  '2026-11-16 08:00'::timestamp,'APPROVED','2026-11-16 11:00'::timestamp,
  '2026-11-16 08:00'::timestamp,false,false,
  null::uuid,null::uuid),
 ('e2000000-0000-4000-8000-000000000017'::uuid,'5107','ENTRY',
  'e2000000-0000-4000-8000-000000000001'::uuid,'2026-11-08'::date,
  '2026-11-21 08:00'::timestamp,'APPROVED','2026-11-21 11:00'::timestamp,
  '2026-11-21 08:00'::timestamp,true,false,
  null::uuid,null::uuid),
 ('e2000000-0000-4000-8000-000000000018'::uuid,'5108','ENTRY',
  'e2000000-0000-4000-8000-000000000001'::uuid,'2026-11-21'::date,
  '2026-11-20 08:00'::timestamp,'COMPLETE',null::timestamp,
  '2026-11-20 08:00'::timestamp,false,false,
  null::uuid,null::uuid)
)
insert into core.notes(
 id,note_number,note_number_normalized,note_type_id,company_id,
 writing_date,arrival_at,status,approved_at,created_at,
 is_deleted,is_loot,factory_id,production_line_id,created_by)
select s.id,s.note_number,s.note_number,
 nt.id,s.company_id,s.writing_date,s.arrival_at,s.status,
 s.approved_at,s.created_at,s.is_deleted,s.is_loot,
 s.factory_id,s.line_id,
 (select u.id from auth.users u where u.username='admin')
from seeded s join core.note_types nt on nt.code=s.type_code;

-- Expected indicator rows already come from original seed type-to-field mapping.
-- 1=correct, 2=error, 6=N/A; absent value row=unreviewed.
insert into core.note_field_values(note_id,field_id,field_value,created_by)
select v.note_id,nf.id,v.field_value,
       (select id from auth.users where username='admin')
from (values
  ('e2000000-0000-4000-8000-000000000011'::uuid,1),
  ('e2000000-0000-4000-8000-000000000012'::uuid,2),
  ('e2000000-0000-4000-8000-000000000015'::uuid,6),
  ('e2000000-0000-4000-8000-000000000016'::uuid,1)
) as v(note_id,field_value)
cross join core.note_fields nf where nf.code='factory';

insert into core.duplicate_incidents(
 classification,created_note_id,matched_note_id,company_id,
 note_type_id,note_number,note_number_normalized,writing_date)
select 'COMPANY_DUPLICATE_NUMBER',
 'e2000000-0000-4000-8000-000000000012'::uuid,
 'e2000000-0000-4000-8000-000000000011'::uuid,
 'e2000000-0000-4000-8000-000000000001'::uuid,
 nt.id,'5102','5102','2026-11-10'::date
from core.note_types nt where nt.code='ENTRY';

insert into core.duplicate_incidents(
 classification,created_note_id,matched_note_id,company_id,
 note_type_id,note_number,note_number_normalized,writing_date)
select 'COMPANY_RESEND',null::uuid,
 'e2000000-0000-4000-8000-000000000011'::uuid,
 'e2000000-0000-4000-8000-000000000001'::uuid,
 nt.id,'5101','5101','2026-11-05'::date
from core.note_types nt where nt.code='ENTRY';

-- A real ENTRY-to-EXIT link, validated and synchronized by migrations 063/070.
insert into core.note_links(source_note_id,related_note_id,created_by)
select
 'e2000000-0000-4000-8000-000000000011'::uuid,
 'e2000000-0000-4000-8000-000000000014'::uuid,
 id from auth.users where username='admin';

commit;

do $checks$
begin
  if (select count(*) from core.notes where id::text like 'e2000000-0000-4000-8000-%') <> 8 then
    raise exception 'Stage 54 dataset mismatch';
  end if;
  if to_regprocedure('reporting.fn_company_metrics_snapshot_v4_filtered(uuid,date,date,uuid,uuid,uuid,text,date,text)') is null then
    raise exception 'Canonical original v4 filtered function was not migrated';
  end if;
end
$checks$;
