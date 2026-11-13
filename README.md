# karoo-intervals-data

A Hammerhead Karoo extension that shows your [Intervals.icu](https://intervals.icu) fitness and week totals as Karoo data fields, updated live as the ride progresses.

Built with Hammerhead's [karoo-ext](https://github.com/hammerheadnav/karoo-ext) library. Targets the Karoo 3 (the 2024 "new Karoo") and later.

## Data fields

All fields appear in the **Intervals Data** group in the Karoo page editor.

| Field | What it shows |
|---|---|
| Fitness now / Fatigue now | Intervals fitness (CTL) and fatigue (ATL) right now: yesterday's closing value plus everything done today, including this ride |
| Form now % | (fitness − fatigue) ÷ fitness, as Intervals shows it |
| CIL now | Chronic intensity load right now |
| Fitness / Fatigue / Form / CIL end of week | The same, projected to the last day of the week: this ride in place of today's plan, then the rest of the week's planned workouts |
| Week time, Week load | Done so far: completed activities + this ride |
| Week time %, Week load % | Done so far as a percentage of the week's planned total (rounded down, as Intervals does; can pass 100%) |
| Week time projected, Week load projected | Done so far + planned workouts still to come (the totals the Intervals week card shows) |
| Week distance | Completed + this ride, in km or mi following the Karoo's unit setting |
| Workout target load | Planned load of the workout running on the Karoo, from today's plan in Intervals; N/A when no workout is running or nothing is planned today |
| Week summary | Graphical field modelled on the Intervals week card: fitness/fatigue/form/CIL (now and end of week) and time and load bars. Dark fill = done so far, light fill = projected, both against the week's plan. Small slots show only the bars |

## How the values are calculated

Before the ride (and when the extension starts) it fetches from the Intervals API:

1. **Wellness** for the last 14 days: yesterday's closing fitness and fatigue.
2. **Activities** for the last 180 days (trimmed to 10 fields): this week's completed sessions, and the intensity history for CIL.
3. **Planned workouts** for the week.

During the ride, the Karoo's ride time, distance and normalised power are combined with that baseline:

- **Ride load** = IF² × hours × 100, where IF = normalised power ÷ the FTP Intervals used for your latest ride (or the override in settings). Without a power meter, load accrues at the rate of today's planned workout; with no plan either, the ride adds time and distance only.
- **Fitness, fatigue, CIL** use the exponential averages Intervals uses (42, 7 and 42 days, decay e^(−1/days)). Rest days count as zero intensity for CIL.
- **Week totals** count only what's been done. The projected totals add planned workouts still to come, counting a planned workout through its paired activity, never twice. While a ride is live it replaces today's open planned workouts (setting).
- **Targets** are the sum of every planned workout in the week, paired or not. The percentage is done ÷ target, so skipping a session never raises it.

The projected totals and end-of-week values reproduce the Intervals week card and fitness chart for a real week; see `core/src/test/.../ProjectorTest.kt`.

After the ride, the live values are kept until the activity appears in Intervals (checked every 30 minutes), then replaced by Intervals' own figures.

### Assumptions still to confirm against real rides

- **Workout target load**: Karoo reports that a workout is running and its time remaining, but not which workout. The field uses today's planned workout that hasn't been done; with several, the one whose duration is closest to the Karoo workout's length. A workout loaded from somewhere other than Intervals still shows today's planned load.

- **CIL on days with two or more sessions**: Intervals doesn't document how it combines them; this uses a duration-weighted average (`Intensity.dailyCombined`).
- **Planned workouts on past days that were never ridden** are left out of the week totals.
- **Live load vs Intervals load**: Intervals may use its own normalised power, or heart-rate load for some rides, so expect small differences that disappear once the ride uploads.
- **Weekly target events**: if you add Intervals "target" calendar entries, the week card may prefer those over the planned total; this version uses the planned total.

## Project layout

```
core/   Pure Kotlin: the calculations and their unit tests. No Android dependency.
app/    Android app: settings screen, Intervals API client, Karoo extension and data fields.
docs/   Development setup.
```

## Building

See [docs/DEV_SETUP_WINDOWS.md](docs/DEV_SETUP_WINDOWS.md) for the full Windows 11 setup, including the emulator.

In short: karoo-ext is on GitHub Packages, so add a classic GitHub token with `read:packages` to `~/.gradle/gradle.properties`:

```properties
gpr.user=<github username>
gpr.key=<token>
```

then:

```
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease
```

The Build workflow tests and builds every push to `main`. To publish a release, bump `versionCode`/`versionName` in `app/build.gradle.kts` and push a matching tag (`git tag v0.1.5 && git push origin v0.1.5`) or create a release with that tag on github.com; the Release workflow builds the APK and attaches it to the GitHub Release.

## Setting up on the Karoo

1. Install the APK. The easiest way: on your phone, open the APK in [`apk/`](apk/) (or the latest [release](https://github.com/hdbonnett/karoo-intervals-data/releases/latest), once there is one), tap **Raw** / the download link, and share it to the Hammerhead Companion app, then tap **Install** on the Karoo. (Or use adb.)
2. Open **Intervals Data** on the Karoo and enter your Intervals athlete id and API key (Intervals › Settings › Developer). To avoid typing the key on the Karoo, with adb connected:

   ```
   adb shell am start -n com.infoprime.karoointervals/.ui.MainActivity --es athlete_id i12345 --es api_key YOUR_KEY
   ```
3. Tap **Save and refresh** while the Karoo has Wi-Fi or a Companion app connection.
4. Add the fields to a ride page.

## Privacy

The API key is stored in the app's private storage on the Karoo and is only sent to intervals.icu. App backups are disabled.
