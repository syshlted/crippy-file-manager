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
3. ~~**Copying in and out.**~~ **Done 2026-10-23 (F6).** Folders and several documents out at once are not
   built; see F6.
4. ~~**Sharing out**~~ **Done 2026-10-23 (F7)**, one document at a time.
5. **encri's backup screens, by their public actions** (encri D123): *Back up*, *Save a backup file* and
   *Restore*. ~~Waits for encri to give the three screens their actions, which is encri's step 13 item 6.~~
   **Unblocked 2026-10-26:** encri exports the three screens (encri D133), and encri-api names their actions
   and gives `Encri.backupsIntent()`, `saveBackupFileIntent()` and `restoreIntent()` (encri-api L20). Each needs
   no session. The screen takes nothing from the Intent and returns no result, and it opens in this app's task.
   **Since encri D137 (2026-10-27)** the scheduled backup leaves encri for two backup apps of its own, so *Back up*
   will open one of them rather than encri, and `backupsIntent()` changes in encri-api before its first release.
   *Save a backup file* and *Restore* stay encri's.
6. **Device tests against encri**, as encri-api's L13 runs them: install encri, let the test in, and drive
   this app's screen from another process. The first run was by hand on the A20 (F2).
7. **The visible name.** "Encri Files" is for now (F1).

## Later

- ~~**A copy that keeps going while another app is in front** (S3, F6's first flag): a `dataSync` foreground service,
  which is this app's first permission. Until then a long copy may pause or stop when the user leaves.~~ **Done
  2026-10-23 (F8).**
- **Choosing several documents at once**, to copy out or share them together, which F6 and F7 left out; and
  copying a folder.

## Before the first release

- **One first release with encri** (encri D136, 2026-10-27): internal testing on Google Play this week, where
  `com.systemhalted.encri.files` is registered. This app's release is built by GitHub's CI and the maintainer and
  must agree (encri D116), so encri-api and this repository are pushed first (below).
- ~~**Signing with encri's key**, which encri D115 sets out, and the fingerprints file it lists.~~ **Built
  2026-10-29 (F9):** a signing kit of this repository's own, which expects encri's app signing and transparency keys
  and this app's upload key; `FINGERPRINTS.md` is encri-api's, word for word in its tables.
- ~~**A reproducible release build**, checked as encri's is (encri D116).~~ **Built 2026-10-29 (F9):**
  `reproduce.sh`, and `reproducible.yml` on every tag.
- **Then, in order** (F9): ~~the minified build walked through on an emulator against encri before it is signed
  (encri D140)~~ **done 2026-10-29**; ~~this repository pushed and its `master` protected (below)~~ **pushed
  2026-10-30, and held by the ruleset it already had**; ~~the maintainer's build agreeing with CI's SHA-256s~~ **CI
  built `6644860` on `master` the same as the maintainer, 2026-10-30, and the kit is made**; a signed `v0.1.0` tag on
  `6644860`, which CI builds again; the kit signed on the key machine, no earlier than 7:03 PM on 2026-10-30, when
  Play takes this app's own upload key; `sign.sh --verify` here; and the bundle uploaded to the internal testing
  track with the listing's automatic protection off (encri D144).
- ~~**A registry entry** in encri-api's `registry/` once this app has a release key (encri D114, D122).~~ **Done
  2026-10-28** as encri-api's L22, under the app signing key's certificate; the pin names that commit (F9).

## When the repository is pushed

- ~~**encri-api's rename must be on GitHub first.** CI checks encri-api's `master` out beside this one, and
  that `master` predates the rename (encri-api L19), so every job fails to build until it is pushed.~~ **Done:**
  encri-api was pushed on 2026-10-29 at `c27f139`, and CI now fetches the commit `config/encri-api.pin` names (F9).
- **Branch protection on `master`**, as encri-api lists it: ~~signed commits~~, a review, `ci` and
  `commit-policy` required, ~~no force push~~, merge commits or rebases only. **In part, 2026-10-30:** the ruleset
  *Protect default branch* refuses a deletion, a force push and an unsigned commit, with no one to bypass it, which is
  what the user asked for. A review and required checks would make a maintainer working alone open a pull request
  for every change, so they wait for a second maintainer.
- **CodeQL and dependency scanning**, carried across from encri-api (its L7 and L10), which this
  repository does not have yet.

## Bug queue

Empty. B111 in encri's roadmap — a share saved through encri tells no app that a folder changed — was
found by this app's first device run, and is encri's to fix.
