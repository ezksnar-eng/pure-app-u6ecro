-- جيرون: تحديث كامل لقاعدة البيانات (آمن تعيده أكثر من مرة)
create table if not exists public.profiles(
 id uuid primary key references auth.users(id) on delete cascade,
 username text unique not null check (username ~ '^[a-z0-9_]{3,20}$'),
 name text not null default '' check (char_length(name)<=40),
 bio text not null default '' check (char_length(bio)<=200),
 avatar text, created_at timestamptz default now());
create table if not exists public.messages(
 id uuid primary key default gen_random_uuid(),
 sender uuid not null references public.profiles(id) on delete cascade,
 receiver uuid not null references public.profiles(id) on delete cascade,
 body text not null default '', media text, deleted boolean not null default false, read_at timestamptz,
 created_at timestamptz not null default now(), check (sender<>receiver), check (char_length(body)>0 or media is not null));
alter table public.profiles add column if not exists last_seen timestamptz, add column if not exists pubkey text, add column if not exists banner text,
 add column if not exists status text not null default '', add column if not exists verified boolean not null default false,
 add column if not exists is_admin boolean not null default false, add column if not exists banned boolean not null default false,
 add column if not exists welcomed boolean not null default false;
alter table public.messages add column if not exists kind text not null default 'text',
 add column if not exists reply_to uuid references public.messages(id) on delete set null,
 add column if not exists edited_at timestamptz, add column if not exists reactions jsonb not null default '{}'::jsonb;
alter table public.messages add column if not exists updated_at timestamptz not null default now(), add column if not exists fwd boolean not null default false;
alter table public.messages drop constraint if exists messages_body_check;
alter table public.messages drop constraint if exists msg_body_len;
alter table public.messages add constraint msg_body_len check (char_length(body)<=20000);
create index if not exists msgs_s on public.messages(sender,created_at);
create index if not exists msgs_r on public.messages(receiver,created_at);
create index if not exists msgs_ru on public.messages(receiver,updated_at);
create index if not exists msgs_su on public.messages(sender,updated_at);
create table if not exists public.blocks(blocker uuid references public.profiles(id) on delete cascade, blocked uuid references public.profiles(id) on delete cascade, created_at timestamptz default now(), primary key(blocker,blocked), check(blocker<>blocked));
create table if not exists public.key_backups(user_id uuid primary key references public.profiles(id) on delete cascade, backup text not null);
create table if not exists public.bot_config(id int primary key default 1 check(id=1), name text, bio text, avatar text, banner text, welcome text, notice text, rules jsonb not null default '[]'::jsonb, maintenance boolean not null default false, reg_open boolean not null default true);
insert into public.bot_config(id) values(1) on conflict do nothing;
create table if not exists public.admin_secret(h text not null);
create or replace function public.norm_ar(t text) returns text language sql immutable as $$ select regexp_replace(translate(lower(btrim(coalesce(t,''))),'أإآىة','ااايه'),'[[:space:]]+',' ','g') $$;
delete from public.admin_secret;
insert into public.admin_secret(h) values(encode(sha256(convert_to(public.norm_ar('جيري اون توب'),'UTF8')),'hex'));
create table if not exists public.reports(id uuid primary key default gen_random_uuid(), reporter uuid references public.profiles(id) on delete cascade, reported uuid references public.profiles(id) on delete cascade, reason text default '', created_at timestamptz default now());
create table if not exists public.announcements(id uuid primary key default gen_random_uuid(), body text not null, created_at timestamptz default now());
alter table public.profiles enable row level security; alter table public.messages enable row level security; alter table public.blocks enable row level security;
alter table public.key_backups enable row level security; alter table public.bot_config enable row level security; alter table public.admin_secret enable row level security;
alter table public.reports enable row level security; alter table public.announcements enable row level security;
create or replace function public.is_admin() returns boolean language sql security definer stable set search_path=public as $$
 select coalesce((select is_admin from public.profiles where id=auth.uid()),false) $$;
create or replace function public.guard_msg() returns trigger language plpgsql security definer set search_path=public as $$
declare d boolean:=new.deleted; r timestamptz:=new.read_at; b text:=new.body; e timestamptz:=new.edited_at; x jsonb:=new.reactions;
begin new:=old; new.reactions:=x;
 if auth.uid()=old.sender then new.deleted:=d; new.body:=b; new.edited_at:=e; end if;
 if auth.uid()=old.receiver then new.read_at:=r; end if;
 return new; end $$;
create or replace function public.set_updated() returns trigger language plpgsql as $$ begin new.updated_at:=now(); return new; end $$;
drop trigger if exists trg_set_updated on public.messages;
create trigger trg_set_updated before insert or update on public.messages for each row execute function public.set_updated();
drop trigger if exists trg_guard_msg on public.messages;
create trigger trg_guard_msg before update on public.messages for each row execute function public.guard_msg();
create or replace function public.guard_profile() returns trigger language plpgsql security definer set search_path=public as $$
begin if not (public.is_admin() or coalesce(current_setting('jeron.claim',true),'')='1') then
  new.is_admin:=old.is_admin; new.verified:=old.verified; new.banned:=old.banned; end if; return new; end $$;
drop trigger if exists trg_guard_profile on public.profiles;
create trigger trg_guard_profile before update on public.profiles for each row execute function public.guard_profile();
create or replace function public.handle_new_user() returns trigger language plpgsql security definer set search_path=public as $$
begin
 if not coalesce((select reg_open from public.bot_config where id=1),true) then raise exception 'REG_CLOSED'; end if;
 insert into public.profiles(id,username,name) values(new.id, lower(new.raw_user_meta_data->>'username'), coalesce(new.raw_user_meta_data->>'name',''));
 return new; end $$;
drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users for each row execute function public.handle_new_user();
create or replace function public.username_free(u text) returns boolean language sql security definer set search_path=public as $$
 select not exists(select 1 from public.profiles where username=lower(u)) $$;
grant execute on function public.username_free(text) to anon, authenticated;
drop function if exists public.find_user(text);
create function public.find_user(q text) returns table(id uuid,username text,name text,bio text,avatar text,status text,verified boolean,is_admin boolean,banned boolean,pubkey text,last_seen timestamptz) language sql security definer set search_path=public,auth as $$
 select p.id,p.username,p.name,p.bio,p.avatar,p.status,p.verified,p.is_admin,p.banned,p.pubkey,p.last_seen from public.profiles p where p.id<>auth.uid() and length(trim(q))>=2 and (
  p.username like lower(replace(trim(q),'%',''))||'%' or lower(p.name) like '%'||lower(replace(trim(q),'%',''))||'%'
  or p.id in (select u.id from auth.users u where lower(u.email)=lower(trim(q)))) limit 20 $$;
grant execute on function public.find_user(text) to authenticated;
create or replace function public.claim_admin(p text) returns boolean language plpgsql security definer set search_path=public as $$
begin
 if auth.uid() is null then return false; end if;
 if exists(select 1 from public.admin_secret where h=encode(sha256(convert_to(public.norm_ar(p),'UTF8')),'hex')) then
  perform set_config('jeron.claim','1',true);
  update public.profiles set is_admin=true where id=auth.uid();
  return true;
 end if; return false; end $$;
grant execute on function public.claim_admin(text) to authenticated;
create or replace function public.admin_stats() returns json language plpgsql security definer set search_path=public as $$
begin if not public.is_admin() then raise exception 'forbidden'; end if;
 return json_build_object('users',(select count(*) from public.profiles),'messages',(select count(*) from public.messages),
  'today',(select count(*) from public.messages where created_at>now()-interval '1 day'),'banned',(select count(*) from public.profiles where banned),
  'admins',(select count(*) from public.profiles where is_admin),'reports',(select count(*) from public.reports)); end $$;
grant execute on function public.admin_stats() to authenticated;
create or replace function public.admin_delete_user(uid uuid) returns void language plpgsql security definer set search_path=public,auth as $$
begin if not public.is_admin() then raise exception 'forbidden'; end if; delete from auth.users where id=uid; end $$;
grant execute on function public.admin_delete_user(uuid) to authenticated;
create or replace function public.delete_me() returns void language sql security definer set search_path=public,auth as $$
 delete from auth.users where id=auth.uid() $$;
grant execute on function public.delete_me() to authenticated;
drop policy if exists "p_read" on public.profiles; drop policy if exists "p_upd" on public.profiles; drop policy if exists "p_upd_admin" on public.profiles;
drop policy if exists "m_read" on public.messages; drop policy if exists "m_ins" on public.messages; drop policy if exists "m_upd" on public.messages;
drop policy if exists "b_read" on public.blocks; drop policy if exists "b_ins" on public.blocks; drop policy if exists "b_del" on public.blocks;
drop policy if exists "kb_all" on public.key_backups; drop policy if exists "bc_read" on public.bot_config; drop policy if exists "bc_upd" on public.bot_config;
drop policy if exists "r_ins" on public.reports; drop policy if exists "r_read" on public.reports; drop policy if exists "r_del" on public.reports;
drop policy if exists "a_read" on public.announcements; drop policy if exists "a_ins" on public.announcements; drop policy if exists "a_del" on public.announcements;
create policy "p_read" on public.profiles for select to authenticated using(true);
create policy "p_upd" on public.profiles for update to authenticated using(id=auth.uid()) with check(id=auth.uid());
create policy "p_upd_admin" on public.profiles for update to authenticated using(public.is_admin()) with check(public.is_admin());
create policy "m_read" on public.messages for select to authenticated using(sender=auth.uid() or receiver=auth.uid());
create policy "m_ins" on public.messages for insert to authenticated with check(sender=auth.uid()
 and not coalesce((select banned from public.profiles where id=auth.uid()),false)
 and not exists(select 1 from public.blocks b where (b.blocker=receiver and b.blocked=auth.uid()) or (b.blocker=auth.uid() and b.blocked=receiver)));
create policy "m_upd" on public.messages for update to authenticated using(sender=auth.uid() or receiver=auth.uid());
create policy "b_read" on public.blocks for select to authenticated using(blocker=auth.uid() or blocked=auth.uid());
create policy "b_ins" on public.blocks for insert to authenticated with check(blocker=auth.uid());
create policy "b_del" on public.blocks for delete to authenticated using(blocker=auth.uid());
create policy "kb_all" on public.key_backups for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy "bc_read" on public.bot_config for select to anon, authenticated using(true);
create policy "bc_upd" on public.bot_config for update to authenticated using(public.is_admin()) with check(public.is_admin());
create policy "r_ins" on public.reports for insert to authenticated with check(reporter=auth.uid());
create policy "r_read" on public.reports for select to authenticated using(public.is_admin());
create policy "r_del" on public.reports for delete to authenticated using(public.is_admin());
create policy "a_read" on public.announcements for select to authenticated using(true);
create policy "a_ins" on public.announcements for insert to authenticated with check(public.is_admin());
create policy "a_del" on public.announcements for delete to authenticated using(public.is_admin());
do $$ begin alter publication supabase_realtime add table public.messages; exception when others then null; end $$;
do $$ begin alter publication supabase_realtime add table public.blocks; exception when others then null; end $$;
do $$ begin alter publication supabase_realtime add table public.profiles; exception when others then null; end $$;
do $$ begin alter publication supabase_realtime add table public.announcements; exception when others then null; end $$;
do $$ begin alter publication supabase_realtime add table public.bot_config; exception when others then null; end $$;
insert into storage.buckets(id,name,public) values('media','media',true) on conflict (id) do update set public=true;
drop policy if exists "media_up" on storage.objects; drop policy if exists "media_upd" on storage.objects; drop policy if exists "media_sel" on storage.objects;
create policy "media_up" on storage.objects for insert to authenticated with check(bucket_id='media' and (storage.foldername(name))[1]=auth.uid()::text);
create policy "media_upd" on storage.objects for update to authenticated using(bucket_id='media' and (storage.foldername(name))[1]=auth.uid()::text);
create policy "media_sel" on storage.objects for select to authenticated using(bucket_id='media');

-- ===== المجموعات + الدعم + الملصقات + إدارة إضافية =====
do $$ declare c record; begin for c in select conname from pg_constraint where conrelid='public.messages'::regclass and contype='c' and pg_get_constraintdef(oid) ilike '%sender <> receiver%' loop execute format('alter table public.messages drop constraint %I',c.conname); end loop; end $$;
alter table public.messages drop constraint if exists messages_reply_to_fkey;
alter table public.messages add column if not exists gid uuid, add column if not exists mid uuid;
create index if not exists msgs_gid on public.messages(gid);
alter table public.profiles add column if not exists banned_until timestamptz, add column if not exists banned_reason text;
alter table public.bot_config add column if not exists kb text, add column if not exists flags jsonb not null default '{}'::jsonb;
create table if not exists public.groups(id uuid primary key default gen_random_uuid(), name text not null, avatar text, owner uuid references public.profiles(id) on delete set null, created_at timestamptz default now());
create table if not exists public.group_members(group_id uuid references public.groups(id) on delete cascade, user_id uuid references public.profiles(id) on delete cascade, role text not null default 'member', joined_at timestamptz default now(), primary key(group_id,user_id));
create table if not exists public.tickets(id uuid primary key default gen_random_uuid(), user_id uuid references public.profiles(id) on delete cascade, subject text not null, body text not null, status text not null default 'open', reply text, created_at timestamptz default now(), replied_at timestamptz);
create table if not exists public.sticker_packs(id uuid primary key default gen_random_uuid(), name text not null, created_at timestamptz default now());
create table if not exists public.stickers(id uuid primary key default gen_random_uuid(), pack uuid references public.sticker_packs(id) on delete cascade, path text not null, created_at timestamptz default now());
create table if not exists public.admin_log(id uuid primary key default gen_random_uuid(), admin uuid, action text, target uuid, created_at timestamptz default now());
alter table public.groups enable row level security; alter table public.group_members enable row level security; alter table public.tickets enable row level security;
alter table public.sticker_packs enable row level security; alter table public.stickers enable row level security; alter table public.admin_log enable row level security;
create or replace function public.is_member(gid uuid) returns boolean language sql security definer stable set search_path=public as $$ select exists(select 1 from public.group_members where group_id=gid and user_id=auth.uid()) $$;
create or replace function public.is_member_of(gid uuid, uid uuid) returns boolean language sql security definer stable set search_path=public as $$ select exists(select 1 from public.group_members where group_id=gid and user_id=uid) $$;
create or replace function public.create_group(gname text, members uuid[]) returns uuid language plpgsql security definer set search_path=public as $$
declare g uuid:=gen_random_uuid(); u uuid;
begin if auth.uid() is null then raise exception 'auth'; end if;
 insert into public.groups(id,name,owner) values(g,left(gname,50),auth.uid());
 insert into public.group_members(group_id,user_id,role) values(g,auth.uid(),'owner');
 foreach u in array members loop if u<>auth.uid() then insert into public.group_members(group_id,user_id) values(g,u) on conflict do nothing; end if; end loop;
 return g; end $$;
create or replace function public.group_add(gid uuid, uid uuid) returns void language plpgsql security definer set search_path=public as $$
begin if not exists(select 1 from public.group_members where group_id=gid and user_id=auth.uid() and role in ('owner','admin')) then raise exception 'forbidden'; end if;
 if (select count(*) from public.group_members where group_id=gid)>=40 then raise exception 'full'; end if;
 insert into public.group_members(group_id,user_id) values(gid,uid) on conflict do nothing; end $$;
create or replace function public.group_remove(gid uuid, uid uuid) returns void language plpgsql security definer set search_path=public as $$
begin if uid<>auth.uid() then
  if not exists(select 1 from public.group_members where group_id=gid and user_id=auth.uid() and role in ('owner','admin')) then raise exception 'forbidden'; end if;
  if exists(select 1 from public.group_members where group_id=gid and user_id=uid and role='owner') then raise exception 'owner'; end if; end if;
 delete from public.group_members where group_id=gid and user_id=uid; end $$;
create or replace function public.group_leave(gid uuid) returns void language plpgsql security definer set search_path=public as $$
declare was text;
begin select role into was from public.group_members where group_id=gid and user_id=auth.uid();
 delete from public.group_members where group_id=gid and user_id=auth.uid();
 if was='owner' then update public.group_members set role='owner' where group_id=gid and user_id=(select user_id from public.group_members where group_id=gid order by (role='admin') desc, joined_at limit 1); end if;
 if not exists(select 1 from public.group_members where group_id=gid) then delete from public.groups where id=gid; end if; end $$;
create or replace function public.group_update(gid uuid, gname text, gavatar text) returns void language plpgsql security definer set search_path=public as $$
begin if not exists(select 1 from public.group_members where group_id=gid and user_id=auth.uid() and role in ('owner','admin')) then raise exception 'forbidden'; end if;
 update public.groups set name=left(gname,50), avatar=gavatar where id=gid; end $$;
create or replace function public.group_role(gid uuid, uid uuid, r text) returns void language plpgsql security definer set search_path=public as $$
begin if not exists(select 1 from public.group_members where group_id=gid and user_id=auth.uid() and role='owner') then raise exception 'forbidden'; end if;
 if r not in ('admin','member') then raise exception 'role'; end if; update public.group_members set role=r where group_id=gid and user_id=uid and role<>'owner'; end $$;
create or replace function public.admin_daily() returns json language plpgsql security definer set search_path=public as $$
begin if not public.is_admin() then raise exception 'forbidden'; end if;
 return json_build_object('days',(select coalesce(json_agg(x),'[]'::json) from (select to_char(date_trunc('day',created_at),'MM-DD') d, count(*) c from public.messages where created_at>now()-interval '7 days' group by 1 order by 1) x),
  'top',(select coalesce(json_agg(y),'[]'::json) from (select p.username u, count(*) c from public.messages m join public.profiles p on p.id=m.sender where m.created_at>now()-interval '7 days' group by 1 order by 2 desc limit 5) y)); end $$;
grant execute on function public.is_member(uuid), public.is_member_of(uuid,uuid), public.create_group(text,uuid[]), public.group_add(uuid,uuid), public.group_remove(uuid,uuid), public.group_leave(uuid), public.group_update(uuid,text,text), public.group_role(uuid,uuid,text), public.admin_daily() to authenticated;
drop policy if exists "g_read" on public.groups; drop policy if exists "gm_read" on public.group_members; drop policy if exists "t_ins" on public.tickets; drop policy if exists "t_read" on public.tickets; drop policy if exists "t_upd" on public.tickets; drop policy if exists "t_del" on public.tickets;
drop policy if exists "sp_read" on public.sticker_packs; drop policy if exists "sp_all" on public.sticker_packs; drop policy if exists "s_read" on public.stickers; drop policy if exists "s_all" on public.stickers; drop policy if exists "al_all" on public.admin_log; drop policy if exists "m_ins" on public.messages;
create policy "g_read" on public.groups for select to authenticated using(public.is_member(id));
create policy "gm_read" on public.group_members for select to authenticated using(public.is_member(group_id));
create policy "t_ins" on public.tickets for insert to authenticated with check(user_id=auth.uid());
create policy "t_read" on public.tickets for select to authenticated using(user_id=auth.uid() or public.is_admin());
create policy "t_upd" on public.tickets for update to authenticated using(public.is_admin()) with check(public.is_admin());
create policy "t_del" on public.tickets for delete to authenticated using(public.is_admin());
create policy "sp_read" on public.sticker_packs for select to authenticated using(true);
create policy "sp_all" on public.sticker_packs for all to authenticated using(public.is_admin()) with check(public.is_admin());
create policy "s_read" on public.stickers for select to authenticated using(true);
create policy "s_all" on public.stickers for all to authenticated using(public.is_admin()) with check(public.is_admin());
create policy "al_all" on public.admin_log for all to authenticated using(public.is_admin()) with check(public.is_admin());
create policy "m_ins" on public.messages for insert to authenticated with check(sender=auth.uid()
 and not coalesce((select banned or coalesce(banned_until>now(),false) from public.profiles where id=auth.uid()),false)
 and (gid is null or (public.is_member(gid) and public.is_member_of(gid,receiver)))
 and (gid is not null or (sender<>receiver and not exists(select 1 from public.blocks b where (b.blocker=receiver and b.blocked=auth.uid()) or (b.blocker=auth.uid() and b.blocked=receiver)))));
insert into storage.buckets(id,name,public) values('stickers','stickers',true) on conflict (id) do update set public=true;
drop policy if exists "stk_up" on storage.objects; drop policy if exists "stk_del" on storage.objects;
create policy "stk_up" on storage.objects for insert to authenticated with check(bucket_id='stickers' and public.is_admin());
create policy "stk_del" on storage.objects for delete to authenticated using(bucket_id='stickers' and public.is_admin());
do $$ begin perform set_config('jeron.claim','1',true); update public.profiles set is_admin=true where username='ezksnaruser_ag55'; end $$;

-- ===== النسخة 3: صلاحيات المجموعات، رسائل مؤقتة، استطلاعات، أجهزة، تقييم الدعم =====
alter table public.messages add column if not exists expires_at timestamptz;
create index if not exists msgs_exp on public.messages(expires_at) where expires_at is not null;
alter table public.groups add column if not exists descr text, add column if not exists rules text, add column if not exists welcome text,
 add column if not exists mode text not null default 'all', add column if not exists is_public boolean not null default false,
 add column if not exists approval boolean not null default false, add column if not exists invite text, add column if not exists pinned_mid uuid;
alter table public.group_members add column if not exists perms jsonb not null default '{}'::jsonb, add column if not exists muted_until timestamptz;
alter table public.tickets add column if not exists rating int;
create table if not exists public.group_requests(group_id uuid references public.groups(id) on delete cascade, user_id uuid references public.profiles(id) on delete cascade, created_at timestamptz default now(), primary key(group_id,user_id));
create table if not exists public.poll_votes(pid uuid not null, user_id uuid not null references public.profiles(id) on delete cascade, opt int not null, primary key(pid,user_id));
create table if not exists public.devices(user_id uuid references public.profiles(id) on delete cascade, device_id text not null, label text, created_at timestamptz default now(), last_seen timestamptz default now(), primary key(user_id,device_id));
alter table public.group_requests enable row level security; alter table public.poll_votes enable row level security; alter table public.devices enable row level security;
create or replace function public.has_perm(gid uuid, p text) returns boolean language sql security definer stable set search_path=public as $$
 select exists(select 1 from public.group_members where group_id=gid and user_id=auth.uid() and (role in ('owner','admin') or coalesce((perms->>p)::boolean,false))) $$;
create or replace function public.group_add(gid uuid, uid uuid) returns void language plpgsql security definer set search_path=public as $$
begin if not public.has_perm(gid,'add') then raise exception 'forbidden'; end if;
 if (select count(*) from public.group_members where group_id=gid)>=40 then raise exception 'full'; end if;
 insert into public.group_members(group_id,user_id) values(gid,uid) on conflict do nothing; end $$;
create or replace function public.group_remove(gid uuid, uid uuid) returns void language plpgsql security definer set search_path=public as $$
begin if uid<>auth.uid() then
  if not public.has_perm(gid,'remove') then raise exception 'forbidden'; end if;
  if exists(select 1 from public.group_members where group_id=gid and user_id=uid and role='owner') then raise exception 'owner'; end if; end if;
 delete from public.group_members where group_id=gid and user_id=uid; end $$;
create or replace function public.group_update(gid uuid, gname text, gavatar text) returns void language plpgsql security definer set search_path=public as $$
begin if not public.has_perm(gid,'edit') then raise exception 'forbidden'; end if;
 update public.groups set name=left(gname,50), avatar=gavatar where id=gid; end $$;
create or replace function public.group_set(gid uuid, gdescr text, grules text, gwelcome text, gmode text, gpublic boolean, gapproval boolean) returns void language plpgsql security definer set search_path=public as $$
begin if not public.has_perm(gid,'edit') then raise exception 'forbidden'; end if;
 update public.groups set descr=left(gdescr,300), rules=left(grules,1000), welcome=left(gwelcome,500), mode=case when gmode='admins' then 'admins' else 'all' end, is_public=gpublic, approval=gapproval where id=gid; end $$;
create or replace function public.group_invite(gid uuid) returns text language plpgsql security definer set search_path=public as $$
declare c text:=substr(md5(random()::text||clock_timestamp()::text),1,8);
begin if not public.has_perm(gid,'add') then raise exception 'forbidden'; end if; update public.groups set invite=c where id=gid; return c; end $$;
create or replace function public.group_join(code text) returns text language plpgsql security definer set search_path=public as $$
declare g public.groups;
begin select * into g from public.groups where invite=lower(trim(code)); if g.id is null then return 'invalid'; end if;
 if exists(select 1 from public.group_members where group_id=g.id and user_id=auth.uid()) then return 'joined'; end if;
 if (select count(*) from public.group_members where group_id=g.id)>=40 then return 'full'; end if;
 if g.approval then insert into public.group_requests(group_id,user_id) values(g.id,auth.uid()) on conflict do nothing; return 'pending'; end if;
 insert into public.group_members(group_id,user_id) values(g.id,auth.uid()) on conflict do nothing; return 'joined'; end $$;
create or replace function public.group_join_public(gid uuid) returns text language plpgsql security definer set search_path=public as $$
declare g public.groups;
begin select * into g from public.groups where id=gid and is_public; if g.id is null then return 'invalid'; end if;
 if exists(select 1 from public.group_members where group_id=g.id and user_id=auth.uid()) then return 'joined'; end if;
 if (select count(*) from public.group_members where group_id=g.id)>=40 then return 'full'; end if;
 if g.approval then insert into public.group_requests(group_id,user_id) values(g.id,auth.uid()) on conflict do nothing; return 'pending'; end if;
 insert into public.group_members(group_id,user_id) values(g.id,auth.uid()) on conflict do nothing; return 'joined'; end $$;
create or replace function public.group_approve(gid uuid, uid uuid, ok boolean) returns void language plpgsql security definer set search_path=public as $$
begin if not public.has_perm(gid,'add') then raise exception 'forbidden'; end if;
 if ok and (select count(*) from public.group_members where group_id=gid)<40 then insert into public.group_members(group_id,user_id) values(gid,uid) on conflict do nothing; end if;
 delete from public.group_requests where group_id=gid and user_id=uid; end $$;
create or replace function public.group_mute(gid uuid, uid uuid, minutes int) returns void language plpgsql security definer set search_path=public as $$
begin if not public.has_perm(gid,'mute') then raise exception 'forbidden'; end if;
 update public.group_members set muted_until=case when minutes>0 then now()+make_interval(mins=>minutes) else null end where group_id=gid and user_id=uid and role<>'owner'; end $$;
create or replace function public.group_perms(gid uuid, uid uuid, p jsonb) returns void language plpgsql security definer set search_path=public as $$
begin if not exists(select 1 from public.group_members where group_id=gid and user_id=auth.uid() and role='owner') then raise exception 'forbidden'; end if;
 update public.group_members set perms=p where group_id=gid and user_id=uid and role<>'owner'; end $$;
create or replace function public.group_pin(gid uuid, mid uuid) returns void language plpgsql security definer set search_path=public as $$
begin if not public.has_perm(gid,'pin') then raise exception 'forbidden'; end if; update public.groups set pinned_mid=mid where id=gid; end $$;
create or replace function public.public_groups(q text) returns table(id uuid,name text,avatar text,descr text,members bigint,approval boolean) language sql security definer set search_path=public as $$
 select g.id,g.name,g.avatar,g.descr,(select count(*) from public.group_members m where m.group_id=g.id),g.approval from public.groups g
 where g.is_public and (coalesce(q,'')='' or lower(g.name) like '%'||lower(q)||'%') order by 5 desc limit 30 $$;
create or replace function public.purge_expired() returns void language sql security definer set search_path=public as $$ delete from public.messages where expires_at is not null and expires_at<now() $$;
create or replace function public.ticket_rate(tid uuid, r int) returns void language sql security definer set search_path=public as $$ update public.tickets set rating=greatest(1,least(5,r)) where id=tid and user_id=auth.uid() $$;
grant execute on function public.has_perm(uuid,text), public.group_set(uuid,text,text,text,text,boolean,boolean), public.group_invite(uuid), public.group_join(text), public.group_join_public(uuid), public.group_approve(uuid,uuid,boolean), public.group_mute(uuid,uuid,int), public.group_perms(uuid,uuid,jsonb), public.group_pin(uuid,uuid), public.public_groups(text), public.purge_expired(), public.ticket_rate(uuid,int) to authenticated;
drop policy if exists "gr_read" on public.group_requests; drop policy if exists "pv_read" on public.poll_votes; drop policy if exists "pv_ins" on public.poll_votes; drop policy if exists "pv_upd" on public.poll_votes; drop policy if exists "pv_del" on public.poll_votes; drop policy if exists "dv_all" on public.devices; drop policy if exists "m_ins" on public.messages;
create policy "gr_read" on public.group_requests for select to authenticated using(public.has_perm(group_id,'add') or user_id=auth.uid());
create policy "pv_read" on public.poll_votes for select to authenticated using(exists(select 1 from public.messages m where (m.mid=poll_votes.pid or m.id=poll_votes.pid) and (m.sender=auth.uid() or m.receiver=auth.uid())));
create policy "pv_ins" on public.poll_votes for insert to authenticated with check(user_id=auth.uid() and exists(select 1 from public.messages m where (m.mid=pid or m.id=pid) and (m.sender=auth.uid() or m.receiver=auth.uid())));
create policy "pv_upd" on public.poll_votes for update to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy "pv_del" on public.poll_votes for delete to authenticated using(user_id=auth.uid());
create policy "dv_all" on public.devices for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy "m_ins" on public.messages for insert to authenticated with check(sender=auth.uid()
 and not coalesce((select banned or coalesce(banned_until>now(),false) from public.profiles where id=auth.uid()),false)
 and (gid is null or (public.is_member(gid) and public.is_member_of(gid,receiver)
      and not exists(select 1 from public.group_members gm where gm.group_id=gid and gm.user_id=auth.uid() and gm.muted_until>now())
      and (coalesce((select mode from public.groups where id=gid),'all')='all' or public.has_perm(gid,'post'))))
 and (gid is not null or (sender<>receiver and not exists(select 1 from public.blocks b where (b.blocker=receiver and b.blocked=auth.uid()) or (b.blocker=auth.uid() and b.blocked=receiver)))));
alter table public.bot_config add column if not exists llm_url text;
notify pgrst, 'reload schema';
