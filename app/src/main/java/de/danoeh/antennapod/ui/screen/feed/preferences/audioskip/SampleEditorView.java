package de.danoeh.antennapod.ui.screen.feed.preferences.audioskip;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.util.Consumer;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.playback.service.skip.SkipManager;
import de.danoeh.antennapod.playback.service.skip.SkipMarker;
import de.danoeh.antennapod.playback.service.skip.SkipRule;
import de.danoeh.antennapod.playback.service.skip.SkipSample;
import de.danoeh.antennapod.playback.service.skip.SkipSampleCallback;
import de.danoeh.antennapod.playback.service.skip.SkipTask;
import de.danoeh.antennapod.playback.service.skip.SkipWaveform;
import de.danoeh.antennapod.playback.service.skip.SkipWaveformCallback;
import de.danoeh.antennapod.storage.database.DBReader;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class SampleEditorView extends LinearLayout {
    public static final String EXTRA_MARKER = "marker";

    private final long feedId;
    private final SkipRule rule;
    private final SkipMarker marker;
    private final int replaceIndex;
    private final Consumer<SkipSampleResult> onSaved;
    private final Runnable onCancel;
    private final long preferredEpisodeId;
    private final String preferredEpisodeUri;
    private final long preferredPosition;
    private final SkipManager manager;
    private final WaveformView waveformView;
    private final Spinner episodeSpinner;
    private final SeekBar windowPosition;
    private final TextView status;
    private final EditText startLabel;
    private final EditText endLabel;
    private final CheckBox loop;
    private final List<EpisodeInfo> episodes = new ArrayList<>();
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private final Handler inputHandler = new Handler(Looper.getMainLooper());
    private SkipTask waveformTask;
    private SkipTask sampleTask;
    private MediaPlayer mediaPlayer;
    private EpisodeInfo episode;
    private long episodeDuration;
    private long windowStart;
    private long windowLength;
    private long selectionStart;
    private long selectionEnd;
    private boolean destroyed;
    private boolean waveformLoading;
    private boolean updatingInputs;
    private int fineStep = 100;

    public SampleEditorView(Context context, long feedId, SkipRule rule, SkipMarker marker, int replaceIndex,
                            long preferredEpisodeId, String preferredEpisodeUri, long preferredPosition,
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
                    setWindowPosition(progress);
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
        addZoomButton(findViewById(R.id.audioSkipZoomOutAction), R.string.audio_skip_zoom_out, false);
        addZoomButton(findViewById(R.id.audioSkipZoomInAction), R.string.audio_skip_zoom_in, true);
        loop = findViewById(R.id.audioSkipLoopSample);
        MaterialButton play = findViewById(R.id.audioSkipPlaySample);
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
        Button cancel = findViewById(R.id.audioSkipCancelSample);
        cancel.setOnClickListener(view -> onCancel.run());
        Button save = findViewById(R.id.audioSkipSaveSample);
        save.setOnClickListener(view -> saveSample());
        loadEpisodes();
    }

    @Override
    protected void onDetachedFromWindow() {
        destroyed = true;
        if (waveformTask != null) {
            waveformTask.cancel();
        }
        if (sampleTask != null) {
            sampleTask.cancel();
        }
        stopPreview();
        previewHandler.removeCallbacksAndMessages(null);
        inputHandler.removeCallbacksAndMessages(null);
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
        episode = value;
        episodeDuration = Math.max(1, value.durationMs);
        windowLength = Math.min(30_000, episodeDuration);
        if (isPreferredEpisode(value) && preferredPosition >= 0) {
            long position = Math.min(preferredPosition, episodeDuration);
            windowStart = Math.max(0, Math.min(position - 20_000, episodeDuration - windowLength));
            selectionStart = Math.max(windowStart, position - 5_000);
            selectionEnd = Math.min(episodeDuration, Math.max(selectionStart + 500, position));
        } else {
            windowStart = 0;
            selectionStart = windowStart;
            selectionEnd = Math.min(episodeDuration, selectionStart + Math.min(5_000, windowLength));
        }
        windowPosition.setMax((int) episodeDuration);
        updateWindowPosition();
        waveformView.setRange(windowStart, windowStart + windowLength, selectionStart, selectionEnd);
        setSelectionInputs();
        loadWaveform();
    }

    private void setWindowPosition(long value) {
        long selectionLength = selectionEnd - selectionStart;
        windowStart = Math.max(0, Math.min(value - windowLength / 2,
                Math.max(0, episodeDuration - windowLength)));
        selectionStart = Math.max(0, Math.min(value - selectionLength / 2,
                episodeDuration - selectionLength));
        selectionEnd = selectionStart + selectionLength;
        waveformView.setRange(windowStart, windowStart + windowLength, selectionStart, selectionEnd);
        setSelectionInputs();
        if (mediaPlayer != null) {
            stopPreview();
        }
        loadWaveform();
    }

    private void updateWindowPosition() {
        windowPosition.setProgress((int) (windowStart + windowLength / 2));
    }

    private void loadWaveform() {
        if (episode == null || destroyed) {
            return;
        }
        if (waveformTask != null) {
            waveformTask.cancel();
        }
        waveformView.setWaveform(null);
        waveformLoading = true;
        setStatus(R.string.audio_skip_waveform_loading);
        waveformTask = manager.extractWaveform(episode.uri, windowStart,
                Math.min(episodeDuration, windowStart + windowLength), 300,
                new SkipWaveformCallback() {
                    @Override
                    public void onSuccess(SkipWaveform waveform) {
                        post(() -> {
                            if (destroyed) {
                                return;
                            }
                            waveformLoading = false;
                            waveformView.setWaveform(waveform);
                            setStatus("");
                        });
                    }

                    @Override
                    public void onError(Throwable error) {
                        post(() -> {
                            if (!destroyed) {
                                waveformLoading = false;
                                setStatus(R.string.audio_skip_waveform_unavailable);
                            }
                        });
                    }
                });
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
            if (!syncSelectionFromInputs()) {
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
            if (!syncSelectionFromInputs()) {
                return;
            }
            long selectionLength = selectionEnd - selectionStart;
            long center = selectionStart + selectionLength / 2;
            windowLength = Math.max(selectionLength,
                    Math.max(1_000, Math.min(30_000, in ? windowLength / 2 : windowLength * 2)));
            windowLength = Math.min(windowLength, episodeDuration);
            windowStart = Math.max(0, Math.min(center - windowLength / 2,
                    episodeDuration - windowLength));
            windowPosition.setMax((int) episodeDuration);
            updateWindowPosition();
            updateSelection();
            loadWaveform();
        });
        parent.addView(value, new LinearLayout.LayoutParams(-2, -2));
    }

    private void updateSelection() {
        waveformView.setRange(windowStart, windowStart + windowLength, selectionStart, selectionEnd);
        setSelectionInputs();
    }

    private void setSelectionInputs() {
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
            windowLength = Math.min(30_000, Math.min(windowLength, episodeDuration));
            windowStart = Math.max(0, Math.min(selectionStart - (windowLength - selectionEnd + selectionStart) / 2,
                    episodeDuration - windowLength));
            windowPosition.setMax((int) episodeDuration);
            updateWindowPosition();
        }
        waveformView.setRange(windowStart, windowStart + windowLength, selectionStart, selectionEnd);
        if (reloadWaveform) {
            loadWaveform();
        }
    }

    private void saveSample() {
        if (!syncSelectionFromInputs()) {
            return;
        }
        if (episode == null || selectionEnd - selectionStart < 500 || selectionEnd - selectionStart > 30_000) {
            Toast.makeText(getContext(), R.string.audio_skip_validation_duration, Toast.LENGTH_LONG).show();
            return;
        }
        if (sampleTask != null) {
            sampleTask.cancel();
        }
        setStatus(R.string.audio_skip_waveform_loading);
        sampleTask = manager.extractSample(String.valueOf(feedId), String.valueOf(episode.uri), episode.uri,
                marker, selectionStart, selectionEnd, 0, selectionStart,
                new SkipSampleCallback() {
                    @Override
                    public void onSuccess(SkipSample sample) {
                        post(() -> {
                            if (!destroyed) {
                                onSaved.accept(new SkipSampleResult(sample, replaceIndex));
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable error) {
                        post(() -> {
                            if (!destroyed) {
                                setStatus(R.string.audio_skip_sample_weak);
                            }
                        });
                    }
                });
    }

    private void playPreview() {
        if (!syncSelectionFromInputs()) {
            return;
        }
        if (episode == null) {
            Toast.makeText(getContext(), R.string.audio_skip_preview_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        stopPreview();
        PlaybackController.bindToMedia3Service(getContext(), controller -> controller.pause());
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(getContext(), episode.uri);
            final long start = selectionStart;
            final long end = selectionEnd;
            mediaPlayer.setOnPreparedListener(player -> {
                if (destroyed) {
                    stopPreview();
                    return;
                }
                player.seekTo((int) start);
                player.start();
                schedulePreviewStop();
            });
            mediaPlayer.setOnCompletionListener(player -> {
                if (loop.isChecked() && !destroyed) {
                    player.seekTo((int) selectionStart);
                    player.start();
                } else {
                    stopPreview();
                }
            });
            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                stopPreview();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception error) {
            stopPreview();
            Toast.makeText(getContext(), R.string.audio_skip_preview_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void stopPreview() {
        previewHandler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            try {
                mediaPlayer.reset();
            } catch (IllegalStateException ignored) {
            }
            try {
                mediaPlayer.release();
            } catch (IllegalStateException ignored) {
            }
            mediaPlayer = null;
        }
    }

    private void schedulePreviewStop() {
        previewHandler.postDelayed(() -> {
            if (mediaPlayer == null || destroyed) {
                return;
            }
            long end = selectionEnd;
            if (mediaPlayer.getCurrentPosition() < end) {
                schedulePreviewStop();
                return;
            }
            if (loop.isChecked()) {
                mediaPlayer.seekTo((int) selectionStart);
                mediaPlayer.start();
                schedulePreviewStop();
            } else {
                stopPreview();
            }
        }, 100);
    }

    private boolean syncSelectionFromInputs() {
        inputHandler.removeCallbacksAndMessages(null);
        long start = AudioSkipRulesActivity.parseDuration(startLabel.getText().toString());
        long end = AudioSkipRulesActivity.parseDuration(endLabel.getText().toString());
        if (start < 0 || end <= start || end - start < 500 || end - start > 30_000
                || episode == null || end > episodeDuration) {
            setStatus(R.string.audio_skip_validation_timestamp);
            return false;
        }
        selectionStart = start;
        selectionEnd = end;
        updateWindowForSelection();
        return true;
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
            inputHandler.removeCallbacksAndMessages(null);
            if (value.toString().trim().isEmpty()) {
                return;
            }
            inputHandler.postDelayed(() -> updateSelectionFromInputs(start), 300);
        }
    }

    private void updateSelectionFromInputs(boolean editedStart) {
        long previousStart = selectionStart;
        long previousEnd = selectionEnd;
        long start = AudioSkipRulesActivity.parseDuration(startLabel.getText().toString());
        long end = AudioSkipRulesActivity.parseDuration(endLabel.getText().toString());
        if (start < 0 || end < 0 || start > episodeDuration || end > episodeDuration) {
            return;
        }
        if (end - start >= 500 && end - start <= 30_000) {
            selectionStart = start;
            selectionEnd = end;
        } else if (editedStart) {
            long duration = selectionEnd - selectionStart;
            if (start + 500 > episodeDuration) {
                return;
            }
            selectionStart = start;
            selectionEnd = Math.min(episodeDuration, start + duration);
            setSelectionInputs();
        } else {
            return;
        }
        if (mediaPlayer != null && (selectionStart != previousStart || selectionEnd != previousEnd)) {
            stopPreview();
        }
        updateWindowForSelection();
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
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (rangeEnd <= rangeStart) {
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
                    updateWindowPosition();
                    waveform = null;
                }
                if (handle != 3) {
                    updateSelection();
                } else {
                    waveformView.setRange(windowStart, windowStart + windowLength,
                            selectionStart, selectionEnd);
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (handle == 3) {
                    loadWaveform();
                }
                handle = 0;
                return performClick();
            }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (handle == 3) {
                    loadWaveform();
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
