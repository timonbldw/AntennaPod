package de.danoeh.antennapod.ui.screen.feed.preferences.audioskip;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
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
import de.danoeh.antennapod.playback.service.skip.AudioFingerprint;
import de.danoeh.antennapod.playback.service.skip.SkipManager;
import de.danoeh.antennapod.playback.service.skip.SkipMarker;
import de.danoeh.antennapod.playback.service.skip.SkipRule;
import de.danoeh.antennapod.playback.service.skip.SkipSample;
import de.danoeh.antennapod.playback.service.skip.SkipTask;
import de.danoeh.antennapod.ui.common.ToolbarActivity;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class AudioSkipRulesActivity extends ToolbarActivity {
    public static final String EXTRA_FEED_ID = "feedId";

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
    private EditText minInput;
    private EditText maxInput;
    private Spinner missingInput;
    private View durationsSection;
    private View minLayout;
    private View maxLayout;
    private View fixedLayout;
    private View missingLayout;
    private View missingDurationLayout;
    private EditText missingDurationInput;
    private EditText fixedInput;
    private EditText firstInput;
    private EditText lastInput;
    private LinearLayout sampleContainer;
    private TextView validation;
    private SkipTask testTask;
    private Spinner testEpisodeInput;
    private List<SampleEditorView.EpisodeInfo> testEpisodes = Collections.emptyList();
    private boolean sampleVisible;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        feedId = getIntent().getLongExtra(EXTRA_FEED_ID, -1);
        skipManager = SkipManager.getInstance(this);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!editorVisible) {
                    finish();
                } else if (sampleVisible) {
                    sampleVisible = false;
                    showEditor();
                } else {
                    showRules();
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
        ArrayList<Bundle> samples = new ArrayList<>();
        for (SkipSample sample : draft.samples) {
            Bundle value = new Bundle();
            value.putString("id", sample.id);
            value.putString("marker", sample.marker.name());
            value.putLong("duration", sample.durationMs);
            value.putLong("offset", sample.markerOffsetMs);
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
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (editorVisible) {
            if (sampleVisible) {
                sampleVisible = false;
                showEditor();
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
        draft = null;
        if (testTask != null) {
            testTask.cancel();
            testTask = null;
        }
        setTitle(R.string.audio_skip_rules_title);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        View root = getLayoutInflater().inflate(R.layout.audio_skip_rules, null);
        View add = root.findViewById(R.id.audioSkipAddRule);
        add.setOnClickListener(view -> {
            draft = new SkipRule(UUID.randomUUID().toString(), getString(R.string.audio_skip_new_rule), true,
                    SkipRule.Type.BETWEEN, 0, 120_000,
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
        typeInput.setOnItemSelectedListener(new SimpleItemSelectedListener(this::updateTypeVisibility));
        durationsSection = root.findViewById(R.id.audioSkipDurationsSection);
        minLayout = root.findViewById(R.id.audioSkipMinDurationLayout);
        maxLayout = root.findViewById(R.id.audioSkipMaxDurationLayout);
        fixedLayout = root.findViewById(R.id.audioSkipFixedDurationLayout);
        missingLayout = root.findViewById(R.id.audioSkipMissingEndLayout);
        missingDurationLayout = root.findViewById(R.id.audioSkipMissingDurationLayout);
        minInput = root.findViewById(R.id.audioSkipMinDurationInput);
        maxInput = root.findViewById(R.id.audioSkipMaxDurationInput);
        fixedInput = root.findViewById(R.id.audioSkipFixedDurationInput);
        minInput.setText(formatTime(draft.minDurationMs));
        maxInput.setText(formatTime(draft.maxDurationMs));
        fixedInput.setText(formatTime(draft.fixedDurationMs));
        missingInput = root.findViewById(R.id.audioSkipMissingEndInput);
        missingInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.audio_skip_missing_untouched),
                        getString(R.string.audio_skip_missing_fixed)}));
        missingInput.setSelection(draft.missingEndBehavior == SkipRule.MissingEndBehavior.FIXED ? 1 : 0);
        missingDurationInput = root.findViewById(R.id.audioSkipMissingDurationInput);
        firstInput = root.findViewById(R.id.audioSkipFirstRegionInput);
        lastInput = root.findViewById(R.id.audioSkipLastRegionInput);
        missingDurationInput.setText(formatTime(draft.missingEndDurationMs));
        firstInput.setText(formatTime(draft.firstRegionMs));
        lastInput.setText(formatTime(draft.lastRegionMs));
        testEpisodeInput = root.findViewById(R.id.audioSkipTestEpisodeInput);
        loadTestEpisodes();
        sampleContainer = root.findViewById(R.id.audioSkipSampleContainer);
        root.findViewById(R.id.audioSkipAddStartSample).setOnClickListener(
                view -> openSampleEditor(SkipMarker.START, -1));
        root.findViewById(R.id.audioSkipAddEndSample).setOnClickListener(
                view -> openSampleEditor(SkipMarker.END, -1));
        validation = root.findViewById(R.id.audioSkipValidation);
        root.findViewById(R.id.audioSkipTestRule).setOnClickListener(view -> testRule());
        root.findViewById(R.id.audioSkipCancelRule).setOnClickListener(view -> showRules());
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
        durationsSection.setVisibility(position == SkipRule.Type.FINISH.ordinal() ? View.GONE : View.VISIBLE);
        minLayout.setVisibility(between ? View.VISIBLE : View.GONE);
        maxLayout.setVisibility(between ? View.VISIBLE : View.GONE);
        fixedLayout.setVisibility(position == SkipRule.Type.FIXED.ordinal() ? View.VISIBLE : View.GONE);
        missingLayout.setVisibility(between ? View.VISIBLE : View.GONE);
        missingDurationLayout.setVisibility(between && missingInput.getSelectedItemPosition() == 1
                ? View.VISIBLE : View.GONE);
        missingInput.setOnItemSelectedListener(new SimpleItemSelectedListener(value ->
                missingDurationLayout.setVisibility(between && value == 1 ? View.VISIBLE : View.GONE)));
    }

    private void updateSamples() {
        sampleContainer.removeAllViews();
        if (draft.samples.isEmpty()) {
            sampleContainer.addView(text(R.string.audio_skip_no_samples), matchWrap());
        }
        for (int index = 0; index < draft.samples.size(); index++) {
            SkipSample sample = draft.samples.get(index);
            View row = getLayoutInflater().inflate(R.layout.audio_skip_sample_row, sampleContainer, false);
            TextView marker = row.findViewById(R.id.audioSkipSampleMarker);
            marker.setText(sample.marker == SkipMarker.START ? R.string.audio_skip_sample_start
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
        saveRule(value, this::showRules);
    }

    private SkipRule readDraft(boolean showValidation) {
        return readDraft(showValidation, true);
    }

    private SkipRule readDraft(boolean showValidation, boolean requireSamples) {
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            return invalid(R.string.audio_skip_validation_name, showValidation);
        }
        SkipRule.Type type = SkipRule.Type.values()[typeInput.getSelectedItemPosition()];
        long min = type == SkipRule.Type.BETWEEN ? parseDuration(minInput.getText().toString()) : 0;
        long max = type == SkipRule.Type.BETWEEN ? parseDuration(maxInput.getText().toString()) : 0;
        long fixed = type == SkipRule.Type.FIXED ? parseDuration(fixedInput.getText().toString()) : 0;
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
        if (requireSamples && (!hasStart || (type == SkipRule.Type.BETWEEN && !hasEnd))) {
            return invalid(R.string.audio_skip_validation_samples, showValidation);
        }
        try {
            return new SkipRule(draft.id, name, enabledInput.isChecked(), type, min, max,
                    fixedFallback ? SkipRule.MissingEndBehavior.FIXED
                            : SkipRule.MissingEndBehavior.UNTOUCHED,
                    fallback, fixed, first, last, draft.samples);
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
        boolean fixedFallback = type == SkipRule.Type.BETWEEN
                && missingInput.getSelectedItemPosition() == 1;
        return new SkipRule(draft.id, name.isEmpty() ? getString(R.string.audio_skip_new_rule) : name,
                enabledInput.isChecked(), type, min, max,
                fixedFallback ? SkipRule.MissingEndBehavior.FIXED : SkipRule.MissingEndBehavior.UNTOUCHED,
                fixedFallback ? nonnegative(parseDuration(missingDurationInput.getText().toString())) : 0,
                type == SkipRule.Type.FIXED
                        ? nonnegative(parseDuration(fixedInput.getText().toString())) : 0,
                nonnegative(parseDuration(firstInput.getText().toString())),
                nonnegative(parseDuration(lastInput.getText().toString())), draft.samples);
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
        SampleEditorView view = new SampleEditorView(this, feedId, draft, marker, index,
                result -> {
                    List<SkipSample> samples = new ArrayList<>(this.draft.samples);
                    if (result.replaceIndex >= 0 && result.replaceIndex < samples.size()) {
                        samples.set(result.replaceIndex, result.sample);
                    } else {
                        samples.add(result.sample);
                    }
                    this.draft = this.draft.withSamples(samples);
                    showEditor();
                }, this::showEditor);
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
        setValidation(R.string.audio_skip_test_analyzing, false);
        if (testTask != null) {
            testTask.cancel();
        }
        try {
            testTask = skipManager.testRule(value, episode.uri, episode.durationMs,
                    snapshot -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !editorVisible || sampleVisible
                            || validation == null) {
                        return;
                    }
                    switch (snapshot.status) {
                        case READY:
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
                            break;
                        case NO_MATCHES:
                            setValidation(R.string.audio_skip_test_no_matches, false);
                            break;
                        case ERROR:
                            setValidation(getString(R.string.audio_skip_test_error, snapshot.error), true);
                            break;
                        default:
                            setValidation(R.string.audio_skip_test_analyzing, false);
                            break;
                    }
                    }));
        } catch (IllegalArgumentException error) {
            testTask = null;
            setValidation(R.string.audio_skip_validation_rule, true);
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
            testEpisodes = episodes;
            List<String> labels = new ArrayList<>();
            for (SampleEditorView.EpisodeInfo episode : episodes) {
                labels.add(episode.title == null ? episode.uri.toString() : episode.title);
            }
            testEpisodeInput.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, labels));
        });
    }

    private SkipRule ruleFromState(Bundle state) {
        ArrayList<Bundle> values = state.getParcelableArrayList(STATE_SAMPLES);
        List<SkipSample> samples = new ArrayList<>();
        if (values != null) {
            for (Bundle value : values) {
                samples.add(new SkipSample(value.getString("id"),
                        SkipMarker.valueOf(value.getString("marker")), value.getLong("duration"),
                        value.getLong("offset"), new AudioFingerprint(
                        value.getInt("rate"), value.getInt("frame"), value.getInt("hop"),
                        value.getIntArray("hashes"))));
            }
        }
        return new SkipRule(state.getString(STATE_RULE_ID), state.getString(STATE_NAME),
                state.getBoolean(STATE_ENABLED), SkipRule.Type.valueOf(state.getString(STATE_TYPE)),
                state.getLong(STATE_MIN), state.getLong(STATE_MAX),
                SkipRule.MissingEndBehavior.valueOf(state.getString(STATE_MISSING)),
                state.getLong(STATE_MISSING_DURATION), state.getLong(STATE_FIXED),
                state.getLong(STATE_FIRST), state.getLong(STATE_LAST), samples);
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
