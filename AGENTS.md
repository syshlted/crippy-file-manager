# encri-files — Project Facts & Decision Log

> Living document. Append decisions as they're made; mark superseded ones rather than deleting them.
> Date format: YYYY-MM-DD.

## What this is

**encri's file manager**: an Android app that browses an encri vault and hands its documents to other
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

**Scaffolded, and the first screen works (2026-10-23).** It connects to encri, asks the user to let it
in on encri's own screen, unlocks the vault on encri's unlock screen, lists the vault a folder at a time,
and opens a document with another app through a handoff (F2). Since then it has a settings screen
with a switch for screenshots (F3) and one for previews (F4). No writes and no sharing yet. Nothing is
pushed, and nothing has been released.

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

### F2 — The first screen: one session for the process, reading alone, and nothing shown while locked
*2026-10-23 · accepted; **flagged for veto on two points** (below); the second answered by the user the same
day, who asked for a setting, which is F3*

`FilesActivity` connects to encri, opens a session, lists the folder the user let it into, goes into
folders and back out, and opens a document with another app. Seven choices decide how.

**The session belongs to the process, not to the screen.** `FilesApplication` holds the `VaultLink` and
the `Browser`, and a screen draws them. A handoff ends when the session that made it ends (encri D117),
so a session owned by the Activity would close a document another app is showing whenever the phone
turned. A session encri ends — the user turned this app out, turned access for other apps off, or
encri's process went — is asked for again at once, so the screen says why there is no session instead
of showing a vault that is gone. *Rejected:* `configChanges` to keep the Activity alive through a
rotation, which covers rotation and nothing else.

**It asks to read, and for nothing more.** Reading is all this version does, and the consent screen
lists what an app asks for (encri D114); rights nothing exercises are rights a user has no reason to
grant. Asking for more later costs nothing extra, because encri already asks again whenever this app is
updated (encri D120, flagged there).

**While the vault is locked, the screen shows nothing of it**: no document, and no folder's name in the
title. A user who locks the vault expects its names off the screen, as encri's file picker takes them off
(encri D17). The place is kept in memory and an unlock lists it again, because the session outlives the
lock (encri D120) and the names are already in this process.

**`FLAG_SECURE` on the window**, for encri's reason (encri D8): the screen shows the names of what is in
the vault, and a screenshot or the recents thumbnail would keep them outside it. **A setting since F3**, still
blocked unless the user allows it.

**Opening a document is a handoff and `ACTION_VIEW`**, with `FLAG_GRANT_READ_URI_PERMISSION`, through
the platform's own resolver rather than a chooser, so a user can set a default app for a type. The URI
names neither the document nor its id (encri D117). A remedy — *Ask encri*, *Unlock*, *Try again* — is a
button the user presses: nothing here starts one of encri's screens on its own (encri D17's rule that a
prompt starts with the user).

**Folders first, then names as the phone's language sorts them**, with `java.text.Collator`. encri
lists in its catalog's byte order, which puts `Zebra` before `apple`.

**The folder is listed again whenever the screen comes to the front**, as well as when encri says it
changed. encri's notice of a change is best effort (encri-api's `PROTOCOL.md`), and the first device run
showed how far: a document saved through encri's own share screen arrived with no notice at all, which is
encri's B111. A user coming back to the screen should not see what was there when they left it, and one
listing per return is the price.

**Smaller choices.** Back goes up a folder before it leaves: through `OnBackInvokedCallback` from API 33,
opted into in the manifest so every ROM uses it rather than only API 36, and through `onBackPressed`
below. A message encri meant for a developer never reaches the screen (encri-api's `PROTOCOL.md`).

**Verified.** 57 test cases, 76 runs with the screens at both API 27 and 36 under Robolectric, and
91% of lines. Removing the listing on return fails its test at both levels. **On the Galaxy A20 (API 30),
against encri's debug build:** with no vault, encri answered *locked* and its own screen created one; the
consent screen named the app from the system and listed reading alone; the whole vault was allowed; a note
shared into encri's *Received* folder listed and opened in HTML Viewer, another app, which read its name and
its content through the handoff; locking from the menu left no name on screen, and the unlock on encri's
screen came straight back to *Received*; a rotation kept the place and the session; Up and back went up, and
back at the top left. **Not run on a phone:** the API 33+ back callback, which Robolectric only registers;
and the G6, the S9 and the S24.

**Flagged for veto:**

1. **Reading alone**, so the update that adds writing asks the user again, as every update does today.
2. ~~**`FLAG_SECURE`**, which also stops a user taking a screenshot of their own folder.~~ **Answered by the
   user the same day: a setting (F3).**

### F3 — Screenshots are a setting, blocked until the user allows them
*2026-10-23 · accepted, at the user's direction ("a toggle to allow or reject screenshots"); **flagged for veto
on one point** (below)*

**A switch in a settings screen of this app's own**, *Allow screenshots*, off by default. Off, every window this
app draws carries `FLAG_SECURE`: Android blocks screenshots and screen recordings, keeps the app off a screen it
is cast to, and shows a blank card among the recent apps. On, the flag is cleared and all of that is allowed.
The screen says what turning it on costs — a screenshot is kept in the photos, outside the vault, where photo
backups and other apps reach it — and that it changes this app alone: encri's own screens always block
screenshots (encri D8), and this app cannot change that, nor should it.

**Off by default**, which keeps F2's position for everyone who never opens the settings: the screen shows what
is in the vault, and D8's reason for the flag is encri's whole stance. The user who wants a screenshot of their
own folder turns it on, knowing what it costs.

**Applied when a window is made and again whenever a screen comes to the front**, so a change made in settings
reaches the browsing screen without a restart, and the first frame — the one the app switcher keeps — is
already right. Every dialog this app shows will follow it too: a dialog is a window of its own, and the
Activity's flag does not reach it (`followScreenshotSetting`).

**Kept in the app's private preferences**, a single yes or no. The app is out of Android's backups
(`allowBackup` is false), so the choice stays on the phone.

*Rejected:* allowed by default, which would put the vault's names in every app-switcher picture for a user who
never chose that. *Rejected:* a setting per screen, which is more to explain than it is worth.

**Verified.** Under Robolectric at API 27 and 36: blocked by default, allowed once chosen, applied on return from
settings — removing that re-apply fails its test at both levels — and the switch shows what was chosen. **On the
A20:** `screencap` came back empty, then captured the folder list once the switch was on, then came back empty
again once it was off.

**Flagged for veto:**

1. **Blocked by default.**

### F4 — Previews: asked of encri per row, held in memory alone, dropped at every lock
*2026-10-23 · accepted; **flagged for veto on one point** (below)*

encri D112 set the rules and this builds them: a toggle of this app's own, on by default, saying it changes
this app alone; previews encri encodes, so this app never decodes an original; and never a preview on the
phone's storage. Four choices are this app's.

**A row asks for its preview when it is drawn, and stops asking when it is scrolled away** (`ListView`'s
recycler listener), and cancelling a request cancels encri's work (encri-api L12). So a long folder flung past
asks for what is on screen and little else, which matters because encri makes two previews at a time behind
a queue of 256 (encri D120). *Rejected:* encri D112's background pass, which is encri's to build and would
fill a cache encri keeps; this app asks for what it shows.

**Held in an `LruCache` of decoded pictures for the process**, an eighth of the heap and never more than
16 MiB, keyed by the document's id, its modified time, its size and the size asked: a rewritten document is a
new preview. Nothing is ever written to the phone's storage, which is why no image library is used (F1).

**Dropped at every lock, when the session goes, and when the user turns previews off**, along with every
request in flight; an answer to a request made before the drop is thrown away rather than shown. A locked vault
shows nothing of itself (F2), and a picture is more of it than a name. A preview encri cannot make — the type
is not one it previews, or the answer does not decode — is not asked for again until the next drop; one that
merely failed is.

**One square slot per row while previews are on**, so names line up, sized 48 dp, which encri fits each
preview inside without enlarging it (encri D121). Off, the slot is gone and nothing is asked for.

**Verified.** Under Robolectric and coroutine tests: asked at the row's size and held; not asked for a folder
or a type encri cannot preview; nothing asked while off; dropped at a lock, and a request in flight then is
cancelled and never shown — removing the drop at a lock fails both of those tests; a cancelled request
cancels encri's; a rewritten document asked for again. 94% of lines. **On the A20:** a photo shared into
encri from the Gallery app showed its preview in the folder, the text file beside it none, and turning previews
off took every slot away.

**Flagged for veto:**

1. **No background pass of this app's own**, so the first look at a folder of photos waits for each preview.

---

## Open questions

- **The visible name.** "Encri Files" is for now (F1).
- **Whether handoffs should be revoked sooner** than encri ends them. Every document opened mints one,
  and each lasts until the vault locks or the session ends (encri D117). Revoking the previous one when
  another is made would break a viewer the user went back to.

## Build order

1. ~~Scaffold and the first screen.~~ **Done 2026-10-23** — F1, F2.
2. Everything after is in `ROADMAP.md`.
