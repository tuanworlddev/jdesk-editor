# Performance

Measured against the running editor (e2e-flavored build, production-mode Vite frontend) via the
automation endpoint. Latencies are client-observed loopback round-trips (they include the WebView
bridge and Java command dispatch). Spec §22.

## Hardware / environment

- OS: macOS-26.5.1-arm64-arm-64bit-Mach-O
- Machine: ['Model Name: MacBook Pro', 'Chip: Apple M5', 'Memory: 24 GB']
- Build: e2e flavor (automation enabled), frontend built by `vite build` (minified).

## Results

| Metric | p50 | p95 | p99 | max | n | Target |
|---|---|---|---|---|---|---|
| Command ack (workspace.getState round-trip) | 1.00 ms | 1.00 ms | 1.00 ms | 4.00 ms | 200 | p95 &lt; 75 ms |
| Warm file-open (doc.open, 2 KiB) | 1.00 ms | 1.00 ms | 1.00 ms | 1.00 ms | 100 | record actual |

Terminal 10 MiB backpressure is covered by `TerminalOutputPumpTest` (delivers 10 MiB in full with
in-flight events bounded to the credit window).

## Limitations

- Single machine; thermals uncontrolled. Numbers are loopback client-observed (include bridge +
  dispatch), not isolated server compute. The pure-production package excludes the automation
  channel, so these are measured on the e2e build; the IPC path is identical.
