# encri-files — Roadmap

What is next, and in what order. `AGENTS.md` is why; `CHANGELOG.md` is what happened. When this file
and `AGENTS.md` disagree, `AGENTS.md` wins.

Severities, as in encri: **S1** loses a user's data or lets the wrong app read it · **S2** a wrong
answer a user cannot tell from a right one · **S3** wrong under a narrow race, or a cost nobody chose ·
**S4** debt.

## Now

The scaffold and the first screen are done (F1, F2): connect, ask, unlock, browse, and open with another
app. What follows is encri's step 13 for the file manager (encri D112 to D118, D123), in order.

1. ~~**Previews**, with a toggle of this app's own, on by default (encri D112).~~ **Done 2026-10-23 (F4).**
2. ~~**Writing**: a new folder, rename, delete and move.~~ **Done 2026-10-23 (F5)**, asking for every right.
3. **Copying in and out.** In: a file chosen in the system picker, written into the vault through the
   session. Out: a document written to a place the user picks, with a sentence before it saying that the
   copy is plaintext in another app's storage (invariant 2 in encri; encri D123).
4. **Sharing out** through a handoff and the share sheet (encri D118), saying that the app it reaches may
   read it later, after the vault has locked, and find it gone.
5. **encri's backup screens, by their public actions** (encri D123): *Back up*, *Save a backup file* and
   *Restore*. Waits for encri to give the three screens their actions, which is encri's step 13 item 6.
6. **Device tests against encri**, as encri-api's L13 runs them: install encri, let the test in, and drive
   this app's screen from another process. The first run was by hand on the A20 (F2).
7. **The visible name.** "Encri Files" is for now (F1).

## Before the first release

- **Signing with encri's key**, which encri D115 sets out, and the fingerprints file it lists.
- **A reproducible release build**, checked as encri's is (encri D116).
- **A registry entry** in encri-api's `registry/` once this app has a release key (encri D114, D122).

## When the repository is pushed

- **encri-api's rename must be on GitHub first.** CI checks encri-api's `master` out beside this one, and
  that `master` predates the rename (encri-api L19), so every job fails to build until it is pushed.
- **Branch protection on `master`**, as encri-api lists it: signed commits, a review, `ci` and
  `commit-policy` required, no force push, merge commits or rebases only.
- **CodeQL and dependency scanning**, carried across from encri-api (its L7 and L10), which this
  repository does not have yet.

## Bug queue

Empty. B111 in encri's roadmap — a share saved through encri tells no app that a folder changed — was
found by this app's first device run, and is encri's to fix.
