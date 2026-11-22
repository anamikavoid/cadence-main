package com.hrms.backend.services.aiService;

import com.hrms.backend.dtos.ai.AiAction;
import com.hrms.backend.dtos.ai.GeminiActionResponse;
import com.hrms.backend.dtos.entityDtos.Event.EventRequestDto;
import com.hrms.backend.exceptions.AiServiceException;
import com.hrms.backend.exceptions.BadApiRequestException;
import com.hrms.backend.exceptions.ResourceNotFoundException;
import com.hrms.backend.models.Event;
import com.hrms.backend.models.PendingAiAction;
import com.hrms.backend.models.enums.EventCategory;
import com.hrms.backend.models.enums.EventPriority;
import com.hrms.backend.services.eventService.EventServiceImpl;
import com.hrms.backend.services.eventService.EventServiceInterface;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * THE SAFETY LAYER BETWEEN GEMINI AND THE DATABASE.
 *
 * Gemini only PROPOSES an action (a GeminiActionResponse). This class never trusts it:
 *  - every date/time/enum is parsed and validated,
 *  - event ids are loaded with the CURRENT USER's id, so another user's id simply is "not found",
 *  - overlaps with existing events are detected before anything is saved,
 *  - changes are executed ONLY through EventServiceInterface (the same service the manual API uses),
 *    never through direct database queries,
 *  - destructive or conflicting actions are NOT executed: they become a PendingAiAction that the
 *    user must confirm.
 *
 * Confirmation policy (decided here by the server, NOT by Gemini):
 *   CREATE_EVENT  - executed immediately unless it overlaps another event (then: confirm).
 *   UPDATE_EVENT  - executed immediately unless the new time overlaps another event (then: confirm).
 *   CANCEL_EVENT  - ALWAYS needs confirmation.
 *
 * Answers to schedule questions are written here from the real database rows, so the assistant can
 * never "invent" an event.
 */
@Service
public class GeminiActionService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_IN = DateTimeFormatter.ofPattern("H:mm[:ss]", Locale.ENGLISH);

    static final String SOURCE_AI = "AI";
    static final int MAX_QUERY_DAYS = 62;
    static final int MAX_LISTED_EVENTS = 25;
    static final int MAX_DURATION_MINUTES = 24 * 60;
    static final int SUGGESTION_COUNT = 3;

    private final EventServiceInterface eventService;
    private final ScheduleConflictService conflictService;

    @Autowired
    public GeminiActionService(EventServiceInterface eventService,
                               ScheduleConflictService conflictService) {
        this.eventService = eventService;
        this.conflictService = conflictService;
    }

    // ------------------------------------------------------------ entry points

    /** Validates one proposal from Gemini and either executes it, asks a question, or asks for confirmation. */
    public ActionOutcome handle(String userId, ZoneId zone, Instant now, GeminiActionResponse a) {
        AiAction action = AiAction.parse(a.getAction());
        if (action == null) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "I couldn't understand the AI's answer. Please try again.");
        }
        switch (action) {
            case CREATE_EVENT:
                return create(userId, zone, now, a);
            case UPDATE_EVENT:
                return update(userId, zone, now, a);
            case CANCEL_EVENT:
                return cancel(userId, zone, now, a);
            case QUERY_EVENTS:
                return query(userId, zone, now, a);
            case ASK_CLARIFICATION: {
                String q = AiText.clean(a.getReply(), 500);
                return ActionOutcome.clarification(q.isEmpty() ? "Could you give me a bit more detail?" : q);
            }
            case GENERAL_RESPONSE:
            default: {
                String r = AiText.clean(a.getReply(), 1000);
                return ActionOutcome.info(r.isEmpty() ? "How can I help with your schedule?" : r);
            }
        }
    }

    /** Executes a previously proposed (and now confirmed) action. Everything is re-checked at this moment. */
    public ActionOutcome executePending(String userId, ZoneId zone, Instant now, PendingAiAction p) {
        AiAction type = AiAction.parse(p.getType());
        try {
            if (type == AiAction.CREATE_EVENT) {
                String problem = checkStartAllowed(p.getStartTime(), now);
                if (problem != null) {
                    return ActionOutcome.info("That time has already passed, so I didn't create the event. Tell me a new date and time.");
                }
                Event created = eventService.create(userId, dtoFromPending(p), SOURCE_AI);
                return ActionOutcome.completed(AiAction.CREATE_EVENT.name(),
                        "Done! I've added " + quote(created.getTitle()) + " on " + describeWhen(created.getStartTime(), created.getEndTime(), zone) + ".",
                        List.of(created), created.getId());
            }
            if (type == AiAction.UPDATE_EVENT) {
                Event saved = eventService.update(userId, p.getTargetEventId(), dtoFromPending(p));
                return ActionOutcome.completed(AiAction.UPDATE_EVENT.name(),
                        "Done! " + quote(saved.getTitle()) + " is now on " + describeWhen(saved.getStartTime(), saved.getEndTime(), zone) + ".",
                        List.of(saved), saved.getId());
            }
            if (type == AiAction.CANCEL_EVENT) {
                Event existing = eventService.getById(userId, p.getTargetEventId()); // ownership check
                eventService.delete(userId, existing.getId());
                return ActionOutcome.completed(AiAction.CANCEL_EVENT.name(),
                        "Done. I've cancelled " + quote(existing.getTitle()) + ".", List.of(), "");
            }
        } catch (ResourceNotFoundException e) {
            return ActionOutcome.info("I couldn't find that event any more, so nothing was changed.");
        } catch (BadApiRequestException e) {
            return ActionOutcome.info("I couldn't do that: " + e.getMessage());
        }
        return ActionOutcome.info("Sorry, I can't do that action.");
    }

    // ------------------------------------------------------------ CREATE

    private ActionOutcome create(String userId, ZoneId zone, Instant now, GeminiActionResponse a) {
        String title = AiText.clean(a.getTitle(), 120);
        if (title.isEmpty()) return ActionOutcome.clarification("What should I call this event?");

        LocalDate date;
        LocalTime time;
        try {
            date = parseDate(a.getDate());
            time = parseTime(a.getStartTime());
        } catch (DateTimeParseException e) {
            return ActionOutcome.clarification("I couldn't understand that date or time. Could you tell me the date and the start time again?");
        }
        // Never invent missing information: ask for it.
        if (date == null) return ActionOutcome.clarification("Which date should I schedule " + quote(title) + " on?");
        if (time == null) return ActionOutcome.clarification("What time should I schedule " + quote(title) + "?");

        ZonedDateTime start = ZonedDateTime.of(date, time, zone);
        String problem = checkStartAllowed(start.toInstant(), now);
        if (problem != null) return ActionOutcome.clarification(problem);

        EndResult end;
        try {
            end = computeEnd(start, a.getEndTime(), a.getDurationMinutes());
        } catch (DateTimeParseException e) {
            return ActionOutcome.clarification("I couldn't understand the end time. When should " + quote(title) + " end?");
        }
        if (end.error != null) return ActionOutcome.clarification(end.error);

        EventRequestDto dto = new EventRequestDto();
        dto.setTitle(title);
        dto.setDescription(AiText.notBlank(a.getDescription()) ? AiText.clean(a.getDescription(), 500) : null);
        dto.setCategory(parseEnum(EventCategory.class, a.getCategory()));
        dto.setPriority(parseEnum(EventPriority.class, a.getPriority()));
        dto.setLocation(AiText.notBlank(a.getLocation()) ? AiText.clean(a.getLocation(), 120) : null);
        dto.setStartTime(start.toInstant());
        dto.setEndTime(end.end);

        List<Event> conflicts = conflictService.findConflicts(userId, dto.getStartTime(), dto.getEndTime(), null);
        if (conflicts.isEmpty()) {
            Event created = eventService.create(userId, dto, SOURCE_AI);
            String reply = "Done! I've added " + quote(title) + " on "
                    + describeWhen(created.getStartTime(), created.getEndTime(), zone)
                    + (end.assumed ? " (I assumed it lasts 1 hour)." : ".");
            return ActionOutcome.completed(AiAction.CREATE_EVENT.name(), reply, List.of(created), created.getId());
        }

        // Conflict: never silently double-book. Ask, and offer alternatives.
        long minutes = Duration.between(dto.getStartTime(), dto.getEndTime()).toMinutes();
        List<Slot> alternatives = conflictService.suggestAlternatives(userId, zone, dto.getStartTime(), minutes, null, now, SUGGESTION_COUNT);
        String summary = "Create " + quote(title) + " on " + describeWhen(dto.getStartTime(), dto.getEndTime(), zone);
        PendingAiAction pending = newPending(AiAction.CREATE_EVENT, null, dto, summary, now);
        String reply = "That overlaps with " + describeList(conflicts, zone) + ". Should I create " + quote(title) + " anyway?"
                + suggestionText(alternatives, zone);
        return ActionOutcome.needsConfirmation(AiAction.CREATE_EVENT.name(), reply, pending, alternatives);
    }

    // ------------------------------------------------------------ UPDATE

    private ActionOutcome update(String userId, ZoneId zone, Instant now, GeminiActionResponse a) {
        Resolution r = resolveTarget(userId, zone, now, a);
        if (r.question != null) return ActionOutcome.clarification(r.question);
        Event target = r.event;

        String newTitle = AiText.clean(a.getTitle(), 120);
        boolean timeChange = AiText.notBlank(a.getDate()) || AiText.notBlank(a.getStartTime())
                || AiText.notBlank(a.getEndTime()) || a.getDurationMinutes() != null;
        boolean otherChange = !newTitle.isEmpty() || AiText.notBlank(a.getDescription()) || AiText.notBlank(a.getCategory())
                || AiText.notBlank(a.getPriority()) || AiText.notBlank(a.getLocation());
        if (!timeChange && !otherChange) {
            return ActionOutcome.clarification("What would you like to change about " + quote(target.getTitle()) + "?");
        }

        Instant newStart = target.getStartTime();
        Instant newEnd = target.getEndTime();
        if (timeChange) {
            ZonedDateTime oldStart = target.getStartTime().atZone(zone);
            Duration oldDuration = Duration.between(target.getStartTime(), target.getEndTime());
            LocalDate date;
            LocalTime time;
            LocalTime endTime;
            try {
                date = parseDate(a.getDate());
                time = parseTime(a.getStartTime());
                endTime = parseTime(a.getEndTime());
            } catch (DateTimeParseException e) {
                return ActionOutcome.clarification("I couldn't understand that date or time. What should I change it to?");
            }
            // Fields the user did not mention keep their old value.
            ZonedDateTime start = ZonedDateTime.of(date != null ? date : oldStart.toLocalDate(),
                    time != null ? time : oldStart.toLocalTime(), zone);
            if (endTime != null) {
                ZonedDateTime end = ZonedDateTime.of(start.toLocalDate(), endTime, zone);
                if (!end.isAfter(start)) {
                    return ActionOutcome.clarification("The end time must be after the start time. When should it end?");
                }
                newEnd = end.toInstant();
            } else if (a.getDurationMinutes() != null) {
                int d = a.getDurationMinutes();
                if (d < 1 || d > MAX_DURATION_MINUTES) {
                    return ActionOutcome.clarification("A duration must be between 1 minute and 24 hours. How long should it last?");
                }
                newEnd = start.toInstant().plus(Duration.ofMinutes(d));
            } else {
                newEnd = start.toInstant().plus(oldDuration); // moving the start keeps the same length
            }
            newStart = start.toInstant();
            if (!newStart.equals(target.getStartTime())) {
                String problem = checkStartAllowed(newStart, now);
                if (problem != null) return ActionOutcome.clarification(problem);
            }
        }

        EventRequestDto dto = new EventRequestDto();
        dto.setTitle(newTitle.isEmpty() ? target.getTitle() : newTitle);
        dto.setDescription(AiText.notBlank(a.getDescription()) ? AiText.clean(a.getDescription(), 500) : target.getDescription());
        EventCategory category = parseEnum(EventCategory.class, a.getCategory());
        dto.setCategory(category != null ? category : target.getCategory());
        EventPriority priority = parseEnum(EventPriority.class, a.getPriority());
        dto.setPriority(priority != null ? priority : target.getPriority());
        dto.setLocation(AiText.notBlank(a.getLocation()) ? AiText.clean(a.getLocation(), 120) : target.getLocation());
        dto.setStartTime(newStart);
        dto.setEndTime(newEnd);

        boolean timeMoved = !newStart.equals(target.getStartTime()) || !newEnd.equals(target.getEndTime());
        // The event being moved must not conflict with ITSELF, hence the exclude id.
        List<Event> conflicts = timeMoved ? conflictService.findConflicts(userId, newStart, newEnd, target.getId()) : List.of();

        if (conflicts.isEmpty()) {
            Event saved = eventService.update(userId, target.getId(), dto);
            String reply = timeMoved
                    ? "Done! " + quote(saved.getTitle()) + " is now on " + describeWhen(saved.getStartTime(), saved.getEndTime(), zone) + "."
                    : "Done! I've updated " + quote(saved.getTitle()) + ".";
            return ActionOutcome.completed(AiAction.UPDATE_EVENT.name(), reply, List.of(saved), saved.getId());
        }

        long minutes = Duration.between(newStart, newEnd).toMinutes();
        List<Slot> alternatives = conflictService.suggestAlternatives(userId, zone, newStart, minutes, target.getId(), now, SUGGESTION_COUNT);
        String summary = "Move " + quote(target.getTitle()) + " to " + describeWhen(newStart, newEnd, zone);
        PendingAiAction pending = newPending(AiAction.UPDATE_EVENT, target.getId(), dto, summary, now);
        String reply = "Moving " + quote(target.getTitle()) + " to " + describeWhen(newStart, newEnd, zone) + " overlaps with "
                + describeList(conflicts, zone) + ". Should I move it anyway?" + suggestionText(alternatives, zone);
        return ActionOutcome.needsConfirmation(AiAction.UPDATE_EVENT.name(), reply, pending, alternatives);
    }

    // ------------------------------------------------------------ CANCEL

    private ActionOutcome cancel(String userId, ZoneId zone, Instant now, GeminiActionResponse a) {
        Resolution r = resolveTarget(userId, zone, now, a);
        if (r.question != null) return ActionOutcome.clarification(r.question);
        Event target = r.event;
        String when = describeWhen(target.getStartTime(), target.getEndTime(), zone);
        // Destructive: never executed here. The user must confirm first.
        PendingAiAction pending = newPending(AiAction.CANCEL_EVENT, target.getId(), null,
                "Cancel " + quote(target.getTitle()) + " on " + when, now);
        String reply = "Do you want me to cancel " + quote(target.getTitle()) + " (" + when + ")? This removes it from your schedule.";
        return ActionOutcome.needsConfirmation(AiAction.CANCEL_EVENT.name(), reply, pending, List.of());
    }

    // ------------------------------------------------------------ QUERY

    private ActionOutcome query(String userId, ZoneId zone, Instant now, GeminiActionResponse a) {
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate from;
        LocalDate to;
        try {
            from = parseDate(a.getQueryFromDate());
            to = parseDate(a.getQueryToDate());
        } catch (DateTimeParseException e) {
            return ActionOutcome.clarification("I couldn't understand those dates. Which day or days do you mean?");
        }
        if (from == null) from = to != null ? to : today;
        if (to == null) to = from;
        if (to.isBefore(from)) {
            LocalDate tmp = from;
            from = to;
            to = tmp;
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_QUERY_DAYS - 1) to = from.plusDays(MAX_QUERY_DAYS - 1);

        if ("AVAILABILITY".equalsIgnoreCase(a.getQueryMode())) {
            return availability(userId, zone, now, from, a);
        }
        return listEvents(userId, zone, now, today, from, to, a);
    }

    private ActionOutcome availability(String userId, ZoneId zone, Instant now, LocalDate day, GeminiActionResponse a) {
        LocalTime startTime;
        LocalTime endTime;
        try {
            startTime = parseTime(a.getQueryStartTime());
            endTime = parseTime(a.getQueryEndTime());
        } catch (DateTimeParseException e) {
            return ActionOutcome.clarification("I couldn't understand that time. What time are you asking about?");
        }
        if (startTime == null) return ActionOutcome.clarification("What time are you asking about?");

        ZonedDateTime start = ZonedDateTime.of(day, startTime, zone);
        Instant end = start.toInstant().plus(Duration.ofMinutes(EventServiceImpl.DEFAULT_DURATION_MINUTES));
        if (endTime != null) {
            ZonedDateTime e = ZonedDateTime.of(day, endTime, zone);
            if (e.isAfter(start)) end = e.toInstant();
        }
        List<Event> conflicts = conflictService.findConflicts(userId, start.toInstant(), end, null);
        String when = describeWhen(start.toInstant(), end, zone);
        if (conflicts.isEmpty()) {
            return ActionOutcome.info(AiAction.QUERY_EVENTS.name(), "Yes, you're free on " + when + ".", List.of(), List.of());
        }
        long minutes = Duration.between(start.toInstant(), end).toMinutes();
        List<Slot> alternatives = conflictService.suggestAlternatives(userId, zone, start.toInstant(), minutes, null, now, SUGGESTION_COUNT);
        String reply = "You're not free then (" + when + "). You have " + describeList(conflicts, zone) + "." + suggestionText(alternatives, zone);
        return ActionOutcome.info(AiAction.QUERY_EVENTS.name(), reply, conflicts, alternatives);
    }

    private ActionOutcome listEvents(String userId, ZoneId zone, Instant now, LocalDate today, LocalDate from, LocalDate to,
                                     GeminiActionResponse a) {
        Instant windowStart = from.atStartOfDay(zone).toInstant();
        Instant windowEnd = to.plusDays(1).atStartOfDay(zone).toInstant();
        List<Event> found = eventService.findBetween(userId, windowStart, windowEnd);

        String keyword = AiText.clean(a.getQueryKeyword(), 60).toLowerCase(Locale.ROOT);
        EventCategory category = parseEnum(EventCategory.class, a.getQueryCategory());
        boolean multiDayFromToday = !from.equals(to) && !from.isAfter(today);
        List<Event> events = new ArrayList<>();
        for (Event e : found) {
            if (!keyword.isEmpty()) {
                String text = (e.getTitle() + " " + (e.getDescription() == null ? "" : e.getDescription())).toLowerCase(Locale.ROOT);
                if (!text.contains(keyword)) continue;
            }
            if (category != null && e.getCategory() != category) continue;
            if (multiDayFromToday && e.getEndTime().isBefore(now)) continue; // "upcoming" should not list finished events
            events.add(e);
        }

        String range = from.equals(to) ? dayLabel(from, today) : dayLabel(from, today) + " to " + dayLabel(to, today);
        if (events.isEmpty()) {
            return ActionOutcome.info(AiAction.QUERY_EVENTS.name(), "You have nothing scheduled "
                    + (from.equals(to) ? "for " : "from ") + range + ".", List.of(), List.of());
        }

        StringBuilder sb = new StringBuilder("Here's your schedule for ").append(range).append(":");
        int shown = Math.min(events.size(), MAX_LISTED_EVENTS);
        LocalDate lastDay = null;
        for (int i = 0; i < shown; i++) {
            Event e = events.get(i);
            LocalDate day = e.getStartTime().atZone(zone).toLocalDate();
            if (!from.equals(to) && !day.equals(lastDay)) {
                sb.append("\n").append(DAY_FMT.format(day)).append(":");
                lastDay = day;
            }
            sb.append("\n• ").append(timeRange(e.getStartTime(), e.getEndTime(), zone)).append(" — ").append(e.getTitle());
            if (e.getCategory() != null && e.getCategory() != EventCategory.OTHER) sb.append(" (").append(e.getCategory().name()).append(")");
            if (AiText.notBlank(e.getLocation())) sb.append(" @ ").append(e.getLocation());
        }
        if (events.size() > shown) sb.append("\n…and ").append(events.size() - shown).append(" more.");
        return ActionOutcome.info(AiAction.QUERY_EVENTS.name(), sb.toString(), events.subList(0, shown), List.of());
    }

    // ------------------------------------------------------------ resolving which event the user means

    private static final class Resolution {
        final Event event;
        final String question;

        private Resolution(Event event, String question) {
            this.event = event;
            this.question = question;
        }

        static Resolution found(Event e) {
            return new Resolution(e, null);
        }

        static Resolution ask(String q) {
            return new Resolution(null, q);
        }
    }

    /**
     * Finds the event Gemini means, SAFELY: the id from Gemini is looked up with the current user's id
     * (so a made-up id or another user's id is simply "not found"), and a text hint is matched only
     * against this user's own events. Zero or several matches become a question for the user.
     */
    private Resolution resolveTarget(String userId, ZoneId zone, Instant now, GeminiActionResponse a) {
        String id = AiText.clean(a.getTargetEventId(), 64);
        if (!id.isEmpty()) {
            try {
                return Resolution.found(eventService.getById(userId, id));
            } catch (ResourceNotFoundException ignored) {
                // Unknown or someone else's id: fall through to the hint, then ask.
            }
        }
        String hint = AiText.clean(a.getTargetHint(), 120).toLowerCase(Locale.ROOT);
        if (hint.isEmpty()) {
            return Resolution.ask("Which event do you mean? Tell me its title and when it is.");
        }
        List<String> tokens = new ArrayList<>();
        for (String t : hint.split("[^\\p{L}\\p{N}]+")) {
            if (t.length() >= 3) tokens.add(t);
        }
        if (tokens.isEmpty()) tokens.add(hint);

        List<Event> candidates = new ArrayList<>();
        for (Event e : eventService.findBetween(userId, now.minus(1, ChronoUnit.DAYS), now.plus(90, ChronoUnit.DAYS))) {
            String title = e.getTitle().toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String t : tokens) {
                if (!title.contains(t)) {
                    all = false;
                    break;
                }
            }
            if (all) candidates.add(e);
        }
        if (candidates.size() == 1) return Resolution.found(candidates.get(0));
        if (candidates.isEmpty()) return Resolution.ask("I couldn't find an event matching \"" + hint + "\" in your schedule. Which one do you mean?");

        StringBuilder sb = new StringBuilder("I found several matching events. Which one do you mean?");
        for (int i = 0; i < Math.min(5, candidates.size()); i++) {
            Event e = candidates.get(i);
            sb.append("\n• ").append(e.getTitle()).append(" — ").append(describeWhen(e.getStartTime(), e.getEndTime(), zone));
        }
        return Resolution.ask(sb.toString());
    }

    // ------------------------------------------------------------ validation helpers

    private static final class EndResult {
        final Instant end;
        final boolean assumed;
        final String error;

        EndResult(Instant end, boolean assumed, String error) {
            this.end = end;
            this.assumed = assumed;
            this.error = error;
        }
    }

    /** Decides the end of a new event: explicit end time, else duration, else a default of 60 minutes. */
    private EndResult computeEnd(ZonedDateTime start, String endTimeText, Integer durationMinutes) {
        LocalTime endTime = parseTime(endTimeText);
        if (endTime != null) {
            ZonedDateTime end = ZonedDateTime.of(start.toLocalDate(), endTime, start.getZone());
            if (!end.isAfter(start)) {
                return new EndResult(null, false, "The end time must be after the start time. When should it end?");
            }
            return new EndResult(end.toInstant(), false, null);
        }
        if (durationMinutes != null) {
            if (durationMinutes < 1 || durationMinutes > MAX_DURATION_MINUTES) {
                return new EndResult(null, false, "A duration must be between 1 minute and 24 hours. How long should it last?");
            }
            return new EndResult(start.toInstant().plus(Duration.ofMinutes(durationMinutes)), false, null);
        }
        return new EndResult(start.toInstant().plus(Duration.ofMinutes(EventServiceImpl.DEFAULT_DURATION_MINUTES)), true, null);
    }

    /** Returns a question for the user if the start is in the past or absurdly far away, else null. */
    private static String checkStartAllowed(Instant start, Instant now) {
        if (start == null) return "Which date and time should I use?";
        if (start.isBefore(now.minus(1, ChronoUnit.MINUTES))) {
            return "That time has already passed. Which date and time should I use instead?";
        }
        if (start.isAfter(now.plus(5 * 365L, ChronoUnit.DAYS))) {
            return "That date is too far in the future. Could you check the date?";
        }
        return null;
    }

    private static LocalDate parseDate(String text) {
        return AiText.notBlank(text) ? LocalDate.parse(text.trim()) : null;
    }

    private static LocalTime parseTime(String text) {
        return AiText.notBlank(text) ? LocalTime.parse(text.trim(), TIME_IN) : null;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String text) {
        if (!AiText.notBlank(text)) return null;
        try {
            return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ pending actions

    private static PendingAiAction newPending(AiAction type, String targetEventId, EventRequestDto dto, String summary, Instant now) {
        PendingAiAction p = new PendingAiAction();
        p.setId(UUID.randomUUID().toString());
        p.setType(type.name());
        p.setTargetEventId(targetEventId);
        if (dto != null) {
            p.setTitle(dto.getTitle());
            p.setDescription(dto.getDescription());
            p.setCategory(dto.getCategory() == null ? null : dto.getCategory().name());
            p.setPriority(dto.getPriority() == null ? null : dto.getPriority().name());
            p.setLocation(dto.getLocation());
            p.setStartTime(dto.getStartTime());
            p.setEndTime(dto.getEndTime());
        }
        p.setSummary(summary);
        p.setCreatedAt(now);
        return p;
    }

    private static EventRequestDto dtoFromPending(PendingAiAction p) {
        EventRequestDto dto = new EventRequestDto();
        dto.setTitle(p.getTitle());
        dto.setDescription(p.getDescription());
        dto.setCategory(parseEnum(EventCategory.class, p.getCategory()));
        dto.setPriority(parseEnum(EventPriority.class, p.getPriority()));
        dto.setLocation(p.getLocation());
        dto.setStartTime(p.getStartTime());
        dto.setEndTime(p.getEndTime());
        return dto;
    }

    // ------------------------------------------------------------ text formatting (all in the USER's time zone)

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    private static String timeRange(Instant start, Instant end, ZoneId zone) {
        ZonedDateTime s = start.atZone(zone);
        ZonedDateTime e = end.atZone(zone);
        String endText = s.toLocalDate().equals(e.toLocalDate())
                ? TIME_FMT.format(e)
                : DAY_FMT.format(e) + " " + TIME_FMT.format(e);
        return TIME_FMT.format(s) + " – " + endText;
    }

    static String describeWhen(Instant start, Instant end, ZoneId zone) {
        return DAY_FMT.format(start.atZone(zone)) + ", " + timeRange(start, end, zone);
    }

    private static String dayLabel(LocalDate d, LocalDate today) {
        if (d.equals(today)) return "today";
        if (d.equals(today.plusDays(1))) return "tomorrow";
        return DAY_FMT.format(d);
    }

    private static String describeList(List<Event> events, ZoneId zone) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(3, events.size());
        for (int i = 0; i < shown; i++) {
            Event e = events.get(i);
            if (i > 0) sb.append(", ");
            sb.append(quote(e.getTitle())).append(" (").append(timeRange(e.getStartTime(), e.getEndTime(), zone)).append(")");
        }
        if (events.size() > shown) sb.append(" and ").append(events.size() - shown).append(" more");
        return sb.toString();
    }

    static String slotLabel(Slot s, ZoneId zone) {
        ZonedDateTime start = s.start().atZone(zone);
        return DAY_FMT.format(start) + " " + TIME_FMT.format(start) + " – " + TIME_FMT.format(s.end().atZone(zone));
    }

    private static String suggestionText(List<Slot> slots, ZoneId zone) {
        if (slots == null || slots.isEmpty()) return "";
        List<String> labels = new ArrayList<>();
        for (Slot s : slots) labels.add(slotLabel(s, zone));
        return "\nFree times that would work: " + String.join(", ", labels) + ".";
    }
}
