begin;

-- Remove only objects introduced by the compatibility layer.
drop trigger if exists enforce_patrol_occurrence_reservation_before_insert on public.patrol_runs;
drop function if exists public.enforce_patrol_occurrence_reservation();
drop function if exists public.reserve_current_patrol_occurrence(uuid, uuid, uuid);
drop table if exists public.patrol_occurrence_reservations;
drop function if exists public.create_extra_checkpoint_with_qr(text, uuid, text, text, text);

commit;
