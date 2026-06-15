# SOP: Issue-reference discipline — never bare `#NN`, never the fork root

## Why this SOP exists

This project lives in a GitHub **fork network**, and a bare `#NN`
issue reference does NOT point where you think. Bare references in
commit messages / PR text leaked to the network **root**
(`Advanced-Rocketry/AdvancedRocketry`) and notified participants of
unrelated **2015** issues there — including a stranger who showed up
on PR #22 thinking we'd "fixed" his decade-old closed issue.

## The fork network (know it cold)

```
Advanced-Rocketry/AdvancedRocketry   ← network ROOT (source). issues ON.
        │                              The original. 2015-era issues live here.
        │                              We NEVER reference this repo.
        └─ StannisMod/AdvancedRocketry     ← our fork. PRs live here (e.g. #22).
                │                            base of our work.
                └─ dercodeKoenig/AdvancedRocketry   ← active fork. issues ON.
                                                       Most bug reports we fix
                                                       are filed here.
```

A given bug report may come from **either** `StannisMod/AdvancedRocketry`
**or** `dercodeKoenig/AdvancedRocketry`. It is never the root.

## How GitHub resolves a bare `#NN` (the trap)

A bare `#NN` in a commit message or PR body resolves to an issue/PR in
the repository it's *attached to*, and in a fork network GitHub
attributes commit references up the network — to the **root** when the
attached fork can't host it. So a bare `#NN` will land on
`Advanced-Rocketry/AdvancedRocketry#NN` (a 2015 issue), NOT on the
dercodeKoenig / StannisMod issue you meant. GitHub never resolves a
bare `#NN` to a *sibling/child* fork like dercodeKoenig, so you can
**never** hit the intended tracker with a bare reference.

## The rule

**Always fully-qualify issue references, using the `owner/repo` from
the issue link the user gave you.**

- Form: `dercodeKoenig/AdvancedRocketry#NN` or
  `StannisMod/AdvancedRocketry#NN` (or the full
  `https://github.com/<owner>/AdvancedRocketry/issues/NN` URL).
- The `owner/repo` is dictated by the **original issue link** in the
  request — if the user pointed you at a dercodeKoenig issue, reference
  dercodeKoenig; if a StannisMod issue, reference StannisMod.
- **Never** write a bare `#NN`. Even now that StannisMod has issues
  enabled, a bare `#NN` resolves to the *wrong* repo (StannisMod's own
  numbering, or the root) — not to the tracker the report came from.
- **Never** reference `Advanced-Rocketry/AdvancedRocketry`. The root is
  off-limits; we do not look at it for issue numbers.

Applies everywhere a number can autolink: **commit messages, PR title
and body, TASK files, the bug ledger, code comments.**

## Examples

```
# WRONG — leaks to Advanced-Rocketry/AdvancedRocketry#76 (2015 "AR liquids texture")
fix: guard the JEI gas-giant-refresh call so it loads without JEI (#76)

# RIGHT — points at the report we actually fixed
fix: guard the JEI gas-giant-refresh call so it loads without JEI (dercodeKoenig/AdvancedRocketry#76)
```

## Damage control for already-pushed bare refs

Cross-references already created in the root cannot be un-sent, and
rewriting history to "fix" them risks re-notifying on re-push — so
leave pushed commits as-is and just qualify everything going forward.
PR **body/title** text, however, can be edited freely (editing PR prose
creates no commit cross-reference): replace bare `#NN` with the
fully-qualified form in place, touching nothing else.

## Related

- `bug-report-workflow.md` — the pipeline that produces these refs.
- `task-lifecycle.md` — TASK files carry the qualified reference.
