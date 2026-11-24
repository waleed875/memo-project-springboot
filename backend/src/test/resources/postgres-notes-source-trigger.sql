create or replace function core.validate_source_exit_reference_test()
returns trigger language plpgsql as $$
declare source_company uuid;
declare exit_company uuid;
declare type_code text;
declare source_kind text;
begin
    select n.company_id, s.source_type into source_company, source_kind
    from core.note_sources s join core.notes n on n.id = s.note_id
    where s.id = new.source_id and not n.is_deleted;
    select n.company_id, nt.code into exit_company, type_code
    from core.notes n join core.note_types nt on nt.id = n.note_type_id
    where n.id = new.exit_note_id and not n.is_deleted;
    if source_kind is distinct from 'EXIT'
       or source_company is distinct from exit_company
       or type_code is distinct from 'EXIT' then
        raise exception using errcode='23514',
            message='invalid cross-company or cross-type exit source reference';
    end if;
    return new;
end; $
create trigger trg_validate_source_exit_reference_test
before insert or update on core.note_source_exit_refs
for each row execute function core.validate_source_exit_reference_test()@@
