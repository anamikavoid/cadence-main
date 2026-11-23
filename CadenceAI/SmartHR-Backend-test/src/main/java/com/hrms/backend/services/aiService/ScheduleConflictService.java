package com.hrms.backend.services.aiService;

import com.hrms.backend.models.Event;
import com.hrms.backend.repositories.EventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Detects scheduling conflicts and suggests alternative free times.
 * It only READS the database (via the repository) and always for ONE user.
 *
 * Two events conflict when they overlap: a.start < b.end AND b.start < a.end.
 * Events that merely touch (one ends at 3:00, the next starts at 3:00) do NOT conflict.
 */
@Service
public class ScheduleConflictService {

    /** Suggestions are only offered inside this local-time window ("normal waking hours"). */
    static final int DAY_START_HOUR = 8;
    static final int DAY_END_HOUR = 21;
    /** Candidate start times are tried every 15 minutes. */
    static final int GRID_MINUTES = 15;
    /** Suggestions must start at least this far apart, so the user gets variety. */
    static final long MIN_SPACING_MINUTES = 60;

    private final EventRepository eventRepository;

    @Autowired
    public ScheduleConflictService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** The user's events that overlap [start, end), ignoring {@code excludeEventId} (used when moving an event). */
    public List<Event> findConflicts(String userId, Instant start, Instant end, String excludeEventId) {
        List<Event> overlapping = eventRepository
                .findByUserIdAndStartTimeLessThanAndEndTimeGreaterThanOrderByStartTimeAsc(userId, end, start);
        List<Event> result = new ArrayList<>();
        for (Event e : overlapping) {
            if (excludeEventId != null && excludeEventId.equals(e.getId())) continue;
            result.add(e);
        }
        return result;
    }

    /**
     * Up to {@code max} free slots of the given length, nearest to the preferred start first.
     * Looks at the preferred day, and at the next day if that did not give enough.
     */
    public List<Slot> suggestAlternatives(String userId, ZoneId zone, Instant preferredStart, long durationMinutes,
                                          String excludeEventId, Instant notBefore, int max) {
        return suggestAlternatives(userId, zone, preferredStart, Duration.ofMinutes(durationMinutes), excludeEventId, notBefore, max);
    }

    List<Slot> suggestAlternatives(String userId, ZoneId zone, Instant preferredStart, Duration duration,
                                   String excludeEventId, Instant notBefore, int max) {
        LocalDate day = preferredStart.atZone(zone).toLocalDate();
        List<Slot> all = new ArrayList<>();
        for (int i = 0; i < 2 && all.size() < max; i++) {
            LocalDate d = day.plusDays(i);
            Instant dayStart = d.atStartOfDay(zone).toInstant();
            Instant dayEnd = d.plusDays(1).atStartOfDay(zone).toInstant();
            List<Event> busy = findConflicts(userId, dayStart, dayEnd, excludeEventId);
            all.addAll(computeFreeSlots(busy, zone, d, duration, preferredStart, notBefore, max - all.size()));
        }
        return all;
    }

    /**
     * PURE function (no database): finds free slots on one local day.
     * Kept separate so it can be unit-tested without MongoDB.
     */
    static List<Slot> computeFreeSlots(List<Event> busy, ZoneId zone, LocalDate day, Duration duration,
                                       Instant preferred, Instant notBefore, int max) {
        List<Slot> candidates = new ArrayList<>();
        ZonedDateTime windowEnd = day.atTime(DAY_END_HOUR, 0).atZone(zone);
        for (ZonedDateTime s = day.atTime(DAY_START_HOUR, 0).atZone(zone);
             !s.plus(duration).isAfter(windowEnd);
             s = s.plusMinutes(GRID_MINUTES)) {
            Instant start = s.toInstant();
            Instant end = start.plus(duration);
            if (start.isBefore(notBefore)) continue;          // never suggest the past
            if (overlapsAny(busy, start, end)) continue;
            candidates.add(new Slot(start, end));
        }
        // Closest to what the user asked for first.
        candidates.sort(Comparator
                .comparingLong((Slot c) -> Math.abs(Duration.between(preferred, c.start()).toMinutes()))
                .thenComparing(Slot::start));

        List<Slot> picked = new ArrayList<>();
        for (Slot c : candidates) {
            boolean farEnough = true;
            for (Slot p : picked) {
                if (Math.abs(Duration.between(p.start(), c.start()).toMinutes()) < MIN_SPACING_MINUTES) {
                    farEnough = false;
                    break;
                }
            }
            if (farEnough) picked.add(c);
            if (picked.size() >= max) break;
        }
        picked.sort(Comparator.comparing(Slot::start));
        return picked;
    }

    private static boolean overlapsAny(List<Event> events, Instant start, Instant end) {
        for (Event e : events) {
            Instant eEnd = e.getEndTime() != null ? e.getEndTime() : e.getStartTime().plus(Duration.ofHours(1));
            if (e.getStartTime().isBefore(end) && eEnd.isAfter(start)) return true;
        }
        return false;
    }
}
