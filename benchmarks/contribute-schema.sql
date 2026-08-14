-- ENTITY Bench: contributed results table.
-- Paste this whole file into the Supabase SQL editor and run it once.
--
-- Shape rationale: one table, exposed directly by PostgREST, under row-level security
-- that grants the anon role INSERT and SELECT and nothing else. There is no server code
-- anywhere in this project - the endpoint IS the table - and the key shipped inside the
-- app can append a run and read the public dataset, but cannot update or delete a row,
-- including its own. That is why publishing it is safe. See the policies below for the
-- reason SELECT is granted as well as INSERT.

create table if not exists public.bench_results (
  id                bigint generated always as identity primary key,
  received_at       timestamptz not null default now(),

  -- Fresh per submission. Used only so a retried offline upload cannot create a
  -- duplicate row; it deliberately does NOT identify a device or link two runs.
  submission_id     uuid not null unique,

  app_version       text,
  app_version_code  int,
  run_type          text,
  run_ts            bigint,

  -- Device fingerprint. This is the point of the dataset: the finding is only a claim
  -- about Arm silicon if it is measured across silicon.
  device_manufacturer  text,
  device_model         text,
  soc                  text,
  android_release      text,
  android_sdk          int,
  abis                 jsonb,
  cpu_flags            jsonb,   -- dotprod / i8mm / sve / sve2 / sme / sme2 / fp16
  max_freqs_mhz        jsonb,
  -- The kernel's own normalised per-core capacity (1024 = strongest core), from
  -- capacity-dmips-mhz x max clock. This is the signal the scheduler uses to tell
  -- performance cores from efficiency ones; max_freqs_mhz is only a proxy and cannot
  -- separate an A55 from an A78 at a similar clock. Null on kernels that omit it.
  cpu_capacities       jsonb,
  fast_cores           int,
  little_cores         int,

  model_file           text,
  quantization         text,
  kleidiai_accelerated boolean, -- Q4_0 and Q8_0 are the only types KleidiAI accelerates

  runs_per_arm         int,
  duration_min         int,
  start_temp_c         double precision,

  -- The most important columns in the table. A charging phone reports the charger's
  -- current, not the workload's, so power and tok/W from a charging run are physically
  -- meaningless. Never average across power_valid = false.
  charging             boolean,
  power_valid          boolean,

  -- Per-arm medians and standard deviations: naive / threads_only / optimized /
  -- efficiency, each with threads, pinned, decode, prompt, ttft, watts, tok_per_w.
  arms                 jsonb
);

alter table public.bench_results enable row level security;

-- Insert and read for the anonymous key: no update, no delete.
-- The APK never reads back (it sends "Prefer: return=minimal"), so the insert policy is
-- all the app needs. The select policy exists for the project site's live leaderboard
-- (ENTITY-WEB), which fetches this table from the browser with the same publishable key.
-- Every row it can read is already published in the site's committed snapshot and in
-- benchmarks/results/, so the read grants no new visibility - but it does mean this table
-- is public, and nothing private may ever be inserted into it.
drop policy if exists "anon can insert results" on public.bench_results;
create policy "anon can insert results"
  on public.bench_results
  for insert
  to anon
  with check (true);

drop policy if exists "anon can read results" on public.bench_results;
create policy "anon can read results"
  on public.bench_results
  for select
  to anon
  using (true);

-- Handy views for pulling the dataset back into benchmarks/results/ as CSV.

-- Only rows that can legitimately carry a power or tok/W claim.
create or replace view public.bench_results_valid_power as
  select * from public.bench_results where power_valid;

-- One row per distinct SoC, newest first: the "does the finding generalise" question.
create or replace view public.bench_results_by_soc as
  select distinct on (soc, device_model)
         soc, device_manufacturer, device_model, cpu_flags,
         fast_cores, little_cores, power_valid, arms, received_at
    from public.bench_results
   order by soc, device_model, received_at desc;
