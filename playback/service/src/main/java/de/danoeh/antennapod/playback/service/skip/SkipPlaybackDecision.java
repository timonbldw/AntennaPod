package de.danoeh.antennapod.playback.service.skip;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SkipPlaybackDecision {
    public enum Action {
        NONE,
        SEEK_TO,
        FINISH
    }

    public static final class State {
        private final Set<String> knownOccurrences = new HashSet<>();
        private final Set<String> suppressedOccurrences = new HashSet<>();
        private final Set<String> consumedOccurrences = new HashSet<>();

        public State() {
        }
    }

    public static final class Decision {
        public final Action action;
        public final long targetMs;
        public final SkipOccurrence occurrence;

        private Decision(Action action, long targetMs, SkipOccurrence occurrence) {
            this.action = action;
            this.targetMs = targetMs;
            this.occurrence = occurrence;
        }

        public static Decision none() {
            return new Decision(Action.NONE, -1, null);
        }

        public static Decision seekTo(long targetMs, SkipOccurrence occurrence) {
            return new Decision(Action.SEEK_TO, targetMs, occurrence);
        }

        public static Decision finish(SkipOccurrence occurrence) {
            return new Decision(Action.FINISH, -1, occurrence);
        }
    }

    private SkipPlaybackDecision() {
    }

    public static void updateOccurrences(List<SkipOccurrence> occurrences, long positionMs, State state) {
        if (occurrences == null || state == null) {
            return;
        }
        Set<String> current = new HashSet<>();
        for (SkipOccurrence occurrence : occurrences) {
            String key = key(occurrence);
            current.add(key);
            if (!state.knownOccurrences.contains(key) && contains(occurrence, positionMs)) {
                state.suppressedOccurrences.add(key);
            }
        }
        state.knownOccurrences.clear();
        state.knownOccurrences.addAll(current);
        state.suppressedOccurrences.retainAll(current);
        state.consumedOccurrences.retainAll(current);
        removeExited(occurrences, positionMs, state.suppressedOccurrences);
        removeExited(occurrences, positionMs, state.consumedOccurrences);
    }

    public static void onUserSeek(List<SkipOccurrence> occurrences, long positionMs, State state) {
        if (occurrences == null || state == null) {
            return;
        }
        removeExited(occurrences, positionMs, state.suppressedOccurrences);
        removeExited(occurrences, positionMs, state.consumedOccurrences);
        for (SkipOccurrence occurrence : occurrences) {
            if (contains(occurrence, positionMs)) {
                state.suppressedOccurrences.add(key(occurrence));
            }
        }
    }

    public static Decision decide(List<SkipOccurrence> occurrences, long previousPositionMs,
                                  long positionMs, State state) {
        if (occurrences == null || state == null || positionMs < 0) {
            return Decision.none();
        }
        removeExited(occurrences, positionMs, state.suppressedOccurrences);
        removeExited(occurrences, positionMs, state.consumedOccurrences);
        if (isSuppressed(occurrences, positionMs, state)) {
            return Decision.none();
        }
        for (SkipOccurrence occurrence : occurrences) {
            String key = key(occurrence);
            boolean entered = contains(occurrence, positionMs)
                    || previousPositionMs >= 0 && previousPositionMs < occurrence.startMs
                    && positionMs >= occurrence.startMs;
            if (!entered || state.suppressedOccurrences.contains(key)
                    || state.consumedOccurrences.contains(key)) {
                continue;
            }
            if (occurrence.type == SkipRule.Type.FINISH) {
                state.consumedOccurrences.add(key);
                return Decision.finish(occurrence);
            }
            long targetMs = occurrence.endMs;
            boolean extended;
            do {
                extended = false;
                for (SkipOccurrence overlapping : occurrences) {
                    if (overlapping.startMs <= targetMs
                            && !state.suppressedOccurrences.contains(key(overlapping))) {
                        if (overlapping.type == SkipRule.Type.FINISH
                                && overlapping.startMs >= occurrence.startMs) {
                            state.consumedOccurrences.add(key);
                            return Decision.finish(overlapping);
                        }
                        if (overlapping.endMs > targetMs) {
                            targetMs = overlapping.endMs;
                            extended = true;
                        }
                    }
                }
            } while (extended);
            state.consumedOccurrences.add(key);
            if (targetMs > positionMs) {
                return Decision.seekTo(targetMs, occurrence);
            }
        }
        return Decision.none();
    }

    public static boolean isSuppressed(List<SkipOccurrence> occurrences, long positionMs, State state) {
        if (occurrences == null || state == null) {
            return false;
        }
        for (SkipOccurrence occurrence : occurrences) {
            if (contains(occurrence, positionMs) && state.suppressedOccurrences.contains(key(occurrence))) {
                return true;
            }
        }
        return false;
    }

    private static void removeExited(List<SkipOccurrence> occurrences, long positionMs, Set<String> keys) {
        keys.removeIf(key -> !containsKey(occurrences, key, positionMs));
    }

    private static boolean containsKey(List<SkipOccurrence> occurrences, String key, long positionMs) {
        for (SkipOccurrence occurrence : occurrences) {
            if (key.equals(key(occurrence)) && contains(occurrence, positionMs)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(SkipOccurrence occurrence, long positionMs) {
        return positionMs >= occurrence.startMs
                && (occurrence.type == SkipRule.Type.FINISH || positionMs < occurrence.endMs);
    }

    private static String key(SkipOccurrence occurrence) {
        return occurrence.ruleId + ':' + occurrence.type + ':' + occurrence.startMs + ':' + occurrence.endMs;
    }
}
