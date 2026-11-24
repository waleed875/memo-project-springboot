-- TEST ONLY: an isolated subset of migration 040/062.
-- Do not use this fixture on live databases.
alter table core.notes
    add column note_number_normalized varchar(100),
    add column note_number_value bigint,
    add column note_year integer;

create index ix_notes_duplicate_numeric_test
    on core.notes(company_id,note_type_id,note_year,note_number_value)
    where not is_deleted and note_number_value is not null;
