# CadenceAI — API Documentation

Covers the endpoints added for the AI assistant and personal events. The older HR endpoints
(`/auth`, `/users`, `/tasks`, `/leaves`, ...) are unchanged; you can browse all of them in
Swagger at `/swagger-ui/index.html`.

**Status:** the examples below are real output of the backend code run in a test harness with a
scripted Gemini (see `IMPLEMENTATION_STATUS.md`). None of it has been run against a live Gemini
API, a live MongoDB, or the real Spring server yet.

## Conventions

| | |
|---|---|
| Base URL | your deployed backend, e.g. `https://YOUR-APP.onrender.com` (HTTPS required for phones) |
| Authentication | `Authorization: Bearer <token>` on every call below. Get the token from `POST /auth/login`. |
| Who is calling | **Always taken from the token.** No request contains a user id, and none would be trusted. |
| Content type | `application/json` (UTF-8) |
| Times | ISO-8601 in UTC, e.g. `2026-11-22T11:30:00Z`. The assistant converts to/from the user's time zone. |
| Errors | `{"error": "message the user can read"}` with an HTTP status code (table at the end) |

---

## AI assistant

### `POST /ai/chat`

Send one user message. The server asks Gemini what the user wants, **validates** the answer, and
either does it, asks a question, or asks for confirmation.

Request:
```json
{ "message": "Remind me to complete my DSA assignment tomorrow at 5 PM.", "timeZone": "Asia/Kolkata" }
```
- `message`: required, 1–1000 characters.
- `timeZone`: the phone's IANA zone (`TimeZone.getDefault().id` on Android). Missing or invalid falls back to `UTC`.

Response (`GeminiChatResponse`):
```json
{
  "status": "COMPLETED",
  "reply": "Done! I've added \"DSA Assignment\" on Sun, Nov 22, 5:00 PM – 6:00 PM (I assumed it lasts 1 hour).",
  "action": "CREATE_EVENT",
  "events": [ { "id": "…", "title": "DSA Assignment", "category": "ASSIGNMENT", "priority": "HIGH",
                "startTime": "2026-11-22T11:30:00Z", "endTime": "2026-11-22T12:30:00Z",
                "description": null, "location": null, "source": "AI" } ],
  "pendingAction": null,
  "suggestions": []
}
```

| Field | Meaning |
|---|---|
| `status` | `COMPLETED` the action was carried out · `NEEDS_CONFIRMATION` waiting for yes/no · `NEEDS_CLARIFICATION` the assistant asked a question · `INFO` information only |
| `reply` | Text for the assistant's chat bubble |
| `action` | `CREATE_EVENT`, `UPDATE_EVENT`, `CANCEL_EVENT`, `QUERY_EVENTS` or `null` |
| `events` | Events the reply is about (created/updated event, query results; at most 25) |
| `pendingAction` | `{id, type, summary}` when `status` is `NEEDS_CONFIRMATION`, else `null` |
| `suggestions` | `[{start, end, label}]` free slots offered after a conflict |

#### What the assistant does (policy enforced by the server, not by Gemini)

| User intent | Behaviour |
|---|---|
| Create an event | Missing title/date/time → asks a question (never guesses). Past time → asks again. No duration given → 1 hour is assumed **and the reply says so**. No overlap → created immediately. Overlap → `NEEDS_CONFIRMATION` with alternative free times. |
| Query | Answered from the database by the server (Gemini never writes the answer). "Am I free at 4 PM?" checks overlaps. |
| Update | The target event is identified by id (verified to belong to the caller) or by a title hint. Zero or several matches → asks which. Moving the start keeps the duration. No overlap → done. Overlap → `NEEDS_CONFIRMATION`. |
| Cancel | **Always** `NEEDS_CONFIRMATION`; nothing is deleted until confirmed. |
| Not about scheduling | `INFO` with Gemini's short reply. |

Real example of a conflict (alice already has "DSA Assignment" 5:00–6:00 PM):
```json
{
  "status": "NEEDS_CONFIRMATION",
  "reply": "That overlaps with \"DSA Assignment\" (5:00 PM – 6:00 PM). Schedule \"Maths class\" on Sun, Nov 22, 4:00 PM – 5:00 PM?",
  "action": "CREATE_EVENT",
  "events": [],
  "pendingAction": { "id": "4f0c…", "type": "CREATE_EVENT", "summary": "Schedule \"Maths class\" on Sun, Nov 22, 4:00 PM – 5:00 PM" },
  "suggestions": [ { "start": "…", "end": "…", "label": "Sun, Nov 22 4:00 PM – 5:00 PM" }, … ]
}
```

**Answering a proposal.** Either call `POST /ai/confirm`, or send a plain `yes` / `no` as the next
chat message (a bare yes/no is recognised locally and does **not** call Gemini). Any other
message drops the pending proposal. A proposal expires after 10 minutes.

### `POST /ai/confirm`

Request:
```json
{ "pendingActionId": "4f0c…", "confirm": true, "timeZone": "Asia/Kolkata" }
```
Returns a `GeminiChatResponse` (`COMPLETED`, or `INFO` with "Okay, I won't do that." when `confirm` is false).
Only the **id** is sent: what happens is whatever the server stored when it made the proposal, and
the id only works for the user it was issued to. A second confirm of the same id returns `409`.

### `GET /ai/history`

```json
{ "messages": [ {"role":"USER","text":"…","timestamp":"…"}, {"role":"ASSISTANT","text":"…","timestamp":"…"} ],
  "pendingAction": null }
```
The caller's own conversation (last 20 messages; empty if idle for more than 24 hours).

### `DELETE /ai/conversation`
Deletes the caller's conversation. `204 No Content`. Events are not affected.

---

## Scheduling proposals

`POST /scheduling/proposals` takes a bearer token and an availability window with ISO-8601 instants. The entire window is available except for the caller's existing events. Example request body:

```json
{
  "availability": {"start":"2026-11-25T09:00:00Z","end":"2026-11-25T13:00:00Z"},
  "activities": [{"title":"Prepare report","minutes":60,"priority":"HIGH","deadline":"2026-11-25T10:30:00Z"}],
  "flexibleEventIds": []
}
```

The response contains `placements` with a proposed slot and alternatives, `movesToConfirm`, and `unplaced` activities. Only events explicitly listed as flexible can receive proposed moves. Their duration is preserved. A proposal makes no calendar writes.

To accept a move, send `{"request": <original request>, "move": <first movesToConfirm entry>}` to `POST /scheduling/confirm-move`. The server recalculates the proposal and rejects a stale move with HTTP 409. Refresh the proposal after each move. Save accepted new placements using `POST /events` with the placement title, priority, startTime and endTime. Confirmation and creation each write one event.

## Events (manual API)

These use the same service and the same ownership rules as the assistant.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/events?from=&to=` | Events overlapping the window (ISO UTC). Default: now to +30 days. Max range 366 days. |
| `GET` | `/events/{id}` | `404` if the event does not exist **or belongs to someone else** (same answer for both). |
| `POST` | `/events` | `201`. `409` if it overlaps another event even with `?force=true`. |
| `PUT` | `/events/{id}` | Replaces the editable fields. Moving into an occupied slot returns `409`. |
| `DELETE` | `/events/{id}` | `204`. |

Create/replace body (`EventRequestDto`):
```json
{ "title": "Maths class", "description": null, "category": "CLASS", "priority": "MEDIUM",
  "startTime": "2026-11-23T04:30:00Z", "endTime": "2026-11-23T05:30:00Z", "location": "Room 5" }
```
`title` (≤120) and `startTime` are required. `category`: `CLASS, MEETING, PERSONAL, WORK, ASSIGNMENT, OTHER`
(default `OTHER`). `priority`: `LOW, MEDIUM, HIGH` (default `MEDIUM`). Missing `endTime` means 60 minutes.
`endTime` must be after `startTime`, at most 24 hours later.

---

## Limits (all enforced by the server)

| Limit | Value |
|---|---|
| Chat message length | 1000 characters |
| AI requests per user | 10 per minute, 300 per day (`AI_RATE_LIMIT_PER_MINUTE`, `AI_RATE_LIMIT_PER_DAY`); counters live in server memory |
| Pending proposal lifetime | 10 minutes |
| Stored conversation | last 20 messages; reset after 24 h idle; MongoDB deletes it 7 days after the last update |
| Context sent to Gemini | last 8 messages + the caller's events for the next 30 days (max 60) |
| Events per user | 2000 |
| Event length / distance | at most 24 h long; at most 5 years ahead (assistant) |
| Schedule query range | at most 62 days, at most 25 events listed |

## Error responses

| Status | When | Example `error` |
|---|---|---|
| 400 | Invalid input (empty/too long message, bad event fields) | `Please type a message.` |
| 401 / 403 | Missing, expired or invalid token / not allowed | produced by the security filter (body not guaranteed) |
| 404 | Event not found (or not yours) | `Event not found` |
| 409 | Nothing to confirm / already confirmed / simultaneous request / manual event overlaps | `There is nothing waiting for your confirmation. It may have expired.` |
| 422 | Gemini's safety system refused the request | `I can't help with that request.` |
| 429 | You are sending too fast, or Google's quota is exhausted | `You're sending messages too quickly. Please wait a moment and try again.` |
| 500 | Unexpected server/database problem | `Something went wrong while processing your request. Please try again.` |
| 502 | Gemini returned unusable output twice | `I couldn't understand the AI's answer. Please rephrase your request and try again.` |
| 503 | `GEMINI_API_KEY` missing/invalid, model unavailable, or Gemini overloaded | `The AI assistant is not configured on the server yet.` |
| 504 | Gemini did not answer in time (30 s) | `The AI service did not respond in time. Please try again.` |

Error messages never contain stack traces, API keys, database details or raw Gemini output;
technical details go to the server log only.
