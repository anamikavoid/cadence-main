package com.hrms.backend.services.scheduling;

import com.hrms.backend.exceptions.BadApiRequestException;
import com.hrms.backend.models.Event;
import com.hrms.backend.models.enums.EventPriority;
import com.hrms.backend.services.aiService.Slot;
import com.hrms.backend.services.eventService.EventServiceInterface;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class SchedulingEngine {
    public record Activity(String title, int minutes, EventPriority priority, Instant deadline) {}
    public record Request(Slot availability, List<Activity> activities, List<String> flexibleEventIds) {}
    public record Placement(Activity activity, Slot slot, List<Slot> alternatives) {}
    public record Move(String eventId, String title, Slot previous, Slot proposed) {}
    public record Proposal(List<Placement> placements, List<Move> movesToConfirm, List<Activity> unplaced) {}

    private final EventServiceInterface events;
    private final Clock clock;

    public SchedulingEngine(EventServiceInterface events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    public Proposal propose(String userId, Request request) {
        validate(request);
        Slot window = request.availability();
        List<Event> calendar = events.findBetween(userId, window.start(), window.end());
        List<Slot> busy = new ArrayList<>();
        Map<String, Event> movable = new TreeMap<>();
        Set<String> flexible = request.flexibleEventIds() == null ? Set.of() : new HashSet<>(request.flexibleEventIds());
        for (Event event : calendar) {
            Slot slot = new Slot(event.getStartTime(), event.getEndTime());
            busy.add(slot);
            if (flexible.contains(event.getId()) && !slot.start().isBefore(window.start()) && !slot.end().isAfter(window.end())) {
                movable.put(event.getId(), event);
            }
        }
        if (movable.size() != flexible.size()) {
            throw new BadApiRequestException("Each flexible event must belong to your calendar and be inside the availability window.");
        }

        List<Activity> ordered = new ArrayList<>(request.activities());
        ordered.sort(Comparator.comparing(Activity::priority).reversed().thenComparing(Activity::deadline));
        List<Placement> placements = new ArrayList<>();
        List<Move> moves = new ArrayList<>();
        List<Activity> unplaced = new ArrayList<>();
        for (Activity activity : ordered) {
            List<Slot> options = options(activity, window, busy);
            if (options.isEmpty()) {
                // Try moving one explicitly flexible event. Keep its old slot unless both activities fit.
                for (Iterator<Event> it = movable.values().iterator(); it.hasNext();) {
                    Event event = it.next();
                    Slot old = new Slot(event.getStartTime(), event.getEndTime());
                    List<Slot> trial = new ArrayList<>(busy);
                    trial.remove(old);
                    options = options(activity, window, trial);
                    if (options.isEmpty()) continue;
                    trial.add(options.getFirst());
                    List<Slot> replacement = candidates(window, Duration.between(old.start(), old.end()), trial);
                    if (!replacement.isEmpty()) {
                        Slot next = replacement.getFirst();
                        moves.add(new Move(event.getId(), event.getTitle(), old, next));
                        busy.remove(old);
                        busy.add(next);
                        it.remove();
                        break;
                    }
                    options = List.of();
                }
            }
            if (options.isEmpty()) {
                unplaced.add(activity);
            } else {
                Slot slot = options.getFirst();
                placements.add(new Placement(activity, slot, List.of()));
                busy.add(slot);
            }
        }

        // Alternatives must also fit around later placements in the finished proposal.
        placements.replaceAll(p -> {
            List<Slot> others = new ArrayList<>(busy);
            others.remove(p.slot());
            return new Placement(p.activity(), p.slot(), options(p.activity(), window, others).stream()
                    .filter(s -> !s.equals(p.slot())).limit(3).toList());
        });
        return new Proposal(placements, moves, unplaced);
    }

    private static List<Slot> options(Activity a, Slot window, List<Slot> busy) {
        Instant end = a.deadline().isBefore(window.end()) ? a.deadline() : window.end();
        return candidates(new Slot(window.start(), end), Duration.ofMinutes(a.minutes()), busy);
    }

    /** Walk sorted intervals once. Touching boundaries leave the following instant available. */
    private static List<Slot> candidates(Slot window, Duration length, List<Slot> busy) {
        List<Slot> sorted = new ArrayList<>(busy);
        sorted.sort(Comparator.comparing(Slot::start));
        List<Slot> result = new ArrayList<>();
        Instant cursor = window.start();
        for (Slot block : sorted) {
            if (!block.start().isBefore(window.end())) break;
            addGap(result, cursor, block.start(), length);
            if (block.end().isAfter(cursor)) cursor = block.end();
            if (!cursor.isBefore(window.end())) break;
        }
        addGap(result, cursor, window.end(), length);
        return result;
    }

    private static void addGap(List<Slot> result, Instant start, Instant end, Duration length) {
        while (result.size() < 4 && Duration.between(start, end).compareTo(length) >= 0) {
            Instant next = start.plus(length);
            result.add(new Slot(start, next));
            start = next;
        }
    }

    private void validate(Request r) {
        if (r == null || r.availability() == null || r.availability().start() == null || r.availability().end() == null) {
            throw new BadApiRequestException("An availability window is required.");
        }
        Slot w = r.availability();
        Instant now = clock.instant();
        if (w.start().isBefore(now) || !w.end().isAfter(w.start()) || w.end().isAfter(now.plus(Duration.ofDays(366)))
                || Duration.between(w.start(), w.end()).compareTo(Duration.ofDays(7)) > 0) {
            throw new BadApiRequestException("Choose a future availability window of up to seven days within the next year.");
        }
        if (r.activities() == null || r.activities().isEmpty() || r.activities().size() > 32
                || (r.flexibleEventIds() != null && r.flexibleEventIds().size() > 16)) {
            throw new BadApiRequestException("Supply 1–32 activities and up to 16 flexible events.");
        }
        for (Activity a : r.activities()) {
            if (a == null || a.title() == null || a.title().isBlank() || a.title().length() > 120
                    || a.minutes() < 1 || a.minutes() > 1440 || a.priority() == null || a.deadline() == null) {
                throw new BadApiRequestException("Each activity needs a title, priority, deadline and duration of 1–1440 minutes.");
            }
        }
    }
}
