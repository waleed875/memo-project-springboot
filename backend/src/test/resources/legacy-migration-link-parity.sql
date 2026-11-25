-- Run only after applying the actual, untouched 001-070 migrations on
-- an EMPTY disposable PostgreSQL 16 database. Raises on the first mismatch.
\set ON_ERROR_STOP on
begin;
do $parity$
declare
    company_id uuid := gen_random_uuid();
    foreign_company_id uuid := gen_random_uuid();
    entry_type_id uuid;
    exit_type_id uuid;
    admin_id uuid;
    e uuid := gen_random_uuid();
    a uuid := gen_random_uuid();
    b uuid := gen_random_uuid();
    c uuid := gen_random_uuid();
    foreign_exit uuid := gen_random_uuid();
    other_entry uuid := gen_random_uuid();
    loot_entry uuid := gen_random_uuid();
    link_id uuid;
    exit_source_id uuid;
    loot_source_id uuid;
    actual int;
    current_type text;
    loot_flag boolean;
begin
    select id into entry_type_id from core.note_types where code='ENTRY';
    select id into exit_type_id from core.note_types where code='EXIT';
    select id into admin_id from auth.users limit 1;
    if entry_type_id is null or exit_type_id is null or admin_id is null then
        raise exception 'Migration seed data missing ENTRY/EXIT or admin';
    end if;
    insert into core.companies(id,name) values
        (company_id,'Parity company'),(foreign_company_id,'Foreign company');
    insert into core.notes(id,note_number,note_number_normalized,note_type_id,
                           company_id,writing_date,arrival_at,created_by,status,is_loot)
    values
        (e,'101','101',entry_type_id,company_id,current_date,current_date,admin_id,'COMPLETE',false),
        (a,'201','201',exit_type_id,company_id,current_date,current_date,admin_id,'COMPLETE',false),
        (b,'202','202',exit_type_id,company_id,current_date,current_date,admin_id,'COMPLETE',false),
        (c,'203','203',exit_type_id,company_id,current_date,current_date,admin_id,'COMPLETE',false),
        (foreign_exit,'204','204',exit_type_id,foreign_company_id,current_date,current_date,admin_id,'COMPLETE',false),
        (other_entry,'102','102',entry_type_id,company_id,current_date,current_date,admin_id,'COMPLETE',false),
        (loot_entry,'103','103',entry_type_id,company_id,current_date,current_date,admin_id,'COMPLETE',true);

    -- Direct legacy link -> new source reference, and compatibility view
    -- must count it exactly once, not one row per each physical representation.
    insert into core.note_links(source_note_id,related_note_id,created_by)
        values(e,a,admin_id);
    select s.id into exit_source_id from core.note_sources s where s.note_id=e;
    if exit_source_id is null then raise exception 'Legacy link did not create EXIT source'; end if;
    select count(*) into actual from core.note_source_exit_refs
        where source_id=exit_source_id and exit_note_id=a;
    if actual <> 1 then raise exception 'Legacy link not reflected exactly once in exit refs: %', actual; end if;
    select count(*) into actual from core.v_note_links_compat
        where (source_note_id=e and related_note_id=a)
           or (source_note_id=a and related_note_id=e);
    if actual <> 1 then raise exception 'Compatibility view duplicated legacy link: %', actual; end if;

    -- Migration 063 supports more than one EXIT; migration 070 preserves it.
    insert into core.note_links(source_note_id,related_note_id,created_by)
        values(e,b,admin_id);
    select count(*) into actual from core.note_source_exit_refs
        where source_id=exit_source_id;
    if actual <> 2 then raise exception 'Second EXIT reference lost: %', actual; end if;

    -- Reverse orientation deletes must remove their matching source ref.
    delete from core.note_links where source_note_id=e and related_note_id=a;
    select count(*) into actual from core.note_source_exit_refs
        where source_id=exit_source_id and exit_note_id=a;
    if actual <> 0 then raise exception 'Unlink did not remove source ref'; end if;

    -- Source-side changes must propagate back to legacy physical table.
    insert into core.note_source_exit_refs(source_id,exit_note_id,reference_order)
        values(exit_source_id,c,10);
    select count(*) into actual from core.note_links
        where (source_note_id=e and related_note_id=c)
           or (source_note_id=c and related_note_id=e);
    if actual <> 1 then raise exception 'Source ref did not create legacy link exactly once: %', actual; end if;
    select count(*) into actual from core.v_note_links_compat
        where (source_note_id=e and related_note_id=c)
           or (source_note_id=c and related_note_id=e);
    if actual <> 1 then raise exception 'Source-side compatibility projection duplicated: %', actual; end if;
    delete from core.note_source_exit_refs where source_id=exit_source_id and exit_note_id=c;
    select count(*) into actual from core.note_links
        where (source_note_id=e and related_note_id=c)
           or (source_note_id=c and related_note_id=e);
    if actual <> 0 then raise exception 'Source ref delete did not remove legacy link'; end if;

    -- The 070 trigger permits clean LOOT -> EXIT source transition.
    insert into core.note_sources(note_id,source_type,created_by)
        values(loot_entry,'LOOT',admin_id) returning id into loot_source_id;
    insert into core.note_source_loot(source_id,description)
        values(loot_source_id,'Original loot payload');
    insert into core.note_links(source_note_id,related_note_id,created_by)
        values(loot_entry,a,admin_id);
    select source_type into current_type from core.note_sources where id=loot_source_id;
    select is_loot into loot_flag from core.notes where id=loot_entry;
    if current_type <> 'EXIT' or loot_flag then
        raise exception 'LOOT transition failed; source %, loot %',current_type,loot_flag;
    end if;
    select count(*) into actual from core.note_source_loot where source_id=loot_source_id;
    if actual <> 0 then raise exception 'LOOT payload not cleared on transition'; end if;

    -- Reject cross-company and same-type links via original DB triggers.
    begin
        insert into core.note_links(source_note_id,related_note_id,created_by)
            values(e,foreign_exit,admin_id);
        raise exception 'Invalid cross-company link was accepted';
    exception when check_violation then null;
    end;
    begin
        insert into core.note_links(source_note_id,related_note_id,created_by)
            values(e,other_entry,admin_id);
        raise exception 'Invalid same-type link was accepted';
    exception when check_violation then null;
    end;

    -- The final pair set and source references must agree exactly.
    select count(*) into actual
    from core.note_links l
    where not exists (
       select 1 from core.v_note_links_compat v
       where (v.source_note_id=l.source_note_id and v.related_note_id=l.related_note_id)
          or (v.source_note_id=l.related_note_id and v.related_note_id=l.source_note_id)
    );
    if actual <> 0 then raise exception 'Physical links missing from compatibility view: %',actual; end if;

    raise notice 'PASS: actual migrations 063/070 synchronized legacy links, EXIT sources, LOOT conversion and validations';
end
$parity$;
rollback;
