-- Isolated TEST FIXTURE, derived from the read-only legacy migration files.
-- core.companies: 002_create_tables.sql, 008_simplify_companies.sql
-- core.factories: 016_add_factories.sql, 017_link_factories_to_companies.sql
-- core.production_lines: 024_add_production_lines.sql
-- This is NOT the full schema and must NEVER be applied to production.
create schema if not exists core;

create table core.companies (
    id uuid primary key default gen_random_uuid(),
    name varchar(255) not null,
    is_active boolean not null default true,
    is_deleted boolean not null default false,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);

create table core.factories (
    id uuid primary key default gen_random_uuid(),
    name varchar(200) not null,
    is_active boolean not null default true,
    is_deleted boolean not null default false,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now(),
    company_id uuid references core.companies(id)
);

create unique index ux_factories_company_name_active
    on core.factories(company_id, lower(name)) where is_deleted = false;

create table core.production_lines (
    id uuid primary key default gen_random_uuid(),
    factory_id uuid not null references core.factories(id),
    name varchar(200) not null,
    is_active boolean not null default true,
    is_deleted boolean not null default false,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);
create unique index ux_production_lines_factory_name_active
    on core.production_lines(factory_id, lower(name)) where is_deleted = false;
