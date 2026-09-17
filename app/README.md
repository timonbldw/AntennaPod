# :app

The main application module that integrates all features and hosts app-specific UI screens not large enough for their own module.
`PodcastApp` initializes the app; `ClientConfigurator` registers service implementations (download, sync) at startup.

The miniplayer (the collapsed player bar at the bottom of the screen) is implemented in `ExternalPlayerFragment`.
It is hosted in `MainActivity` as a bottom sheet. `MainActivity` controls its visibility via `setPlayerVisible()` based on playback state.

## Wear OS Communication (play flavor only)

`WearListenerService` is a `WearableListenerService` that handles `DataLayer` messages from connected watches.
It responds to watch-initiated requests.
The service is kept alive by the Android framework while at least one watch is connected.

## Audio skip sample editing

`SampleEditorView` edits downloaded episodes or cached audio from the active stream. Streaming selections are copied asynchronously into a `SkipAudioClip`; waveform, preview, and sample extraction use its local URI with clip-relative timestamps. Saved sample positions remain episode-relative. The view owns clip cleanup and discards callbacks for obsolete selections. Missing cached audio disables preview and saving until an available selection is loaded.
