# Resident Foreground Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the SMS gateway run as reliably as Android permits for a normal APK, with a default-enabled user switch and a persistent foreground notification.

**Architecture:** Add a dedicated sticky `ResidentForegroundService` as the only automatic business-lifecycle entry point. Keep `OrchestratorService` responsible for business modules, protect it with an idempotent lifecycle gate, and route app launch, boot, upgrade, and UI controls through the resident service.

**Tech Stack:** Kotlin, Android Service/BroadcastReceiver, Koin, WorkManager, JUnit 4, Robolectric, Gradle 8/Android Gradle Plugin 8.1.

---

## File structure

- Create `app/src/main/java/me/capcom/smsgateway/modules/orchestrator/IdempotentLifecycle.kt`: thread-safe start/stop gate.
- Create `app/src/main/java/me/capcom/smsgateway/services/ResidentForegroundService.kt`: sticky foreground service and start/stop helpers.
- Create `app/src/test/java/me/capcom/smsgateway/modules/orchestrator/IdempotentLifecycleTest.kt`: lifecycle gate JVM tests.
- Create `app/src/test/java/me/capcom/smsgateway/helpers/SettingsHelperTest.kt`: default and persisted autostart behavior.
- Create `app/src/test/java/me/capcom/smsgateway/services/ResidentForegroundServiceTest.kt`: foreground service sticky and idempotent behavior under Robolectric.
- Create `app/src/test/java/me/capcom/smsgateway/receivers/BootReceiverTest.kt`: boot/upgrade recovery policy under Robolectric.
- Modify `app/build.gradle`: enable Android resources in JVM tests and add Robolectric test dependencies.
- Modify `app/src/main/AndroidManifest.xml`: register the resident service, notification permission, and upgrade broadcast.
- Modify `app/src/main/java/me/capcom/smsgateway/helpers/SettingsHelper.kt`: default autostart to true while preserving explicit false.
- Modify `app/src/main/java/me/capcom/smsgateway/modules/orchestrator/OrchestratorService.kt`: idempotent, failure-isolated startup and stop.
- Modify `app/src/main/java/me/capcom/smsgateway/modules/gateway/workers/PullMessagesWorker.kt`: retain an existing periodic schedule with `KEEP`.
- Modify `app/src/main/java/me/capcom/smsgateway/modules/notifications/NotificationsService.kt`: resident notification ID and ongoing behavior.
- Modify `app/src/main/java/me/capcom/smsgateway/receivers/BootReceiver.kt`: start only the resident service when enabled.
- Modify `app/src/main/java/me/capcom/smsgateway/App.kt`: route automatic application startup through the resident service.
- Modify `app/src/main/java/me/capcom/smsgateway/ui/HomeFragment.kt`: make the switch and start button control the resident service and request notification permission on Android 13+.
- Modify `app/src/main/res/values/strings.xml` and `app/src/main/res/values-zh/strings.xml`: resident notification and switch labels.

### Task 1: Test infrastructure and default-enabled setting

- [ ] **Step 1: Add JVM Android test support**

Add to `android {}` in `app/build.gradle`:

```groovy
testOptions {
    unitTests.includeAndroidResources = true
}
```

Add test dependencies:

```groovy
testImplementation("androidx.test:core:1.5.0")
testImplementation("org.robolectric:robolectric:4.11.1")
```

- [ ] **Step 2: Write failing settings tests**

Create `SettingsHelperTest.kt` with Robolectric application setup. Assert a fresh preference store returns `autostart == true`, then set `autostart = false`, recreate `SettingsHelper`, and assert the explicit false remains false.

- [ ] **Step 3: Run the tests and verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests me.capcom.smsgateway.helpers.SettingsHelperTest
```

Expected: fresh-settings test fails because the current default is false.

- [ ] **Step 4: Implement the default**

Change only the fallback in `SettingsHelper.autostart`:

```kotlin
get() = settings.getBoolean(PREF_KEY_AUTOSTART, true)
```

- [ ] **Step 5: Run the tests and verify GREEN**

Run the same filtered Gradle command. Expected: both tests pass.

### Task 2: Idempotent business orchestration

- [ ] **Step 1: Write failing lifecycle-gate tests**

Create `IdempotentLifecycleTest.kt` asserting:

```kotlin
val lifecycle = IdempotentLifecycle()
var starts = 0
var stops = 0
lifecycle.start { starts++ }
lifecycle.start { starts++ }
lifecycle.stop { stops++ }
lifecycle.stop { stops++ }
assertEquals(1, starts)
assertEquals(1, stops)
```

Add a second test asserting a failed start resets the gate so a subsequent start can retry.

- [ ] **Step 2: Run the tests and verify RED**

Run the filtered test class. Expected: compilation fails because `IdempotentLifecycle` does not exist.

- [ ] **Step 3: Implement the lifecycle gate**

Create a synchronized internal class whose `start` transitions stopped to running, rolls back on exception, and whose `stop` transitions running to stopped. Execute callbacks once per transition.

- [ ] **Step 4: Integrate the gate and isolate module failures**

Use the gate in `OrchestratorService`. Wrap each module start/stop independently with a named helper that logs failures through `LogsService`, ensuring one failed module does not skip later modules. Remove the `autostart` parameter because callers enforce the setting before starting the resident service.

- [ ] **Step 5: Keep existing WorkManager periodic schedules**

Change `PullMessagesWorker` periodic enqueue policy from `ExistingPeriodicWorkPolicy.REPLACE` to `ExistingPeriodicWorkPolicy.KEEP`; keep immediate work behavior unchanged.

- [ ] **Step 6: Run lifecycle tests and the complete JVM suite**

Expected: lifecycle tests and existing project tests pass.

### Task 3: Sticky resident service and recovery entry points

- [ ] **Step 1: Write failing resident-service tests**

Using Robolectric with a plain `Application`, install a small Koin test module containing a fake resident runtime and `NotificationsService`. Assert:

- `onStartCommand()` returns `Service.START_STICKY`.
- two start commands call the runtime start once.
- stopping/destroying the service calls runtime stop once.
- the foreground notification uses `NOTIFICATION_ID_RESIDENT_SERVICE`.

- [ ] **Step 2: Run service tests and verify RED**

Expected: compilation fails because `ResidentForegroundService` and its notification ID do not exist.

- [ ] **Step 3: Implement resident runtime boundary and service**

Add a small `ResidentRuntime` interface implemented by `OrchestratorService`, then create `ResidentForegroundService` that:

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(
        NotificationsService.NOTIFICATION_ID_RESIDENT_SERVICE,
        notificationsService.makeNotification(
            this,
            NotificationsService.NOTIFICATION_ID_RESIDENT_SERVICE,
            getString(R.string.resident_service_is_active)
        )
    )
    runtime.start(applicationContext)
    return START_STICKY
}
```

Its companion uses `startForegroundService` on API 26+ and `startService` below API 26, plus `stopService` for explicit stop.

- [ ] **Step 4: Register notification and manifest entries**

Add resident notification ID `8`, mark its builder ongoing, add `POST_NOTIFICATIONS`, and declare the new non-exported `dataSync` foreground service.

- [ ] **Step 5: Write failing boot/upgrade tests**

Assert `BootReceiver` starts `ResidentForegroundService` for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` only when autostart is enabled. Assert it does nothing for unrelated actions or an explicitly disabled setting.

- [ ] **Step 6: Implement recovery routing**

Replace direct orchestrator startup in `BootReceiver` with resident-service startup. Add `MY_PACKAGE_REPLACED`, remove shutdown from the accepted event set, and route `App.onCreate()` through the resident service when autostart is enabled.

- [ ] **Step 7: Run resident and receiver tests**

Expected: all new Robolectric tests pass.

### Task 4: UI control, permissions, resources, and full verification

- [ ] **Step 1: Route UI controls through the resident service**

In `HomeFragment`, make enabling the autostart switch save true and start the resident service immediately; make disabling it save false and stop the service immediately. Route the existing Online/Offline toggle through the same start/stop helpers.

- [ ] **Step 2: Request Android 13 notification permission**

Add `Manifest.permission.POST_NOTIFICATIONS` to the runtime permission list when `Build.VERSION.SDK_INT >= 33`, while retaining existing phone/SMS permissions.

- [ ] **Step 3: Add localized resources**

Add:

```xml
<string name="resident_service_is_active">SMS base station is running</string>
```

and Chinese:

```xml
<string name="resident_service_is_active">短信基站运行中</string>
```

Update the switch label to describe both startup and continued operation.

- [ ] **Step 4: Run all unit tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: all JVM and Robolectric tests pass.

- [ ] **Step 5: Build the debug APK**

Run:

```powershell
.\gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL` and a debug APK under `app/build/outputs/apk/debug/`.

- [ ] **Step 6: Inspect the merged manifest and diff**

Verify the merged manifest contains the resident service, foreground-service type, notification permission, boot receiver, and `MY_PACKAGE_REPLACED`. Run `git diff --check` and review only scoped changes.

- [ ] **Step 7: Commit implementation**

Stage only the files listed in this plan and commit with:

```powershell
git commit -m "feat: keep SMS gateway resident"
```
