# jfrq: what is still open, 2026-09-22

Round 1 (`HOUSTON-EDGE2-JFRQ-SEP-22.md` §4, eleven items) shipped in `cd45013`..`69c03ad`. Round 2
(`~/edge_zulu/JFRQ-FEEDBACK-SEP-22.md`), on a **fresh** Edge 2 node — distribution built from
`feature/houston-on-edge2` @ `8695a25bd`, 12 adapters, the 63 k-node rig plus the Cologne PLCs, then
a 10m31s idle window — confirmed all eleven in use and raised four more. Three of those four are
fixed; this page is what is left.

- **The waiting-for-work default does not know an application's own actor runtime** (N2, superseding
  T8/T1, which shipped the `--idle` list). Predicted when that list landed — *"a framework with its
  own worker loop will need adding"* — and Edge 2 is one. On the v2 load, **8 of 8**
  `LOCKS BY TOTAL WAIT` rows were `protocol-adapter-dispatcher-N` threads parked on
  `DefaultMailbox.awaitNextMessage:92`. Adding that one frame to `--idle` takes `Blocked` from
  **32m42s to 4m50s**, 31 046 waits to 4 206, and promotes the row that matters
  (`ChannelBrowseSink.take:154`) into the top three. An operator who does not already know the
  codebase will not know to pass it.
  **Proposal: infer the shape, don't name the frame.** A mailbox has a signature no list is needed
  to see — a lock with **exactly one distinct waiter**, whose total ≈ the window length, made of
  thousands of timed parks of near-identical duration. That is a thread's own perch, not contention,
  whoever wrote it. Keep the name list as an override; lead with the measurement.
  **Measure before coding.** `ChannelBrowseSink.take:154` — the row this is meant to promote — may
  have that same signature, in which case shape inference would swallow the finding it was written
  to surface. Step one is to compute the four numbers (distinct waiters, total/window, park count,
  duration spread) for every lock in the round-2 dump and check they separate the two. Duration
  spread is the likeliest discriminator: a poll with a fixed timeout piles up at exactly that
  timeout, a starved consumer produces a few long irregular parks. If nothing separates them, the
  answer is not inference but printing the signature next to each lock and keeping the name list.
  **Blocked on:** whether the round-2 `.jfr` still exists in `~/edge_zulu`. A fixture built here
  would only prove something about the fixture.
- **`--min` and `--gap` now apply to the in-window part of a wait or a block** (T3). `USAGE`
  (`Main.java:70`) and `README.md` still describe `--min` as "ignore waits shorter than D" without
  the qualifier. Still Miguel's call: the report already prints `Note 13 waits began before the
  recording or outlived it; only the part inside it is counted`, so a reader who hits the case is
  told, and the one-line option description may be fine as it is.
