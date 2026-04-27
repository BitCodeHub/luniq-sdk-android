# Luniq SDK Sample App

A minimal Android app that depends on `:hpulse` (the Luniq SDK module) and
exercises the public API: `Luniq.start`, `Luniq.track`, `Luniq.identify`.

## Run on a connected device or emulator

```bash
./gradlew :sample:installDebug
adb shell am start -n ai.luniq.sample/.MainActivity
```

## What it does

- Calls `Luniq.start(application, apiKey="lq_live_demo", endpoint="https://uselunaai.com", environment="dev")` from `MainActivity.onCreate`.
- "Track event" button calls `Luniq.track("button_click", mapOf("source" to "sample_app"))`.
- "Identify user" button calls `Luniq.identify(accountId="demo_user_42", traits=mapOf("plan" to "free"))`.

## Notes

- Initialization happens in the Activity for brevity. Production apps should
  call `Luniq.start()` from a custom `Application.onCreate()` so auto-capture
  works across the whole app lifecycle.
- The endpoint is a placeholder — events will be queued and retried until a
  reachable Luniq backend is configured.
