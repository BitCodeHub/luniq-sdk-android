# Changelog

All notable changes to `com.github.BitCodeHub:luniq-sdk-android` are
documented in this file. The project adheres to
[Semantic Versioning](https://semver.org/).

## [1.1.0] — 2026-04-29

### Added
- **In-app engage runtime** — native Kotlin renderers for banners,
  guides, and surveys, matching the iOS + Web SDK feature set:
  - `BannerRenderer` — sticky banner in the decor view (top or bottom)
    with click + dismiss telemetry.
  - `GuideRenderer` — multi-step `AlertDialog` walkthrough; emits
    `$guide_shown`, `$guide_step`, `$guide_completed`, `$guide_dismissed`.
  - `SurveyRenderer` — rating, single-choice, multi-choice, and
    free-text survey questions with submission roundtrip.
- `Engage` background scheduler — polls
  `/v1/sdk/{banners,guides,surveys}` every 5 minutes via
  `ScheduledExecutorService`, evaluates audience + trigger locally,
  and renders matched content. Dismissals persisted per-id in
  `SharedPreferences("luniq_engage_dismissed")`.
- Public API: `Luniq.showBanner(id)`, `Luniq.showGuide(id)`,
  `Luniq.showSurvey(id)` — `@JvmStatic` so callable from Java or
  Kotlin.
- Activity lifecycle integration via `ActivityLifecycleCallbacks` so
  renderers always target the foreground activity.

### Notes
- No new dependencies. Uses `HttpURLConnection`, stock Android views,
  and the existing `LuniqConfig` write-key.

## [1.0.1] — 2026-04-27

### Fixed
- **ProGuard / R8 stripping (release builds crash)**: consumer rules
  pointed at the legacy package `io.pulse.sdk` so R8 was stripping the
  public `ai.luniq.sdk.*` API in release builds, causing
  `ClassNotFoundException` at runtime. Rules now correctly target
  `ai.luniq.sdk.**`.

### Added
- `:sample` Gradle module — minimal app showing `Luniq.start`, `track`,
  `identify` end-to-end. Run with `./gradlew :sample:installDebug`.
- 13 JVM-only unit tests covering `IntelligenceEngine` (persona, churn
  prediction, conversion probability, journey summary, breadcrumb
  buffer) and `LuniqConfig`.
- `LICENSE` (Apache-2.0).

## [1.0.0] — 2026-04-26

### Added
- Initial public release: `Luniq.start` / `track` / `identify` /
  `screen` / `reportError`, on-device intelligence (`profile`,
  `predictChurn`, `sessionScore`, `persona`, `journeySummary`,
  `conversionProbability`), adaptive nudges (`onNudge`), Design Mode,
  optional `LuniqInterceptor` for OkHttp network capture.
- Published via JitPack: `com.github.BitCodeHub:luniq-sdk-android:1.0.0`.
