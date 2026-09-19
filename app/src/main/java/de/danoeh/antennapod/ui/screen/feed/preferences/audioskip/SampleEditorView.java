package de.danoeh.antennapod.ui.screen.feed.preferences.audioskip;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.util.Consumer;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.playback.service.skip.SkipAudioClip;
import de.danoeh.antennapod.playback.service.skip.SkipManager;
import de.danoeh.antennapod.playback.service.skip.SkipMarker;
import de.danoeh.antennapod.playback.service.skip.SkipPreviewPlayer;
import de.danoeh.antennapod.playback.service.skip.SkipRule;
import de.danoeh.antennapod.playback.service.skip.SkipSample;
import de.danoeh.antennapod.playback.service.skip.SkipSampleCallback;
import de.danoeh.antennapod.playback.service.skip.SkipTask;
import de.danoeh.antennapod.playback.service.skip.SkipStreamingSource;
import de.danoeh.antennapod.playback.service.skip.SkipWaveform;
import de.danoeh.antennapod.playback.service.skip.SkipWaveformCallback;
import de.danoeh.antennapod.storage.database.DBReader;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class SampleEditorView extends LinearLayout {
    public static final String EXTRA_MARKER = "marker";
    private static final long CLIP_LOAD_DEBOUNCE_MS = 400;
    private static final long MAX_WAVEFORM_WINDOW_MS = 90_000;

    private final long feedId;
    private final SkipRule rule;
    private final SkipMarker marker;
    private final int replaceIndex;
    private final Consumer<SkipSampleResult> onSaved;
    private final Runnable onCancel;
    private final long preferredEpisodeId;
    private final String preferredEpisodeUri;
    private final long preferredPosition;
    private final long preferredDuration;
    private final SkipManager manager;
    private final WaveformView waveformView;
    private final Spinner episodeSpinner;
    private final SeekBar windowPosition;
    private final TextView status;
    private final EditText startLabel;
    private final EditText endLabel;
    private final MaterialButton loop;
    private final MaterialButton play;
    private final Button save;
    private final List<EpisodeInfo> episodes = new ArrayList<>();
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private final Handler clipHandler = new Handler(Looper.getMainLooper());
    private final Runnable debouncedWaveformLoad = this::loadWaveform;
    private SkipTask waveformTask;
    private SkipTask sampleTask;
    private Player mediaPlayer;
    private SkipAudioClip clip;
    private Disposable clipTask;
    private EpisodeInfo episode;
    private long episodeDuration;
    private long windowStart;
    private long windowLength;
    private long selectionStart;
    private long selectionEnd;
    private long playbackCursor;
    private boolean destroyed;
    private boolean waveformLoading;
    private boolean updatingInputs;
    private boolean pendingInput;
    private boolean pendingStartEdit;
    private boolean playerPrepared;
    private boolean keyboardVisible;
    private int fineStep = 100;
    private int clipGeneration;
    private int sampleGeneration;

    public SampleEditorView(Context context, long feedId, SkipRule rule, SkipMarker marker, int replaceIndex,
                             long preferredEpisodeId, String preferredEpisodeUri, long preferredPosition,
                             long preferredDuration,
                             Consumer<SkipSampleResult> onSaved, Runnable onCancel) {
        super(context);
        this.feedId = feedId;
        this.rule = rule;
        this.marker = marker;
        this.replaceIndex = replaceIndex;
        this.onSaved = onSaved;
        this.onCancel = onCancel;
        this.preferredEpisodeId = preferredEpisodeId;
        this.preferredEpisodeUri = preferredEpisodeUri;
        this.preferredPosition = preferredPosition;
        this.preferredDuration = preferredDuration;
        manager = SkipManager.getInstance(context);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(16), dp(16), dp(24));
        LayoutInflater.from(context).inflate(R.layout.audio_skip_sample_editor, this, true);

        episodeSpinner = findViewById(R.id.audioSkipSampleEpisode);
        status = findViewById(R.id.audioSkipSampleStatus);
        waveformView = new WaveformView(context);
        FrameLayout waveformContainer = findViewById(R.id.audioSkipWaveformContainer);
        waveformContainer.addView(waveformView, new FrameLayout.LayoutParams(-1, -1));
        windowPosition = findViewById(R.id.audioSkipWindowPosition);
        windowPosition.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    if (commitPendingInput()) {
                        seekCursor(progress, true);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        startLabel = findViewById(R.id.audioSkipSampleStart);
        endLabel = findViewById(R.id.audioSkipSampleEnd);
        addFineButton(findViewById(R.id.audioSkipStartBackAction), R.string.audio_skip_start_back, -1, true);
        addFineButton(findViewById(R.id.audioSkipStartForwardAction), R.string.audio_skip_start_forward, 1, true);
        addFineButton(findViewById(R.id.audioSkipEndBackAction), R.string.audio_skip_end_back, -1, false);
        addFineButton(findViewById(R.id.audioSkipEndForwardAction), R.string.audio_skip_end_forward, 1, false);
        startLabel.addTextChangedListener(new SelectionTextWatcher(true));
        endLabel.addTextChangedListener(new SelectionTextWatcher(false));
        setInputCommitListeners(startLabel);
        setInputCommitListeners(endLabel);
        ViewCompat.setOnApplyWindowInsetsListener(this, (view, insets) -> {
            boolean visible = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (keyboardVisible && !visible) {
                commitPendingInput();
            }
            keyboardVisible = visible;
            return insets;
        });
        addZoomButton(findViewById(R.id.audioSkipZoomOutAction), R.string.audio_skip_zoom_out, false);
        addZoomButton(findViewById(R.id.audioSkipZoomInAction), R.string.audio_skip_zoom_in, true);
        loop = findViewById(R.id.audioSkipLoopSample);
        loop.setText(null);
        loop.setIconResource(R.drawable.ic_audio_skip_loop);
        loop.setIconPadding(0);
        loop.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);
        loop.setPadding(0, 0, 0, 0);
        loop.setBackgroundTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{MaterialColors.getColor(loop, R.attr.colorPrimary),
                        MaterialColors.getColor(loop, R.attr.colorSecondaryContainer)}));
        loop.setIconTint(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{MaterialColors.getColor(loop, R.attr.colorOnPrimary),
                        MaterialColors.getColor(loop, R.attr.action_icon_color)}));
        play = findViewById(R.id.audioSkipPlaySample);
        play.setText(null);
        play.setIconResource(R.drawable.ic_play_24dp);
        play.setIconPadding(0);
        play.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);
        play.setPadding(0, 0, 0, 0);
        play.setOnClickListener(view -> playPreview());
        MaterialButton stop = findViewById(R.id.audioSkipStopPreview);
        stop.setText(null);
        stop.setIconResource(R.drawable.ic_stop);
        stop.setIconPadding(0);
        stop.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);
        stop.setPadding(0, 0, 0, 0);
        stop.setOnClickListener(view -> stopPreview());
        findViewById(R.id.audioSkipCursorBack).setOnClickListener(view -> seekCursorBy(-10_000));
        findViewById(R.id.audioSkipCursorForward).setOnClickListener(view -> seekCursorBy(10_000));
        findViewById(R.id.audioSkipCenterCursor).setOnClickListener(view -> centerCursor());
        findViewById(R.id.audioSkipSetStart).setOnClickListener(view -> setStartFromCursor());
        Button cancel = findViewById(R.id.audioSkipCancelSample);
        cancel.setOnClickListener(view -> onCancel.run());
        save = findViewById(R.id.audioSkipSaveSample);
        save.setOnClickListener(view -> saveSample());
        loadEpisodes();
    }

    @Override
    protected void onDetachedFromWindow() {
        destroyed = true;
        if (waveformTask != null) {
            waveformTask.cancel();
        }
        cancelSampleExtraction();
        stopPreview();
        clipGeneration++;
        if (clipTask != null) {
            clipTask.dispose();
        }
        closeClip();
        previewHandler.removeCallbacksAndMessages(null);
        clipHandler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    private void loadEpisodes() {
        Single.fromCallable(() -> {
                    Feed feed = DBReader.getFeed(feedId, false, 0, 0);
                    if (feed == null) {
                        return new ArrayList<EpisodeInfo>();
                    }
                    List<FeedItem> items = DBReader.getFeedItemList(feed,
                                    new FeedItemFilter(FeedItemFilter.DOWNLOADED,
                                    FeedItemFilter.INCLUDE_ALL_FEED_STATES), SortOrder.DATE_NEW_OLD, 0, -1);
                    List<EpisodeInfo> result = new ArrayList<>();
                    for (FeedItem item : items) {
                        FeedMedia media = item.getMedia();
                        if (media == null || media.getLocalFileUrl() == null) {
                            continue;
                        }
                        Uri uri = media.getLocalFileUrl().startsWith("content://")
                                ? Uri.parse(media.getLocalFileUrl())
                                : Uri.fromFile(new File(media.getLocalFileUrl()));
                        long duration = media.getDuration();
                        if (duration < 500) {
                            continue;
                        }
                        result.add(new EpisodeInfo(item.getId(), item.getTitle(), uri, duration));
                    }
                    if (preferredEpisodeUri != null) {
                        Uri preferredUri = Uri.parse(preferredEpisodeUri);
                        if (SkipStreamingSource.isStreaming(preferredUri) && preferredDuration >= 500) {
                            boolean included = false;
                            for (EpisodeInfo value : result) {
                                included |= value.id == preferredEpisodeId;
                            }
                            if (!included) {
                                FeedItem item = preferredEpisodeId < 0 ? null
                                        : DBReader.getFeedItem(preferredEpisodeId);
                                result.add(0, new EpisodeInfo(preferredEpisodeId,
                                        item == null ? null : item.getTitle(), preferredUri, preferredDuration));
                            }
                        }
                    }
                    return result;
                })
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (destroyed) {
                        return;
                    }
                    episodes.clear();
                    episodes.addAll(result);
                    List<String> labels = new ArrayList<>();
                    for (EpisodeInfo value : episodes) {
                        labels.add(value.title == null ? value.uri.toString() : value.title);
                    }
                    if (labels.isEmpty()) {
                        setStatus(R.string.audio_skip_no_downloaded_episodes);
                        return;
                    }
                    int selected = -1;
                    for (int index = 0; index < episodes.size(); index++) {
                        EpisodeInfo value = episodes.get(index);
                        if (isPreferredEpisode(value)) {
                            selected = index;
                            break;
                        }
                    }
                    episodeSpinner.setAdapter(new ArrayAdapter<>(getContext(),
                            android.R.layout.simple_spinner_dropdown_item, labels));
                    if (selected < 0 && (preferredEpisodeId >= 0 || preferredEpisodeUri != null)) {
                        episodeSpinner.setEnabled(false);
                        setStatus(R.string.audio_skip_download_required);
                        return;
                    }
                    if (selected < 0) {
                        selected = 0;
                    }
                    episodeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                        @Override
                        public void onItemSelected(AdapterView<?> parent, View view,
                                                    int position, long id) {
                            selectEpisode(episodes.get(position));
                        }

                        @Override
                        public void onNothingSelected(AdapterView<?> parent) {
                        }
                    });
                    episodeSpinner.setSelection(selected);
                    selectEpisode(episodes.get(selected));
                }, error -> {
                    if (!destroyed) {
                        setStatus(error.getLocalizedMessage());
                    }
                });
    }

    private void selectEpisode(EpisodeInfo value) {
        stopPreview();
        cancelClipLoad();
        closeClip();
        episode = value;
        episodeDuration = Math.max(1, value.durationMs);
        windowLength = Math.min(30_000, episodeDuration);
        if (isPreferredEpisode(value) && preferredPosition >= 0) {
            long position = Math.min(preferredPosition, episodeDuration);
            windowStart = Math.max(0, Math.min(position - 20_000, episodeDuration - windowLength));
            selectionStart = Math.max(windowStart, position - 5_000);
            selectionEnd = Math.min(episodeDuration, Math.max(selectionStart + 500, position));
            playbackCursor = position;
        } else {
            windowStart = 0;
            selectionStart = windowStart;
            selectionEnd = Math.min(episodeDuration, selectionStart + Math.min(5_000, windowLength));
            playbackCursor = selectionStart;
        }
        windowPosition.setMax((int) episodeDuration);
        updateCursorProgress();
        waveformView.setRange(windowStart, windowStart + windowLength, selectionStart, selectionEnd);
        setSelectionInputs();
        scheduleWaveformLoad();
    }

    private void updateCursorProgress() {
        windowPosition.setProgress((int) playbackCursor);
    }

    private void loadWaveform() {
        if (episode == null || destroyed) {
            return;
        }
        int generation = ++clipGeneration;
        if (waveformTask != null) {
            waveformTask.cancel();
        }
        cancelSampleExtraction();
        waveformView.setWaveform(null);
        waveformLoading = true;
        setStatus(R.string.audio_skip_waveform_loading);
        if (SkipStreamingSource.isStreaming(episode.uri)) {
            if (clip != null && windowStart >= clip.startMs
                    && windowStart + windowLength <= clip.endMs && isSelectionAvailable()) {
                extractWaveform(clip.uri, SkipPreviewPlayer.toSourcePosition(windowStart, clip.startMs),
                        SkipPreviewPlayer.toSourcePosition(windowStart + windowLength, clip.startMs));
            } else {
                loadStreamingClip(generation);
            }
            return;
        }
        play.setEnabled(true);
        save.setEnabled(true);
        extractWaveform(episode.uri, windowStart, Math.min(episodeDuration, windowStart + windowLength));
    }

    private void scheduleWaveformLoad() {
        if (episode == null || destroyed) {
            return;
        }
        if (!SkipStreamingSource.isStreaming(episode.uri)) {
            loadWaveform();
            return;
        }
        cancelClipLoad();
        clipHandler.postDelayed(debouncedWaveformLoad, CLIP_LOAD_DEBOUNCE_MS);
    }

    private void cancelClipLoad() {
        clipHandler.removeCallbacks(debouncedWaveformLoad);
        clipGeneration++;
        if (clipTask != null) {
            clipTask.dispose();
            clipTask = null;
        }
        if (waveformTask != null) {
            waveformTask.cancel();
            waveformTask = null;
        }
        cancelSampleExtraction();
        if (!isSelectionAvailable()) {
            closeClip();
        }
        waveformLoading = false;
        waveformView.setWaveform(null);
        setStatus("");
        updateStreamControls();
    }

    private void loadStreamingClip(int generation) {
        if (clipTask != null) {
            clipTask.dispose();
        }
        updateStreamControls();
        Uri sourceUri = episode.uri;
        long start = windowStart;
        long end = Math.min(episodeDuration, windowStart + windowLength);
        long selectedStart = selectionStart;
        long selectedEnd = selectionEnd;
        Object clipLock = new Object();
        SkipAudioClip[] createdClip = {null};
        boolean[] clipDisposed = {false};
        boolean[] clipAdopted = {false};
        clipTask = Single.fromCallable(() -> {
                    SkipAudioClip value;
                    try {
                        value = SkipAudioClip.create(getContext(), sourceUri, start, end);
                    } catch (IOException error) {
                        try {
                            value = SkipAudioClip.create(getContext(), sourceUri, start, end, true);
                        } catch (IOException windowError) {
                            post(() -> {
                                if (!destroyed && generation == clipGeneration) {
                                    setStatus(R.string.audio_skip_audio_loading);
                                }
                            });
                            value = SkipAudioClip.create(getContext(), sourceUri,
                                    selectedStart, selectedEnd, true);
                        }
                    }
                    synchronized (clipLock) {
                        if (clipDisposed[0]) {
                            value.close();
                            throw new InterruptedException();
                        } else {
                            createdClip[0] = value;
                        }
                    }
                    return value;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> {
                    synchronized (clipLock) {
                        clipDisposed[0] = true;
                        if (!clipAdopted[0] && createdClip[0] != null) {
                            createdClip[0].close();
                            createdClip[0] = null;
                        }
                    }
                })
                .subscribe(value -> {
                    synchronized (clipLock) {
                        if (clipDisposed[0] || createdClip[0] != value) {
                            value.close();
                            return;
                        }
                        clipAdopted[0] = true;
                        createdClip[0] = null;
                    }
                    if (destroyed || generation != clipGeneration || episode == null
                            || !sourceUri.equals(episode.uri)) {
                        value.close();
                        return;
                    }
                    cancelSampleExtraction();
                    closeClip();
                    clip = value;
                    windowStart = value.startMs;
                    windowLength = value.endMs - value.startMs;
                    waveformView.setRange(windowStart, windowStart + windowLength, selectionStart, selectionEnd);
                    updateStreamControls();
                    if (!play.isEnabled()) {
                        setStatus(R.string.audio_skip_stream_sample_unavailable);
                    }
                    extractWaveform(value.uri, 0, value.endMs - value.startMs);
                }, error -> {
                    if (!destroyed && generation == clipGeneration) {
                        Log.w("SampleEditorView", "Unable to capture streaming sample "
                                + selectedStart + "-" + selectedEnd + " ms", error);
                        waveformLoading = false;
                        waveformView.setWaveform(null);
                        updateStreamControls();
                        if (isSelectionAvailable()) {
                            windowStart = clip.startMs;
                            windowLength = clip.endMs - clip.startMs;
                            waveformView.setRange(windowStart, windowStart + windowLength,
                                    selectionStart, selectionEnd);
                            extractWaveform(clip.uri, 0, windowLength);
                        } else {
                            setStatus(R.string.audio_skip_stream_sample_download_required);
                        }
                    }
                });
    }

    private void extractWaveform(Uri uri, long startMs, long endMs) {
        final int generation = clipGeneration;
        waveformTask = manager.extractWaveform(uri, startMs, endMs, 300,
                new SkipWaveformCallback() {
                    @Override
                    public void onSuccess(SkipWaveform waveform) {
                        post(() -> {
                            if (destroyed || generation != clipGeneration) {
                                return;
                            }
                            waveformLoading = false;
                            waveformView.setWaveform(waveform);
                            if (episode != null && SkipStreamingSource.isStreaming(episode.uri)
                                    && !isSelectionAvailable()) {
                                setStatus(R.string.audio_skip_stream_sample_unavailable);
                            } else {
                                setStatus("");
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable error) {
                        post(() -> {
                            if (!destroyed && generation == clipGeneration) {
                                waveformLoading = false;
                                setStatus(R.string.audio_skip_waveform_unavailable);
                            }
                        });
                    }
                });
    }

    private void closeClip() {
        if (clip != null) {
            clip.close();
            clip = null;
        }
    }

    private boolean isPreferredEpisode(EpisodeInfo value) {
        return value.id == preferredEpisodeId
                || (preferredEpisodeUri != null
                && (preferredEpisodeUri.equals(value.uri.toString())
                || value.uri.equals(Uri.fromFile(new File(preferredEpisodeUri)))));
    }

    private void addFineButton(LinearLayout parent, int title, int direction, boolean start) {
        MaterialButton value = (MaterialButton) LayoutInflater.from(getContext()).inflate(
                R.layout.audio_skip_text_button, parent, false);
        value.setText(null);
        value.setIconResource(direction < 0 ? R.drawable.ic_fast_rewind : R.drawable.ic_fast_forward);
        value.setIconPadding(0);
        value.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);
        value.setPadding(0, 0, 0, 0);
        value.setMinWidth(0);
        value.setMinimumWidth(0);
        value.setContentDescription(getContext().getString(title));
        value.setOnClickListener(view -> {
            if (!commitPendingInput()) {
                return;
            }
            long delta = direction * fineStep;
            if (start) {
                selectionStart = Math.max(Math.max(0, selectionEnd - 30_000),
                        Math.min(selectionEnd - 500, selectionStart + delta));
            } else {
                selectionEnd = Math.max(selectionStart + 500,
                        Math.min(Math.min(episodeDuration, selectionStart + 30_000), selectionEnd + delta));
            }
            cancelSampleExtraction();
            if (mediaPlayer != null) {
                stopPreview();
            }
            setSelectionInputs();
            updateWindowForSelection();
        });
        parent.addView(value, new LinearLayout.LayoutParams(dp(40), dp(48)));
    }

    private void addZoomButton(LinearLayout parent, int title, boolean in) {
        MaterialButton value = (MaterialButton) LayoutInflater.from(getContext()).inflate(
                R.layout.audio_skip_text_button, parent, false);
        value.setText(title);
        value.setContentDescription(getContext().getString(title));
        value.setOnClickListener(view -> {
            if (!commitPendingInput()) {
                return;
            }
            long selectionLength = selectionEnd - selectionStart;
            long center = selectionStart + selectionLength / 2;
            windowLength = Math.max(selectionLength,
                    Math.max(1_000, Math.min(MAX_WAVEFORM_WINDOW_MS,
                            in ? windowLength / 2 : windowLength * 2)));
            windowLength = Math.min(windowLength, episodeDuration);
            windowStart = Math.max(0, Math.min(center - windowLength / 2,
                    episodeDuration - windowLength));
            windowPosition.setMax((int) episodeDuration);
            updateSelection();
            scheduleWaveformLoad();
        });
        parent.addView(value, new LinearLayout.LayoutParams(-2, -2));
    }

    private void updateSelection() {
        waveformView.setRange(windowStart, windowStart + windowLength, selectionStart, selectionEnd);
        setSelectionInputs();
        updateStreamControls();
        if (episode != null && SkipStreamingSource.isStreaming(episode.uri)
                && !isSelectionAvailable()) {
            scheduleWaveformLoad();
        }
    }

    private void setSelectionInputs() {
        pendingInput = false;
        updatingInputs = true;
        startLabel.setText(AudioSkipRulesActivity.formatTime(selectionStart));
        endLabel.setText(AudioSkipRulesActivity.formatTime(selectionEnd));
        updatingInputs = false;
    }

    private void updateWindowForSelection() {
        boolean reloadWaveform = selectionStart < windowStart || selectionEnd > windowStart + windowLength;
        if (!waveformLoading) {
            setStatus("");
        }
        if (reloadWaveform) {
            windowLength = Math.max(windowLength, selectionEnd - selectionStart);
            windowLength = Math.min(MAX_WAVEFORM_WINDOW_MS, Math.min(windowLength, episodeDuration));
            windowStart = Math.max(0, Math.min(selectionStart - (windowLength - selectionEnd + selectionStart) / 2,
                    episodeDuration - windowLength));
            windowPosition.setMax((int) episodeDuration);
        }
        waveformView.setRange(windowStart, windowStart + windowLength, selectionStart, selectionEnd);
        updateStreamControls();
        if (reloadWaveform) {
            scheduleWaveformLoad();
        } else if (episode != null && SkipStreamingSource.isStreaming(episode.uri)
                && !isSelectionAvailable()) {
            scheduleWaveformLoad();
        }
    }

    private void updateStreamControls() {
        if (episode == null || !SkipStreamingSource.isStreaming(episode.uri)) {
            return;
        }
        boolean available = isSelectionAvailable();
        play.setEnabled(SkipStreamingSource.isAvailable(episode.uri));
        save.setEnabled(available);
    }

    private boolean isSelectionAvailable() {
        return clip != null && selectionStart >= clip.startMs && selectionEnd <= clip.endMs;
    }

    private void cancelSampleExtraction() {
        sampleGeneration++;
        if (sampleTask != null) {
            sampleTask.cancel();
            sampleTask = null;
        }
    }

    private void saveSample() {
        if (!commitPendingInput()) {
            return;
        }
        if (episode == null || selectionEnd - selectionStart < 500 || selectionEnd - selectionStart > 30_000) {
            Toast.makeText(getContext(), R.string.audio_skip_validation_duration, Toast.LENGTH_LONG).show();
            return;
        }
        cancelSampleExtraction();
        Uri audioUri = episode.uri;
        long audioStart = selectionStart;
        long audioEnd = selectionEnd;
        if (SkipStreamingSource.isStreaming(episode.uri)) {
            if (clip == null || selectionStart < clip.startMs || selectionEnd > clip.endMs) {
                setStatus(R.string.audio_skip_stream_sample_unavailable);
                return;
            }
            audioUri = clip.uri;
            audioStart = SkipPreviewPlayer.toSourcePosition(selectionStart, clip.startMs);
            audioEnd = SkipPreviewPlayer.toSourcePosition(selectionEnd, clip.startMs);
        }
        final Uri requestUri = audioUri;
        final long requestStart = audioStart;
        final long requestEnd = audioEnd;
        setStatus(R.string.audio_skip_waveform_loading);
        final int generation = clipGeneration;
        final int requestGeneration = sampleGeneration;
        sampleTask = manager.extractSample(String.valueOf(feedId), String.valueOf(episode.id), requestUri,
                marker, requestStart, requestEnd, 0, selectionStart,
                new SkipSampleCallback() {
                    @Override
                    public void onSuccess(SkipSample sample) {
                        post(() -> {
                            if (!destroyed && generation == clipGeneration
                                    && requestGeneration == sampleGeneration) {
                                onSaved.accept(new SkipSampleResult(sample, replaceIndex));
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable error) {
                        post(() -> {
                            if (!destroyed && generation == clipGeneration
                                    && requestGeneration == sampleGeneration) {
                                setStatus(R.string.audio_skip_sample_weak);
                            }
                        });
                    }
                });
    }

    private void playPreview() {
        if (!commitPendingInput()) {
            return;
        }
        if (episode == null) {
            Toast.makeText(getContext(), R.string.audio_skip_preview_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        stopPreview();
        try {
            Player player = SkipPreviewPlayer.create(getContext(), episode.uri);
            mediaPlayer = player;
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (mediaPlayer != player) {
                        return;
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        if (loop.isChecked() && !destroyed) {
                            playbackCursor = selectionStart;
                            player.seekTo(selectionStart);
                            player.play();
                            scheduleCursorUpdate();
                        } else {
                            playbackCursor = episodeDuration;
                            updateCursorDisplay();
                            releasePreview();
                        }
                    } else if (playbackState == Player.STATE_READY && !playerPrepared) {
                        if (destroyed) {
                            stopPreview();
                            return;
                        }
                        playerPrepared = true;
                        long start = loop.isChecked() ? selectionStart : playbackCursor;
                        player.seekTo(start);
                        playbackCursor = start;
                        updateCursorDisplay();
                        player.play();
                        scheduleCursorUpdate();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    if (mediaPlayer == player) {
                        stopPreview();
                    }
                }
            });
            player.prepare();
        } catch (Exception error) {
            stopPreview();
            Toast.makeText(getContext(), R.string.audio_skip_preview_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void stopPreview() {
        previewHandler.removeCallbacksAndMessages(null);
        Player player = mediaPlayer;
        mediaPlayer = null;
        if (player != null) {
            if (playerPrepared) {
                playbackCursor = Math.max(0, Math.min(episodeDuration, player.getCurrentPosition()));
            }
            player.pause();
            player.release();
        }
        playerPrepared = false;
        updateCursorDisplay();
    }

    private void releasePreview() {
        playerPrepared = false;
        stopPreview();
    }

    private void scheduleCursorUpdate() {
        previewHandler.postDelayed(() -> {
            if (mediaPlayer == null || !playerPrepared || destroyed) {
                return;
            }
            playbackCursor = mediaPlayer.getCurrentPosition();
            if (loop.isChecked() && playbackCursor >= selectionEnd) {
                playbackCursor = selectionStart;
                mediaPlayer.seekTo(selectionStart);
                mediaPlayer.play();
            }
            updateCursorDisplay();
            scheduleCursorUpdate();
        }, 50);
    }

    private boolean commitPendingInput() {
        if (!pendingInput) {
            return true;
        }
        long start = AudioSkipRulesActivity.parseDuration(startLabel.getText().toString());
        long end = AudioSkipRulesActivity.parseDuration(endLabel.getText().toString());
        if (pendingStartEdit && start >= 0 && start <= episodeDuration && (end <= start
                || end - start < 500 || end - start > 30_000)) {
            long duration = selectionEnd - selectionStart;
            if (start + 500 <= episodeDuration) {
                end = Math.min(episodeDuration, start + duration);
            }
        }
        if (start < 0 || end <= start || end - start < 500 || end - start > 30_000
                || episode == null || end > episodeDuration) {
            updateCursorProgress();
            setStatus(R.string.audio_skip_validation_timestamp);
            return false;
        }
        long previousStart = selectionStart;
        long previousEnd = selectionEnd;
        selectionStart = start;
        selectionEnd = end;
        pendingInput = false;
        setSelectionInputs();
        if (selectionStart != previousStart || selectionEnd != previousEnd) {
            cancelSampleExtraction();
        }
        updateWindowForSelection();
        return true;
    }

    private void setInputCommitListeners(EditText input) {
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitPendingInput();
                return true;
            }
            return false;
        });
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                commitPendingInput();
            }
        });
    }

    private final class SelectionTextWatcher implements TextWatcher {
        private final boolean start;

        SelectionTextWatcher(boolean start) {
            this.start = start;
        }

        @Override
        public void beforeTextChanged(CharSequence value, int startIndex, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence value, int startIndex, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable value) {
            if (updatingInputs || episode == null) {
                return;
            }
            cancelSampleExtraction();
            pendingInput = true;
            pendingStartEdit = start;
        }
    }

    private void seekCursorBy(long delta) {
        if (commitPendingInput()) {
            seekCursor(playbackCursor + delta, true);
        }
    }

    private void seekCursor(long position, boolean keepVisible) {
        playbackCursor = Math.max(0, Math.min(episodeDuration, position));
        if (mediaPlayer != null && playerPrepared) {
            mediaPlayer.seekTo(playbackCursor);
        }
        if (keepVisible && (playbackCursor < windowStart || playbackCursor > windowStart + windowLength)) {
            centerCursorWindow();
        } else {
            updateCursorDisplay();
        }
    }

    private void centerCursor() {
        if (commitPendingInput()) {
            centerCursorWindow();
        }
    }

    private void centerCursorWindow() {
        windowStart = Math.max(0, Math.min(playbackCursor - windowLength / 2,
                Math.max(0, episodeDuration - windowLength)));
        waveformView.setRange(windowStart, windowStart + windowLength, selectionStart, selectionEnd);
        scheduleWaveformLoad();
        updateCursorDisplay();
    }

    private void setStartFromCursor() {
        if (!commitPendingInput() || episode == null) {
            return;
        }
        long duration = Math.max(500, Math.min(30_000, selectionEnd - selectionStart));
        selectionStart = Math.max(0, Math.min(playbackCursor, episodeDuration - duration));
        selectionEnd = selectionStart + duration;
        cancelSampleExtraction();
        setSelectionInputs();
        updateWindowForSelection();
    }

    private void updateCursorDisplay() {
        updateCursorProgress();
        waveformView.invalidate();
    }

    void stopPlayback() {
        stopPreview();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE && !destroyed) {
            stopPreview();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setStatus(int value) {
        setStatus(getContext().getString(value));
    }

    private void setStatus(CharSequence value) {
        status.setText(value);
        status.setVisibility(value == null || value.length() == 0 ? GONE : VISIBLE);
    }

    public static void findEpisode(Context context, long feedId, Consumer<EpisodeInfo> callback) {
        findEpisodes(context, feedId, episodes -> callback.accept(episodes.isEmpty() ? null : episodes.get(0)));
    }

    public static void findEpisodes(Context context, long feedId, Consumer<List<EpisodeInfo>> callback) {
        Single.fromCallable(() -> {
                    Feed feed = DBReader.getFeed(feedId, false, 0, 0);
                    if (feed == null) {
                        return new ArrayList<EpisodeInfo>();
                    }
                    List<FeedItem> items = DBReader.getFeedItemList(feed,
                            new FeedItemFilter(FeedItemFilter.DOWNLOADED,
                                    FeedItemFilter.INCLUDE_ALL_FEED_STATES), SortOrder.DATE_NEW_OLD, 0, -1);
                    List<EpisodeInfo> result = new ArrayList<>();
                    for (FeedItem item : items) {
                        FeedMedia media = item.getMedia();
                        if (media != null && media.getLocalFileUrl() != null) {
                            Uri uri = media.getLocalFileUrl().startsWith("content://")
                                    ? Uri.parse(media.getLocalFileUrl())
                                    : Uri.fromFile(new File(media.getLocalFileUrl()));
                            if (media.getDuration() >= 500) {
                                result.add(new EpisodeInfo(item.getId(), item.getTitle(), uri, media.getDuration()));
                            }
                        }
                    }
                    return result;
                })
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> callback.accept(result), ignored -> callback.accept(new ArrayList<>()));
    }

    public static final class EpisodeInfo {
        public final long id;
        public final String title;
        public final Uri uri;
        public final long durationMs;

        EpisodeInfo(long id, String title, Uri uri, long durationMs) {
            this.id = id;
            this.title = title;
            this.uri = uri;
            this.durationMs = durationMs;
        }
    }

    public static final class SkipSampleResult {
        final SkipSample sample;
        final int replaceIndex;

        SkipSampleResult(SkipSample sample, int replaceIndex) {
            this.sample = sample;
            this.replaceIndex = replaceIndex;
        }
    }

    private final class WaveformView extends View {
        private final Paint waveformPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private SkipWaveform waveform;
        private long rangeStart;
        private long rangeEnd;
        private long start;
        private long end;
        private int handle;
        private float lastX;

        WaveformView(Context context) {
            super(context);
            setContentDescription(context.getString(R.string.audio_skip_waveform_accessibility));
            waveformPaint.setColor(MaterialColors.getColor(this, R.attr.colorOnSurfaceVariant));
            selectionPaint.setColor(MaterialColors.getColor(this, R.attr.colorPrimaryContainer));
            handlePaint.setColor(MaterialColors.getColor(this, R.attr.colorPrimary));
            cursorPaint.setColor(MaterialColors.getColor(this, R.attr.colorError));
            cursorPaint.setStrokeWidth(dp(2));
        }

        void setWaveform(SkipWaveform waveform) {
            this.waveform = waveform;
            invalidate();
        }

        void setRange(long rangeStart, long rangeEnd, long start, long end) {
            this.rangeStart = rangeStart;
            this.rangeEnd = rangeEnd;
            this.start = start;
            this.end = end;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float left = xFor(start);
            float right = xFor(end);
            canvas.drawRect(left, 0, right, height, selectionPaint);
            if (waveform != null) {
                float step = width / waveform.maximum.length;
                for (int index = 0; index < waveform.maximum.length; index++) {
                    float x = index * step;
                    float top = height / 2 * (1 - waveform.maximum[index]);
                    float bottom = height / 2 * (1 - waveform.minimum[index]);
                    canvas.drawLine(x, top, x, bottom, waveformPaint);
                }
            }
            canvas.drawRect(left - 3, 0, left + 3, height, handlePaint);
            canvas.drawRect(right - 3, 0, right + 3, height, handlePaint);
            float cursorX = xFor(playbackCursor);
            if (cursorX >= 0 && cursorX <= width) {
                canvas.drawLine(cursorX, 0, cursorX, height, cursorPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (rangeEnd <= rangeStart) {
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN && !commitPendingInput()) {
                return true;
            }
            float x = Math.max(0, Math.min(getWidth(), event.getX()));
            long value = rangeStart + (long) ((rangeEnd - rangeStart) * x / getWidth());
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                float startX = xFor(start);
                float endX = xFor(end);
                boolean startHit = startX >= 0 && startX <= getWidth()
                        && Math.abs(event.getX() - startX) < dp(24);
                boolean endHit = endX >= 0 && endX <= getWidth()
                        && Math.abs(event.getX() - endX) < dp(24);
                if (startHit && endHit) {
                    handle = Math.abs(event.getX() - startX) <= Math.abs(event.getX() - endX) ? 1 : 2;
                } else if (startHit) {
                    handle = 1;
                } else if (endHit) {
                    handle = 2;
                } else {
                    handle = 3;
                    lastX = event.getX();
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                if (handle == 1) {
                    selectionStart = Math.max(windowStart, Math.min(selectionEnd - 500, value));
                } else if (handle == 2) {
                    selectionEnd = Math.min(windowStart + windowLength, Math.max(selectionStart + 500, value));
                } else if (handle == 3) {
                    long delta = (long) ((event.getX() - lastX) * (rangeEnd - rangeStart) / getWidth());
                    windowStart = Math.max(0, Math.min(windowStart - delta,
                            episodeDuration - windowLength));
                    lastX = event.getX();
                    waveform = null;
                }
                if (handle != 3) {
                    cancelSampleExtraction();
                    updateSelection();
                } else {
                    waveformView.setRange(windowStart, windowStart + windowLength,
                            selectionStart, selectionEnd);
                    if (episode != null && SkipStreamingSource.isStreaming(episode.uri)) {
                        scheduleWaveformLoad();
                    }
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (handle == 3) {
                    scheduleWaveformLoad();
                }
                handle = 0;
                return performClick();
            }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (handle == 3) {
                    scheduleWaveformLoad();
                }
                handle = 0;
                return true;
            }
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        private float xFor(long value) {
            return (float) (value - rangeStart) / Math.max(1, rangeEnd - rangeStart) * getWidth();
        }
    }
}
