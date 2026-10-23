# Encri Files

A file manager for an encri vault. It reaches the vault through encri's
companion service, using the [encri-api](https://github.com/syshlted/encri-api) client library, and never
through Android's file picker: so it works with encri's file-picker access turned off, and Android keeps
no record of which document went to which app.

**Pre-release. Nothing is published.** Today it lists the vault a folder at a time and opens a document
with another app. See [`ROADMAP.md`](ROADMAP.md) for what comes next and [`AGENTS.md`](AGENTS.md) for why
it is built the way it is.

It is also an example: everything it does, any app can do with the same library, and it is licensed so
that its code can be lifted.

## Building

JDK 21, the Android SDK (compileSdk 36), and encri-api checked out beside this repository at
`../encri-api`, or named with `-Pencri.api.dir=<path>`. Then:

```sh
lefthook install    # once per clone: signed, Conventional commits and the local gates
./gradlew ktlintCheck detekt allUnitTests koverVerify assemble
```

The app talks only to an encri signed by the same key, so a debug build of this app finds a debug build
of encri made on the same machine.

Licensed under the [Apache License 2.0](LICENSE).
