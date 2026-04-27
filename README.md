# Luna AI SDK for Android

[![](https://jitpack.io/v/BitCodeHub/luniq-sdk-android.svg)](https://jitpack.io/#BitCodeHub/luniq-sdk-android)

Production Kotlin SDK for [Luna AI](https://uselunaai.com). Same auto-capture, frustration detection, error capture, and guides / surveys engine as the iOS SDK — emits to the same `/v1/events` endpoint.

## Install (JitPack)

In your **root** `settings.gradle.kts` (or `settings.gradle`), add JitPack to repositories:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }   // ← add this
    }
}
```

In your **app module** `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.BitCodeHub:luniq-sdk-android:1.0.0")
}
```

Groovy DSL:

```groovy
implementation 'com.github.BitCodeHub:luniq-sdk-android:1.0.0'
```

## Initialize

In your `Application.onCreate()`:

```kotlin
import ai.luniq.sdk.Luniq

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Luniq.start(
            app         = this,
            apiKey      = "lq_live_xxxxxxxxxxxxxxxxxxxx",
            endpoint    = "https://uselunaai.com",   // or your self-hosted URL
            environment = "PRD",
        )
    }
}
```

Register it in `AndroidManifest.xml`:

```xml
<application android:name=".MyApp" ...>
```

## Usage

### Identify users

```kotlin
Luniq.identify(
    visitorId = "anon-uuid",
    accountId = "user_42",
    traits    = mapOf("plan" to "pro", "engine_type" to "EV")
)
```

### Track custom events

```kotlin
Luniq.track("checkout_started", mapOf("cart_size" to 3))
Luniq.screen("ProductDetail", mapOf("sku" to "GV60"))
Luniq.reportError(throwable, mapOf("feature" to "checkout"))
```

### OkHttp network capture (optional)

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(LuniqInterceptor())
    .build()
```

## Auto-capture (on by default)

| Event | What triggers it |
|---|---|
| `$screen` | `Activity.onResume` via `registerActivityLifecycleCallbacks` |
| `$tap`    | View click listeners (wrapped, not replaced) with x/y coordinates |
| `$rage_click` | 3+ clicks on the same view within 2 s |
| `$dead_click` | Click with no UI response within 1.5 s |
| `$error`  | `Thread.UncaughtExceptionHandler` for unhandled crashes |
| `$network_call` | `LuniqInterceptor` on your OkHttp client (opt-in) |

## Privacy

- Events queue locally, batched every 30 s.
- `visitorId` persisted via `SharedPreferences`.
- Opt-out: `Luniq.optOut(true)` halts all collection.

## Min SDK

- `minSdk` 24 (Android 7+) · covers ~99 % of devices.
- Kotlin 1.9 · JVM target 17.

## Documentation

Full docs at <https://uselunaai.com/docs/android>.

## License

Apache-2.0. Commercial / self-hosted enterprise: <sales@uselunaai.com>.
