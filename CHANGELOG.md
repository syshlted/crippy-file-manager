# encri-files — Changelog

What happened, one entry per roadmap item. `AGENTS.md` is why, and an entry links to a decision rather
than restating it.

## Unreleased

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
