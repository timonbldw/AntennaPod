package de.danoeh.antennapod.ui.screen.feed.preferences.audioskip;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.core.util.Consumer;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.playback.service.skip.AudioFingerprint;
import de.danoeh.antennapod.playback.service.skip.SkipManager;
import de.danoeh.antennapod.playback.service.skip.SkipAnalysisSnapshot;
import de.danoeh.antennapod.playback.service.skip.SkipCoverage;
import de.danoeh.antennapod.playback.service.skip.SkipDetection;
import de.danoeh.antennapod.playback.service.skip.SkipMarker;
import de.danoeh.antennapod.playback.service.skip.SkipRule;
import de.danoeh.antennapod.playback.service.skip.SkipSample;
import de.danoeh.antennapod.playback.service.skip.SkipStreamingSource;
import de.danoeh.antennapod.playback.service.skip.SkipTask;
import de.danoeh.antennapod.ui.common.ToolbarActivity;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class AudioSkipRulesActivity extends ToolbarActivity {
    public static final String EXTRA_FEED_ID = "feedId";
    public static final String EXTRA_PLAYER_ORIGIN = "playerOrigin";
    public static final String EXTRA_EPISODE_ID = "episodeId";
    public static final String EXTRA_EPISODE_URI = "episodeUri";
    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_DURATION = "duration";

    private static final String STATE_SCREEN = "screen";
    private static final String STATE_RULE_ID = "ruleId";
    private static final String STATE_NAME = "name";
    private static final String STATE_TYPE = "type";
    private static final String STATE_ENABLED = "enabled";
    private static final String STATE_MIN = "min";
    private static final String STATE_MAX = "max";
    private static final String STATE_MISSING = "missing";
    private static final String STATE_MISSING_DURATION = "missingDuration";
    private static final String STATE_FIXED = "fixed";
    private static final String STATE_FIRST = "first";
    private static final String STATE_LAST = "last";
    private static final String STATE_USE_START_AS_END = "useStartAsEnd";
    private static final String STATE_SAMPLES = "samples";

    private final CompositeDisposable disposables = new CompositeDisposable();
    private SkipManager skipManager;
    private long feedId;
    private List<SkipRule> rules = Collections.emptyList();
    private SkipRule draft;
    private boolean editorVisible;
    private EditText nameInput;
    private Spinner typeInput;
    private MaterialSwitch enabledInput;
    private MaterialSwitch useStartAsEndInput;
    private EditText minInput;
    private EditText maxInput;
    private Spinner missingInput;
    private View durationsSection;
    private View minLayout;
    private View maxLayout;
    private View fixedLayout;
    private View missingLayout;
    private View missingDurationLayout;
    private View durationSettingsView;
    private View searchSettingsView;
    private Button durationSettingsButton;
    private Button searchSettingsButton;
    private Button addStartSample;
    private Button addEndSample;
    private EditText missingDurationInput;
    private EditText fixedInput;
    private EditText firstInput;
    private EditText lastInput;
    private LinearLayout sampleContainer;
    private TextView validation;
    private SkipTask testTask;
    private MediaPlayer testPlayer;
    private final Handler testHandler = new Handler(Looper.getMainLooper());
    private boolean testPlaybackStarted;
    private boolean testPlayerPrepared;
    private long pendingTestEnd = -1;
    private long testPreviewStartedAt;
    private Spinner testEpisodeInput;
    private Button testRuleButton;
    private List<SampleEditorView.EpisodeInfo> testEpisodes = Collections.emptyList();
    private boolean sampleVisible;
    private boolean playerOrigin;
    private SampleEditorView sampleEditorView;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        feedId = getIntent().getLongExtra(EXTRA_FEED_ID, -1);
        playerOrigin = getIntent().getBooleanExtra(EXTRA_PLAYER_ORIGIN, false);
        skipManager = SkipManager.getInstance(this);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!editorVisible) {
                    finish();
                } else if (sampleVisible) {
                    if (playerOrigin) {
                        finish();
                    } else {
                        sampleVisible = false;
                        showEditor();
                    }
                } else {
                    if (playerOrigin) {
                        finish();
                    } else {
                        showRules();
                    }
                }
            }
        });
        if (feedId < 0) {
            finish();
            return;
        }
        if (state != null && "editor".equals(state.getString(STATE_SCREEN))) {
            draft = ruleFromState(state);
            showEditor();
        } else if (playerOrigin) {
            draft = new SkipRule(UUID.randomUUID().toString(), "", true,
                    SkipRule.Type.BETWEEN, 10_000, 120_000,
                    SkipRule.MissingEndBehavior.UNTOUCHED, 0, 0, 0, 0,
                    Collections.emptyList());
            showEditor();
            openSampleEditor(SkipMarker.START, -1);
        } else {
            showRules();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (!editorVisible || draft == null) {
            outState.putString(STATE_SCREEN, "rules");
            return;
        }
        SkipRule savedDraft = readDraft(false);
        if (savedDraft != null) {
            draft = savedDraft;
        } else {
            draft = readDraftForState();
        }
        outState.putString(STATE_SCREEN, "editor");
        outState.putString(STATE_RULE_ID, draft.id);
        outState.putString(STATE_NAME, draft.name);
        outState.putString(STATE_TYPE, draft.type.name());
        outState.putBoolean(STATE_ENABLED, draft.enabled);
        outState.putLong(STATE_MIN, draft.minDurationMs);
        outState.putLong(STATE_MAX, draft.maxDurationMs);
        outState.putString(STATE_MISSING, draft.missingEndBehavior.name());
        outState.putLong(STATE_MISSING_DURATION, draft.missingEndDurationMs);
        outState.putLong(STATE_FIXED, draft.fixedDurationMs);
        outState.putLong(STATE_FIRST, draft.firstRegionMs);
        outState.putLong(STATE_LAST, draft.lastRegionMs);
        outState.putBoolean(STATE_USE_START_AS_END, draft.useStartAsEnd);
        ArrayList<Bundle> samples = new ArrayList<>();
        for (SkipSample sample : draft.samples) {
            Bundle value = new Bundle();
            value.putString("id", sample.id);
            value.putString("marker", sample.marker.name());
            value.putLong("duration", sample.durationMs);
            value.putLong("offset", sample.markerOffsetMs);
            value.putLong("sourcePosition", sample.sourcePositionMs);
            value.putInt("rate", sample.fingerprint.sampleRate);
            value.putInt("frame", sample.fingerprint.frameMs);
            value.putInt("hop", sample.fingerprint.hopMs);
            value.putIntArray("hashes", sample.fingerprint.hashes);
            samples.add(value);
        }
        outState.putParcelableArrayList(STATE_SAMPLES, samples);
    }

    @Override
    protected void onDestroy() {
        disposables.clear();
        if (testTask != null) {
            testTask.cancel();
        }
        stopTestPlayback();
        testHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        if (sampleEditorView != null) {
            sampleEditorView.stopPlayback();
        }
        super.onStop();
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (editorVisible) {
            if (sampleVisible) {
                if (playerOrigin) {
                    finish();
                } else {
                    sampleVisible = false;
                    showEditor();
                }
            } else if (playerOrigin) {
                finish();
            } else {
                showRules();
            }
        } else {
            finish();
        }
        return true;
    }

    private void showRules() {
        editorVisible = false;
        sampleVisible = false;
        sampleEditorView = null;
        draft = null;
        if (testTask != null) {
            testTask.cancel();
            testTask = null;
        }
        stopTestPlayback();
        testHandler.removeCallbacksAndMessages(null);
        setTitle(R.string.audio_skip_rules_title);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        View root = getLayoutInflater().inflate(R.layout.audio_skip_rules, null);
        View add = root.findViewById(R.id.audioSkipAddRule);
        add.setOnClickListener(view -> {
            draft = new SkipRule(UUID.randomUUID().toString(), "", true,
                    SkipRule.Type.BETWEEN, 10_000, 120_000,
                    SkipRule.MissingEndBehavior.UNTOUCHED, 0, 0, 0, 0,
                    Collections.emptyList());
            showEditor();
        });
        LinearLayout list = root.findViewById(R.id.audioSkipRulesList);
        setContentView(root);
        loadRules(list);
    }

    private void loadRules(LinearLayout list) {
        disposables.add(Single.fromCallable(
                        () -> skipManager.getRules(String.valueOf(feedId)))
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    rules = result;
                    list.removeAllViews();
                    if (result.isEmpty()) {
                        TextView empty = text(R.string.audio_skip_rules_summary_empty);
                        empty.setGravity(Gravity.CENTER);
                        empty.setPadding(0, dp(48), 0, dp(48));
                        list.addView(empty);
                    }
                    for (SkipRule rule : result) {
                        addRuleRow(list, rule);
                    }
                }, error -> showError(error)));
    }

    private void addRuleRow(LinearLayout list, SkipRule rule) {
        View row = getLayoutInflater().inflate(R.layout.audio_skip_rule_row, list, false);
        TextView label = row.findViewById(R.id.audioSkipRuleName);
        label.setText(rule.name);
        TextView details = row.findViewById(R.id.audioSkipRuleSummary);
        details.setText(getString(R.string.audio_skip_rule_summary,
                getString(rule.enabled ? R.string.audio_skip_enabled : R.string.audio_skip_disabled),
                ruleTypeLabel(rule.type), getString(R.string.audio_skip_markers_summary, rule.samples.size())));
        MaterialSwitch toggle = row.findViewById(R.id.audioSkipRuleEnabled);
        toggle.setChecked(rule.enabled);
        toggle.setOnCheckedChangeListener((button, checked) ->
                saveRule(rule.withEnabled(checked), () -> loadRules(list)));
        row.findViewById(R.id.audioSkipEditRule).setOnClickListener(view -> editRule(rule));
        row.setOnClickListener(view -> editRule(rule));
        row.findViewById(R.id.audioSkipDeleteRule).setOnClickListener(view -> new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.audio_skip_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> deleteRule(rule))
                .show());
        list.addView(row, matchWrap());
    }

    private void editRule(SkipRule rule) {
        draft = rule;
        showEditor();
    }

    private void showEditor() {
        editorVisible = true;
        sampleVisible = false;
        sampleEditorView = null;
        setTitle(R.string.audio_skip_edit_rule);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        View root = getLayoutInflater().inflate(R.layout.audio_skip_rule_editor, null);
        nameInput = root.findViewById(R.id.audioSkipRuleNameInput);
        nameInput.setText(draft.name);
        enabledInput = root.findViewById(R.id.audioSkipRuleEnabledInput);
        enabledInput.setChecked(draft.enabled);
        typeInput = root.findViewById(R.id.audioSkipRuleTypeInput);
        typeInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.audio_skip_type_between),
                        getString(R.string.audio_skip_type_fixed), getString(R.string.audio_skip_type_finish)}));
        typeInput.setSelection(draft.type.ordinal());
        typeInput.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
            updateTypeVisibility(position);
            if (sampleContainer != null) {
                updateSamples();
            }
        }));
        useStartAsEndInput = root.findViewById(R.id.audioSkipRuleUseStartAsEndInput);
        useStartAsEndInput.setChecked(draft.useStartAsEnd);
        useStartAsEndInput.setOnCheckedChangeListener((button, checked) -> {
            draft = draft.withUseStartAsEnd(checked);
            updateTypeVisibility(typeInput.getSelectedItemPosition());
            updateSamples();
        });
        durationSettingsView = getLayoutInflater().inflate(R.layout.audio_skip_duration_settings, null);
        searchSettingsView = getLayoutInflater().inflate(R.layout.audio_skip_search_settings, null);
        durationsSection = durationSettingsView.findViewById(R.id.audioSkipDurationsSection);
        minLayout = durationSettingsView.findViewById(R.id.audioSkipMinDurationLayout);
        maxLayout = durationSettingsView.findViewById(R.id.audioSkipMaxDurationLayout);
        fixedLayout = durationSettingsView.findViewById(R.id.audioSkipFixedDurationLayout);
        missingLayout = durationSettingsView.findViewById(R.id.audioSkipMissingEndLayout);
        missingDurationLayout = durationSettingsView.findViewById(R.id.audioSkipMissingDurationLayout);
        minInput = durationSettingsView.findViewById(R.id.audioSkipMinDurationInput);
        maxInput = durationSettingsView.findViewById(R.id.audioSkipMaxDurationInput);
        fixedInput = durationSettingsView.findViewById(R.id.audioSkipFixedDurationInput);
        minInput.setText(formatTime(draft.minDurationMs));
        maxInput.setText(formatTime(draft.maxDurationMs));
        fixedInput.setText(formatTime(draft.fixedDurationMs));
        missingInput = durationSettingsView.findViewById(R.id.audioSkipMissingEndInput);
        missingInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.audio_skip_missing_untouched),
                        getString(R.string.audio_skip_missing_fixed)}));
        missingInput.setSelection(draft.missingEndBehavior == SkipRule.MissingEndBehavior.FIXED ? 1 : 0);
        missingDurationInput = durationSettingsView.findViewById(R.id.audioSkipMissingDurationInput);
        firstInput = searchSettingsView.findViewById(R.id.audioSkipFirstRegionInput);
        lastInput = searchSettingsView.findViewById(R.id.audioSkipLastRegionInput);
        missingDurationInput.setText(formatTime(draft.missingEndDurationMs));
        firstInput.setText(formatRegion(draft.firstRegionMs));
        lastInput.setText(formatRegion(draft.lastRegionMs));
        testEpisodeInput = root.findViewById(R.id.audioSkipTestEpisodeInput);
        loadTestEpisodes();
        sampleContainer = root.findViewById(R.id.audioSkipSampleContainer);
        addStartSample = root.findViewById(R.id.audioSkipAddStartSample);
        addEndSample = root.findViewById(R.id.audioSkipAddEndSample);
        addStartSample.setOnClickListener(
                view -> openSampleEditor(SkipMarker.START, -1));
        addEndSample.setOnClickListener(
                view -> openSampleEditor(SkipMarker.END, -1));
        durationSettingsButton = root.findViewById(R.id.audioSkipDurationSettingsButton);
        searchSettingsButton = root.findViewById(R.id.audioSkipSearchSettingsButton);
        durationSettingsButton.setOnClickListener(view -> showDurationSettingsDialog());
        searchSettingsButton.setOnClickListener(view -> showSearchSettingsDialog());
        validation = root.findViewById(R.id.audioSkipValidation);
        testRuleButton = root.findViewById(R.id.audioSkipTestRule);
        testRuleButton.setOnClickListener(view -> testRule());
        root.findViewById(R.id.audioSkipCancelRule).setOnClickListener(view -> {
            if (playerOrigin) {
                finish();
            } else {
                showRules();
            }
        });
        root.findViewById(R.id.audioSkipSaveRule).setOnClickListener(view -> saveEditor());
        setContentView(root);
        updateTypeVisibility(typeInput.getSelectedItemPosition());
        updateSamples();
    }

    private void updateTypeVisibility(int position) {
        if (minInput == null) {
            return;
        }
        boolean between = position == SkipRule.Type.BETWEEN.ordinal();
        boolean finish = position == SkipRule.Type.FINISH.ordinal();
        boolean useStartAsEnd = between && useStartAsEndInput != null && useStartAsEndInput.isChecked();
        durationsSection.setVisibility(finish ? View.GONE : View.VISIBLE);
        minLayout.setVisibility(between ? View.VISIBLE : View.GONE);
        maxLayout.setVisibility(between ? View.VISIBLE : View.GONE);
        fixedLayout.setVisibility(position == SkipRule.Type.FIXED.ordinal() ? View.VISIBLE : View.GONE);
        missingLayout.setVisibility(between ? View.VISIBLE : View.GONE);
        missingDurationLayout.setVisibility(between && missingInput.getSelectedItemPosition() == 1
                ? View.VISIBLE : View.GONE);
        useStartAsEndInput.setVisibility(between ? View.VISIBLE : View.GONE);
        addStartSample.setText(finish ? R.string.audio_skip_add_episode_end_marker
                : R.string.audio_skip_add_start_sample);
        addEndSample.setVisibility(between && !useStartAsEnd ? View.VISIBLE : View.GONE);
        durationSettingsButton.setVisibility(finish ? View.GONE : View.VISIBLE);
        searchSettingsButton.setVisibility(finish ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams startParams = (LinearLayout.LayoutParams) addStartSample.getLayoutParams();
        startParams.width = finish ? -1 : 0;
        startParams.weight = finish ? 0 : 1;
        startParams.setMargins(0, 0, finish ? 0 : dp(4), 0);
        addStartSample.setLayoutParams(startParams);
        missingInput.setOnItemSelectedListener(new SimpleItemSelectedListener(value ->
                missingDurationLayout.setVisibility(between && value == 1 ? View.VISIBLE : View.GONE)));
    }

    private void showDurationSettingsDialog() {
        if (durationSettingsView.getParent() instanceof ViewGroup) {
            ((ViewGroup) durationSettingsView.getParent()).removeView(durationSettingsView);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.audio_skip_duration_settings)
                .setView(durationSettingsView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showSearchSettingsDialog() {
        if (searchSettingsView.getParent() instanceof ViewGroup) {
            ((ViewGroup) searchSettingsView.getParent()).removeView(searchSettingsView);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.audio_skip_search_settings)
                .setView(searchSettingsView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void updateSamples() {
        sampleContainer.removeAllViews();
        SkipRule.Type type = SkipRule.Type.values()[typeInput.getSelectedItemPosition()];
        boolean useStartAsEnd = type == SkipRule.Type.BETWEEN && useStartAsEndInput.isChecked();
        boolean hideEndSamples = type == SkipRule.Type.BETWEEN && useStartAsEnd;
        boolean hasVisibleSample = false;
        for (SkipSample sample : draft.samples) {
            if (sample.marker != SkipMarker.END || !hideEndSamples) {
                hasVisibleSample = true;
                break;
            }
        }
        if (!hasVisibleSample) {
            sampleContainer.addView(text(R.string.audio_skip_no_samples), matchWrap());
        }
        for (int index = 0; index < draft.samples.size(); index++) {
            SkipSample sample = draft.samples.get(index);
            if (sample.marker == SkipMarker.END && hideEndSamples) {
                continue;
            }
            View row = getLayoutInflater().inflate(R.layout.audio_skip_sample_row, sampleContainer, false);
            TextView marker = row.findViewById(R.id.audioSkipSampleMarker);
            marker.setText(type == SkipRule.Type.FINISH && sample.marker == SkipMarker.START
                    ? R.string.audio_skip_sample_episode_end
                    : sample.marker == SkipMarker.START ? R.string.audio_skip_sample_start
                    : R.string.audio_skip_sample_end);
            TextView duration = row.findViewById(R.id.audioSkipSampleDuration);
            duration.setText(formatTime(sample.durationMs));
            int sampleIndex = index;
            row.findViewById(R.id.audioSkipReplaceSample).setOnClickListener(
                    view -> openSampleEditor(sample.marker, sampleIndex));
            row.findViewById(R.id.audioSkipRemoveSample).setOnClickListener(view -> {
                List<SkipSample> samples = new ArrayList<>(draft.samples);
                samples.remove(sampleIndex);
                draft = draft.withSamples(samples);
                updateSamples();
            });
            sampleContainer.addView(row, matchWrap());
        }
    }

    private void saveEditor() {
        SkipRule value = readDraft(true);
        if (value == null) {
            return;
        }
        saveRule(value, playerOrigin ? this::finish : this::showRules);
    }

    private SkipRule readDraft(boolean showValidation) {
        return readDraft(showValidation, true);
    }

    private SkipRule readDraft(boolean showValidation, boolean requireSamples) {
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty() && (requireSamples || !playerOrigin)) {
            return invalid(R.string.audio_skip_validation_name, showValidation);
        }
        SkipRule.Type type = SkipRule.Type.values()[typeInput.getSelectedItemPosition()];
        long min = type == SkipRule.Type.BETWEEN ? parseDuration(minInput.getText().toString()) : 0;
        long max = type == SkipRule.Type.BETWEEN ? parseDuration(maxInput.getText().toString()) : 0;
        long fixed = type == SkipRule.Type.FIXED ? parseDuration(fixedInput.getText().toString()) : 0;
        boolean useStartAsEnd = type == SkipRule.Type.BETWEEN && useStartAsEndInput.isChecked();
        boolean fixedFallback = type == SkipRule.Type.BETWEEN
                && missingInput.getSelectedItemPosition() == 1;
        long fallback = fixedFallback ? parseDuration(missingDurationInput.getText().toString()) : 0;
        long first = parseDuration(firstInput.getText().toString());
        long last = parseDuration(lastInput.getText().toString());
        if (type == SkipRule.Type.BETWEEN && (min < 0 || max < 0 || min > max)) {
            return invalid(R.string.audio_skip_validation_range, showValidation);
        }
        if (type == SkipRule.Type.BETWEEN && missingInput.getSelectedItemPosition() == 1
                && fallback <= 0) {
            return invalid(R.string.audio_skip_validation_fallback, showValidation);
        }
        if (type == SkipRule.Type.FIXED && fixed <= 0) {
            return invalid(R.string.audio_skip_validation_fixed, showValidation);
        }
        if (first < 0 || last < 0) {
            return invalid(R.string.audio_skip_validation_region, showValidation);
        }
        boolean hasStart = false;
        boolean hasEnd = false;
        for (SkipSample sample : draft.samples) {
            hasStart |= sample.marker == SkipMarker.START;
            hasEnd |= sample.marker == SkipMarker.END;
        }
        if (requireSamples && (!hasStart || (type == SkipRule.Type.BETWEEN && !useStartAsEnd && !hasEnd))) {
            return invalid(R.string.audio_skip_validation_samples, showValidation);
        }
        try {
            return new SkipRule(draft.id, name, enabledInput.isChecked(), type, min, max,
                    fixedFallback ? SkipRule.MissingEndBehavior.FIXED
                            : SkipRule.MissingEndBehavior.UNTOUCHED,
                    fallback, fixed, first, last, draft.samples).withUseStartAsEnd(useStartAsEnd);
        } catch (IllegalArgumentException error) {
            return invalid(R.string.audio_skip_validation_range, showValidation);
        }
    }

    private SkipRule readDraftForState() {
        String name = nameInput.getText().toString().trim();
        SkipRule.Type type = SkipRule.Type.values()[typeInput.getSelectedItemPosition()];
        long min = type == SkipRule.Type.BETWEEN
                ? nonnegative(parseDuration(minInput.getText().toString())) : 0;
        long max = type == SkipRule.Type.BETWEEN
                ? Math.max(min, nonnegative(parseDuration(maxInput.getText().toString()))) : 0;
        boolean useStartAsEnd = type == SkipRule.Type.BETWEEN && useStartAsEndInput.isChecked();
        boolean fixedFallback = type == SkipRule.Type.BETWEEN
                && missingInput.getSelectedItemPosition() == 1;
        return new SkipRule(draft.id, name,
                enabledInput.isChecked(), type, min, max,
                fixedFallback ? SkipRule.MissingEndBehavior.FIXED : SkipRule.MissingEndBehavior.UNTOUCHED,
                fixedFallback ? nonnegative(parseDuration(missingDurationInput.getText().toString())) : 0,
                type == SkipRule.Type.FIXED
                        ? nonnegative(parseDuration(fixedInput.getText().toString())) : 0,
                nonnegative(parseDuration(firstInput.getText().toString())),
                nonnegative(parseDuration(lastInput.getText().toString())), draft.samples)
                .withUseStartAsEnd(useStartAsEnd);
    }

    private SkipRule invalid(int message, boolean showValidation) {
        if (showValidation && validation != null) {
            setValidation(message, true);
        }
        return null;
    }

    private void saveRule(SkipRule rule, @Nullable Runnable after) {
        disposables.add(Completable.fromAction(
                        () -> skipManager.saveRule(String.valueOf(feedId), rule))
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    if (after != null && !isFinishing()) {
                        after.run();
                    }
                }, this::showError));
    }

    private void deleteRule(SkipRule rule) {
        disposables.add(Completable.fromAction(
                        () -> skipManager.deleteRule(String.valueOf(feedId), rule.id))
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::showRules, this::showError));
    }

    private void openSampleEditor(SkipMarker marker, int index) {
        SkipRule currentDraft = readDraft(true, false);
        if (currentDraft == null) {
            return;
        }
        draft = currentDraft;
        getIntent().putExtra(SampleEditorView.EXTRA_MARKER, marker.name());
        long preferredPosition = playerOrigin ? getIntent().getLongExtra(EXTRA_POSITION, -1) : -1;
        if (playerOrigin && marker == SkipMarker.END) {
            for (SkipSample sample : draft.samples) {
                if (sample.marker == SkipMarker.START && sample.sourcePositionMs >= 0) {
                    preferredPosition = sample.sourcePositionMs;
                    break;
                }
            }
        }
        SampleEditorView view = new SampleEditorView(this, feedId, draft, marker, index,
                playerOrigin ? getIntent().getLongExtra(EXTRA_EPISODE_ID, -1) : -1,
                playerOrigin ? getIntent().getStringExtra(EXTRA_EPISODE_URI) : null,
                preferredPosition,
                playerOrigin ? getIntent().getLongExtra(EXTRA_DURATION, -1) : -1,
                result -> {
                    List<SkipSample> samples = new ArrayList<>(this.draft.samples);
                    if (result.replaceIndex >= 0 && result.replaceIndex < samples.size()) {
                        samples.set(result.replaceIndex, result.sample);
                    } else {
                        samples.add(result.sample);
                    }
                    this.draft = this.draft.withSamples(samples);
                    showEditor();
                }, () -> {
                    if (playerOrigin) {
                        finish();
                    } else {
                        showEditor();
                    }
                });
        sampleEditorView = view;
        sampleVisible = true;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(view);
        setContentView(scroll);
        setTitle(R.string.audio_skip_sample_editor);
    }

    private void testRule() {
        SkipRule value = readDraft(true);
        if (value == null) {
            return;
        }
        draft = value;
        if (testEpisodes.isEmpty() || testEpisodeInput.getSelectedItemPosition() < 0) {
            Toast.makeText(this, R.string.audio_skip_no_downloaded_episodes, Toast.LENGTH_LONG).show();
            return;
        }
        SampleEditorView.EpisodeInfo episode = testEpisodes.get(testEpisodeInput.getSelectedItemPosition());
        if (SkipStreamingSource.isStreaming(episode.uri)) {
            setValidation(R.string.audio_skip_stream_test_download_required, true);
            return;
        }
        if (testTask != null) {
            testTask.cancel();
        }
        stopTestPlayback();
        testPlaybackStarted = false;
        long startHint = startSamplePosition(value);
        if (startHint >= 0 && value.type == SkipRule.Type.BETWEEN) {
            setValidation(getString(R.string.audio_skip_test_analyzing_from,
                    formatTime(Math.max(0, startHint - 5_000))), false);
            startTestPreview(episode.uri, startHint);
        } else {
            setValidation(getString(R.string.audio_skip_test_analyzing_progress,
                    formatTime(0)), false);
        }
        try {
            testTask = startHint >= 0 && value.type == SkipRule.Type.BETWEEN
                    ? skipManager.testRule(value, episode.uri, episode.durationMs, startHint,
                    snapshot -> onTestSnapshot(snapshot, episode, startHint))
                    : skipManager.testRule(value, episode.uri, episode.durationMs,
                    snapshot -> onTestSnapshot(snapshot, episode, -1));
        } catch (IllegalArgumentException error) {
            testTask = null;
            setValidation(R.string.audio_skip_validation_rule, true);
        }
    }

    private void onTestSnapshot(SkipAnalysisSnapshot snapshot, SampleEditorView.EpisodeInfo episode,
                                long startHint) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || !editorVisible || sampleVisible
                    || validation == null) {
                return;
            }
            if (!snapshot.occurrences.isEmpty() && !testPlaybackStarted) {
                testPlaybackStarted = true;
                long previewStart = startHint >= 0 ? startHint : snapshot.occurrences.get(0).startMs;
                playTestPreview(episode.uri, episode.durationMs, previewStart,
                        snapshot.occurrences.get(0).endMs);
            }
            switch (snapshot.status) {
                case READY:
                    if (snapshot.occurrences.isEmpty()) {
                        stopTestPlayback();
                        if (snapshot.detections.isEmpty()) {
                            setValidation(R.string.audio_skip_test_no_matches, false);
                        } else {
                            showTestDiagnostics(snapshot);
                        }
                    } else {
                        showTestResult(snapshot);
                    }
                    break;
                case NO_MATCHES:
                    stopTestPlayback();
                    setValidation(R.string.audio_skip_test_no_matches, false);
                    break;
                case ERROR:
                    setValidation(getString(R.string.audio_skip_test_error, snapshot.error), true);
                    break;
                default:
                    setTestProgress(snapshot, startHint);
                    break;
            }
        });
    }

    private void setTestProgress(SkipAnalysisSnapshot snapshot, long startHint) {
        long analyzedThrough = startHint >= 0 ? startHint - 5_000 : 0;
        for (SkipCoverage coverage : snapshot.coverage) {
            analyzedThrough = Math.max(analyzedThrough, coverage.endMs);
        }
        setValidation(getString(R.string.audio_skip_test_analyzing_progress,
                formatTime(analyzedThrough)), false);
    }

    private void showTestResult(SkipAnalysisSnapshot snapshot) {
        StringBuilder ranges = new StringBuilder();
        for (int i = 0; i < snapshot.occurrences.size(); i++) {
            if (i > 0) {
                ranges.append(", ");
            }
            ranges.append(formatTime(snapshot.occurrences.get(i).startMs))
                    .append("-").append(formatTime(snapshot.occurrences.get(i).endMs));
        }
        setValidation(getString(R.string.audio_skip_test_ready,
                snapshot.occurrences.size(), ranges), false);
    }

    private void showTestDiagnostics(SkipAnalysisSnapshot snapshot) {
        StringBuilder diagnostics = new StringBuilder();
        for (SkipDetection detection : snapshot.detections) {
            if (diagnostics.length() > 0) {
                diagnostics.append("; ");
            }
            String role = getString(detection.marker == SkipMarker.START
                    ? R.string.audio_skip_playback_start_marker
                    : R.string.audio_skip_playback_end_marker);
            String reason = getString(diagnosticReason(detection.reason));
            String time = formatTime(detection.timeMs);
            if (detection.reason == SkipDetection.Reason.FALLBACK) {
                diagnostics.append(getString(R.string.audio_skip_playback_diagnostic_fallback,
                        time, formatTime(detection.endMs), role, reason));
            } else {
                diagnostics.append(getString(R.string.audio_skip_playback_diagnostic,
                        time, role, reason));
            }
        }
        setValidation(getString(R.string.audio_skip_test_diagnostics, diagnostics), false);
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

    private long startSamplePosition(SkipRule rule) {
        for (SkipSample sample : rule.samples) {
            if (sample.marker == SkipMarker.START && sample.sourcePositionMs >= 0) {
                return sample.sourcePositionMs;
            }
        }
        return -1;
    }

    private void playTestPreview(Uri uri, long durationMs, long startHint, long detectedEnd) {
        if (testPlayer == null) {
            startTestPreview(uri, startHint);
        }
        if (testPlayer == null) {
            return;
        }
        pendingTestEnd = Math.min(durationMs, detectedEnd);
        if (testPlayerPrepared) {
            seekTestPreviewToEnd();
        }
    }

    private void startTestPreview(Uri uri, long startHint) {
        stopTestPlayback();
        try {
            PlaybackController.bindToMedia3Service(this, controller -> controller.pause());
            testPlayer = new MediaPlayer();
            testPlayer.setDataSource(this, uri);
            long start = Math.max(0, startHint - 5_000);
            testPlayer.setOnPreparedListener(player -> {
                if (isFinishing() || isDestroyed()) {
                    stopTestPlayback();
                    return;
                }
                testPlayerPrepared = true;
                testPreviewStartedAt = SystemClock.uptimeMillis();
                player.seekTo((int) start);
                player.start();
                if (pendingTestEnd >= 0) {
                    seekTestPreviewToEnd();
                }
            });
            testPlayer.setOnCompletionListener(player -> stopTestPlayback());
            testPlayer.setOnErrorListener((player, what, extra) -> {
                stopTestPlayback();
                return true;
            });
            testPlayer.prepareAsync();
        } catch (Exception error) {
            stopTestPlayback();
            Toast.makeText(this, R.string.audio_skip_preview_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void seekTestPreviewToEnd() {
        if (testPlayer == null || !testPlayerPrepared || pendingTestEnd < 0) {
            return;
        }
        long remainingPreRoll = 5_000 - (SystemClock.uptimeMillis() - testPreviewStartedAt);
        if (remainingPreRoll > 0) {
            testHandler.postDelayed(this::seekTestPreviewToEnd, remainingPreRoll);
            return;
        }
        testPlayer.seekTo((int) pendingTestEnd);
        testPlayer.start();
        testHandler.postDelayed(() -> stopTestPlayback(), 5_000);
        pendingTestEnd = -1;
    }

    private void stopTestPlayback() {
        testHandler.removeCallbacksAndMessages(null);
        testPlayerPrepared = false;
        pendingTestEnd = -1;
        testPreviewStartedAt = 0;
        if (testPlayer != null) {
            try {
                testPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            try {
                testPlayer.reset();
            } catch (IllegalStateException ignored) {
            }
            try {
                testPlayer.release();
            } catch (IllegalStateException ignored) {
            }
            testPlayer = null;
        }
    }

    private void setValidation(int message, boolean error) {
        setValidation(getString(message), error);
    }

    private void setValidation(String message, boolean error) {
        validation.setTextColor(MaterialColors.getColor(validation,
                error ? R.attr.colorError : R.attr.colorOnSurfaceVariant));
        validation.setText(message);
    }

    private void loadTestEpisodes() {
        SampleEditorView.findEpisodes(this, feedId, episodes -> {
            if (isFinishing() || isDestroyed() || !editorVisible || sampleVisible
                    || testEpisodeInput == null) {
                return;
            }
            List<SampleEditorView.EpisodeInfo> availableEpisodes = new ArrayList<>(episodes);
            String source = playerOrigin ? getIntent().getStringExtra(EXTRA_EPISODE_URI) : null;
            Uri sourceUri = source == null ? null : Uri.parse(source);
            if (SkipStreamingSource.isStreaming(sourceUri)) {
                availableEpisodes.add(0, new SampleEditorView.EpisodeInfo(
                        getIntent().getLongExtra(EXTRA_EPISODE_ID, -1),
                        getString(R.string.audio_skip_current_streamed_episode), sourceUri,
                        getIntent().getLongExtra(EXTRA_DURATION, -1)));
            }
            testEpisodes = availableEpisodes;
            List<String> labels = new ArrayList<>();
            for (SampleEditorView.EpisodeInfo episode : availableEpisodes) {
                labels.add(episode.title == null ? episode.uri.toString() : episode.title);
            }
            testEpisodeInput.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, labels));
            testEpisodeInput.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
                boolean streaming = position >= 0 && position < testEpisodes.size()
                        && SkipStreamingSource.isStreaming(testEpisodes.get(position).uri);
                testRuleButton.setEnabled(!streaming);
                if (streaming) {
                    setValidation(R.string.audio_skip_stream_test_download_required, false);
                } else {
                    setValidation("", false);
                }
            }));
        });
    }

    private SkipRule ruleFromState(Bundle state) {
        ArrayList<Bundle> values = state.getParcelableArrayList(STATE_SAMPLES);
        List<SkipSample> samples = new ArrayList<>();
        if (values != null) {
            for (Bundle value : values) {
                samples.add(new SkipSample(value.getString("id"),
                        SkipMarker.valueOf(value.getString("marker")), value.getLong("duration"),
                        value.getLong("offset"), value.getLong("sourcePosition", -1),
                        new AudioFingerprint(
                        value.getInt("rate"), value.getInt("frame"), value.getInt("hop"),
                        value.getIntArray("hashes"))));
            }
        }
        return new SkipRule(state.getString(STATE_RULE_ID), state.getString(STATE_NAME),
                state.getBoolean(STATE_ENABLED), SkipRule.Type.valueOf(state.getString(STATE_TYPE)),
                state.getLong(STATE_MIN), state.getLong(STATE_MAX),
                SkipRule.MissingEndBehavior.valueOf(state.getString(STATE_MISSING)),
                state.getLong(STATE_MISSING_DURATION), state.getLong(STATE_FIXED),
                state.getLong(STATE_FIRST), state.getLong(STATE_LAST), samples)
                .withUseStartAsEnd(state.getBoolean(STATE_USE_START_AS_END));
    }

    private void showError(Throwable error) {
        if (!isFinishing()) {
            Toast.makeText(this, error.getLocalizedMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String ruleTypeLabel(SkipRule.Type type) {
        return getString(type == SkipRule.Type.BETWEEN ? R.string.audio_skip_type_between_short
                : type == SkipRule.Type.FIXED ? R.string.audio_skip_type_fixed_short
                : R.string.audio_skip_type_finish_short);
    }

    private static long nonnegative(long value) {
        return Math.max(0, value);
    }

    private TextView text(int resource) {
        return text(getString(resource));
    }

    private TextView text(String value) {
        TextView result = new TextView(this);
        result.setText(value);
        return result;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    static long parseDuration(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            String[] parts = value.trim().split(":");
            double seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + Double.parseDouble(part.replace(',', '.'));
            }
            if (Double.isNaN(seconds) || Double.isInfinite(seconds)) {
                return -1;
            }
            long result = Math.round(seconds * 1000);
            return result < 0 ? -1 : result;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    static String formatTime(long milliseconds) {
        long value = Math.max(0, milliseconds);
        long hours = value / 3_600_000;
        value %= 3_600_000;
        long minutes = value / 60_000;
        value %= 60_000;
        long seconds = value / 1_000;
        long millis = value % 1_000;
        return hours > 0 ? String.format(Locale.US, "%d:%02d:%02d.%03d", hours, minutes,
                seconds, millis) : String.format(Locale.US, "%02d:%02d.%03d", minutes,
                seconds, millis);
    }

    private static String formatRegion(long milliseconds) {
        return milliseconds == 0 ? "" : formatTime(milliseconds);
    }

    private static final class SimpleItemSelectedListener implements AdapterView.OnItemSelectedListener {
        private final Consumer<Integer> consumer;

        SimpleItemSelectedListener(Consumer<Integer> consumer) {
            this.consumer = consumer;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            consumer.accept(position);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }
}
