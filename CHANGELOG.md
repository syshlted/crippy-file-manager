# encri-files — Changelog

What happened, one entry per roadmap item. `AGENTS.md` is why, and an entry links to a decision rather
than restating it.

## Unreleased

### The scaffold — 2026-10-23

**Completed (F1).** A Gradle project with one application module, `com.systemhalted.encri.files`, called
"Encri Files" for now. encri-api's toolchain, hooks, CI and gates, an 80% line gate on the module, the
dependencies pinned by sha256 and the release classpath locked. encri-api is an included build.

**Known issues.** CI cannot build until encri-api's rename is pushed (`ROADMAP.md`).
