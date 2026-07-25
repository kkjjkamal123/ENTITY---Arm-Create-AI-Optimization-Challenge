# Contributed results: the backend

ENTITY Bench can optionally send a finished benchmark to a public dataset, so the project's
central finding is a claim about Arm silicon rather than about the two phones its author owns.

This file is the whole backend. There is no server code: Supabase exposes a PostgREST endpoint
over one table, and the app posts a single JSON body to it with `HttpURLConnection` - the same
primitive the model downloader already uses. **No SDK, no analytics library and no backend
dependency is linked into the APK.**

## Why this shape

| Decision | Reason |
|---|---|
| One table, PostgREST | No server to write, deploy or keep alive. The endpoint *is* the table. |
| Insert-only RLS policy | The key shipped in the app can only append. It cannot read, update or delete - so publishing it is safe. |
| `Prefer: return=minimal` | The insert does not echo the row back, so no `SELECT` policy is needed at all. |
| Endpoint in a gitignored file | A fork builds and runs with contribution switched **off** instead of posting into this project's database. |
| Opt-in, default off | Nothing is sent until someone taps the toggle, and Settings shows the exact body first. |

## 1. Create the table

In the Supabase SQL editor:

```sql
create table public.bench_results (
  id                bigint generated always as identity primary key,
  received_at       timestamptz not null default now(),

  submission_id     uuid not null unique,          -- de-duplication only; not a device id
  app_version       text,
  app_version_code  int,
  run_type          text,
  run_ts            bigint,

  device_manufacturer text,
  device_model        text,
  soc                 text,
  android_release     text,
  android_sdk         int,
  abis                jsonb,
  cpu_flags           jsonb,
  max_freqs_mhz       jsonb,
  cpu_capacities      jsonb,          -- per-core cpu_capacity, 1024 = strongest core
  fast_cores          int,
  little_cores        int,

  model_file          text,
  quantization        text,
  kleidiai_accelerated boolean,

  runs_per_arm        int,
  duration_min        int,
  start_temp_c        double precision,

  -- The most important column in the table. A charging phone reports the charger's
  -- current, not the workload's, so power and tok/W from a charging run are physically
  -- meaningless and must never be averaged in.
  charging            boolean,
  power_valid         boolean,

  arms                jsonb
);

alter table public.bench_results enable row level security;

-- Insert and read for the anonymous key: no update, no delete.
create policy "anon can insert results"
  on public.bench_results
  for insert
  to anon
  with check (true);

-- Read is for the project site's live leaderboard, not the app: the APK sends
-- "Prefer: return=minimal" and never selects. Consequence to keep in mind - this table
-- is world-readable, so nothing private may be inserted into it.
create policy "anon can read results"
  on public.bench_results
  for select
  to anon
  using (true);
```

`submission_id` is `unique`, so a retried upload from a queued offline run cannot create a
duplicate row - the second insert simply fails and the queued copy is dropped.

## 2. Point the app at it

Create `results.properties` next to `keystore.properties` in the bench app project. It is
gitignored, exactly like the keystore:

```properties
RESULTS_ENDPOINT=https://<your-project>.supabase.co/rest/v1/bench_results
RESULTS_KEY=<your project's anon / publishable key>
```

Rebuild. Without this file both build-config values are empty strings, `ResultUploader.configured`
is false, and the Settings toggle is disabled with an explanation rather than silently doing
nothing.

The anon key is designed to be public - it is embedded in every Supabase web client - and with
the insert-only policy above it grants nothing except appending a row.

## 3. Export back into the repo

The dataset is only evidence if it is auditable, so accepted rows belong in
[`results/`](results/) beside the hand-collected ones:

```sql
-- Valid-power rows only: the ones that can carry a tok/W claim.
select * from public.bench_results where power_valid order by received_at;
```

Download as CSV from the Supabase table editor and commit it. Keep charging runs separate, or
clearly flagged, for the reason in the table above.

## What is sent

Summary statistics per arm, plus a device fingerprint. Explicitly **not** sent: the per-pass
150 ms telemetry trace, any account, and any identifier that links two runs from the same
phone. The exact body is visible in the app before the first upload - Settings → Contribute
results → *Show exactly what gets sent* renders the real JSON for the most recent run, not a
sample.

```json
{
  "submission_id": "…",           "app_version": "1.5.0",
  "device_manufacturer": "…",     "device_model": "…",
  "soc": "…",                     "cpu_flags": ["dotprod", "i8mm"],
  "max_freqs_mhz": [...],         "cpu_capacities": [344, 344, 1024, 1024],
  "fast_cores": 4,                "little_cores": 4,
  "model_file": "…Q4_0.gguf",     "quantization": "Q4_0",
  "charging": false,              "power_valid": true,
  "arms": [
    { "arm": "naive",        "threads": 8, "pinned": false,
      "decode_tok_s": 10.8,  "watts": 4.1, "tok_per_w": 2.61 },
    { "arm": "threads_only", "threads": 4, "pinned": false, "decode_tok_s": 15.0 },
    { "arm": "optimized",    "threads": 4, "pinned": true,  "decode_tok_s": 18.1 }
  ]
}
```
