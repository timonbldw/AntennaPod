package de.danoeh.antennapod.ui.screen.playback.audio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import de.danoeh.antennapod.playback.service.skip.SkipAnalysisSnapshot;
import de.danoeh.antennapod.playback.service.skip.SkipAnalysisStatus;
import de.danoeh.antennapod.playback.service.skip.SkipCoverage;
import de.danoeh.antennapod.playback.service.skip.SkipDetection;
import de.danoeh.antennapod.playback.service.skip.SkipMarker;
import de.danoeh.antennapod.playback.service.skip.SkipOccurrence;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.common.ThemeUtils;

public class ChapterSeekBar extends androidx.appcompat.widget.AppCompatSeekBar {

    private float top;
    private float width;
    private float center;
    private float bottom;
    private float density;
    private float progressPrimary;
    private float progressSecondary;
    private float[] dividerPos;
    private boolean isHighlighted = false;
    private final Paint paintBackground = new Paint();
    private final Paint paintBuffer = new Paint();
    private final Paint paintCoverage = new Paint();
    private final Paint paintDetected = new Paint();
    private final Paint paintProgressPrimary = new Paint();
    private final Paint paintDiagnosticError = new Paint();
    private final Paint paintDiagnosticFallback = new Paint();
    private final Paint paintDiagnosticPending = new Paint();
    private List<SkipCoverage> analysisCoverage = Collections.emptyList();
    private List<SkipOccurrence> detectedOccurrences = Collections.emptyList();
    private List<SkipDetection> detections = Collections.emptyList();
    private long analysisDuration;

    public ChapterSeekBar(Context context) {
        super(context);
        init(context);
    }

    public ChapterSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ChapterSeekBar(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        setBackground(null); // Removes the thumb shadow
        dividerPos = null;
        density = context.getResources().getDisplayMetrics().density;

        paintBackground.setColor(ThemeUtils.getColorFromAttr(getContext(), R.attr.colorSurfaceVariant));
        paintBackground.setAlpha(128);
        paintBuffer.setColor(ThemeUtils.getColorFromAttr(getContext(), R.attr.colorSurfaceVariant));
        paintBuffer.setAlpha(220);
        paintCoverage.setColor(ThemeUtils.getColorFromAttr(getContext(), R.attr.colorSurfaceContainerHighest));
        paintCoverage.setAlpha(180);
        paintDetected.setColor(ThemeUtils.getColorFromAttr(getContext(), R.attr.colorPrimary));
        paintDetected.setAlpha(150);
        paintProgressPrimary.setColor(ThemeUtils.getColorFromAttr(getContext(), R.attr.colorPrimary));
        paintDiagnosticError.setColor(Color.RED);
        paintDiagnosticFallback.setColor(Color.rgb(255, 179, 0));
        paintDiagnosticPending.setColor(ThemeUtils.getColorFromAttr(getContext(), R.attr.colorOnSurfaceVariant));
        paintDiagnosticPending.setStyle(Paint.Style.STROKE);
        paintDiagnosticError.setStrokeWidth(Math.max(2, density * 2));
        paintDiagnosticFallback.setStrokeWidth(Math.max(2, density * 2));
        paintDiagnosticPending.setStrokeWidth(Math.max(1, density * 1.5f));
    }

    public void setSkipAnalysis(SkipAnalysisSnapshot snapshot) {
        if (snapshot == null) {
            analysisCoverage = Collections.emptyList();
            detectedOccurrences = Collections.emptyList();
            detections = Collections.emptyList();
            analysisDuration = 0;
            setContentDescription(getContext().getString(R.string.audio_skip_playback_not_analyzed));
        } else {
            analysisCoverage = new ArrayList<>(snapshot.coverage);
            detectedOccurrences = new ArrayList<>(snapshot.occurrences);
            detections = new ArrayList<>(snapshot.detections);
            analysisDuration = snapshot.durationMs;
            if (!snapshot.occurrences.isEmpty() && !snapshot.detections.isEmpty()) {
                String diagnostic = diagnosticAccessibility(snapshot.detections.get(0));
                setContentDescription(getContext().getString(
                        R.string.audio_skip_playback_detected_count_accessibility,
                        snapshot.occurrences.size()) + ". " + getContext().getString(
                        R.string.audio_skip_playback_diagnostics_count_accessibility,
                        snapshot.detections.size()) + ". " + diagnostic);
            } else if (!snapshot.occurrences.isEmpty()) {
                SkipOccurrence occurrence = snapshot.occurrences.get(0);
                setContentDescription(getContext().getString(
                        R.string.audio_skip_playback_detected_count_accessibility,
                        snapshot.occurrences.size()) + ". " + getContext().getString(
                        R.string.audio_skip_playback_detected_accessibility,
                        Converter.getDurationStringLong((int) occurrence.startMs),
                        Converter.getDurationStringLong((int) occurrence.endMs)));
            } else if (!snapshot.detections.isEmpty()) {
                setContentDescription(getContext().getString(
                        R.string.audio_skip_playback_diagnostics_count_accessibility,
                        snapshot.detections.size()) + ". "
                        + diagnosticAccessibility(snapshot.detections.get(0)));
            } else if (!snapshot.coverage.isEmpty()) {
                SkipCoverage coverage = snapshot.coverage.get(0);
                setContentDescription(getContext().getString(
                        R.string.audio_skip_playback_coverage_accessibility,
                        Converter.getDurationStringLong((int) coverage.startMs),
                        Converter.getDurationStringLong((int) coverage.endMs)));
            } else if (snapshot.status == SkipAnalysisStatus.NO_MATCHES) {
                setContentDescription(getContext().getString(R.string.audio_skip_playback_no_matches));
            } else if (snapshot.status == SkipAnalysisStatus.ERROR) {
                setContentDescription(getContext().getString(R.string.audio_skip_playback_error));
            } else {
                setContentDescription(getContext().getString(R.string.audio_skip_playback_analyzing));
            }
        }
        invalidate();
    }

    private String diagnosticAccessibility(SkipDetection detection) {
        String role = getContext().getString(detection.marker == SkipMarker.START
                ? R.string.audio_skip_playback_start_marker
                : R.string.audio_skip_playback_end_marker);
        String reason = getContext().getString(diagnosticReason(detection.reason));
        String time = Converter.getDurationStringLong((int) detection.timeMs);
        return detection.reason == SkipDetection.Reason.FALLBACK
                ? getContext().getString(R.string.audio_skip_playback_diagnostic_fallback, time,
                Converter.getDurationStringLong((int) detection.endMs), role, reason)
                : getContext().getString(R.string.audio_skip_playback_diagnostic, time, role, reason);
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

    /**
     * Sets the relative positions of the chapter dividers.
     * @param dividerPos of the chapter dividers relative to the duration of the media.
     */
    public void setDividerPos(final float[] dividerPos) {
        if (dividerPos != null) {
            this.dividerPos = new float[dividerPos.length + 2];
            this.dividerPos[0] = 0;
            System.arraycopy(dividerPos, 0, this.dividerPos, 1, dividerPos.length);
            this.dividerPos[this.dividerPos.length - 1] = 1;
        } else {
            this.dividerPos = null;
        }
        invalidate();
    }

    public void highlightCurrentChapter() {
        isHighlighted = true;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                isHighlighted = false;
                invalidate();
            }
        }, 1000);
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        center = (getBottom() - getPaddingBottom() - getTop() - getPaddingTop()) / 2.0f;
        top = center - density * 1.5f;
        bottom = center + density * 1.5f;
        width = (float) (getRight() - getPaddingRight() - getLeft() - getPaddingLeft());
        progressSecondary = getSecondaryProgress() / (float) getMax() * width;
        progressPrimary = getProgress() / (float) getMax() * width;

        if (dividerPos == null) {
            drawProgress(canvas);
        } else {
            drawProgressChapters(canvas);
        }
        drawDiagnostics(canvas);
        drawThumb(canvas);
    }

    private void drawProgress(Canvas canvas) {
        final int saveCount = canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop());
        canvas.drawRect(0, top, width, bottom, paintBackground);
        canvas.drawRect(0, top, progressSecondary, bottom, paintBuffer);
        drawAnalysisRanges(canvas);
        canvas.drawRect(0, top, progressPrimary, bottom, paintProgressPrimary);
        canvas.restoreToCount(saveCount);
    }

    private void drawProgressChapters(Canvas canvas) {
        final int saveCount = canvas.save();
        int currChapter = 1;
        float chapterMargin = density * 1.2f;
        float topExpanded = center - density * 2.0f;
        float bottomExpanded = center + density * 2.0f;

        canvas.translate(getPaddingLeft(), getPaddingTop());

        canvas.drawRect(0, top, width, bottom, paintBackground);
        canvas.drawRect(0, top, progressSecondary, bottom, paintBuffer);
        drawAnalysisRanges(canvas);

        for (int i = 1; i < dividerPos.length; i++) {
            float right = dividerPos[i] * width - chapterMargin;
            float left = dividerPos[i - 1] * width;
            float rightCurr = dividerPos[currChapter] * width - chapterMargin;
            float leftCurr = dividerPos[currChapter - 1] * width;

            if (right < progressPrimary) {
                currChapter = i + 1;
                canvas.drawRect(left, top, right, bottom, paintProgressPrimary);
            } else if (isHighlighted || isPressed()) {
                canvas.drawRect(leftCurr, topExpanded, rightCurr, bottomExpanded, paintBackground);
                canvas.drawRect(leftCurr, topExpanded, progressPrimary, bottomExpanded, paintProgressPrimary);
            } else {
                canvas.drawRect(leftCurr, top, progressPrimary, bottom, paintProgressPrimary);
            }
        }
        canvas.restoreToCount(saveCount);
    }

    private void drawAnalysisRanges(Canvas canvas) {
        if (analysisDuration <= 0) {
            return;
        }
        for (SkipCoverage range : analysisCoverage) {
            drawRange(canvas, range.startMs, range.endMs, paintCoverage);
        }
        for (SkipOccurrence occurrence : detectedOccurrences) {
            drawRange(canvas, occurrence.startMs, occurrence.endMs, paintDetected);
        }
    }

    private void drawDiagnostics(Canvas canvas) {
        if (analysisDuration <= 0) {
            return;
        }
        final int saveCount = canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop());
        for (SkipDetection detection : detections) {
            if (detection.reason == SkipDetection.Reason.FALLBACK) {
                drawRange(canvas, detection.timeMs, detection.endMs, paintDiagnosticFallback);
            }
        }
        for (SkipDetection detection : detections) {
            Paint paint = detection.reason == SkipDetection.Reason.PENDING
                    ? paintDiagnosticPending : detection.reason == SkipDetection.Reason.FALLBACK
                    ? paintDiagnosticFallback : paintDiagnosticError;
            float x = Math.max(0, Math.min(width, detection.timeMs / (float) analysisDuration * width));
            if (detection.reason == SkipDetection.Reason.PENDING) {
                canvas.drawCircle(x, center, density * 3, paint);
            } else {
                canvas.drawLine(x, top - density, x, bottom + density, paint);
            }
        }
        canvas.restoreToCount(saveCount);
    }

    private void drawRange(Canvas canvas, long startMs, long endMs, Paint paint) {
        float left = Math.max(0, Math.min(width, startMs / (float) analysisDuration * width));
        float right = Math.max(left, Math.min(width, endMs / (float) analysisDuration * width));
        if (right > left) {
            canvas.drawRect(left, top, right, bottom, paint);
        }
    }

    private void drawThumb(Canvas canvas) {
        final int saveCount = canvas.save();
        canvas.translate(getPaddingLeft() - getThumbOffset(), getPaddingTop());
        getThumb().draw(canvas);
        canvas.restoreToCount(saveCount);
    }
}
