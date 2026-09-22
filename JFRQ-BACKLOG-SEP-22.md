# jfrq: what is still open, 2026-09-22

Round 1 (`HOUSTON-EDGE2-JFRQ-SEP-22.md` §4, eleven items) shipped in `cd45013`..`69c03ad`. Round 2
(`~/edge_zulu/JFRQ-FEEDBACK-SEP-22.md`), on a **fresh** Edge 2 node — distribution built from
`feature/houston-on-edge2` @ `8695a25bd`, 12 adapters, the 63 k-node rig plus the Cologne PLCs, then
a 10m31s idle window — confirmed all eleven in use and raised four more, all now fixed. This is what
is left.

- **`--min` and `--gap` now apply to the in-window part of a wait or a block** (T3). `USAGE`
  (`Main.java:70`) and `README.md` still describe `--min` as "ignore waits shorter than D" without
  the qualifier. Still Miguel's call: the report already prints `Note 13 waits began before the
  recording or outlived it; only the part inside it is counted`, so a reader who hits the case is
  told, and the one-line option description may be fine as it is.

The round-2 recordings are in `~/jfr-round2` (copied out of the session scratchpad, which `/tmp`
would have taken): `v2load.jfr` is the one N2 was measured on.
