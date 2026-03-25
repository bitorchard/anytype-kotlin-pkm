# Pebble PKM — Testing Guide

## Unit Tests

All Pebble module unit tests run on the JVM (no device required).

```bash
# Run all Pebble unit tests at once
./gradlew :pebble-core:testDebugUnitTest \
          :pebble-changecontrol:testDebugUnitTest \
          :pebble-assimilation:testDebugUnitTest \
          :pebble-webhook:testDebugUnitTest \
          :feature-pebble-ui:testDebugUnitTest

# Run a single module
./gradlew :pebble-assimilation:testDebugUnitTest
```

### Test inventory (145 total, 0 failures)

| Module | Tests | Key areas |
|---|---|---|
| `pebble-core` | 36 | `SchemaBootstrapper`, `MigrationRunner`, `TaxonomyProvider` |
| `pebble-changecontrol` | 24 | `ChangeExecutor`, `ChangeRollback` |
| `pebble-assimilation` | 55 | `AssimilationEngine`, `EntityResolver`, `ScoringEngine`, `NameSimilarity`, E2E pipeline |
| `pebble-webhook` | 13 | `InputProcessor`, `WebhookConfig` |
| `feature-pebble-ui` | 17 | `ManualInputViewModel`, `ApprovalViewModel` |

### Test architecture decisions

- **Fakes over Mockito for inline value classes**: `SpaceId` and `TypeKey` are Kotlin value
  classes that unbox to `String` at the JVM level. Mockito's `any()` matcher returns `null`
  which cannot be unboxed, causing `NullPointerException`. All tests that involve these types
  use hand-written fakes (`FakePebbleGraphService`, `FakeEntityExtractionService`, etc.).

- **Service interfaces for testability**: `EntityExtractionService` and `EntityResolutionService`
  were extracted from the concrete `EntityExtractor`/`EntityResolver` classes so that tests
  can use simple queue-based fakes without dealing with Mockito's checked-exception restrictions.

- **Mockito used safely**: Mockito is only used for types whose parameters are plain Kotlin/Java
  types (no value classes), e.g. `ChangeStore`, `ChangeExecutor` in ViewModel tests.

---

## Emulator / Device Testing

### Prerequisites

1. **Android SDK** with an AVD image (see "Creating an AVD" below).
2. **Java 17** — set `JAVA_HOME` to your JDK 17 installation.
3. **Middleware** — the Go backend must be built and linked (see `docs/Setup_For_Middleware.md`).
4. **Config files** — `github.properties` (or `GPR_USER`/`GPR_TOKEN` env vars) and
   `apikeys.properties` with an OpenAI-compatible API key for the LLM.

### Creating an AVD (Android Virtual Device)

```bash
# Install the emulator and a system image (API 35, x86_64)
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
    "emulator" \
    "system-images;android-35;google_apis;x86_64" \
    "platform-tools"

# Create an AVD
$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd \
    --name "Pebble_Test" \
    --abi "google_apis/x86_64" \
    --package "system-images;android-35;google_apis;x86_64" \
    --device "pixel_6"

# Start the emulator (headless)
$ANDROID_HOME/emulator/emulator -avd Pebble_Test -no-window -no-audio &

# Wait for boot
$ANDROID_HOME/platform-tools/adb wait-for-device shell \
    'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
```

### Build and install the debug APK

```bash
# Build
./gradlew assembleDebug

# Install on the running emulator
$ANDROID_HOME/platform-tools/adb install -r \
    app/build/outputs/apk/debug/app-debug.apk
```

### Manual end-to-end test of the Pebble pipeline

Once the app is running on the emulator:

1. **Start the webhook server**: Open the app → Settings → Pebble Settings → enable the
   listener. The embedded Ktor server starts on port `8391`.

2. **Forward the port** so you can hit it from your dev machine:

   ```bash
   adb forward tcp:8391 tcp:8391
   ```

3. **Send a voice input** via `curl`:

   ```bash
   curl -s -X POST http://localhost:8391/api/v1/input \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer <your-token>" \
     -d '{"text": "Aarav has a basketball game on Friday at 5pm"}'
   ```

   Expected response:
   ```json
   {"id":"...","traceId":"...","status":"queued"}
   ```

4. **Observe the pipeline**: The input is processed by the LLM extractor, resolved against
   your Anytype space, and a `ChangeSet` is created. Check the Approval screen to review and
   apply the proposed changes.

5. **Check the Debug screen** for the full pipeline trace (INPUT_RECEIVED → LLM_EXTRACTED →
   PLAN_GENERATED → APPROVAL_PENDING → CHANGE_APPLIED).

### Webhook server health check

```bash
curl http://localhost:8391/api/v1/status
# {"status":"ok","port":8391,"pendingInputs":0,"version":"1.0"}
```

### Running instrumentation tests

Instrumentation tests require a running device/emulator:

```bash
./gradlew connectedDebugAndroidTest
```

> **Note**: The Pebble modules do not yet have instrumentation tests. Adding them is tracked
> as a future improvement. The current test suite (145 unit tests) covers all business logic
> without requiring a device.

---

## CI / Automated Checks

```bash
# Full PR validation (lint + unit tests)
make pr_check

# Unit tests only
make test_debug_all

# Lint only
./gradlew lintDebug
```

The `settings.gradle` falls back to `GPR_USER` / `GPR_TOKEN` environment variables when
`github.properties` is absent, so CI runners do not need the properties file.
