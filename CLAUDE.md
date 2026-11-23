# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

nextrobytemusic is a YouTube Music client for Android and desktop (Compose Multiplatform), a rebranded
fork of BitChord under GPLv3. The Android `applicationId` is `com.barankrky.nextrobytemusic`, but the
Kotlin package namespace is still `com.music.bitchord` everywhere, and a number of identifiers keep the
old name on purpose (`bitchord.version` system property, `bitchord_analysis` native library,
`nextrobytemusic://` playback URI scheme, `keystore.properties`). Do not "clean up" those names: they
are contracts with stored data, native code, release tooling, and shipped builds.

## Modules

| Gradle module | What lives there |
|---|---|
| `:app` | Android app. Media3 `PlaybackService`, `MainActivity`, `MainViewModel`, downloads, platforms specifics (cast, widgets, DSD, USB/Bluetooth audio), the Android `PlayerHost`/`AppUiHost` implementations. |
| `:shared` | Kotlin Multiplatform data layer for both apps. `commonMain` has pure models and algorithms; most code is in `jvmSharedMain` (both targets are JVM): Innertube client/parser, `StreamResolver`, lyrics providers + `LyricsRepository`, Spotify, Discord RPC (vendored `com.my.kizzy`), queue rules, party protocol models, Automix transition planner. |
| `:sharedUi` | Compose Multiplatform UI shared by phone and desktop: screens, player, lyric view, theming. Real code lives in `jvmSharedMain`; `androidMain`/`jvmMain` hold `expect`/`actual` platform seams. |
| `:desktopApp` | Compose Desktop JVM app (Java 21). `DesktopApp.kt` is the shell; `Desktop*` files replace Android services (audio engine, downloads, party sync, MPRIS/tray, update checker). |
| `backend/` | Go "Listen Together" party server. Separate Go module, not part of Gradle. |
| `native/` | Shared C++ DSP for Automix (tempo/beat/mel/vocal spectrogram) and the librempeg FFmpeg build script. |

`:app` is only included when an Android SDK is found — `settings.gradle.kts` reads `sdk.dir` from
`local.properties` (or `ANDROID_HOME`/`ANDROID_SDK_ROOT`) and skips the module otherwise, so desktop-only
machines can build. If `:app` tasks fail with `project 'app' not found`, fix `local.properties`; do not
edit the build to force-include it.

## Commands

Android (JDK 17+, SDK 37, NDK `27.0.12077973`, CMake 3.22.1):

```bash
./gradlew assembleDevDebug                 # dev APK (installs alongside prod)
./gradlew testDevDebugUnitTest             # all Android unit tests
./gradlew testDevDebugUnitTest --tests "com.music.bitchord.playback.audio.DirectAudioStreamingRegressionTest"
./gradlew :app:assembleProdRelease         # release APK (unsigned without keystore.properties)
./gradlew installDevDebug                  # installs, then pre-verifies dex on device (verifyDevInstall)
./gradlew installDevBenchmark              # R8-shrunk, debug-key build for perf testing
```

Desktop (JDK 21):

```bash
./gradlew :desktopApp:run
./gradlew :shared:jvmTest :desktopApp:test
./gradlew :sharedUi:jvmTest                # shared-UI tests (not run by CI)
./gradlew :desktopApp:createDistributable  # standalone app image
./gradlew :desktopApp:packageDmg|packagePkg|packageDeb|packageRpm|packageExe|packageMsi
./gradlew :desktopApp:windowsPortable      # runnable Windows zip, buildable on any OS
```

Backend (Go 1.27 per `backend/go.mod`):

```bash
cd backend && go build ./... && go vet ./... && go test -v ./...
```

There is no ktlint/detekt/spotless setup; code style is maintained by convention (see below).

## Architecture

### Shared code seam between the two apps

Both apps draw the same player and screens from `:sharedUi`, and supply their platform answers through
two installed interfaces before the first composition:

- `PlayerPlatform.install(PlayerHost)` — settings flows, Canvas decoding, audio outputs, party state,
  lyric translation, cast. Android: `AndroidPlayerHost`; desktop: `DesktopPlayerHost`.
- `AppUi.install(AppUiHost)` — library/recents settings and downloaded-track ids, read by the shared
  Home/Explore/Library/Search pages. Android: `AndroidAppUiHost`; desktop: `DesktopAppUiHost`.

New UI shared by both platforms belongs in `sharedUi/src/jvmSharedMain`; anything platform-specific goes
behind an `expect`/`actual` in `PlayerPlatformUi.kt`-style files or into the host interface — never assume
Android APIs inside `:shared` or `sharedUi` (they must compile for the desktop JVM target too).

The phone's own shell (`MainActivity`, `MainViewModel`, phone-only screens like Local Music, Sources,
Settings) still lives in `:app`. The desktop shell is `DesktopApp.kt` plus many `Desktop*` files.

### Playback and the URI indirection

Queue items never carry a direct stream URL. They carry a virtual URI:

- `nextrobytemusic://watch?v=<videoId>` — YouTube Music tracks
- `nextrobytemusic://source?s=<configId>&t=<trackId>&n=&a=&d=&l=&e=&m=` — tracks from configured sources;
  the query params carry title/artist/duration so cross-source matching works on the loader thread

`PlaybackService` installs a `ResolvingDataSource` that turns those into real streams at open time
(`StreamResolver` for YouTube: InnerTubeX first, NewPipe fallback, URL probed with real bytes before use).
The same indirection is why cast resolves through `CastPlayback` instead of handing URIs to the receiver,
and why `AudioCache`/`ChunkedDataSource` key on the virtual URI. Any change that hands a raw URL to
ExoPlayer breaks caching, quality upgrade, fallback, and offline download behavior.

Queue construction rules (tiers `USER_QUEUE` / `CONTEXT` / `AUTOPLAY`, stable `queueEntryId`) live in
`shared`'s `QueueTimeline`; `QueueCoordinator` applies them to an ExoPlayer on Android, the desktop
applies them to its own list. Change the rules in `QueueTimeline`, not in either app.

### Source layer

`data/sources` on Android (mirrored by `DesktopSourceRegistry` on desktop):

- `SourceKind` is a small fixed enum; its `rank` is the order sources are tried (user-added addons first,
  then legacy module kinds, JioSaavn, YouTube). Adding a protocol means a `MusicSource` implementation
  plus an enum entry — the app deliberately cannot learn new protocols at runtime.
- `SourceRegistry` stores the user's `SourceConfig`s in encrypted prefs and builds the live instances.
  YouTube is seeded, cannot be deleted, only disabled.
- `SourceResolver` walks enabled sources for a track: it honours the source a track is pinned to but
  treats the pin as a starting point, substituting a higher-ranked source when `TrackMatcher` confirms the
  same recording. Quality (`StreamRequest.Lossless/Capped/Best`) is decided here per request from the
  connection's ceiling; downloads use `requestForDownload` (download quality only).
- Two source protocols: **addons** (`addon/`) are plain HTTP JSON servers — `/manifest.json`, `/search`,
  `/stream` — and execute nothing locally; **modules** (`module/`) are JavaScript plugin indexes run in a
  sandboxed QuickJS VM. Addons are the current path; module support is legacy compatibility.

### Automix / native audio

`native/analyzer` C++ is compiled into `libbitchord_analysis` for Android (via `app/src/main/cpp/CMakeLists.txt`)
and for desktop (Gradle invokes CMake into `desktopApp/build/native/<os>`). ONNX beat/vocal models live in
`app/src/main/assets` and are copied into the desktop jar by `processResources`. `TransitionPlanner` (pure
Kotlin, shared) decides where/how a transition happens; `CrossfadeController` executes it. If CMake/Ninja/
MinGW are missing, the desktop build silently builds without the analyser — check the log line before
concluding Automix is broken.

Android also vendors Media3's FFmpeg audio decoder (`app/src/main/java/androidx/media3/decoder/ffmpeg`) with
`libffmpegJNI.so` built from librempeg by `native/ffmpeg/build.sh` — needed for ALAC, AC-4/AC-3/E-AC-3,
TrueHD and DTS on phones without licensed decoders.

### Listen Together

The Go server (`backend/`) holds party state in memory and syncs by publishing a position plus the server
clock instant it was true at; devices compute their own playhead from a measured clock offset. Client side:
`ListenTogether.kt` (socket, clock, protocol) and `PartySync.kt` (in `PlaybackService`, so the party survives
backgrounding). Inbound state is written straight to the player underneath the `ForwardingPlayer`; every
*user* action goes through the wrapper and is published. Preserve that split — anything else echoes
corrections around the party forever. Read `backend/README.md` before touching sync, queue limits, or host
control.

### Downloads

`download/Downloads` keeps two deliberately different maps: `active` (in-memory progress from
`DownloadService`) and `saved` (persisted videoId→URI claims about a folder the app does not own, verified
before every read). Formats get tagged in-place (`Mp4Tagger`, `FlacTagger`, `WebmTagger`) and HLS/DASH
manifests saved for offline playback (`OfflineHls`, `OfflineDash`).

### Logging

Shared code must not call `android.util.Log`. Use `DebugLog` (installed sink: logcat on debug Android,
desktop track log) or `TrackLog` (the pasteable per-track diagnostic log that also echoes to logcat).
`TrackLog` exists because the resolver/auth paths need a per-track record; add lines where a future reader
would ask "why did this track play from this source at this bitrate".

## Localization

There are three string universes and they are edited separately:

1. `app/src/main/res/values*/strings.xml` — Android app strings: the base `values/` plus 18 translated
   catalogues. Any new key must be added to **every** catalogue; `LocaleStringsTest` fails the build
   otherwise. Locale directory names
   follow Android qualifiers (`values-zh-rTW`, `values-b+zh+Hant`), while `SUPPORTED_LANGUAGES`
   (`ui/components/AppLanguageDialog.kt`) and `res/xml/locales_config.xml` drive the in-app picker.
2. `sharedUi/src/commonMain/composeResources/values*/strings.xml` — Compose resources for the shared UI
   (accessed as `Res.string.*` in `jvmSharedMain` code).
3. `desktopApp/src/main/resources/strings/<tag>.xml` (Android catalogue copies) and `desktop-<tag>.xml`
   (desktop-only keys, `d_` prefix). `DesktopStringsTest` requires every desktop key to exist in every
   language and rejects English copied into a translation slot.

When adding UI, decide which universe the string belongs to; do not add a key to only one locale, and do
not put a desktop-only string into the Android catalogues.

## Versioning and releases

- Android: `versionName = "0.1_beta"`, `versionCode = 1` in `app/build.gradle.kts`. `betaSuffix` marks
  sideloaded test builds; blanking it is what turns a beta into a release (the update checker treats
  `1.6.1-beta2` as older than `1.6.1`).
- Desktop: version comes from `-Pbitchord.version=` (CI strips the `v` from the tag), baked in as the
  `bitchord.version` system property. Installers use a separate numeric `nativePackageVersion`
  (`1.0.<desktopVersionCode>`); bump `desktopVersionCode` for every published build and never change the
  Windows `upgradeUuid`. Full protocol in `docs/DESKTOP_RELEASES.md`.
- Release assets are `nextrobytemusic-<version>-<platform>.<ext>`; the desktop update checker matches on the
  platform suffix, so renaming files silently breaks updates.
- CI: `check.yml` runs desktop tests on Linux/Windows/macOS plus `createDistributable` and asserts the
  analyser/models are inside the jar, runs Android unit tests + dev APK, and `go vet`/`go test` for the
  backend. `android.yml` builds a signed prod release APK on pushes to `main`. `release.yml` builds all
  packages for a `v*.*.*` tag (or manual dispatch with a version input) and drafts a GitHub release.

## Build-system landmines (each is load-bearing)

- Root `build.gradle.kts` force-resolves R8 `8.13.23` over AGP's bundled 8.10.x. The older R8 miscompiles
  `NowPlayingScreen` in debug dexing (VerifyError at composition). Do not remove.
- `:app` strips `Utils.class` out of the NewPipeExtractor jar (`stripNewPipeExtractorUtils`) and provides a
  patched copy at `app/src/main/java/org/schabi/newpipe/extractor/utils/Utils.java`. Removing either side
  breaks release dex merging ("Utils is defined multiple times"); keep edits to that file confined to the
  two URL encode/decode methods so it stays diffable against upstream v0.26.3.
- `:sharedUi` compiles against Compose Multiplatform 1.10.3 for Android but against `CMP_DESKTOP`
  (1.12.0 on Linux, 1.10.3 on Windows) for the desktop JVM target. Windows is pinned to 1.10.3 + Haze
  1.7.2 because Haze 1.7.x was compiled against skiko 0.9's `ShaderBrush.createShader` signature. Violating
  these pins produces runtime `NoSuchMethodError`s, not compile errors.
- Media3's DASH and HLS artifacts are required: source addons may return `.m3u8` or `.mpd` manifests, and
  removing them makes those tracks fall back to progressive and fail as unparseable XML.
- Release builds run R8 with `-dontobfuscate`; libraries reached by reflection (Rhino, NewPipe, InnerTubeX,
  QuickJS, SMBJ, BouncyCastle, ONNX JNI, protobuf-lite, Ktor, GraalVM polyglot on desktop) are kept whole
  in `app/proguard-rules.pro`. Add rules there rather than disabling minification.
- Android unit tests run with `isReturnDefaultValues = true` because `android.util.Log` (used by `TrackLog`)
  would otherwise throw in stubs.
- Desktop resource copies (ONNX models, native libs, string XMLs) are wired manually in
  `desktopApp/build.gradle.kts`; a new asset for desktop must be added to `processResources`.

## Conventions

- Comments and KDoc are unusually long-form and explain *why* — measured behavior, regressions that
  motivated the code, trade-offs rejected. Match this; a terse "what" comment in this codebase stands out.
  Reference issues by `#n` where relevant.
- Tests use backticked sentence names (`fun \`a streaming-only addon is still a download source\`()`), JUnit 4
  on Android and `kotlin.test` elsewhere. Tests that assert absence of behavior state why in the file KDoc,
  so a later change does not silently restore the old rule.
- Adding a new audio format or source protocol means wiring **both** apps: Android `MusicSource`/Media3
  path and the desktop `DesktopSourceRegistry`/`DesktopAudioDecoder` path. `Desktop*ParityTest` files assert
  parity rather than assuming it.
- Keep `:shared` free of Android and Compose dependencies — that is what lets both apps and the shared
  tests compile the same code.
- Local secrets go in `local.properties` (`LASTFM_API_KEY`, `LASTFM_SECRET`, `LISTEN_TOGETHER_SERVER`,
  `MODULE_INDEX_URL`) or `keystore.properties`; both are gitignored, and every consumer has a supported
  "absent" path that still builds.
