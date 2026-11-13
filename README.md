# Responsly Android SDK

Show [Responsly](https://responsly.com) surveys inside your Android app: after a purchase, on a
screen, at launch, or whenever you ask for one.

- **Targeting happens on the device.** The SDK downloads your survey configuration and decides
  locally who sees what, and when. Nothing about your users is sent to pick a survey.
- **Native container.** Surveys appear in a bottom sheet, a dialog or full screen, above whatever
  activity is in front. You do not pass an activity, add a fragment or change your theme.
- **Small.** One dependency (`androidx.webkit`), one permission (`INTERNET`), no coroutines, no
  HTTP or JSON library.

Documentation: <https://developers.responsly.com>

## Requirements

- Android 6.0 (API 23) or newer, AndroidX
- An SDK key (`rsk_…`) from the **Mobile app** tab of a form's Share page in Responsly

## Install

The SDK is on Maven Central.

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.responsly:sdk:1.2.2")
}
```

```groovy
// build.gradle
dependencies {
    implementation 'com.responsly:sdk:1.2.2'
}
```

No ProGuard / R8 rules are needed; the library brings its own.

## Quick start

### Kotlin

```kotlin
import com.responsly.sdk.*

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Responsly.initialize(this, "rsk_…")
    }
}
```

```kotlin
// Tell the SDK who the user is. Traits are used for targeting and passed to the survey.
Responsly.identify("user-123", mapOf("plan" to "pro", "seats" to 5, "signed_up" to Date()))

// Report what happens. A survey with a matching trigger is shown if the user is eligible.
Responsly.track("purchase_completed", mapOf("value" to 49.9))
Responsly.screen("Checkout")

// Or show a survey yourself.
Responsly.showSurvey("AbCd1234")

// Listen to what happens. Implement only the callbacks you need.
Responsly.addListener(object : ResponslyListener {
    override fun onSurveyCompleted(event: SurveyCompletedEvent) {
        Log.d("App", "Survey ${event.surveyId} completed: ${event.answers}")
    }

    override fun onSurveyNotShown(event: SurveyNotShownEvent) {
        Log.d("App", "Survey ${event.surveyId} not shown: ${event.reason.value}")
    }
})

// On logout.
Responsly.reset()
```

### Java

```java
import com.responsly.sdk.*;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Responsly.initialize(this, "rsk_…");
    }
}
```

```java
Map<String, Object> traits = new HashMap<>();
traits.put("plan", "pro");
traits.put("seats", 5);
Responsly.identify("user-123", traits);

Responsly.track("purchase_completed", Collections.singletonMap("value", 49.9));
Responsly.screen("Checkout");
Responsly.showSurvey("AbCd1234");

Responsly.addListener(new ResponslyListener() {
    @Override
    public void onSurveyCompleted(SurveyCompletedEvent event) {
        Log.d("App", "Survey " + event.getSurveyId() + " completed");
    }
});

Responsly.setDisplayGuard((survey, decision) -> {
    if (checkoutInProgress) decision.veto(); else decision.allow();
});
```

## API

Everything lives on the `Responsly` object. Every call is safe from any thread and before
`initialize` (calls made earlier are remembered and applied when it runs; `showSurvey` before
`initialize` reports `not_initialized`).

| Call | What it does |
|---|---|
| `initialize(context, sdkKey, options)` | Starts the SDK. `ResponslyOptions(apiHost, logLevel)`. Calling it again with the same key and API host does nothing; a different key resets the stored state first; the same key with another `apiHost` switches to that host and downloads the configuration again. |
| `identify(userId, traits)` | Sets the user id and merges traits. |
| `setAttributes(traits)` | Merges traits; a `null` value removes a trait. |
| `track(event, properties)` | Counts the event and evaluates event triggers. |
| `screen(name)` | Sets the current screen and evaluates screen triggers. |
| `showSurvey(surveyId, options)` | Shows a survey by its Survey ID from the form's **Share → Mobile app** tab (a form with several configurations has one for each). `ShowOptions(force, hiddenFields, locale, presentation)`. |
| `closeSurvey()` | Closes the visible survey (a dismissal unless it was completed). One that is still waiting or loading is cancelled silently. |
| `pause()` / `resume()` | Stops and resumes targeting. `showSurvey` still works while paused. |
| `setLocale(languageTag)` | Overrides the locale used for targeting and the survey language; `null` returns to the device language. |
| `setHiddenFields(fields)` | Hidden-field values passed to every survey. Replaces the previous map. |
| `setTrackingEnabled(enabled)` | `false` turns the SDK off: no network, no targeting, nothing shown. Persisted. |
| `reset()` | Forgets the user and all local state except the install id and the tracking switch. Call it on logout. |
| `setDisplayGuard(guard)` | Lets you veto a survey right before it becomes visible. |
| `addListener(listener)` / `removeListener(listener)` | Survey lifecycle callbacks. |
| `Responsly.SDK_VERSION` | The SDK version. |

Trait and property values can be `String`, any `Number`, `Boolean` or `java.util.Date` (stored as
an ISO 8601 UTC string). Anything else is stored as its `toString()`.

### Listener callbacks

Delivered on the main thread. Every event has `toMap()` with snake_case keys, ready to be passed
on as JSON.

| Callback | Event |
|---|---|
| `onSurveyShown` | `survey` (`id`, `name`), `trigger` (`type`: `event`, `screen`, `app_launch` or `manual`; `name`) |
| `onSurveyNotShown` | `surveyId`, `reason` (a `NotShownReason`, e.g. `FREQUENCY_LIMIT`; `reason.value` is the protocol string) |
| `onQuestionAnswered` | `surveyId`, `questionId`, `type`, `title`, `value` |
| `onSurveyCompleted` | `surveyId`, `responseId`, `answers` (`questionId`, `type`, `title`, `value`) |
| `onSurveyDismissed` | `surveyId` |
| `onSurveyError` | `surveyId`, `code`, `message` |

An answer `value` is whatever the survey stores for that question: a `String`, a `Long` or
`Double`, a `Boolean`, a `List`, a `Map` or `null`.

### Display guard

```kotlin
Responsly.setDisplayGuard { survey, decision ->
    if (videoIsPlaying) decision.veto() else decision.allow()
}
```

The guard is asked on the main thread when a survey is ready to appear. `allow()` and `veto()` may
be called later and from any thread; no answer within 2 seconds counts as allow. It is not asked
for `showSurvey(id, ShowOptions(force = true))`.

### Debugging

```kotlin
Responsly.initialize(this, "rsk_…", ResponslyOptions(logLevel = LogLevel.DEBUG))
```

With `LogLevel.DEBUG` the SDK logs every targeting decision with its reason, every configuration
download and every message from the survey to Logcat, tag `Responsly`.

## How surveys appear

- Style (bottom sheet, dialog, full screen), size and whether the survey can be dismissed are set
  per survey in Responsly; `ShowOptions.presentation` overrides the style for one call.
- A dismissible survey has a close button and also closes on a tap outside and on system back. To
  translate the button's accessibility label, define a string resource named `responsly_close`.
- The container stays above the keyboard, follows the survey's background colour and honours the
  system's "remove animations" setting.
- File and photo questions open the system document picker. The SDK declares one small transparent
  activity for that; no storage or camera permission is involved.
- To know which activity is in front, the SDK registers activity lifecycle callbacks when the
  process starts, through a small provider in its manifest. That is all the provider does: no disk
  access and no network happen before you call `initialize`.
- If the activity under a visible survey is destroyed (for example on rotation without
  `configChanges`), the survey is closed and counted as dismissed.

## Privacy

- No advertising id, no device fingerprint, no location, no third-party SDKs.
- The only permission is `INTERNET`.
- The SDK talks to the Responsly API for two things: downloading the survey configuration and
  sending anonymous counters (`displayed`, `dismissed`, `completed` with the survey id). The
  install id never leaves the device.
- The user id, traits and hidden fields are handed to the survey only when it is shown.
- Where the system WebView supports it, surveys keep their cookies and storage in a profile of
  their own, separate from your app's web content.
- `Responsly.setTrackingEnabled(false)` switches everything off; `Responsly.reset()` forgets the user.

## Example app

`example/` is a small control panel: enter an SDK key and API host, press the buttons, watch the
listener callbacks.

```bash
./gradlew :example:installDebug
```

## Development

```bash
./gradlew :sdk:testDebugUnitTest      # unit tests, including the protocol's targeting fixtures
./gradlew :sdk:lintRelease            # Android lint
./gradlew :sdk:assembleRelease        # sdk/build/outputs/aar/sdk-release.aar
./gradlew :sdk:publishToMavenLocal    # com.responsly:sdk:1.2.2 in ~/.m2
```

JDK 17 and the Android SDK (platform 36) are required. The behaviour the SDK implements is
specified in [`docs/PROTOCOL.md`](docs/PROTOCOL.md); `sdk/src/test/resources/targeting-fixtures.json`
holds the conformance cases shared by all Responsly SDKs and must not be edited here.

## License

[MIT](LICENSE)
