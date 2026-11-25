-- CI-ONLY credentials and roles. Applied solely to a disposable PostgreSQL service.
-- Never install this fixture on production or any persistent environment.
begin;
update auth.users
set password_hash = '$2y$10$q2rD9n7.8Fnqcn1NOTGpz./2nEbDLi7jeSBjcqjcM5FLg54Rfand.',
    must_change_password = false,
    is_active = true, is_deleted = false,
    role_id = (select id from auth.roles where code = 'ADMIN'),
    updated_at = now()
where username = 'admin';

insert into auth.users(username,password_hash,full_name,role_id,must_change_password,
                       is_active,is_deleted)
select 'ci_viewer',
       '$2y$10$q2rD9n7.8Fnqcn1NOTGpz./2nEbDLi7jeSBjcqjcM5FLg54Rfand.',
       'CI Browser Read Only',r.id,false,true,false
from auth.roles r where r.code = 'VIEWER'
on conflict (username) do update
set password_hash=excluded.password_hash,
    must_change_password=false,
    is_active=true,is_deleted=false,
    role_id=excluded.role_id,
    updated_at=now();
commit;

do $check$
begin
 if (select count(*) from auth.users where username in ('admin','ci_viewer')
       and not must_change_password and is_active and not is_deleted) <> 2 then
   raise exception 'CI auth fixture must have two active users';
 end if;
end $check$;
