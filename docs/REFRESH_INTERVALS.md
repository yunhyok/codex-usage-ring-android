# Refresh intervals

Settings offers adaptive refresh (the default) and fixed 3, 5, 10, 15 or 30
minute intervals. Previously saved 15/30 minute choices are preserved; the
removed 60 minute choice becomes 30 minutes. An unset preference selects adaptive.

Adaptive refresh starts at 10 minutes. After a successful scheduled read, a
change in either usage percentage selects the next shorter interval:
`10 -> 5 -> 3 -> 1`. Unchanged readings select the next longer interval:
`1 -> 3 -> 5 -> 10`. Changes below one percentage point and decreases after a
quota reset count. Capture times and reset-time metadata alone do not count.
Missing first observations start at 10 minutes. Errors never shorten the interval;
one quick retry is retained before the next regular attempt is scheduled.

These are requested delays, not exact deadlines. Android periodic WorkManager
requests have a 15 minute minimum, so the app persists one delayed one-time
successor after each scheduled run. Android power saving, Doze, network constraints
and job quotas can delay execution. No exact alarm, permanent foreground service,
or battery exemption is requested. See [Android's work-request guidance](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

The old periodic job is retired when the app restores scheduling or a legacy
worker completes. Reopening the app keeps the pending due time. Changing the
setting replaces the existing chain; a cancelled old worker cannot reinsert its
previous interval. Manual refresh remains separate from the scheduled chain.

Validation covers the adaptive transitions, missing/error data, setting migration,
one real WorkManager successor per completed mock worker, repeated restoration,
and late completion after a setting change. Mock instrumentation changes usage
scenarios only in an isolated mock installation; it must not be used to replace
the authenticated native app on a user's phone. A test that skips the initial
delay checks scheduling behavior, not elapsed wall-clock delivery or Doze timing.
