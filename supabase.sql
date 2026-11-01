-- Mes Comptes v2.0 : configuration de la base Supabase
-- À coller dans Supabase > SQL Editor > New query, puis cliquer sur "Run".
-- Crée une seule table "vault" qui contient tes données CHIFFRÉES (illisibles sans ta phrase secrète).

create table if not exists public.mescomptes_vault (
  user_id    uuid primary key default auth.uid() references auth.users(id) on delete cascade,
  salt       text not null,               -- sel public du chiffrement
  iv         text not null,               -- vecteur d'initialisation AES-GCM
  data       text not null,               -- données chiffrées (base64)
  kdf_iter   integer not null default 600000,
  version    integer not null default 1,  -- évite d'écraser une modification faite sur un autre appareil
  updated_at timestamptz not null default now()
);

-- Sécurité au niveau des lignes : chacun ne voit QUE sa ligne
alter table public.mescomptes_vault enable row level security;
alter table public.mescomptes_vault force row level security;

revoke all on public.mescomptes_vault from anon;
revoke all on public.mescomptes_vault from public;
grant select, insert, update, delete on public.mescomptes_vault to authenticated;

drop policy if exists "mc_select_own" on public.mescomptes_vault;
drop policy if exists "mc_insert_own" on public.mescomptes_vault;
drop policy if exists "mc_update_own" on public.mescomptes_vault;
drop policy if exists "mc_delete_own" on public.mescomptes_vault;
drop policy if exists "mc_require_mfa_if_enrolled" on public.mescomptes_vault;

create policy "mc_select_own" on public.mescomptes_vault for select to authenticated
  using ((select auth.uid()) = user_id);
create policy "mc_insert_own" on public.mescomptes_vault for insert to authenticated
  with check ((select auth.uid()) = user_id);
create policy "mc_update_own" on public.mescomptes_vault for update to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "mc_delete_own" on public.mescomptes_vault for delete to authenticated
  using ((select auth.uid()) = user_id);

-- Si la double authentification est activée sur le compte, elle devient OBLIGATOIRE pour lire/écrire
create policy "mc_require_mfa_if_enrolled" on public.mescomptes_vault as restrictive to authenticated
  using (
    array[(select auth.jwt()->>'aal')] <@ (
      select case when count(id) > 0 then array['aal2'] else array['aal1', 'aal2'] end
      from auth.mfa_factors
      where (select auth.uid()) = user_id and status = 'verified'
    )
  );

-- Date de mise à jour automatique
create or replace function public.mescomptes_mescomptes_vault_touch() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end $$;

drop trigger if exists mescomptes_vault_touch on public.mescomptes_vault;
create trigger mescomptes_vault_touch before update on public.mescomptes_vault
  for each row execute function public.mescomptes_mescomptes_vault_touch();
