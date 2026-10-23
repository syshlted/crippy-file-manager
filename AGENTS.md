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
with a switch for screenshots (F3) and one for previews (F4), and it makes folders and renames, moves and
deletes what is in the vault (F5). It copies files in from the system's picker and copies a document out to a
place the user picks (F6), and shares a document through the share sheet (F7). A copy runs behind a foreground
service, so it carries on when the user leaves the app (F8). Nothing is pushed, and nothing has been released.

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
updated (encri D120, flagged there). **Superseded by F5 the same day**, once the app could do more than read:
it asks for every right, because it uses every one.

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

1. ~~**Reading alone**, so the update that adds writing asks the user again, as every update does today.~~
   **Moot since F5**, which asks for every right.
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

### F5 — Changes: every right asked for, a name taken refused, and a move made by going there
*2026-10-23 · accepted; **flagged for veto on two points** (below). **Supersedes F2** on the rights asked for.*

The app makes a folder, and renames, moves and deletes documents and folders. Each is one request to encri,
which checks the right and the name itself and refuses what it will not do (encri D120); nothing here decides
whether a change is allowed. Six choices are this app's.

**It asks for every right, because it uses every one**: reading, renaming — which encri counts as writing —
making folders, deleting and moving (`Asked.RIGHTS`). F2 asked to read alone while reading was all it did. A
grant may hold fewer, one made for an earlier version or one the user narrowed, and then the changes it lacks
are not offered and the menu offers *Allow more in encri*, which starts encri's consent screen for all five.
encri ends the old session when it replaces a grant, and the app opens a new one at once (F2).

**A name already taken is refused, never changed into another.** encri can pick a free name instead
(encri-api's `NameConflict.PICK_FREE_NAME`); this app never asks it to, so a file is never silently given a name
the user did not choose. The user is told something with that name is already there, and decides.

**A move is made by going to the folder**: *Move* puts a bar at the foot of the screen naming what is moving,
the user browses to where it goes, and *Move here* moves it. The button is off in the folder it is already in
and inside a folder being moved into itself; encri's own cycle check is still the authority, and its refusal is
said like any other. *Rejected:* a folder picker of its own, which would be a second browser to keep in step
with the first.

**A delete is confirmed by name, and says what it cannot undo**: that a folder goes with everything in it, that
nothing here brings it back, and that a backup encri made before may still hold a copy (encri D95 keeps eight
generations).

**What encri says is put in the user's terms** (`Outcomes`), per change: *invalid* means a name that cannot be
used for a rename, and a folder moved into itself for a move. encri's own message is for a developer and is
never shown.

**Every dialog follows the screenshot setting (F3)**, because a dialog is a window of its own and shows a name,
and **no toast names a document**: a toast is drawn by the system, outside this app's windows, where the setting
cannot reach. The name field is focused with the keyboard up and the name before its extension selected, so a
rename is typing the new name.

**Verified.** Under Robolectric at API 27 and 36 against a stand-in with a tree that changes: each change, a
blank name refused before encri is asked, a rename to the same name asking nothing, a delete confirmed first, a
move gone through and given up, a refusal in the user's terms, a read-only session offering nothing but *Allow
more*, and dialogs under the screenshot setting. Removing the dialogs' screenshot setting, or the check that a
folder is not moved into itself, each fails its tests. 95% of lines. **On the A20**, against encri: the read-only
grant offered *Allow more in encri*, whose consent screen listed all five rights; once allowed, a folder was
made, the note renamed by typing over its selected name, moved into the new folder from a bar at the foot, a
second folder with a taken name refused in words, and the new folder deleted with the note inside it.

**Flagged for veto:**

1. **A taken name is refused**, where many file managers offer to keep both.
2. **A delete cannot be undone here**: there is no bin.

### F6 — Copies: through the system's picker, never a whole-looking file that is not, one at a time
*2026-10-23 · accepted; **flagged for veto on two points** (below)*

**Copying in** takes any number of files of any type from the system's picker (`ACTION_OPEN_DOCUMENT`) into the
folder the user is looking at. **Copying out** takes one document from its long press to a place the user picks
(`ACTION_CREATE_DOCUMENT`). Both go through the session's ordinary requests (encri-api L11), so they work with
encri's file-picker access off. Nine choices are this app's.

**A file is copied in as `(unfinished) <name>`, written, and only then renamed to its own name**, which is encri's
own rule for a share (encri D118), with the same marker so a user meets one convention. encri saves a write only
when the writer finishes (encri-api L11), so a copy that is stopped is abandoned by throwing out of the writer,
never by returning early, and what it had made is deleted. One that cannot be deleted — the vault locked under it
— keeps the name that says what it is, and the report counts it.

**A name already in the folder is refused**, F5's rule: checked against a fresh listing before anything is written,
and by encri again at the rename, where a name taken in between removes the copy. Two picked files of one name:
the second is refused.

**A copy in that will not fit is refused before anything is written**, when the sizes the files' apps give add up
to more than encri says it can take (encri D118's rule). A file whose app gives no size is not counted, and encri's
own `NO_SPACE` still stops the copy if it runs out.

**A file keeps the date its app gives it**, set after the rename, because encri's rename stamps the time it happened
(its `Catalog.rename`) — which the first device run found by showing *just now* for a file from that morning. Only
the last change is kept: a picker says nothing of when a file was made.

**Before anything leaves, a copy out says what it costs**: the copy is not encrypted, the app keeping the place
chosen can read it and so can whatever it backs up to, and deleting the document later does not delete the copy
(encri D123). Every time — there is no *don't ask again*. Then the place is opened asking for truncation and
settling for less (`wt`, then `w`, encri D78), and after the close its length is asked again for three seconds
before a short answer is believed (encri B102). A copy out that fails leaves the file the picker made, empty or
part-written, and says so rather than deleting it, for encri D78's reason: the picker may have handed back a file
that was there before.

**Another app's files are reached through an unstable client**, as encri reaches them (its B100), so a cloud app
that dies mid-copy is a copy that failed rather than the death of this app, its session and every handoff another
app is reading. `ForeignDocumentsTest` holds every source to it.

**One copy at a time, for the process, in a bar at the foot of the screen**: how far it has got, with Stop, and
then how it ended, until the user presses OK. Names appear in the bar only while the vault is open and unlocked
(F2): an ending over a locked vault is counted, and promises the names after an unlock. Each file refused on its own
is named with why; the ones a stop or a lock never reached are counted once, with the reason. A copy in that brought
anything says the originals are still where they were, unencrypted, because a user may believe the file is now
protected.

**While a copy runs, back at the top sends the app to the background instead of closing it.** A file a picker hands
over is readable while the task of the activity that received it is alive (Android's own `FileProvider`
documentation: *"Permissions granted in an Intent remain in effect while the stack of the receiving Activity is
active"*), so closing the task mid-copy could leave the rest unreadable. From API 31 back at the top does that anyway;
below it, this keeps it so. Recalled from the documentation, and not measured.

**64 KiB at a time**, which no FUSE request an AppFuse bridge carries can fail on (encri D88), whichever side another
app's provider is on.

*Rejected:* taking a persistable grant on each picked file so the copy survives its task — it writes the files'
URIs into the system's grant record until released, where keeping the task alive needs no record at all.
*Rejected:* a free name for a taken one (F5). *Rejected:* deleting a part-written copy out (encri D78).
*Rejected:* staging a copy anywhere on the phone's storage — nothing of the vault is written outside it.

**Not built:** copying a folder in or out, copying several documents out at once, and choosing what happens to a
taken name. Each needs a choice of its own, and none is needed to move one file either way.

**Verified.** Under Robolectric and coroutine tests, against stand-ins for encri and for the apps the picker reaches,
and against a real `ContentProvider` for the resolver: a file arriving whole with its date; several in turn; a taken
name refused before and during a copy; no room; a file that will not open, that stops part way, or has no name; a
full vault ending the copy; a stop keeping only what finished; a copy that cannot be removed counted; and a copy out
whole, refused by encri, cut off by encri, refused by its place at the open, the write and the close, stopped, and
believed only once its length agrees. 217 runs, 94.8% of lines. A stop that returned from the writer instead of
throwing fails two of them; back at the top closing the app during a copy fails one. **On the Galaxy A20 (API 30),
against encri:** three files chosen with the picker's *Select all*, two copied, the third refused in words for a taken
name, and a file from March 2025 keeping its date; a 400 MB file copied in and back out, the SHA-256 of the copy out
the same as the original's; and Stop pressed a second into a copy left nothing in the folder and said so.
**Not run on a phone:** back at the top during a copy, and the S9, the G6 and the S24.

**Flagged for veto:**

1. ~~**No foreground service.** A copy runs in the app's process, so Android may pause it (from API 34) or stop it
   while another app is in front; the bar says to keep the app open, and a copy that is stopped leaves its
   unfinished name. A service would need `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` and a notification, the
   first permissions this app would declare.~~ **Answered by the user the same day, who asked for one: F8.**
2. **A copy out is confirmed every time**, with no way to stop being asked.

### F7 — Sharing: a handoff through the share sheet, said first, and never back to encri
*2026-10-23 · accepted; **flagged for veto on one point** (below)*

A document's long press offers *Share* first, for a document and not a folder; reading is all it needs. It is encri
D118's sharing out, which that decision gave to the file manager. Four choices are this app's.

**Before the share sheet opens, the screen says what a share costs**: the app chosen gets what is in the document,
not encrypted, and can keep a copy that deleting the document does not delete; and it reads from the vault, so if it
waits to read until after the vault locks or this app is closed, it finds nothing (encri D117's lock rule, which encri
D118 said the file manager's share must say). Every time, as a copy out is (F6).

**A handoff, sent with `ACTION_SEND` through `Intent.createChooser`**, the URI in the stream extra and in the clip,
with the read grant, so the grant reaches whichever app is picked (encri D118). The clip's label is empty, so the
document's name goes only where an app asks encri's handoff provider for it — the share sheet does, to draw its
preview, as encri D118 recorded.

**encri is left out of the share sheet.** Its share screen refuses a URI on its own authorities (encri D118), so a
vault document offered back to it is a choice certain to fail — found on the A20, where choosing it answered *There is
nothing here encri can save*. Its components are excluded by asking the package manager which of encri's
(`Encri.PACKAGE`) activities would take the share, so this holds whatever encri names them, and nothing else is left
out. To put a document in another folder of the vault, a move does it (F5).

**What Android keeps of a share is not repeated here.** The share sheet records the app chosen and the type in the
phone's usage history (encri D118, which says so on encri's privacy screen); the user is choosing an app on that screen,
which is the act the record describes.

*Rejected:* a decrypted copy shared through a `FileProvider` of this app's own, which writes the document to the
phone's storage. *Rejected:* revoking the handoff once the share sheet closes, which nothing reports and which would
break an app that reads when it sends rather than when it is chosen. *Not built:* several documents at once, which
needs a selection the list does not have yet, and a folder.

**Verified.** Under Robolectric at API 27 and 36: a document offers a share first and a folder none; nothing is handed
over before the confirmation; the share sheet carries a handoff in the stream and the clip with the grant; encri's
component is left out and another app's is not; a refused handoff opens nothing. 231 runs, 94.9% of lines. **On the A20,
against encri:** the share sheet named the document, read through the handoff; Nextcloud uploaded it to the test
server, whose copy has the original's SHA-256; and once encri was left out, the sheet offered everything it had but
encri.

**Flagged for veto:**

1. **Every share is confirmed**, with no way to stop being asked.

### F8 — A copy runs behind a `dataSync` foreground service, which holds the picker's grants and runs no copy
*2026-10-23 · accepted, at the user's direction ("add a foreground service to the files app so the user isn't stuck
on the app screen while running long operations"); answers F6's first flag; **flagged for veto on two points** (below)*

**`TransferService` keeps the process in the foreground for as long as a copy runs, and runs no copy itself.**
`Transfers` still does, in the application (F6). So nothing about how a copy is made changes: the service is what
stops Android pausing the process (from API 34) or stopping it for being in the background, and it is what holds the
picker's grants. It follows `Transfers.state`: started when a copy starts, it goes to the foreground at once, and when
the copy ends it stops itself with the latest start's id, which is the one `stopSelf(int)` has to name. Not exported,
binds to nothing, and `START_NOT_STICKY`, because a restarted service would have no copy to hold.

**The grants ride in its start intent.** A file the picker handed over is readable while the task of the screen that
received it is alive. Handed to a service as `ClipData` with the grant flags, the grant lasts until the service stops
for that start, which `Service`'s documentation says and encri measured for its own share screen (encri D118): read
for a copy in, and read and write for a copy out, whose length is read back after the close (encri B102). So a copy
goes on after its screen is closed or swiped away from the recent apps. F6's *back at the top keeps the app* stays,
for the case below where the platform refuses the service.

**The first permissions this app declares, three for one capability.** `FOREGROUND_SERVICE` (API 28) and
`FOREGROUND_SERVICE_DATA_SYNC` (API 34) are both install-time and both needed by an app targeting 36, the second a
declaration of its own and not a successor (encri D78 read the same). `POST_NOTIFICATIONS` is a runtime permission
from API 33, asked for when a copy starts — after the picker has closed and the copy is under way, so the question
does not hold the copy up — and again at the next copy until answered, which Android stops showing after a second
refusal. Refused, the copy still carries on; only its notification is not drawn.

**What the notifications say: counts, never a name.** A notification is read off a locked phone by whoever holds it,
and the names of what is in the vault are what F2 keeps off a locked screen. The ongoing one says which file of how
many and how much, with a progress bar and *Stop*; *Stop* reaches the copy at its next block, as the bar's does.
It is told at most once a second, because Android drops a package's notifications beyond a few a second and a fast
copy reports a mebibyte at a time. The ending is said as a count — *Copied 3 files into the vault*, *A document was
not copied out of the vault* — with *Open Encri Files to see what happened* when something was not whole. Both are
private on the lock screen, in one channel, *Copies*, at low importance, so a copy makes no sound and silencing it
silences nothing else. Tapping either opens the browsing screen, whose bar has the rest.

**The ending is not said while a screen of this app is in front**, because the bar already says it, and the
notification that said it away from the screen is taken back when a screen comes to the front.

**A refusal is said, not hidden.** From API 31 Android refuses a foreground service to an app in the background. A
screen choosing files is never in the background, so this is not expected to happen; if it does, the copy runs as it
did before (F6), and the bar says to keep the app open rather than that it can be left. The bar tells which from
`Transfer.Running.held`.

**From API 35 a `dataSync` service has six hours a day**, and the platform's `onTimeout` stops the copy at its next
block. At the rates measured below that is a terabyte or more.

*Rejected:* a user-initiated data transfer job (`JobInfo.Builder.setUserInitiated`, API 34), which Android's
documentation points to for transfers a user starts and which may one day replace `dataSync`. It needs API 34 against
a floor of 27, and a network constraint, which a copy between this phone's storage and the vault does not have.
*Rejected:* WorkManager, which is AndroidX (F1) and, for this, a foreground service underneath. *Rejected:*
`shortService` (API 34), whose three minutes a large copy outlasts. *Rejected:* a service that runs the copy itself,
which would move the copy engine for nothing the service needs. *Rejected:* `setForegroundServiceBehavior` to show the
notification at once: from API 31 Android holds a new foreground service's notification back for up to ten seconds,
so a short copy flashes nothing.

**Verified.** Under Robolectric at API 27 and 36: the service goes to the foreground with a notification that counts
and names nothing, says how a copy ended away from the screen and not in front of it, goes to the foreground and stops
at once for a copy already ended, stops with the latest start's id, takes a stop and a spent budget to the copy, and
is handed the grants each direction needs; the notice counts, offers *Stop* as a service intent, is told at most once
a second, words each ending without a name, and posts nothing with notifications refused; the screen starts the
service, asks for notifications from API 33 and not below, and takes back the ending. 285 runs, 95.3% of lines. Eight
mutations each fail the test written for them, at both levels. **On the Galaxy A20 (API 30), against encri:** a 2 GB
file copied in with the app sent to the Home screen straight after, the notification counting up to the end and the
service gone once it said *Copied 1 file into the vault*, which opening the app took back; the same document copied
out with the app's task removed two seconds in (`am stack remove`, which is what swiping it from the recent apps does),
carrying on in the same process to the end, its SHA-256 the same as the original's; and *Stop* in the notification
about 700 MB into a copy left nothing in the vault and said
*Nothing was copied into the vault*. **Not run on a phone:** the API 33+ notification question, API 34's type
permission and API 35's budget, which the S24 would take; and the G6 and the S9.

**Flagged for veto:**

1. **Notifications asked for when a copy starts**, rather than never — the copy runs either way — or at first launch.
2. **No ending said while a screen of this app is in front.**

---

## Open questions

- **The visible name.** "Encri Files" is for now (F1).
- **Whether handoffs should be revoked sooner** than encri ends them. Every document opened or shared mints
  one, and each lasts until the vault locks or the session ends (encri D117). Revoking the previous one when
  another is made would break a viewer the user went back to, and an app a share reached that reads when it
  sends rather than when it is chosen (F7).

## Build order

1. ~~Scaffold and the first screen.~~ **Done 2026-10-23** — F1, F2.
2. Everything after is in `ROADMAP.md`.
