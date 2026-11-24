-- TEST ONLY: subset of legacy 002 and 003, never apply to production.
alter table core.note_field_values
    add column created_by uuid references auth.users(id),
    add column updated_by uuid references auth.users(id),
    add column updated_at timestamp not null default now();
-- Trigger DDL uses a dollar-quoted PL/pgSQL body. Execute it as a single
-- JDBC statement in NoteDraftEditPostgresIT, not through ResourceDatabasePopulator.
