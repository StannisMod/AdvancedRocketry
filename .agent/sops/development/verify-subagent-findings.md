# SOP: Verify sub-agent / audit findings against code before acting

## Context

Read whenever a finding comes from a fan-out search, an Explore/research
agent, or an automated audit and you're about to act on it — fix code,
report to the user, or defer work. These findings are useful for
**locating** code but are frequently wrong on **interpretation**.
Shipping or reporting them unverified spreads errors with confidence.

## The rule

Treat a sub-agent finding as a **lead, not a conclusion**. Before acting,
open the cited `file:line` and confirm the claim against the actual code.
Verify at least every finding that would change what you do — a fix, a
"this is a bug" report, or a "this can't be done" deferral.

## Real misses from this project

Sub-agent audits in one session produced, among correct findings:

- "`forcePlanetWeatherWorldInfoWrapper` bypasses the main weather flag" —
  **false**. Reading the code showed it's checked *after* the main flag's
  early `return`, so it's subordinate, not a bypass.
- "parts wear accrues despite the system being off" — **overstated**. The
  accrual path was only reachable in free-flight landing and had no gated
  consequence; the real leak was narrower than claimed.

Acting on either as written would have produced a wrong fix or a wrong
report.

## How to verify cheaply

1. Open each cited `file:line`; read the surrounding method, not just the
   line.
2. Check the **control flow** around the claim (early returns, guards,
   the order of conditions) — most interpretation errors are here.
3. For "X is a bug / X can't be disabled" claims, trace to the observable
   effect; if there's no player-visible consequence it may be impl-trivia
   (see [`bug-ledger-discipline.md`](./bug-ledger-discipline.md)).
4. For high-stakes claims, verify from an independent angle (a second
   read, a probe that exercises the path) before committing.

## When fanning out many agents

Their job is breadth (locate candidates); yours is depth (confirm). Don't
relay a sub-agent's summary to the user as fact — relay the
code-confirmed conclusion.

## Prevention

- [ ] Every actioned finding confirmed at its `file:line`.
- [ ] Control flow around the claim checked, not just the matched line.
- [ ] "Bug"/"can't" claims traced to an observable effect.
- [ ] User-facing reports state code-verified conclusions, not raw agent
      summaries.

## Related

- [`coverage-audit-playbook.md`](./coverage-audit-playbook.md),
  [`bug-ledger-discipline.md`](./bug-ledger-discipline.md),
  [`testing-principles.md`](./testing-principles.md).
