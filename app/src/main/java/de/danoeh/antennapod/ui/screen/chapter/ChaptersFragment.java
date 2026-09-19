package de.danoeh.antennapod.ui.screen.chapter;

import android.app.Dialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.playback.cast.CastStateListener;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.playback.service.skip.SkipAnalysisSnapshot;
import de.danoeh.antennapod.playback.service.skip.SkipAnalysisStatus;
import de.danoeh.antennapod.playback.service.skip.SkipDetection;
import de.danoeh.antennapod.playback.service.skip.SkipManager;
import de.danoeh.antennapod.playback.service.skip.SkipMarker;
import de.danoeh.antennapod.playback.service.skip.SkipOccurrence;
import de.danoeh.antennapod.playback.service.skip.SkipRule;
import de.danoeh.antennapod.playback.service.skip.SkipStreamingSource;
import de.danoeh.antennapod.playback.service.skip.SkipSubscription;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.ui.chapters.ChapterUtils;
import de.danoeh.antennapod.ui.common.Converter;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Collections;
import java.util.List;

public class ChaptersFragment extends AppCompatDialogFragment {
    public static final String TAG = "ChaptersFragment";
    private ChaptersListAdapter adapter;
    private Disposable disposable;
    private int focusedChapter = -1;
    private Playable media;
    private LinearLayoutManager layoutManager;
    private ProgressBar progressBar;
    private LinearLayout skipAnalysisRows;
    private TextView skipAnalysisStatus;
    private SkipSubscription skipSubscription;
    private String skipFeedId;
    private String skipEpisodeId;
    private int skipSourceGeneration;
    private CastStateListener castStateListener;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.chapters_label))
                .setView(onCreateView(getLayoutInflater()))
                .setPositiveButton(getString(R.string.close_label), null) //dismisses
                .setNeutralButton(getString(R.string.refresh_label), null)
                .create();
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(View.INVISIBLE);
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> {
            progressBar.setVisibility(View.VISIBLE);
            loadMediaInfo(true);
        });

        return dialog;
    }


    public View onCreateView(@NonNull LayoutInflater inflater) {
        media = null;
        View root = inflater.inflate(R.layout.chapters_dialog, null, false);
        RecyclerView recyclerView = root.findViewById(R.id.recyclerView);
        progressBar = root.findViewById(R.id.progLoading);
        skipAnalysisRows = root.findViewById(R.id.skipAnalysisRows);
        skipAnalysisStatus = root.findViewById(R.id.skipAnalysisStatus);
        layoutManager = new LinearLayoutManager(getActivity());
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(),
                layoutManager.getOrientation()));

        adapter = new ChaptersListAdapter(getActivity(), pos -> {
            Chapter chapter = adapter.getItem(pos);
            PlaybackController.bindToMedia3Service(getActivity(), controller -> {
                if (!controller.isPlaying()) {
                    controller.play();
                }
                controller.seekTo(chapter.getStart());
            });
            updateChapterSelection(pos, true);
        });
        recyclerView.setAdapter(adapter);

        progressBar.setVisibility(View.VISIBLE);
        observeSkipAnalysis();

        return root;
    }

    private void observeSkipAnalysis() {
        final int generation = ++skipSourceGeneration;
        if (skipSubscription != null) {
            skipSubscription.close();
            skipSubscription = null;
        }
        displaySkipAnalysis(SkipAnalysisSnapshot.notAnalyzed("", ""));
        if (!(media instanceof FeedMedia) || ((FeedMedia) media).getItem() == null
                || ((FeedMedia) media).getItem().getFeed() == null) {
            displaySkipAnalysis(SkipAnalysisSnapshot.notAnalyzed("", ""));
            return;
        }
        skipFeedId = String.valueOf(((FeedMedia) media).getItem().getFeed().getId());
        skipEpisodeId = String.valueOf(((FeedMedia) media).getItem().getId());
        if (skipFeedId == null || skipFeedId.isEmpty() || skipEpisodeId == null || skipEpisodeId.isEmpty()) {
            displaySkipAnalysis(SkipAnalysisSnapshot.notAnalyzed("", ""));
            return;
        }
        final FeedMedia observedMedia = (FeedMedia) media;
        PlaybackController.bindToMedia3Service(getActivity(), controller -> {
            Uri sourceUri = getCurrentSkipSource(controller, observedMedia);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded() || skipAnalysisRows == null || generation != skipSourceGeneration
                            || this.media != observedMedia) {
                        return;
                    }
                    if (sourceUri == null) {
                        skipAnalysisRows.removeAllViews();
                        skipAnalysisStatus.setText(R.string.audio_skip_playback_no_audio);
                        return;
                    }
                    observeSkipSnapshot(generation, observedMedia, sourceUri);
                });
            }
        });
    }

    private boolean isCurrentLocalPlayback(MediaController controller, FeedMedia media) {
        if (!media.localFileAvailable() || media.getLocalFileUrl() == null) {
            return false;
        }
        if (controller.getDeviceInfo().playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            return false;
        }
        MediaItem item = controller.getCurrentMediaItem();
        return item != null && item.localConfiguration != null
                && String.valueOf(media.getId()).equals(item.mediaId)
                && Uri.parse(media.getLocalFileUrl()).equals(item.localConfiguration.uri);
    }

    private Uri getCurrentSkipSource(MediaController controller, FeedMedia media) {
        if (controller.getDeviceInfo().playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            return null;
        }
        MediaItem item = controller.getCurrentMediaItem();
        if (item == null || item.localConfiguration == null || !String.valueOf(media.getId()).equals(item.mediaId)) {
            return null;
        }
        if (isCurrentLocalPlayback(controller, media)) {
            return Uri.parse(media.getLocalFileUrl());
        }
        Uri source = SkipStreamingSource.getSource(item.localConfiguration.uri);
        return source != null && SkipStreamingSource.isAvailable(source) ? source : null;
    }

    private void observeSkipSnapshot(int generation, FeedMedia media, Uri sourceUri) {
        SkipManager manager = SkipManager.getInstance(requireContext());
        SkipAnalysisSnapshot initialSnapshot = manager.getSnapshot(skipFeedId, skipEpisodeId);
        if (matchesSource(initialSnapshot, sourceUri)) {
            displaySkipAnalysis(initialSnapshot);
        }
        final String observedFeedId = skipFeedId;
        final String observedEpisodeId = skipEpisodeId;
        skipSubscription = manager.observe(skipFeedId, skipEpisodeId, snapshot -> {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (isAdded() && skipAnalysisRows != null && generation == skipSourceGeneration
                            && this.media == media && observedFeedId.equals(snapshot.feedId)
                            && observedEpisodeId.equals(snapshot.episodeId) && matchesSource(snapshot, sourceUri)) {
                        displaySkipAnalysis(snapshot);
                    }
                });
            }
        });
    }

    private boolean matchesSource(SkipAnalysisSnapshot snapshot, Uri sourceUri) {
        return snapshot.status == SkipAnalysisStatus.NOT_ANALYZED
                || !SkipStreamingSource.isStreaming(sourceUri)
                || sourceUri.toString().equals(snapshot.sourceIdentity);
    }

    private void displaySkipAnalysis(SkipAnalysisSnapshot snapshot) {
        if (skipAnalysisRows == null || skipAnalysisStatus == null) {
            return;
        }
        skipAnalysisRows.removeAllViews();
        String status;
        if (snapshot.status == SkipAnalysisStatus.ANALYZING) {
            status = snapshot.occurrences.isEmpty()
                    ? getString(R.string.audio_skip_playback_analyzing)
                    : getString(R.string.audio_skip_playback_analyzing_with_skips,
                    snapshot.occurrences.size());
        } else if (snapshot.status == SkipAnalysisStatus.WAITING_FOR_AUDIO) {
            status = snapshot.error != null
                    ? getString(R.string.audio_skip_playback_waiting_for_audio_details, snapshot.error)
                    : snapshot.occurrences.isEmpty()
                    ? getString(R.string.audio_skip_playback_waiting_for_audio)
                    : getString(R.string.audio_skip_playback_waiting_with_skips,
                    snapshot.occurrences.size());
        } else if (snapshot.status == SkipAnalysisStatus.WINDOW_READY) {
            status = snapshot.occurrences.isEmpty() && !snapshot.detections.isEmpty()
                    ? getString(R.string.audio_skip_playback_diagnostics_only,
                    snapshot.detections.size())
                    : getString(R.string.audio_skip_playback_window_ready, snapshot.occurrences.size());
        } else if (snapshot.status == SkipAnalysisStatus.DOWNLOAD_REQUIRED) {
            status = getString(R.string.audio_skip_playback_download_required);
        } else if (snapshot.status == SkipAnalysisStatus.READY) {
            status = snapshot.occurrences.isEmpty()
                    ? getString(R.string.audio_skip_playback_diagnostics_only,
                    snapshot.detections.size())
                    : getString(R.string.audio_skip_playback_ready, snapshot.occurrences.size());
        } else if (snapshot.status == SkipAnalysisStatus.NO_MATCHES) {
            status = snapshot.detections.isEmpty()
                    ? getString(R.string.audio_skip_playback_no_matches)
                    : getString(R.string.audio_skip_playback_diagnostics_only,
                    snapshot.detections.size());
        } else if (snapshot.status == SkipAnalysisStatus.ERROR) {
            status = snapshot.error == null
                    ? getString(R.string.audio_skip_playback_error)
                    : getString(R.string.audio_skip_playback_error_details, snapshot.error);
        } else {
            status = getString(R.string.audio_skip_playback_not_analyzed);
        }
        skipAnalysisStatus.setText(status);
        List<SkipRule> rules = skipFeedId == null ? Collections.emptyList()
                : SkipManager.getInstance(requireContext()).getRules(skipFeedId);
        for (SkipOccurrence occurrence : snapshot.occurrences) {
            View row = getLayoutInflater().inflate(R.layout.skip_analysis_row, skipAnalysisRows, false);
            TextView rule = row.findViewById(R.id.skipAnalysisRule);
            TextView range = row.findViewById(R.id.skipAnalysisRange);
            TextView quality = row.findViewById(R.id.skipAnalysisMatchQuality);
            Button listen = row.findViewById(R.id.skipAnalysisListen);
            Button jump = row.findViewById(R.id.skipAnalysisJump);
            String ruleName = occurrence.ruleId;
            for (SkipRule candidate : rules) {
                if (candidate.id.equals(occurrence.ruleId)) {
                    ruleName = candidate.name;
                    break;
                }
            }
            String start = formatTime(occurrence.startMs);
            String end = formatTime(occurrence.endMs);
            rule.setText(getString(R.string.audio_skip_playback_rule, ruleName));
            range.setText(getString(R.string.audio_skip_playback_range, start, end));
            quality.setText(getString(R.string.audio_skip_playback_match_quality,
                    Math.round(occurrence.score * 100)));
            row.setContentDescription(getString(
                    R.string.audio_skip_playback_detected_with_quality_accessibility,
                    start, end, Math.round(occurrence.score * 100)));
            listen.setOnClickListener(v -> seekTo(occurrence.startMs, true));
            jump.setOnClickListener(v -> seekTo(occurrence.endMs, false));
            skipAnalysisRows.addView(row);
        }
        for (SkipDetection detection : snapshot.detections) {
            View row = getLayoutInflater().inflate(R.layout.skip_analysis_diagnostic_row,
                    skipAnalysisRows, false);
            TextView rule = row.findViewById(R.id.skipAnalysisDiagnosticRule);
            TextView details = row.findViewById(R.id.skipAnalysisDiagnosticDetails);
            String ruleName = detection.ruleId;
            for (SkipRule candidate : rules) {
                if (candidate.id.equals(detection.ruleId)) {
                    ruleName = candidate.name;
                    break;
                }
            }
            String time = formatTime(detection.timeMs);
            String role = detection.marker == SkipMarker.START
                    ? getString(R.string.audio_skip_playback_start_marker)
                    : getString(R.string.audio_skip_playback_end_marker);
            String reason = getString(diagnosticReason(detection.reason));
            rule.setText(getString(R.string.audio_skip_playback_rule, ruleName));
            details.setText(detection.reason == SkipDetection.Reason.FALLBACK
                    ? getString(R.string.audio_skip_playback_diagnostic_fallback, time,
                    formatTime(detection.endMs), role, reason)
                    : getString(R.string.audio_skip_playback_diagnostic, time, role, reason));
            row.setContentDescription(details.getText());
            skipAnalysisRows.addView(row);
        }
    }

    private int diagnosticReason(SkipDetection.Reason reason) {
        switch (reason) {
            case MISSING_END:
                return R.string.audio_skip_playback_reason_missing_end;
            case MISSING_START:
                return R.string.audio_skip_playback_reason_missing_start;
            case TOO_SHORT:
                return R.string.audio_skip_playback_reason_too_short;
            case TOO_LONG:
                return R.string.audio_skip_playback_reason_too_long;
            case REPLACED_START:
                return R.string.audio_skip_playback_reason_replaced_start;
            case FALLBACK:
                return R.string.audio_skip_playback_reason_fallback;
            case PENDING:
            default:
                return R.string.audio_skip_playback_reason_pending;
        }
    }

    private String formatTime(long timeMs) {
        return Converter.getDurationStringLong((int) timeMs);
    }

    private void seekTo(long positionMs, boolean play) {
        PlaybackController.bindToMedia3Service(getActivity(), controller -> {
            controller.seekTo(positionMs);
            if (play && !controller.isPlaying()) {
                controller.play();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        castStateListener = new CastStateListener(requireContext()) {
            @Override
            public void onSessionStartedOrEnded() {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (isAdded() && isResumed()) {
                            observeSkipAnalysis();
                        }
                    });
                }
            }
        };
        loadMediaInfo(false);
    }

    @Override
    public void onStop() {
        super.onStop();
        skipSourceGeneration++;

        if (disposable != null) {
            disposable.dispose();
        }
        if (skipSubscription != null) {
            skipSubscription.close();
            skipSubscription = null;
        }
        if (castStateListener != null) {
            castStateListener.destroy();
            castStateListener = null;
        }
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onDestroyView() {
        skipSourceGeneration++;
        if (skipSubscription != null) {
            skipSubscription.close();
            skipSubscription = null;
        }
        skipAnalysisRows = null;
        skipAnalysisStatus = null;
        super.onDestroyView();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusEvent(PlayerStatusEvent event) {
        loadMediaInfo(false);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(PlaybackPositionEvent event) {
        updateChapterSelection(getCurrentChapter(media, event.getPosition()), false);
        adapter.notifyTimeChanged(event.getPosition());
    }

    private int getCurrentChapter(Playable media, int position) {
        if (media == null) {
            return -1;
        }
        return Chapter.getAfterPosition(media.getChapters(), position);
    }

    private void loadMediaInfo(boolean forceRefresh) {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Maybe.create(emitter -> {
            Playable media = DBReader.getFeedMedia(PlaybackPreferences.getCurrentlyPlayingFeedMediaId());
            if (media != null) {
                ChapterUtils.loadChapters(media, getContext(), forceRefresh);
                emitter.onSuccess(media);
            } else {
                emitter.onComplete();
            }
        })
        .subscribeOn(Schedulers.computation())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(media -> onMediaChanged((Playable) media),
                error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private void onMediaChanged(Playable media) {
        if (skipSubscription != null) {
            skipSubscription.close();
            skipSubscription = null;
        }
        this.media = media;
        focusedChapter = -1;
        if (adapter == null) {
            return;
        }
        progressBar.setVisibility(View.GONE);
        adapter.setMedia(media);
        ((AlertDialog) getDialog()).getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(View.INVISIBLE);
        if (media instanceof FeedMedia && ((FeedMedia) media).getItem() != null
                && !TextUtils.isEmpty(((FeedMedia) media).getItem().getPodcastIndexChapterUrl())) {
            ((AlertDialog) getDialog()).getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(View.VISIBLE);
        }
        int positionOfCurrentChapter = getCurrentChapter(media, media.getPosition());
        updateChapterSelection(positionOfCurrentChapter, true);
        observeSkipAnalysis();
    }

    private void updateChapterSelection(int position, boolean scrollTo) {
        if (adapter == null) {
            return;
        }

        if (position != -1 && focusedChapter != position) {
            focusedChapter = position;
            adapter.notifyChapterChanged(focusedChapter);
            if (scrollTo && (layoutManager.findFirstCompletelyVisibleItemPosition() >= position
                    || layoutManager.findLastCompletelyVisibleItemPosition() <= position)) {
                layoutManager.scrollToPositionWithOffset(position, 100);
            }
        }
    }
}
