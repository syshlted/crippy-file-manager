# encri-files — Changelog

What happened, one entry per roadmap item. `AGENTS.md` is why, and an entry links to a decision rather
than restating it.

## Unreleased

### Copying in and out — 2026-10-23

**Completed (F6).** *Copy files in* in the menu takes any number of files from the system's picker into the folder
being looked at; *Copy out of the vault* on a document's long press says what a copy out costs and then asks where it
goes. Each file is copied in under an *(unfinished)* name and renamed once it is whole, a taken name is refused, a copy
that will not fit is refused before it starts, and a file keeps its date. A bar at the foot shows how far a copy has
got, with Stop, and how it ended. Checked on the A20 against encri, a 400 MB file included.

**Bug found and fixed before it shipped.** A file copied in lost its date, because encri's rename stamps the time and
the date was set before the rename. It is set after.

**Known issues.** A copy runs in the app's process, so Android may pause or stop it while another app is in front
(`ROADMAP.md`, *Later*).

### Folders, renames, moves and deletes — 2026-10-23

**Completed (F5).** *New folder* in the menu, and *Rename*, *Move* and *Delete* from a long press, each one
request to encri. The app asks for every right; a grant that holds fewer offers *Allow more in encri*. A taken name
is refused rather than changed, a move is made by going to the folder, and a delete is confirmed by name. Every
dialog follows the screenshot setting. Checked on the A20 against encri.

### Previews — 2026-10-23

**Completed (F4).** A photo or a video gets a preview in its row, made and encoded by encri, held in memory alone
and dropped at every lock. *Show previews*, on by default, is in the settings screen and changes Encri Files alone;
off, nothing is asked for. Checked on the A20 with a photo shared into encri from the Gallery app.

**Bug found and fixed.** A document changed under a minute ago read *0 minutes ago* on the A20, as encri's own
screens once did (its B98). It reads *just now*.

### A setting for screenshots — 2026-10-23

**Completed (F3).** *Allow screenshots*, off by default, in a settings screen reached from the menu. It says what a
screenshot of the vault costs, and that it changes Encri Files alone. Checked with `screencap` on the A20.

### The first screen — 2026-10-23

**Completed (F2).** The app connects to encri, asks the user to let it in on encri's consent screen,
unlocks the vault on encri's unlock screen, lists the vault a folder at a time, folders first, and opens a
document with another app through a handoff. It asks to read and for nothing more. The session belongs to
the process, so a rotation keeps it and the place; a locked vault leaves nothing of itself on the screen.
Run by hand against encri on the Galaxy A20.

**Bug found, in encri.** A document saved through encri's share screen came with no notice that a folder
changed, so this app showed an empty vault until it was asked again. Filed as encri's B111. This app now
lists the folder again whenever its screen comes to the front.

**Known issues.** The API 33+ back callback has not run on a phone. Every document opened mints a handoff
that lasts until the vault locks (`AGENTS.md`, *Open questions*).

### The scaffold — 2026-10-23

**Completed (F1).** A Gradle project with one application module, `com.systemhalted.encri.files`, called
"Encri Files" for now. encri-api's toolchain, hooks, CI and gates, an 80% line gate on the module, the
dependencies pinned by sha256 and the release classpath locked. encri-api is an included build.

**Known issues.** CI cannot build until encri-api's rename is pushed (`ROADMAP.md`).
