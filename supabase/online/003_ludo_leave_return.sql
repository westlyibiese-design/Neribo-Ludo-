-- =====================================================================================
-- Ludo Mate - Online play, Phase 4: leave and return
--
-- Run this ONCE in the Supabase dashboard, AFTER 001_ludo_online.sql and 002_ludo_realtime.sql:
--   SQL Editor > New query > paste all > Run.
-- It is safe to run twice. It only creates or replaces objects whose names start with "ludo_",
-- and the only policies it adds on realtime.messages are named "ludo_rt_presence_..." and never
-- grant anything for a topic that does not start with "ludo:".
--
-- What it adds:
--   ludo_set_result        the host stores the result of the game that just finished
--   ludo_remove_member     the host removes a person whose connection is truly lost
--   ludo_join_room         (replaced) a returning member also gets the game number, the colour
--                          assignment and the stored result back; an expired room shows its result
--   ludo_room_snapshot     (replaced) a removed person is told "removed"; an expired room shows its result
--   ludo_cleanup_expired   optional housekeeping that closes rooms with no activity for 24 hours
--   two Realtime policies  so every member can track and read Presence on the room's "down" channel
-- =====================================================================================

-- ---------- 1. The host stores the latest finished game's result ----------
-- p_result looks like {"winnerName": "...", "scores": [{"name": "...", "score": 2}], "gameNo": 3}.
-- People who return after the host's phone is gone read it back from the room.

create or replace function public.ludo_set_result(p_room uuid, p_result jsonb)
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
  if p_result is null or jsonb_typeof(p_result) <> 'object' or length(p_result::text) > 2000 then
    return jsonb_build_object('ok', false, 'reason', 'bad_args');
  end if;

  select * into v_room from public.ludo_rooms r where r.id = p_room for update;
  if not found then
    return jsonb_build_object('ok', false, 'reason', 'not_found');
  end if;
  if v_room.host_id <> v_uid then
    return jsonb_build_object('ok', false, 'reason', 'not_host');
  end if;
  if v_room.status = 'ended' then
    return jsonb_build_object('ok', false, 'reason', 'ended');
  end if;

  update public.ludo_rooms
     set result = p_result,
         updated_at = now()
   where id = p_room;

  return jsonb_build_object('ok', true);
end;
$$;

-- ---------- 2. The host removes a member ----------
-- The host cannot be removed. Asking twice is fine (the second answer is also ok). A removed person
-- can never come back: ludo_join_room refuses them and ludo_room_snapshot tells them "removed".

create or replace function public.ludo_remove_member(p_room uuid, p_user uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_room public.ludo_rooms%rowtype;
  v_status text;
begin
  if v_uid is null then
    return jsonb_build_object('ok', false, 'reason', 'not_signed_in');
  end if;
  if p_user is null then
    return jsonb_build_object('ok', false, 'reason', 'bad_args');
  end if;

  select * into v_room from public.ludo_rooms r where r.id = p_room for update;
  if not found then
    return jsonb_build_object('ok', false, 'reason', 'not_found');
  end if;
  if v_room.host_id <> v_uid then
    return jsonb_build_object('ok', false, 'reason', 'not_host');
  end if;
  if p_user = v_room.host_id then
    return jsonb_build_object('ok', false, 'reason', 'bad_args');
  end if;
  if v_room.status = 'ended' then
    return jsonb_build_object('ok', false, 'reason', 'ended');
  end if;

  select m.status into v_status
    from public.ludo_room_members m
   where m.room_id = p_room and m.user_id = p_user;
  if v_status is null then
    return jsonb_build_object('ok', false, 'reason', 'not_member');
  end if;
  if v_status = 'removed' then
    return jsonb_build_object('ok', true);
  end if;

  update public.ludo_room_members
     set status = 'removed'
   where room_id = p_room and user_id = p_user;
  update public.ludo_rooms set updated_at = now() where id = p_room;

  return jsonb_build_object('ok', true);
end;
$$;

-- ---------- 3. Join (or rejoin) a room: now also returns the game record to a returning member ----------
-- Everything from 001 stays the same. Differences: an active member who rejoins also gets game_no,
-- assignment and result; an expired room also returns its stored result.

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
    return jsonb_build_object('ok', false, 'reason', 'expired', 'result', v_room.result);
  end if;

  -- Is the caller already a member of this room?
  select * into v_member
    from public.ludo_room_members m
   where m.room_id = v_room.id and m.user_id = v_uid;
  if found then
    if v_member.status = 'removed' then
      return jsonb_build_object('ok', false, 'reason', 'removed');
    elsif v_member.status = 'active' then
      -- Rejoin: hand back the existing seat and role, and the record of the game that is running.
      return jsonb_build_object('ok', true, 'room_id', v_room.id, 'code', v_room.code,
        'seat', v_member.seat, 'role', v_member.role, 'name', v_member.name,
        'status', v_room.status, 'player_count', v_room.player_count,
        'max_watchers', v_room.max_watchers, 'host_id', v_room.host_id,
        'game_no', v_room.game_no, 'assignment', v_room.assignment, 'result', v_room.result);
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
    -- Watcher (allowed while the game is running, as long as a watcher place is free).
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

-- ---------- 4. Room + member list for an active member (polling) ----------
-- Differences from 001: a person the host removed is told "removed" (so they see the right message
-- even if they missed the live one), and an expired room still shows its stored result.
-- An ended room is still returned to its active members, so a returning person can see "Game ended".

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
    if exists (
      select 1 from public.ludo_room_members m
       where m.room_id = p_room and m.user_id = v_uid and m.status = 'removed'
    ) then
      return jsonb_build_object('ok', false, 'reason', 'removed');
    end if;
    return jsonb_build_object('ok', false, 'reason', 'not_member');
  end if;

  if v_room.status <> 'ended' and v_room.updated_at < now() - interval '24 hours' then
    update public.ludo_rooms set status = 'ended' where id = v_room.id;
    return jsonb_build_object('ok', false, 'reason', 'expired', 'result', v_room.result);
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

-- ---------- 5. Optional housekeeping: close rooms with no activity for 24 hours ----------
-- The functions above already treat such a room as expired whenever somebody touches it, so this is
-- only tidiness. Nobody in the app can call it; it is meant for a scheduled job (see below).

create or replace function public.ludo_cleanup_expired()
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  v_count integer;
begin
  update public.ludo_rooms
     set status = 'ended'
   where status <> 'ended'
     and updated_at < now() - interval '24 hours';
  get diagnostics v_count = row_count;
  return v_count;
end;
$$;

-- To run it every day (optional; you decide). First enable the pg_cron extension in the dashboard
-- (Database > Extensions > pg_cron), then run this once in the SQL Editor:
--
--   select cron.schedule('ludo-cleanup-expired', '17 3 * * *', $cron$ select public.ludo_cleanup_expired(); $cron$);
--
-- To stop it again:
--
--   select cron.unschedule('ludo-cleanup-expired');

-- ---------- 6. Realtime Presence on the room's "down" channel ----------
-- Presence tells the host which phones are connected, and tells everybody whether the host is.
-- Only active members of the room may track or read it, and only on "ludo:<room>:down".
-- Presence never grants anything else: the broadcast rules from 002 are unchanged.

drop policy if exists "ludo_rt_presence_receive" on realtime.messages;
create policy "ludo_rt_presence_receive"
  on realtime.messages
  for select
  to authenticated
  using (
    realtime.messages.extension = 'presence'
    and realtime.topic() like 'ludo:%'
    and public.ludo_topic_kind(realtime.topic()) = 'down'
    and public.ludo_is_member(public.ludo_topic_room(realtime.topic()))
  );

drop policy if exists "ludo_rt_presence_send" on realtime.messages;
create policy "ludo_rt_presence_send"
  on realtime.messages
  for insert
  to authenticated
  with check (
    realtime.messages.extension = 'presence'
    and realtime.topic() like 'ludo:%'
    and public.ludo_topic_kind(realtime.topic()) = 'down'
    and public.ludo_is_member(public.ludo_topic_room(realtime.topic()))
  );

-- ---------- Who may call what: signed-in players only (the cleanup: nobody) ----------

revoke all on function public.ludo_set_result(uuid, jsonb) from public, anon;
revoke all on function public.ludo_remove_member(uuid, uuid) from public, anon;
revoke all on function public.ludo_join_room(text, text, boolean) from public, anon;
revoke all on function public.ludo_room_snapshot(uuid) from public, anon;
revoke all on function public.ludo_cleanup_expired() from public, anon, authenticated;

grant execute on function public.ludo_set_result(uuid, jsonb) to authenticated;
grant execute on function public.ludo_remove_member(uuid, uuid) to authenticated;
grant execute on function public.ludo_join_room(text, text, boolean) to authenticated;
grant execute on function public.ludo_room_snapshot(uuid) to authenticated;

-- ---------- How to undo (only ludo_ objects and these two policies; uncomment and run if you ever want to) ----------
-- Note: ludo_join_room and ludo_room_snapshot are replaced in place. To go back to the Phase 2
-- versions, run 001_ludo_online.sql again (it is safe to run twice).
-- drop policy if exists "ludo_rt_presence_send" on realtime.messages;
-- drop policy if exists "ludo_rt_presence_receive" on realtime.messages;
-- drop function if exists public.ludo_cleanup_expired();
-- drop function if exists public.ludo_remove_member(uuid, uuid);
-- drop function if exists public.ludo_set_result(uuid, jsonb);
