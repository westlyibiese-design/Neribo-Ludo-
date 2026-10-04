-- =====================================================================================
-- Ludo Mate - Online play, Phase 3: the online game (Realtime)
--
-- Run this ONCE in the Supabase dashboard, AFTER 001_ludo_online.sql:
--   SQL Editor > New query > paste all > Run.
-- It is safe to run twice. It only creates objects whose names start with "ludo_", and the
-- only policies it adds on realtime.messages are named "ludo_rt_..." and never grant anything
-- for a topic that does not start with "ludo:".
--
-- Game messages travel over private Realtime Broadcast channels. Three topic shapes exist:
--   ludo:<room id>:down            host -> everybody in the room
--   ludo:<room id>:to:<user id>    host -> one person
--   ludo:<room id>:up:<user id>    one person -> host
-- The user id inside a topic is checked against the signed-in caller (auth.uid()), so the host
-- never has to trust a name or seat that a phone claims inside a message.
-- =====================================================================================

-- ---------- Host check ----------
-- (ludo_is_member already exists from 001_ludo_online.sql)

create or replace function public.ludo_is_host(p_room uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1 from public.ludo_rooms r
    where r.id = p_room and r.host_id = auth.uid()
  )
$$;

-- ---------- Topic parsers ----------
-- Every cast sits inside a CASE that first checks the whole topic against a strict pattern, so a
-- malformed topic can never raise an error (the planner may reorder AND, but not CASE branches).

create or replace function public.ludo_topic_room(p_topic text)
returns uuid
language sql
immutable
set search_path = public
as $$
  select case
    when p_topic ~ '^ludo:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:(down|(to|up):[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$'
    then substr(p_topic, 6, 36)::uuid
    else null
  end
$$;

-- 'down', 'to' or 'up'; null when the topic is not a well-formed ludo topic.
create or replace function public.ludo_topic_kind(p_topic text)
returns text
language sql
immutable
set search_path = public
as $$
  select case
    when public.ludo_topic_room(p_topic) is null then null
    when p_topic like '%:down' then 'down'
    when p_topic like '%:to:%' then 'to'
    else 'up'
  end
$$;

-- The user id at the end of a 'to' / 'up' topic; null for 'down' and for malformed topics.
create or replace function public.ludo_topic_user(p_topic text)
returns uuid
language sql
immutable
set search_path = public
as $$
  select case
    when public.ludo_topic_kind(p_topic) in ('to', 'up')
    then substr(p_topic, length(p_topic) - 35)::uuid
    else null
  end
$$;

-- ---------- Who may receive and who may send on a topic ----------
-- These are what the Realtime policies below call. Anything not understood answers false.
--
--   receive  down      any active member of the room
--            to:<u>    that person (u = the caller), and the host
--            up:<u>    the host, and that person (u = the caller)
--   send     down      the host only
--            to:<u>    the host only
--            up:<u>    that person only (u = the caller, an active member of the room)
--
-- Joining a private channel needs read permission, so the person who SENDS on up:<me> (and the
-- host, who sends on to:<u>) may also read their own channel. That grants nothing extra: nobody
-- else can ever send there, and a sender does not receive its own broadcasts.

create or replace function public.ludo_can_receive(p_topic text)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(
    case public.ludo_topic_kind(p_topic)
      when 'down' then public.ludo_is_member(public.ludo_topic_room(p_topic))
      when 'to' then
        public.ludo_is_host(public.ludo_topic_room(p_topic))
        or (public.ludo_topic_user(p_topic) = auth.uid()
            and public.ludo_is_member(public.ludo_topic_room(p_topic)))
      when 'up' then
        public.ludo_is_host(public.ludo_topic_room(p_topic))
        or (public.ludo_topic_user(p_topic) = auth.uid()
            and public.ludo_is_member(public.ludo_topic_room(p_topic)))
      else false
    end,
    false
  )
$$;

create or replace function public.ludo_can_send(p_topic text)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(
    case public.ludo_topic_kind(p_topic)
      when 'down' then public.ludo_is_host(public.ludo_topic_room(p_topic))
      when 'to' then public.ludo_is_host(public.ludo_topic_room(p_topic))
      when 'up' then
        public.ludo_topic_user(p_topic) = auth.uid()
        and public.ludo_is_member(public.ludo_topic_room(p_topic))
      else false
    end,
    false
  )
$$;

-- ---------- Realtime Authorization policies (private Broadcast channels) ----------
-- Only for topics that start with "ludo:" and only for the "broadcast" extension.

drop policy if exists "ludo_rt_receive" on realtime.messages;
create policy "ludo_rt_receive"
  on realtime.messages
  for select
  to authenticated
  using (
    realtime.messages.extension = 'broadcast'
    and realtime.topic() like 'ludo:%'
    and public.ludo_can_receive(realtime.topic())
  );

drop policy if exists "ludo_rt_send" on realtime.messages;
create policy "ludo_rt_send"
  on realtime.messages
  for insert
  to authenticated
  with check (
    realtime.messages.extension = 'broadcast'
    and realtime.topic() like 'ludo:%'
    and public.ludo_can_send(realtime.topic())
  );

-- ---------- Host: record the current game ----------
-- Called once per game. Guests and watchers read game_no and assignment from the room snapshot,
-- so they can build the board even if a live "start" message never reaches them.

create or replace function public.ludo_publish_start(p_room uuid, p_game_no int, p_assignment jsonb)
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
  if p_game_no is null or p_game_no < 1 or p_assignment is null then
    return jsonb_build_object('ok', false, 'reason', 'bad_args');
  end if;
  if jsonb_typeof(p_assignment) <> 'array' then
    return jsonb_build_object('ok', false, 'reason', 'bad_args');
  end if;
  if jsonb_array_length(p_assignment) < 2 or jsonb_array_length(p_assignment) > 4 then
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
  if v_room.status <> 'playing'
     or p_game_no < v_room.game_no
     or jsonb_array_length(p_assignment) <> v_room.player_count then
    return jsonb_build_object('ok', false, 'reason', 'bad_args');
  end if;

  update public.ludo_rooms
     set game_no = p_game_no,
         assignment = p_assignment,
         updated_at = now()
   where id = p_room;

  return jsonb_build_object('ok', true, 'game_no', p_game_no);
end;
$$;

-- ---------- Host: keep-alive (a room with no activity for 24 hours expires) ----------

create or replace function public.ludo_touch_room(p_room uuid)
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
  if v_room.status = 'ended' then
    return jsonb_build_object('ok', false, 'reason', 'ended');
  end if;

  update public.ludo_rooms set updated_at = now() where id = p_room;
  return jsonb_build_object('ok', true);
end;
$$;

-- ---------- Who may call what ----------

revoke all on function public.ludo_is_host(uuid) from public, anon;
revoke all on function public.ludo_topic_room(text) from public, anon;
revoke all on function public.ludo_topic_kind(text) from public, anon;
revoke all on function public.ludo_topic_user(text) from public, anon;
revoke all on function public.ludo_can_receive(text) from public, anon;
revoke all on function public.ludo_can_send(text) from public, anon;
revoke all on function public.ludo_publish_start(uuid, int, jsonb) from public, anon;
revoke all on function public.ludo_touch_room(uuid) from public, anon;

grant execute on function public.ludo_is_host(uuid) to authenticated;
grant execute on function public.ludo_topic_room(text) to authenticated;
grant execute on function public.ludo_topic_kind(text) to authenticated;
grant execute on function public.ludo_topic_user(text) to authenticated;
grant execute on function public.ludo_can_receive(text) to authenticated;
grant execute on function public.ludo_can_send(text) to authenticated;
grant execute on function public.ludo_publish_start(uuid, int, jsonb) to authenticated;
grant execute on function public.ludo_touch_room(uuid) to authenticated;

-- ---------- How to undo (only ludo_ objects and these two policies; uncomment and run if you ever want to) ----------
-- drop policy if exists "ludo_rt_send" on realtime.messages;
-- drop policy if exists "ludo_rt_receive" on realtime.messages;
-- drop function if exists public.ludo_touch_room(uuid);
-- drop function if exists public.ludo_publish_start(uuid, int, jsonb);
-- drop function if exists public.ludo_can_send(text);
-- drop function if exists public.ludo_can_receive(text);
-- drop function if exists public.ludo_topic_user(text);
-- drop function if exists public.ludo_topic_kind(text);
-- drop function if exists public.ludo_topic_room(text);
-- drop function if exists public.ludo_is_host(uuid);
