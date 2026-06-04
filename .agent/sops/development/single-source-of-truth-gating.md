# SOP: Single source of truth for any gate or decision

## Context

A short cross-cutting principle referenced by several other SOPs. A
"gate" is any decision multiple call sites depend on (can this rocket
launch? is this mechanic enabled? what is this task's status?). When the
decision is re-derived in more than one place, the copies drift and one
of them becomes a bug.

## The rule

Each decision lives in exactly **one** method/field that every consumer
calls. To change the behaviour you edit one place; no consumer re-derives
the logic independently.

## Cases this prevents

- **Launch gating**: the TWR check lives only in
  `StatsRocket.canLaunch()`. `EntityRocket` calls it rather than
  re-computing `thrust/weight >= minLaunchTWR`. So gating the weight
  system (return `true` when off) fixes every caller at once — there is no
  second copy to forget. (Before this, the launch path had its own
  derivation and leaked past the config flag.)
- **Task status**: a task's status lives only in its `TASK-NN-*.md`
  header; `tasks/README.md`, markers, and the navigator are *derived
  views*. See [`task-lifecycle.md`](./task-lifecycle.md).
- **Per-mechanic enable**: gate a mechanic at its one true entry point,
  not at each consequence site. See
  [`config-flag-disableability.md`](./config-flag-disableability.md).

## How to apply

1. Before adding a conditional, search for the same condition elsewhere
   (`Grep` the predicate / the config field). If it exists, call the
   existing method instead of copying the check.
2. If a decision is computed inline in several places, extract it to one
   named method and route all callers through it — that refactor is the
   fix.
3. Everything else that "knows" the answer must read it from the source,
   not recompute it.

## Litmus

> "If I change this rule, how many places must I edit?" If the answer is
> more than one, you don't have a single source of truth yet.

## Related

- [`config-flag-disableability.md`](./config-flag-disableability.md),
  [`task-lifecycle.md`](./task-lifecycle.md).
