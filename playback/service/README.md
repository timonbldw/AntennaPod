# :playback:service

The main service doing media playback.

`Media3PlaybackService` is the active implementation, built on AndroidX Media3/ExoPlayer.
`PlaybackService` is legacy and will throw an exception if started — it exists only during the transition period.

External callers should interact with the service through `PlaybackController`, which provides a
`bindToMedia3Service()` helper that connects a `MediaController` and runs a callback on it.
The `MediaController` exposes the standard Media3 `Player` interface: `seekTo(positionMs)`,
`play()`, `pause()`, `getCurrentPosition()`, `getPlaybackParameters()`, etc.
Each call to `bindToMedia3Service()` creates a short-lived connection that is released after the
callback returns.

## Audio recognition skip rules

Use `SkipManager` for per-feed rule persistence, sample extraction, waveform previews, and progressive local-audio analysis. `createRule` returns an unsaved draft. You can also construct a `SkipRule` with a UUID and call `saveRule` when editing is complete. `SkipRule.validate()` and `saveRule` reject missing marker samples, incompatible fingerprints, and invalid durations with `IllegalArgumentException`. Persistence errors use `IOException`. `getRulesOrThrow` exposes read errors; the compatibility `getRules` method returns an empty list when reading fails. Extracted samples contain spectral fingerprints and remain usable after the source episode is deleted.

`analyze` and `testRule` yield to the priority executor after each 30-second search window and publish partial snapshots. Decoding includes the longest rule sample on both sides of the window, plus frame alignment padding. Coverage describes searched marker beginnings, including full boundary-spanning template checks; it is not merely the decoded PCM range. Silence counts as analyzed audio. The decoder uses bounded PCM buffers, downmixes channels, resamples to 8 kHz, checks cancellation and timestamp continuity, and reports actual decoded coverage and EOF. Matching tolerates gain changes, resampling, and modest noise; it does not compensate for pitch or playback-speed changes.

Call `reprioritize(feedId, episodeId, positionMs, priority)` after playback position changes. It updates an active job's queued priority and next window without discarding hits or coverage. The search starts around the current position, continues through upcoming audio, and then covers earlier gaps. `analyze` also updates an existing active file request for the same URI and duration; content URIs start a new job. Completed results with no enabled rules are retained for either URI kind until rule invalidation, cancellation, or a different request. Playback owns the analysis task; display-only consumers use `observe` and close their subscription rather than canceling shared analysis. Canceling an old task cannot cancel its replacement. A task remains unfinished across window continuations.

New jobs store an empty `ANALYZING` snapshot before being queued; worker callbacks begin after `analyze` releases the manager lock. Startup does not invoke observers inline. `NOT_ANALYZED` means unrequested or invalidated analysis, never job startup. Rule saves and deletions cancel all affected jobs before publishing empty `NOT_ANALYZED` snapshots. Observers can restart analysis in response. Snapshot callbacks are serialized by the manager, but have no main-thread guarantee: initial observation and invalidation run on the calling thread, and analysis updates run on worker threads. Callbacks must return promptly and must not wait for other manager work. UI consumers must post to the main thread and discard callbacks for an obsolete episode or closed screen. Sample and waveform callbacks run on worker threads. Closing a subscription removes future notifications; cancellation stops subsequent job notifications, but a callback already executing can finish. Replacing a job inside its callback does not interrupt that callback thread.

File cache identities include absolute path, file size, modification time, rules revision, and episode duration. `content://` sources have per-job identities and bypass persistent cache reuse because providers do not consistently expose a reliable modification version. Cache format versions prevent reuse of incompatible fingerprints or coverage. Errors publish `ERROR` snapshots without actionable occurrences; malformed saved rules do not enter recognition.

`SkipMarkerHit.timeMs` is the recognized sample beginning in the analyzed episode. `markerOffsetMs` remains available in sample DTOs for editor compatibility but does not move skip boundaries. FIXED starts at the start sample beginning. BETWEEN ends at the end sample's end and applies minimum and maximum durations to that entire timeline interval. Alternate samples within 256 ms are one occurrence; a later distinct start closes an unmatched earlier start. Missing-end fallback waits for complete search coverage through the next start, maximum duration, region boundary, or episode end, rather than merely waiting for the fallback duration. FIXED fallback is capped by maximum duration and the next distinct start. Zero first/last regions means unrestricted search; otherwise their union restricts matching.

`SkipOccurrence.type` distinguishes `FINISH` from seek ranges. Playback consumers must treat FINISH as episode completion rather than a seek to the stored `endMs`. The four-argument occurrence constructor remains available and defaults to BETWEEN; resolver-produced occurrences always carry the actual rule type.
