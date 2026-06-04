# SOP: Propagating one fix across worktrees / branches

## Context

Read when the same fix must land on several branches (a bug present in
all feature branches, a coremod fix that every fork needs). The project
keeps multiple worktrees on different branches; a careless fan-out pushes
an unverified or branch-incompatible change to many places at once.

## Verify on ONE branch before fanning out

The expensive lesson: a fix that looks obviously correct can be wrong.
The Mixin self-bootstrap fix was first done as a `try/catch`, pushed to
several branches, and only later found insufficient (it still crashes
under MixinBooter) — every branch then had to be re-rolled. **Prove the
fix on one branch first** (compile + the relevant test, e.g. a mixin
fix → `WeatherBaselineTest` to confirm weaving still works), and only
then replicate.

## Replication procedure

1. **Confirm the target file is identical** across branches before
   applying the same edit (`git -C <wt> show <branch>:<path>` or read
   each). If it diverged, adapt the edit per branch — don't force a
   patch.
2. **Apply the same edit** in each worktree.
3. **Compile each** as a drift guard (cheap insurance against a branch
   whose surrounding code differs):
   ```bash
   ./gradlew -p <worktree-dir> compileJava --no-daemon
   ```
4. **Commit + push per branch** without `cd` (which can trip a permission
   prompt):
   ```bash
   git -C <worktree-dir> add <path>
   git -C <worktree-dir> commit -m "<msg>"
   git -C <worktree-dir> push origin "$(git -C <worktree-dir> rev-parse --abbrev-ref HEAD)"
   ```

## Scope honestly

- Only branches checked out in a **worktree** are touched by the above;
  other branches with the same bug are NOT. State which branches you
  covered and which still carry the bug.
- The `bridge-cse_*` worktrees are agent sandboxes (master-based, locked)
  — skip them.
- Only RFG-buildable branches (`origin/1.12` base) will `compileJava`
  cleanly; see [`build-and-run-env.md`](./build-and-run-env.md).
- Preserve original authorship when a fix originates from an upstream PR.

## Prevention

- [ ] Fix verified (compile + test) on one branch before fan-out.
- [ ] Target file confirmed identical (or edit adapted) per branch.
- [ ] Each branch compiled before commit.
- [ ] Coverage stated: which branches got it, which still need it.

## Related

- [`build-and-run-env.md`](./build-and-run-env.md),
  [`mixin-coremod-dev-vs-prod.md`](./mixin-coremod-dev-vs-prod.md).
