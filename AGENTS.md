# encri-files — Project Facts & Decision Log

> Living document. Append decisions as they're made; mark superseded ones rather than deleting them.
> Date format: YYYY-MM-DD.

## What this is

**encri's file manager**: an Android app that will browse an encri vault and hand its documents to other
apps. It reaches the vault only through encri's companion service over AIDL, never through the system
file picker (encri D112). So it works with encri's file-picker access off, which is how every vault
starts (encri D47), and Android keeps no record of which document went to which app.

It builds against [encri-api](https://github.com/syshlted/encri-api)'s client library exactly as any
other app would (encri D115). It is meant to be read and lifted from, and is licensed Apache-2.0, as
encri D115 chose and still flags for veto. encri itself is not open source (encri D116).

What crosses the boundary between this app and encri, and why, is decided in encri's `AGENTS.md` —
D112 to D118, D120 and D123 — and in encri-api's for the wire. It is not restated here. What this file
owns is the app.

## Status

**Scaffolded (2026-10-23).** The Gradle project builds, the gates run and the hooks are installed (F1).
There is no screen yet. Nothing is pushed, and nothing has been released.

## Relationship to encri and encri-api

- **Decisions here are numbered F1, F2, …**, and the others' are cited as *encri D120* and *encri-api
  L12*. Three logs each numbered from 1 would make "D3" mean three things.
- **About the app, this file wins.** About the contract, encri-api's does; about encri's internals,
  encri's does, and this one links rather than restates.
- **The toolchain is encri's and encri-api's, version for version** (F1).

---

## Repo conventions

**Three files, and they do not overlap**, as in encri and encri-api. This one is the decision log.
`ROADMAP.md` is what is next and in what order. `CHANGELOG.md` is what happened. When they disagree,
**this file wins**.

**Default branch is `master`**, a SystemHalted organization-wide policy. Do not rename it to `main`.

**Commits are signed and Conventional**, with the repo-local config and the lefthook hooks encri-api
records under its *Repo conventions*: `signing-preflight`, `ktlint` and `detekt` before a commit,
`conventional-commit` on the message, and `verify-signatures` and `allUnitTests` before a push. Fresh
clones bootstrap with `lefthook install`. Every rule a hook enforces is enforced again in
`.github/workflows/`, because `--no-verify` always exists.

**Every commit builds and passes its own tests, not just the branch tip** — encri's rule, for encri's
reason: `verify-signatures` walks every commit in a push and `unit-tests` runs only at the tip.

**Merge with a merge commit or a rebase, never a squash** (encri D7).

**Regenerating the dependency verification metadata**, after adding or bumping anything, from an empty
file over every task a workflow runs (encri D7, and encri-api's note on why the file must start empty):

```sh
rm gradle/verification-metadata.xml
./gradlew --write-verification-metadata sha256 --refresh-dependencies --rerun-tasks \
  ktlintCheck detekt allUnitTests koverVerify koverXmlReport assemble assembleDebugAndroidTest bundleRelease
```

The file covers encri-api's build as well, because a composite build is verified by the root build's
file. Generated on 2026-10-23: 375 components, and every artifact it shares with encri's and encri-api's
files has the same hash. The one component neither has is `kotlinx-coroutines-android`.

**Changing what a user receives** means updating the lockfile, which STRICT mode demands:
`./gradlew assembleRelease --write-locks`.

## Local development environment

The same machine as encri and encri-api, and encri's notes on its JDKs, phones, emulators and Waydroid
apply. Two facts matter here:

- **Set `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`**, and never run two Gradle invocations at once:
  the included encri-api build is one more set of outputs for them to fight over.
- **encri-api must be checked out beside this one**, at `../encri-api`, or pointed at with
  `-Pencri.api.dir=<path>`. CI checks it out into the workspace.

---

## Decisions

### F1 — An app over encri-client, built from framework views, with encri's toolchain
*2026-10-23 · accepted; **flagged for veto on one point** (below)*

**A separate application, `com.systemhalted.encri.files`**, signed with encri's key (encri D112, D115),
that reaches the vault through `com.systemhalted.encri:encri-client` and nothing else. One module,
`:app`. The application id is permanent from the first build a user installs, because encri keys a grant
on it (encri D120).

**The launcher name is "Encri Files", for now**, at the user's direction on 2026-10-23. It is a string
resource and can change in any release without touching anything a user holds. The visible name was left
open at encri's rename (encri D124), and a trademark search for encri is owed before anything ships.

**Framework views, built in code, and no AndroidX.** encri's own screens are built the same way (encri
D33 turned down `androidx.biometric` partly for the appcompat and fragment it would pull in), and here it
buys three things. What a user receives is the client, its coroutines and the Kotlin standard library —
`app/gradle.lockfile` lists exactly that. An auditor reading this app reads Android's API and nothing in
between. And the common image libraries keep a disk cache by default, which encri D112 forbids for
previews; with none on the classpath there is nothing to configure wrongly. A `ListView` with a framework
row layout is enough for a folder.

*Rejected:* AndroidX (RecyclerView, AppCompat, Lifecycle) — more to ship and to audit, for a screen a
`ListView` serves. *Rejected:* Compose — a large runtime, in an app whose purpose includes being an
example others lift code from.

**The toolchain is encri-api's, version for version**: Gradle 9.7.1, AGP 9.4.1 with Kotlin 2.4.21 on
the root classpath (encri D119), ktlint 1.8.0, detekt 1.23.8, Kover 0.9.9, JUnit 5.14.4 and Robolectric
4.17; the same daemon pin, `.editorconfig`, detekt deltas, hooks and pinned action SHAs. minSdk 27,
because encri cannot run below it (encri D14), and targetSdk 36.

**encri-api is an included build until it is published**, beside this checkout or wherever
`encri.api.dir` points, as encri builds it. The app names the client by the coordinates it will have on
Maven Central, so publishing changes one line of the version catalog.

**One coverage gate, on the whole module, with nothing excluded: 80% of lines**, plus 85% of changed
lines in a pull request (encri D5's rule). The screens are kept thin and run under Robolectric at API 27
and 36 against a stand-in for encri, so the number says what was tested rather than what was easy to
test. encri exempts its own `:app`; this repository is an app whose screens are its subject, so it does
not. Line rather than branch, as encri's `:provider` gate is.

**The dependencies are pinned twice**: `gradle/verification-metadata.xml` by sha256 (encri D7) and
`app/gradle.lockfile` for what ships, in STRICT mode (encri-api L10).

**Flagged for veto:**

1. **No AndroidX**, so every screen is framework views written by hand, and predictive back, insets and
   results use the platform's APIs directly.

---

## Open questions

- **The visible name.** "Encri Files" is for now (F1).

## Build order

1. ~~Scaffold.~~ **Done 2026-10-23** — F1.
2. Everything after is in `ROADMAP.md`.
