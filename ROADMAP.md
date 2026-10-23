# encri-files — Roadmap

What is next, and in what order. `AGENTS.md` is why; `CHANGELOG.md` is what happened. When this file
and `AGENTS.md` disagree, `AGENTS.md` wins.

Severities, as in encri: **S1** loses a user's data or lets the wrong app read it · **S2** a wrong
answer a user cannot tell from a right one · **S3** wrong under a narrow race, or a cost nobody chose ·
**S4** debt.

## Now

The scaffold is done (F1). What follows is encri's step 13 for the file manager (encri D112 to D118,
D123), in order.

1. **The first screen**: connect to encri, ask the user to let this app in on encri's own screen, unlock
   the vault on encri's unlock screen, list the vault a folder at a time, and open a document with another
   app through a handoff (encri D117).
2. **Previews**, with a toggle of this app's own, on by default (encri D112), in memory only.
3. **Writing**: a new folder, rename, delete and move.
4. **Copying in and out**, saying that a copy out is plaintext in another app's storage (encri D123).
5. **Sharing out** through a handoff and the share sheet (encri D118).
6. **encri's backup screens, by their public actions** (encri D123), once encri gives them actions.
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

Empty.
