package com.hrms.backend.services.aiService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrms.backend.dtos.ai.GeminiActionResponse;
import com.hrms.backend.dtos.entityDtos.Event.EventRequestDto;
import com.hrms.backend.models.AiConversation;
import com.hrms.backend.models.AiMessage;
import com.hrms.backend.models.Event;
import com.hrms.backend.models.PendingAiAction;
import com.hrms.backend.repositories.AiConversationRepository;
import com.hrms.backend.repositories.EventRepository;
import com.hrms.backend.services.eventService.EventServiceImpl;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Shared helpers for the AI tests. NOTHING here talks to MongoDB, the network or the real Gemini API:
 *  - repositories are in-memory fakes (java.lang.reflect.Proxy) that behave like the real ones,
 *    including "a query for one user never returns another user's data",
 *  - Gemini is replaced by ScriptedGeminiClient, which returns the JSON we tell it to.
 */
final class AiTestSupport {
    private AiTestSupport() {}

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final String TZ = "Asia/Kolkata";
    /** The pretend "current time": Saturday 21 Nov 2026, 14:00 in India (08:30 UTC). */
    static final Instant NOW = Instant.parse("2026-11-21T08:30:00Z");

    /** "2026-11-22", "17:00" in India time -> the exact Instant. */
    static Instant ist(String date, String time) {
        return LocalDateTime.parse(date + "T" + time).atZone(IST).toInstant();
    }

    // ------------------------------------------------------------------ clock

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // ------------------------------------------------------------------ fake Gemini

    static final class ScriptedGeminiClient implements GeminiClient {
        private final Deque<Object> script = new ArrayDeque<>();
        final List<String> userContents = new ArrayList<>();
        int calls = 0;

        void enqueue(String json) {
            script.add(json);
        }

        void enqueueError(RuntimeException e) {
            script.add(e);
        }

        String lastContent() {
            return userContents.get(userContents.size() - 1);
        }

        @Override
        public String generateJson(String systemInstruction, String userContent, Map<String, Object> jsonSchema) {
            calls++;
            userContents.add(userContent);
            Object next = script.poll();
            if (next == null) throw new IllegalStateException("The test did not script a Gemini answer for call #" + calls);
            if (next instanceof RuntimeException) throw (RuntimeException) next;
            return (String) next;
        }
    }

    // ------------------------------------------------------------------ in-memory repositories

    static EventRepository eventRepository() {
        Map<String, Event> store = new LinkedHashMap<>();
        int[] seq = {0};
        return (EventRepository) Proxy.newProxyInstance(EventRepository.class.getClassLoader(), new Class<?>[]{EventRepository.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (method.getDeclaringClass() == Object.class) {
                        return name.equals("hashCode") ? System.identityHashCode(proxy) : name.equals("equals") ? proxy == args[0] : "InMemoryEventRepository";
                    }
                    if (name.equals("save")) {
                        Event e = copy((Event) args[0]);
                        if (e.getId() == null) e.setId("evt" + (++seq[0]));
                        ((Event) args[0]).setId(e.getId());
                        store.put(e.getId(), e);
                        return copy(e);
                    }
                    if (name.equals("findByIdAndUserId")) {
                        Event e = store.get((String) args[0]);
                        return Optional.ofNullable(e != null && e.getUserId().equals(args[1]) ? copy(e) : null);
                    }
                    if (name.startsWith("findByUserIdAndStartTimeLessThanAndEndTimeGreaterThan")) {
                        String userId = (String) args[0];
                        Instant rangeEnd = (Instant) args[1];
                        Instant rangeStart = (Instant) args[2];
                        return store.values().stream()
                                .filter(e -> e.getUserId().equals(userId) && e.getStartTime().isBefore(rangeEnd) && e.getEndTime().isAfter(rangeStart))
                                .sorted(Comparator.comparing(Event::getStartTime))
                                .map(AiTestSupport::copy)
                                .collect(Collectors.toList());
                    }
                    if (name.equals("countByUserId")) {
                        return store.values().stream().filter(e -> e.getUserId().equals(args[0])).count();
                    }
                    if (name.equals("deleteByIdAndUserId")) {
                        Event e = store.get((String) args[0]);
                        if (e != null && e.getUserId().equals(args[1])) {
                            store.remove(e.getId());
                            return 1L;
                        }
                        return 0L;
                    }
                    throw new UnsupportedOperationException("not implemented in the fake: " + name);
                });
    }

    /** Behaves like Spring Data's @Version handling: a save from a stale copy fails. */
    static AiConversationRepository conversationRepository() {
        Map<String, AiConversation> store = new LinkedHashMap<>(); // by userId
        return (AiConversationRepository) Proxy.newProxyInstance(AiConversationRepository.class.getClassLoader(),
                new Class<?>[]{AiConversationRepository.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (method.getDeclaringClass() == Object.class) {
                        return name.equals("hashCode") ? System.identityHashCode(proxy) : name.equals("equals") ? proxy == args[0] : "InMemoryConversationRepository";
                    }
                    if (name.equals("save")) {
                        AiConversation c = (AiConversation) args[0];
                        AiConversation stored = store.get(c.getUserId());
                        if (c.getVersion() == null) {
                            if (stored != null) throw new DuplicateKeyException("duplicate userId");
                            c.setVersion(0L);
                        } else {
                            if (stored == null || !stored.getVersion().equals(c.getVersion())) {
                                throw new OptimisticLockingFailureException("stale copy");
                            }
                            c.setVersion(c.getVersion() + 1);
                        }
                        if (c.getId() == null) c.setId("conv-" + c.getUserId());
                        store.put(c.getUserId(), copy(c));
                        return c;
                    }
                    if (name.equals("findByUserId")) {
                        AiConversation c = store.get((String) args[0]);
                        return Optional.ofNullable(c == null ? null : copy(c));
                    }
                    if (name.equals("deleteByUserId")) {
                        store.remove((String) args[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException("not implemented in the fake: " + name);
                });
    }

    /** A repository that always blows up with a message containing "secrets" (to prove they never reach the user). */
    static AiConversationRepository failingConversationRepository() {
        return (AiConversationRepository) Proxy.newProxyInstance(AiConversationRepository.class.getClassLoader(),
                new Class<?>[]{AiConversationRepository.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return "Failing";
                    throw new RuntimeException("boom: mongodb://admin:secret-db-password@host");
                });
    }

    static Event copy(Event e) {
        Event c = new Event();
        c.setId(e.getId());
        c.setUserId(e.getUserId());
        c.setTitle(e.getTitle());
        c.setDescription(e.getDescription());
        c.setCategory(e.getCategory());
        c.setPriority(e.getPriority());
        c.setStartTime(e.getStartTime());
        c.setEndTime(e.getEndTime());
        c.setLocation(e.getLocation());
        c.setSource(e.getSource());
        c.setCreatedAt(e.getCreatedAt());
        c.setUpdatedAt(e.getUpdatedAt());
        return c;
    }

    static AiConversation copy(AiConversation s) {
        AiConversation c = new AiConversation();
        c.setId(s.getId());
        c.setUserId(s.getUserId());
        List<AiMessage> messages = new ArrayList<>();
        for (AiMessage m : s.getMessages()) messages.add(new AiMessage(m.getRole(), m.getText(), m.getTimestamp()));
        c.setMessages(messages);
        if (s.getPendingAction() != null) {
            PendingAiAction p = s.getPendingAction();
            PendingAiAction q = new PendingAiAction();
            q.setId(p.getId());
            q.setType(p.getType());
            q.setTargetEventId(p.getTargetEventId());
            q.setTitle(p.getTitle());
            q.setDescription(p.getDescription());
            q.setCategory(p.getCategory());
            q.setPriority(p.getPriority());
            q.setLocation(p.getLocation());
            q.setStartTime(p.getStartTime());
            q.setEndTime(p.getEndTime());
            q.setSummary(p.getSummary());
            q.setCreatedAt(p.getCreatedAt());
            c.setPendingAction(q);
        }
        c.setLastEventId(s.getLastEventId());
        c.setVersion(s.getVersion());
        c.setUpdatedAt(s.getUpdatedAt());
        return c;
    }

    // ------------------------------------------------------------------ everything wired together

    /** The real services connected to fake repositories and a scripted Gemini. */
    static final class Fixture {
        final EventRepository eventRepo = eventRepository();
        final ScheduleConflictService conflictService = new ScheduleConflictService(eventRepo);
        final EventServiceImpl eventService = new EventServiceImpl(eventRepo, conflictService);
        final GeminiActionService actionService = new GeminiActionService(eventService, conflictService);
        final ScriptedGeminiClient gemini = new ScriptedGeminiClient();
        final GeminiService geminiService = new GeminiService(gemini, new ObjectMapper());
        final MutableClock clock = new MutableClock(NOW);
        final AiChatService chat;

        private Fixture(AiConversationRepository conversations, AiRateLimiter limiter) {
            this.chat = new AiChatService(geminiService, actionService, eventService, conversations, limiter, clock);
        }

        static Fixture create() {
            return new Fixture(conversationRepository(), new AiRateLimiter(1000, 100000, System::currentTimeMillis));
        }

        static Fixture withRateLimit(int perMinute) {
            return new Fixture(conversationRepository(), new AiRateLimiter(perMinute, 100000, System::currentTimeMillis));
        }

        static Fixture withBrokenDatabase() {
            return new Fixture(failingConversationRepository(), new AiRateLimiter(1000, 100000, System::currentTimeMillis));
        }
    }

    // ------------------------------------------------------------------ builders

    /** Builds the JSON text a Gemini answer would contain: json("action","CREATE_EVENT","title","DSA"...). Null values are skipped. */
    static String json(String... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] == null) continue;
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(keyValues[i]).append("\":");
            if (keyValues[i].equals("durationMinutes")) {
                sb.append(keyValues[i + 1]);
            } else {
                sb.append('"').append(keyValues[i + 1].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return sb.append('}').toString();
    }

    /** Same pairs, but as the parsed object (for tests that call GeminiActionService directly). */
    static GeminiActionResponse action(String action, String... keyValues) {
        GeminiActionResponse a = new GeminiActionResponse();
        a.setAction(action);
        for (int i = 0; i < keyValues.length; i += 2) {
            String v = keyValues[i + 1];
            switch (keyValues[i]) {
                case "reply": a.setReply(v); break;
                case "title": a.setTitle(v); break;
                case "description": a.setDescription(v); break;
                case "category": a.setCategory(v); break;
                case "priority": a.setPriority(v); break;
                case "location": a.setLocation(v); break;
                case "date": a.setDate(v); break;
                case "startTime": a.setStartTime(v); break;
                case "endTime": a.setEndTime(v); break;
                case "durationMinutes": a.setDurationMinutes(v == null ? null : Integer.valueOf(v)); break;
                case "targetEventId": a.setTargetEventId(v); break;
                case "targetHint": a.setTargetHint(v); break;
                case "queryMode": a.setQueryMode(v); break;
                case "queryFromDate": a.setQueryFromDate(v); break;
                case "queryToDate": a.setQueryToDate(v); break;
                case "queryStartTime": a.setQueryStartTime(v); break;
                case "queryEndTime": a.setQueryEndTime(v); break;
                case "queryKeyword": a.setQueryKeyword(v); break;
                case "queryCategory": a.setQueryCategory(v); break;
                default: throw new IllegalArgumentException("unknown key " + keyValues[i]);
            }
        }
        return a;
    }

    /** Creates an event directly through the service (as if made manually). */
    static Event addEvent(Fixture f, String userId, String title, String date, String start, String end) {
        EventRequestDto d = new EventRequestDto();
        d.setTitle(title);
        d.setStartTime(ist(date, start));
        d.setEndTime(ist(date, end));
        return f.eventService.create(userId, d, "MANUAL");
    }

    static int countEvents(Fixture f, String userId) {
        return f.eventService.findBetween(userId, Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2040-01-01T00:00:00Z")).size();
    }
}
