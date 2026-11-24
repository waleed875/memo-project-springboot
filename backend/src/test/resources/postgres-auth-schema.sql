-- PostgreSQL auth fixture copied/derived from the read-only legacy repository
-- waleed875/memo_project @ dd8eb2ba57864eac2371a3addd0b786ccefa2367
-- Exact auth table definitions: 002_create_tables.sql
-- must_change_password: 015_secure_bootstrap_admin.sql
-- normalized username uniqueness: 039_normalize_usernames.sql
-- updates.releases / updates.audit_log: 046_unify_dashboard_contract_and_updates.sql
--
-- INTENTIONALLY ONLY the auth/audit slice, not migration 001-070 as a whole.
-- DO NOT USE AS A PRODUCTION MIGRATION SCRIPT.

create schema if not exists auth;
create schema if not exists updates;

create table if not exists auth.roles (
    id uuid primary key default gen_random_uuid(),
    code varchar(50) unique not null,
    name_ar varchar(100) not null,
    is_active boolean not null default true,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);

create table if not exists auth.users (
    id uuid primary key default gen_random_uuid(),
    username varchar(100) unique not null,
    password_hash text not null,
    full_name varchar(255) not null,
    role_id uuid not null references auth.roles(id),
    is_active boolean not null default true,
    is_deleted boolean not null default false,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);

alter table auth.users
    add column if not exists must_change_password boolean not null default false;

create unique index if not exists ux_auth_users_username_normalized
    on auth.users (lower(trim(username)));

create table if not exists updates.releases (
    id uuid primary key default gen_random_uuid(),
    version varchar(30) not null unique,
    status varchar(20) not null default 'DRAFT',
    release_notes text not null default '',
    minimum_version varchar(30),
    minimum_database_version varchar(20),
    target_database_version varchar(20),
    published_at timestamp,
    published_by uuid references auth.users(id),
    created_at timestamp not null default now(),
    created_by uuid references auth.users(id),
    constraint ck_updates_release_status check (status in ('DRAFT', 'PUBLISHED', 'STOPPED'))
);

create table if not exists updates.audit_log (
    id bigserial primary key,
    user_id uuid references auth.users(id),
    action varchar(80) not null,
    release_id uuid references updates.releases(id),
    description text,
    created_at timestamp not null default now()
);
