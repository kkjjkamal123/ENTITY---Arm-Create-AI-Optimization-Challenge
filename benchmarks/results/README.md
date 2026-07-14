# Retained Android benchmark exports

Place the unmodified CSV exported by ENTITY's **Benchmark** screen in this directory when adding a
new published device result. Use a descriptive, stable filename such as:

```text
entity-v2.0.0-cmf-phone-1-dimensity-7300-q3kl-pp512-tg128-2026-07-14.csv
```

For every CSV added here, add or update the corresponding row in
[`../device-result-template.csv`](../device-result-template.csv) and set `raw_csv_path` to this
file. Do not create synthetic per-pass data from an aggregate median and standard deviation.

The two v2.0.0 reference summaries predate this retention rule; their original Android exports
were not retained. Historical Termux/CLI records belong in the parent `benchmarks/` directory and
must remain clearly labeled as CLI-only evidence.
