# netty-demo

A Netty service with injected event-loop pathologies, recorded with JFR, so that `jfrq`
has something real to find. The walkthrough is in [docs/TUTORIAL.md](../docs/TUTORIAL.md).

```
netty-demo [--scenario all|blocking-io|lock|cpu|alloc|clean] [--duration 20s]
           [--out demo-<scenario>.jfr] [--connections 8] [--rate 200] [--loops 2] [--no-jfr]
```

| Option | Meaning | Default |
|---|---|---|
| `--scenario` | which bug to inject (`all` injects every one, `clean` none) | `all` |
| `--duration` | how long the load runs: `15s`, `2m`, `500ms` | `20s` |
| `--out` | where the recording goes | `demo-<scenario>.jfr` |
| `--connections` | client connections, each a closed loop | `8` |
| `--rate` | requests per second per connection; `0` runs unpaced and saturates the loops | `200` |
| `--loops` | Netty event loops, named `event-loop-*` | `2` |
| `--no-jfr` | do not record in-process; use with `-XX:StartFlightRecording` or a profiler | off |

What each scenario does lives in `Scenario.java`; the bugs themselves are in
`RequestHandler.java` (blocking lookup, CPU burn, registry touch) and `Background.java`
(the housekeeper, the persistence flusher, the bulk allocators). The in-process JFR
settings are in `Recorder.java`, with the equivalent `-XX:StartFlightRecording` line in
its Javadoc.

The demo prints the client-side latency percentiles when it finishes. Compare them with
what `jfrq stalls` reports: the percentiles hide the stalls, the recording does not.
