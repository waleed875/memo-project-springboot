-- TEST ONLY: subset of legacy 002 and 003, never apply to production.
alter table core.note_field_values
    add column created_by uuid references auth.users(id),
    add column updated_by uuid references auth.users(id),
    add column updated_at timestamp not null default now();
create schema if not exists common;
create or replace function common.set_updated_at() returns trigger
language plpgsql as $$
begin
    new.updated_at = now();
    return new;
end;
$$;
create trigger trg_notes_updated_for_edit
before update on core.notes for each row execute function common.set_updated_at();
