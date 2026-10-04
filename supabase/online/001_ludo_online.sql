-- =====================================================================================
-- Ludo Mate - Online play, Phase 2: rooms and lobby
--
-- Run this ONCE in the Supabase dashboard: SQL Editor > New query > paste all > Run.
-- It is safe to run twice. It only creates objects whose names start with "ludo_" and it
-- never touches anything else in the project.
--
-- Design: clients never read or write the tables directly. Every action is a SECURITY DEFINER
-- function that checks auth.uid(), and only signed-in players ("authenticated") may call them.
-- =====================================================================================

-- ---------- Tables ----------

create table if not exists public.ludo_rooms (
  id uuid primary key default gen_random_uuid(),
  code text not null,
  host_id uuid not null references auth.users(id) on delete cascade,
  player_count int not null check (player_count between 2 and 4),
  max_watchers int not null default 0 check (max_watchers between 0 and 4),
  status text not null default 'waiting' check (status in ('waiting','playing','ended')),
  game_no int not null default 0,
  assignment jsonb,          -- Phase 3: seat -> engine player for the current game
  result jsonb,              -- Phase 4: last finished game's winner and scores
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- A code is unique only among rooms that are still alive, so old codes can be used again later.
create unique index if not exists ludo_rooms_live_code
  on public.ludo_rooms (code) where status <> 'ended';

create table if not exists public.ludo_room_members (
  room_id uuid not null references public.ludo_rooms(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  seat int check (seat between 0 and 3),      -- null for watchers; 0 = host
  role text not null check (role in ('host','player','watcher')),
  name text not null,
  status text not null default 'active' check (status in ('active','left','removed')),
  joined_at timestamptz not null default now(),
  primary key (room_id, user_id)
);

create unique index if not exists ludo_members_seat
  on public.ludo_room_members (room_id, seat)
  where seat is not null and status = 'active';

-- Row Level Security on, and deliberately NO policies: the app can only use the functions below.
alter table public.ludo_rooms enable row level security;
alter table public.ludo_room_members enable row level security;

-- Belt and braces: also take away the table privileges that Supabase hands out by default.
-- (The functions below run as their owner, so they are not affected.)
revoke all on table public.ludo_rooms from anon, authenticated;
revoke all on table public.ludo_room_members from anon, authenticated;

-- ---------- Small internal helpers (not callable by the app) ----------

-- Trims, removes control characters, keeps at most 14 characters; falls back to "Player".
create or replace function public.ludo_clean_name(p_name text)
returns text
language sql
immutable
set search_path = public
as $$
  select coalesce(
    nullif(btrim(left(btrim(regexp_replace(coalesce(p_name, ''), '[[:cntrl:]]', '', 'g')), 14)), ''),
    'Player'
  )
$$;

-- Makes a name unique among the ACTIVE members of a room, ignoring case:
-- "Joy" -> "Joy (2)" -> "Joy (3)", always within 14 characters.
create or replace function public.ludo_unique_name(p_room uuid, p_name text)
returns text
language plpgsql
set search_path = public
as $$
declare
  v_try text := p_name;
  v_n int := 2;
  v_suffix text;
begin
  while exists (
    select 1 from public.ludo_room_members m
    where m.room_id = p_room and m.status = 'active' and lower(m.name) = lower(v_try)
  ) loop
    v_suffix := ' (' || v_n || ')';
    v_try := btrim(left(p_name, 14 - length(v_suffix))) || v_suffix;
    v_n := v_n + 1;
  end loop;
  return v_try;
end;
$$;

revoke all on function public.ludo_clean_name(text) from public, anon, authenticated;
revoke all on function public.ludo_unique_name(uuid, text) from public, anon, authenticated;

-- ---------- Membership check (Phase 3 policies will use this) ----------

create or replace function public.ludo_is_member(p_room uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1 from public.ludo_room_members m
    where m.room_id = p_room and m.user_id = auth.uid() and m.status = 'active'
  )
$$;

revoke all on function public.ludo_is_member(uuid) from public, anon;
grant execute on function public.ludo_is_member(uuid) to authenticated;

-- ---------- 1. Create a room ----------

create or replace function public.ludo_create_room(p_player_count int, p_max_watchers int, p_name text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_name text;
  v_other record;
  v_alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  v_code text;
  v_room uuid;
begin
  if v_uid is null then
    return jsonb_build_object('ok', false, 'reason', 'not_signed_in');
  end if;
  if p_player_count is null or p_player_count < 2 or p_player_count > 4
     or p_max_watchers is null or p_max_watchers < 0 or p_max_watchers > 4 then
    return jsonb_build_object('ok', false, 'reason', 'bad_args');
  end if;

  -- One request at a time per person, so a double tap cannot create two rooms.
  perform pg_advisory_xact_lock(hashtext('ludo:' || v_uid::text));

  v_name := public.ludo_clean_name(p_name);

  -- Already an active member of another live (not ended, not expired) room?
  select r.id as id, r.code as code, r.status as status, m.role as role
    into v_other
    from public.ludo_room_members m
    join public.ludo_rooms r on r.id = m.room_id
   where m.user_id = v_uid
     and m.status = 'active'
     and r.status <> 'ended'
     and r.updated_at > now() - interval '24 hours'
   order by m.joined_at desc
   limit 1;
  if found then
    return jsonb_build_object('ok', false, 'reason', 'busy', 'code', v_other.code,
      'room_id', v_other.id, 'role', v_other.role, 'status', v_other.status);
  end if;

  -- A random 4-character code; a clash with a live room is retried up to 20 times.
  for i in 1..20 loop
    v_code := '';
    for j in 1..4 loop
      v_code := v_code || substr(v_alphabet, 1 + floor(random() * 32)::int, 1);
    end loop;
    begin
      insert into public.ludo_rooms (code, host_id, player_count, max_watchers)
      values (v_code, v_uid, p_player_count, p_max_watchers)
      returning id into v_room;
      exit;
    exception when unique_violation then
      v_room := null;
    end;
  end loop;

  if v_room is null then
    return jsonb_build_object('ok', false, 'reason', 'bad_args');
  end if;

  insert into public.ludo_room_members (room_id, user_id, seat, role, name, status)
  values (v_room, v_uid, 0, 'host', v_name, 'active');

  return jsonb_build_object('ok', true, 'room_id', v_room, 'code', v_code, 'seat', 0,
    'role', 'host', 'name', v_name, 'status', 'waiting',
    'player_count', p_player_count, 'max_watchers', p_max_watchers, 'host_id', v_uid);
end;
$$;

-- ---------- 2. Join (or rejoin) a room, as a player or a watcher ----------

create or replace function public.ludo_join_room(p_code text, p_name text, p_watch boolean default false)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_code text;
  v_room public.ludo_rooms%rowtype;
  v_member public.ludo_room_members%rowtype;
  v_other record;
  v_name text;
  v_seat int;
  v_count int;
  v_status text;
begin
  if v_uid is null then
    return jsonb_build_object('ok', false, 'reason', 'not_signed_in');
  end if;

  perform pg_advisory_xact_lock(hashtext('ludo:' || v_uid::text));

  v_code := upper(btrim(coalesce(p_code, '')));
  if v_code !~ '^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}$' then
    return jsonb_build_object('ok', false, 'reason', 'not_found');
  end if;

  -- The live room with this code if there is one, otherwise the newest closed one. Locked.
  select * into v_room
    from public.ludo_rooms r
   where r.code = v_code
   order by (r.status <> 'ended') desc, r.created_at desc
   limit 1
   for update;
  if not found then
    return jsonb_build_object('ok', false, 'reason', 'not_found');
  end if;

  if v_room.status = 'ended' then
    return jsonb_build_object('ok', false, 'reason', 'ended', 'result', v_room.result);
  end if;
  if v_room.updated_at < now() - interval '24 hours' then
    update public.ludo_rooms set status = 'ended' where id = v_room.id;
    return jsonb_build_object('ok', false, 'reason', 'expired');
  end if;

  -- Is the caller already a member of this room?
  select * into v_member
    from public.ludo_room_members m
   where m.room_id = v_room.id and m.user_id = v_uid;
  if found then
    if v_member.status = 'removed' then
      return jsonb_build_object('ok', false, 'reason', 'removed');
    elsif v_member.status = 'active' then
      -- Rejoin: simply hand back the existing seat and role.
      return jsonb_build_object('ok', true, 'room_id', v_room.id, 'code', v_room.code,
        'seat', v_member.seat, 'role', v_member.role, 'name', v_member.name,
        'status', v_room.status, 'player_count', v_room.player_count,
        'max_watchers', v_room.max_watchers, 'host_id', v_room.host_id);
    end if;
    -- status 'left': treated as a new join below.
  end if;

  -- An active member of a DIFFERENT live room?
  select r.id as id, r.code as code, r.status as status, m.role as role
    into v_other
    from public.ludo_room_members m
    join public.ludo_rooms r on r.id = m.room_id
   where m.user_id = v_uid
     and m.status = 'active'
     and r.id <> v_room.id
     and r.status <> 'ended'
     and r.updated_at > now() - interval '24 hours'
   order by m.joined_at desc
   limit 1;
  if found then
    return jsonb_build_object('ok', false, 'reason', 'busy', 'code', v_other.code,
      'room_id', v_other.id, 'role', v_other.role, 'status', v_other.status);
  end if;

  v_name := public.ludo_unique_name(v_room.id, public.ludo_clean_name(p_name));

  if coalesce(p_watch, false) then
    -- Watcher.
    if v_room.max_watchers = 0 then
      return jsonb_build_object('ok', false, 'reason', 'watchers_off');
    end if;
    select count(*) into v_count
      from public.ludo_room_members m
     where m.room_id = v_room.id and m.status = 'active' and m.role = 'watcher';
    if v_count >= v_room.max_watchers then
      return jsonb_build_object('ok', false, 'reason', 'watchers_full');
    end if;

    insert into public.ludo_room_members (room_id, user_id, seat, role, name, status)
    values (v_room.id, v_uid, null, 'watcher', v_name, 'active')
    on conflict (room_id, user_id) do update
      set seat = null, role = 'watcher', name = excluded.name, status = 'active', joined_at = now();

    update public.ludo_rooms set updated_at = now() where id = v_room.id;

    return jsonb_build_object('ok', true, 'room_id', v_room.id, 'code', v_room.code,
      'seat', null::int, 'role', 'watcher', 'name', v_name, 'status', v_room.status,
      'player_count', v_room.player_count, 'max_watchers', v_room.max_watchers,
      'host_id', v_room.host_id);
  end if;

  -- Player.
  if v_room.status <> 'waiting' then
    return jsonb_build_object('ok', false, 'reason', 'started');
  end if;

  -- The lowest free seat from 1 up (seat 0 belongs to the host).
  select s into v_seat
    from generate_series(1, v_room.player_count - 1) s
   where not exists (
     select 1 from public.ludo_room_members m
      where m.room_id = v_room.id and m.status = 'active' and m.seat = s
   )
   order by s
   limit 1;

  if v_seat is null then
    select count(*) into v_count
      from public.ludo_room_members m
     where m.room_id = v_room.id and m.status = 'active' and m.role = 'watcher';
    return jsonb_build_object('ok', false, 'reason', 'full',
      'watchers_available', (v_room.max_watchers > 0 and v_count < v_room.max_watchers));
  end if;

  insert into public.ludo_room_members (room_id, user_id, seat, role, name, status)
  values (v_room.id, v_uid, v_seat, 'player', v_name, 'active')
  on conflict (room_id, user_id) do update
    set seat = excluded.seat, role = 'player', name = excluded.name, status = 'active', joined_at = now();

  -- Automatic start: the moment the last player seat is filled the room is "playing".
  select count(*) into v_count
    from public.ludo_room_members m
   where m.room_id = v_room.id and m.status = 'active' and m.role in ('host', 'player');
  if v_count >= v_room.player_count then
    update public.ludo_rooms set status = 'playing', updated_at = now() where id = v_room.id;
    v_status := 'playing';
  else
    update public.ludo_rooms set updated_at = now() where id = v_room.id;
    v_status := 'waiting';
  end if;

  return jsonb_build_object('ok', true, 'room_id', v_room.id, 'code', v_room.code,
    'seat', v_seat, 'role', 'player', 'name', v_name, 'status', v_status,
    'player_count', v_room.player_count, 'max_watchers', v_room.max_watchers,
    'host_id', v_room.host_id);
end;
$$;

-- ---------- 3. Room + member list, for an active member (the app polls this) ----------

create or replace function public.ludo_room_snapshot(p_room uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_room public.ludo_rooms%rowtype;
begin
  if v_uid is null then
    return jsonb_build_object('ok', false, 'reason', 'not_signed_in');
  end if;

  select * into v_room from public.ludo_rooms r where r.id = p_room;
  if not found then
    return jsonb_build_object('ok', false, 'reason', 'not_found');
  end if;

  if not exists (
    select 1 from public.ludo_room_members m
     where m.room_id = p_room and m.user_id = v_uid and m.status = 'active'
  ) then
    return jsonb_build_object('ok', false, 'reason', 'not_member');
  end if;

  -- An ended room is still returned, so the app can react to it.
  if v_room.status <> 'ended' and v_room.updated_at < now() - interval '24 hours' then
    update public.ludo_rooms set status = 'ended' where id = v_room.id;
    return jsonb_build_object('ok', false, 'reason', 'expired');
  end if;

  return jsonb_build_object(
    'ok', true,
    'room', jsonb_build_object(
      'id', v_room.id,
      'code', v_room.code,
      'player_count', v_room.player_count,
      'max_watchers', v_room.max_watchers,
      'status', v_room.status,
      'game_no', v_room.game_no,
      'assignment', v_room.assignment,
      'result', v_room.result,
      'host_id', v_room.host_id
    ),
    'members', coalesce((
      select jsonb_agg(
        jsonb_build_object('user_id', m.user_id, 'seat', m.seat, 'role', m.role, 'name', m.name)
        order by m.seat nulls last, m.joined_at
      )
      from public.ludo_room_members m
      where m.room_id = v_room.id and m.status = 'active'
    ), '[]'::jsonb)
  );
end;
$$;

-- ---------- 4. The caller's current live room ----------

create or replace function public.ludo_my_room()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_row record;
begin
  if v_uid is null then
    return jsonb_build_object('ok', false, 'reason', 'not_signed_in');
  end if;

  select r.id as id, r.code as code, r.status as status, m.role as role, m.seat as seat
    into v_row
    from public.ludo_room_members m
    join public.ludo_rooms r on r.id = m.room_id
   where m.user_id = v_uid
     and m.status = 'active'
     and r.status <> 'ended'
     and r.updated_at > now() - interval '24 hours'
   order by m.joined_at desc
   limit 1;
  if not found then
    return jsonb_build_object('ok', false, 'reason', 'not_found');
  end if;

  return jsonb_build_object('ok', true, 'room_id', v_row.id, 'code', v_row.code,
    'status', v_row.status, 'role', v_row.role, 'seat', v_row.seat);
end;
$$;

-- ---------- 5. Leave a room ----------
-- Before the game starts a guest's seat is freed, and the host leaving closes the room.
-- After the game starts a watcher leaves, but a player keeps the seat so they can come back.

create or replace function public.ludo_leave_room(p_room uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_room public.ludo_rooms%rowtype;
  v_role text;
begin
  if v_uid is null then
    return jsonb_build_object('ok', false, 'reason', 'not_signed_in');
  end if;

  select * into v_room from public.ludo_rooms r where r.id = p_room for update;
  if not found then
    return jsonb_build_object('ok', false, 'reason', 'not_found');
  end if;

  select m.role into v_role
    from public.ludo_room_members m
   where m.room_id = p_room and m.user_id = v_uid and m.status = 'active';
  if v_role is null then
    return jsonb_build_object('ok', false, 'reason', 'not_member');
  end if;

  if v_room.status = 'ended' then
    return jsonb_build_object('ok', true, 'status', 'ended');
  end if;

  if v_room.status = 'waiting' then
    if v_role = 'host' then
      update public.ludo_rooms set status = 'ended', updated_at = now() where id = p_room;
      return jsonb_build_object('ok', true, 'status', 'ended');
    end if;
    update public.ludo_room_members set status = 'left'
     where room_id = p_room and user_id = v_uid;
    update public.ludo_rooms set updated_at = now() where id = p_room;
    return jsonb_build_object('ok', true, 'status', 'waiting');
  end if;

  -- status = 'playing'
  if v_role = 'watcher' then
    update public.ludo_room_members set status = 'left'
     where room_id = p_room and user_id = v_uid;
  end if;
  return jsonb_build_object('ok', true, 'status', v_room.status);
end;
$$;

-- ---------- 6. The host closes the room ----------

create or replace function public.ludo_end_room(p_room uuid, p_result jsonb default null)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_room public.ludo_rooms%rowtype;
begin
  if v_uid is null then
    return jsonb_build_object('ok', false, 'reason', 'not_signed_in');
  end if;

  select * into v_room from public.ludo_rooms r where r.id = p_room for update;
  if not found then
    return jsonb_build_object('ok', false, 'reason', 'not_found');
  end if;
  if v_room.host_id <> v_uid then
    return jsonb_build_object('ok', false, 'reason', 'not_host');
  end if;

  update public.ludo_rooms
     set status = 'ended',
         result = coalesce(p_result, result),
         updated_at = now()
   where id = p_room;

  return jsonb_build_object('ok', true, 'status', 'ended');
end;
$$;

-- ---------- Who may call what: signed-in players only ----------

revoke all on function public.ludo_create_room(int, int, text) from public, anon;
revoke all on function public.ludo_join_room(text, text, boolean) from public, anon;
revoke all on function public.ludo_room_snapshot(uuid) from public, anon;
revoke all on function public.ludo_my_room() from public, anon;
revoke all on function public.ludo_leave_room(uuid) from public, anon;
revoke all on function public.ludo_end_room(uuid, jsonb) from public, anon;

grant execute on function public.ludo_create_room(int, int, text) to authenticated;
grant execute on function public.ludo_join_room(text, text, boolean) to authenticated;
grant execute on function public.ludo_room_snapshot(uuid) to authenticated;
grant execute on function public.ludo_my_room() to authenticated;
grant execute on function public.ludo_leave_room(uuid) to authenticated;
grant execute on function public.ludo_end_room(uuid, jsonb) to authenticated;

-- ---------- How to undo (only ludo_ objects; uncomment and run if you ever want to) ----------
-- drop function if exists public.ludo_end_room(uuid, jsonb);
-- drop function if exists public.ludo_leave_room(uuid);
-- drop function if exists public.ludo_my_room();
-- drop function if exists public.ludo_room_snapshot(uuid);
-- drop function if exists public.ludo_join_room(text, text, boolean);
-- drop function if exists public.ludo_create_room(int, int, text);
-- drop function if exists public.ludo_is_member(uuid);
-- drop function if exists public.ludo_unique_name(uuid, text);
-- drop function if exists public.ludo_clean_name(text);
-- drop table if exists public.ludo_room_members;
-- drop table if exists public.ludo_rooms;
