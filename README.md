# Encri Files

A file manager for an encri vault. It reaches the vault through encri's
companion service, using the [encri-api](https://github.com/syshlted/encri-api) client library, and never
through Android's file picker: so it works with encri's file-picker access turned off, and Android keeps
no record of which document went to which app.

**Pre-release. Nothing is published.** Today it lists the vault a folder at a time, opens a document
with another app, makes and changes folders, copies files in and out, and shares a document. See [`ROADMAP.md`](ROADMAP.md) for what comes next and [`AGENTS.md`](AGENTS.md) for why
it is built the way it is.

It is also an example: everything it does, any app can do with the same library, and it is licensed so
that its code can be lifted.

## Building

JDK 21, the Android SDK (compileSdk 36), and encri-api checked out beside this repository at
`../encri-api`, at the commit [`config/encri-api.pin`](config/encri-api.pin) names, or named with
`-Pencri.api.dir=<path>`. Then:

```sh
scripts/ci/encri-api fetch ../encri-api   # encri-api at the pinned commit
lefthook install                          # once per clone: signed, Conventional commits and the local gates
./gradlew ktlintCheck detekt allUnitTests koverVerify assemble
```

The app talks only to an encri signed by encri's release key or by the same key as itself, so a debug
build of this app finds the encri on Google Play, and a debug build of encri made on the same machine.

## Checking a release

[`FINGERPRINTS.md`](FINGERPRINTS.md) lists the keys the apps are signed with. A release is built twice from
its tag, by GitHub's CI and by the maintainer, and signed only once the builds agree, so anyone can rebuild
it and compare. On Linux, with Python 3:

```sh
scripts/release/reproduce.sh v0.1.0            # two builds, in build/reproducible/<commit>/
python3 scripts/release/compare-builds.py signed <the release's APK> \
  build/reproducible/<commit>/encri-files-<commit>-unsigned.apk
```

`compare-builds.py signed` takes the signature out of the release's APK and holds every other byte to the
rebuild.

Licensed under the [Apache License 2.0](LICENSE).
