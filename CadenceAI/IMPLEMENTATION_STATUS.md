# CadenceAI — Implementation Status

Last updated: Stage 2 (Gemini AI assistant + personal events).

## Verification status: read this first

| Part | What was actually done | What was NOT done |
|---|---|---|
| Backend AI module (39 new main files, 8 test files) | **Compiled** with the JDK compiler and **111 tests executed and passed** in a sandbox harness. I also broke 4 safety rules on purpose (a "mutation check") and confirmed the tests failed each time. | **Not built with Maven.** Spring, Jackson, Lombok, JUnit and MongoDB were replaced by small stand-ins I wrote, so real Spring wiring, JSON (de)serialisation, Mongo query/index behaviour and the security filter chain have **never run**. |
| Gemini HTTP call | Request/response handling written against Google's current docs; error mapping and parsing tested with fake responses. | **No real call to Gemini was ever made** (no network, no key here). The first live call may reveal a field-name difference. |
| Android (4 new files, 3 edited) | Static checks only: imports, brackets, and a field-by-field comparison of every backend DTO with its Kotlin model and every endpoint path. | **Not compiled.** No Kotlin compiler, Gradle or SDK here. Expect to fix a few compile errors on first build; send them to me. |
| Stage 1a edits (secrets/config) | Reviewed by reading and brace-balance only. | Not compiled with real Gradle/Maven. |

The first real build must happen on your machine (Android Studio for the app, Maven/JDK 21 for the
backend). See `SETUP_INSTRUCTIONS.md`.

---

## 1. Technical report: what the two repositories really contain

### 1.1 Stack

| | Android app (`SmartHR-App-master`) | Backend (`SmartHR-Backend-test`) |
|---|---|---|
| Language | Kotlin | Java 21 |
| Framework | Jetpack Compose (Material 3), MVVM | Spring Boot 3.5.3 |
| Build | Gradle 8.13, AGP 8.12.1, Kotlin 2.2.0 | Maven (wrapper included) |
| Networking | Retrofit 2.9.0 + Gson, OkHttp (WebSocket) | Spring Web, Spring WebSocket (STOMP) |
| Storage | DataStore (token + user JSON) | MongoDB (Spring Data MongoDB) |
| Auth | Sends `Authorization: Bearer <JWT>` | Spring Security, JWT (jjwt 0.12), BCrypt, Google ID-token verification |
| Other | play-services-location, play-services-auth, Coil, Lottie | ModelMapper, Lombok, springdoc (Swagger), OkHttp, Mail, Thymeleaf |
| Size | about 19,800 lines of Kotlin | 12 controllers, 10 repositories |

Android: `minSdk 26`, `compileSdk/targetSdk 35`, `applicationId = com.example.smarthr_app`.
Backend: port 9090 by default, Dockerfile present (builds a JAR and runs it on JDK 21),
GitHub Actions workflow that triggers a Render deploy.

### 1.2 What exists and works by reading the code (not yet run)

- **Registration and login** with email/password: `POST /users`, `POST /auth/login`. Passwords are
  hashed with BCrypt. JWT lasts 5 days and holds `userId` and `role`.
- **JWT filter** validates every request and the controllers get the user ID **from the token**,
  not from the request body. This is the right pattern and is reused for everything new.
- Registering as `ROLE_USER` without a company code works (users are saved with no company).
- Android token storage (DataStore), login state restore at startup, Retrofit setup.
- Local notification helper (`showNotification`) and a notification channel.
- Location helper (`LocationHelper`): reads the last known location, computes distance.
- Profile view/edit and profile image upload (needs Supabase configuration, see below).

### 1.3 What does NOT exist (although names or the README suggest it)

| Claim | Reality |
|---|---|
| "Geofencing-based check-in/out" | **No geofencing.** There is no `GeofencingClient`, no broadcast receiver, no background service. "Attendance" is a button: the user taps it, the app reads the last known location once, checks the distance to the office, then calls `POST /attendances`. |
| "Google Maps integration" | **No map in the app**, no Maps SDK, no API key. The office screen asks the user to paste latitude/longitude copied from Google Maps. |
| "Google Calendar API — meeting syncing" (backend README) | No calendar code anywhere in the backend. |
| Notifications | Only chat-message notifications (triggered from a WebSocket message). No reminders, no scheduling, no push (FCM). |
| Gemini / AI, PDF extraction, OCR | None. No dependency for any of them. |
| Personal events/calendar | None. "Meetings" can only be created by HR users and are invitations to company employees. |

### 1.4 Things that are tied to the HR product and must be bypassed

- Roles `ROLE_HR` / `ROLE_USER` / `ROLE_ADMIN`; a company model (`companyCode`, waitlist, approval).
- `Attendance` and `Office` are keyed by `companyCode`. A user without a company gets the error
  "Employee is not connected to any company". There is **one office per company** and no location name.
- Attendance allows **one check-in/check-out pair per day**, using UTC day boundaries (for India this
  splits the day at 05:30 IST).
- The Android navigation starts at role selection and has separate HR and Employee dashboards,
  tasks, leaves, chat, company management (about 25 screens).

### 1.5 API communication

Android `ApiService.kt` (Retrofit interface) calls the backend over HTTPS with the token in an
`Authorization` header on each call. Endpoints used by the app today:
`/users`, `/auth/login`, `/auth/googleLogin`, `/auth/googleSignUp`, `/companies/*`, `/tasks/*`,
`/comments/*`, `/leaves/*`, `/offices`, `/attendances`, `/attendances/history`,
`/attendances/company`, `/chats/*`, `/meetings/*`, plus a chat WebSocket at `/chat-websocket`.
I compared the attendance, office and meeting endpoints between Android and the backend
controllers and they match; I did not compare the task, leave, company and chat endpoints.

### 1.6 Database

MongoDB, collections: `users`, `companies`, `attendances`, `offices`, `meetings`, `tasks`,
`comments`, `leaveRequests`, `chats`. Documents reference each other by string IDs (no joins).
User isolation relies on filtering by the user ID taken from the JWT.

### 1.7 Build facts and risks (not yet verified by building)

- `app/build.gradle.kts` still contains `composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }`
  although Kotlin 2.2 uses the Compose compiler Gradle plugin. Probably ignored; if the build
  complains, delete that block.
- OkHttp versions are mixed (Retrofit 2.9.0 pulls OkHttp 3.x, logging interceptor 4.12.0, explicit
  `okhttp:5.1.0`). Gradle picks the highest. It built for the original author, so it is probably fine.
- There are two theme packages (`presentation/theme` and `ui/theme`).
- Debug APK needs: JDK 17+ (Android Studio's bundled JDK is fine), Android SDK 35, internet for the
  first Gradle sync.
- `HrmsApplicationTests.contextLoads` needs a reachable MongoDB and the environment variables below;
  it will fail without them. Build with `-DskipTests` until you have them.

### 1.8 Licences

**Neither repository contains a LICENSE file.** `pom.xml` has an empty `<license/>` tag. With no
licence, the authors keep all rights by default, so you do not automatically have permission to
modify and distribute their code. For a private class demo this is probably fine, but get written
permission from the author (or confirm you are the author) before publishing the app or using it
commercially.

---

## 2. Security problems found (and what was done)

| # | Problem | Status |
|---|---|---|
| 1 | `application.properties` had a **real MongoDB Atlas connection string with password**, the **JWT signing secret**, and a **commented-out Gmail app password** committed in plain text. | Moved to environment variables (done). **You must treat these as leaked: they are in the files you uploaded and likely in the GitHub history. Never reuse them.** Create your own database and secrets. |
| 2 | `.github/workflows/deploy.yml` contained a Render **deploy hook key**. | Replaced by a GitHub secret `RENDER_DEPLOY_HOOK_URL` (done). Rotate the old one if it is yours. |
| 3 | `SuperbaseImageStorageServiceImpl` had a **Supabase URL and anon key hardcoded**: profile images would be uploaded into the original author's storage. | Now read from `SUPABASE_URL` / `SUPABASE_ANON_KEY` (done). |
| 4 | Android `BASE_URL`, the chat WebSocket URL and the backend `PingScheduler` all pointed at the **original author's Render server**. Your users' tokens would have gone to a third party. | All three are now configurable and nothing points to the old server (done). |
| 5 | `JwtAuthenticationFilter` logged the full `Authorization` header and token at INFO, and Spring Security logged at TRACE. | Token logging removed, level set to INFO (done). Android HTTP body logging is now debug-builds only and redacts the `Authorization` header (done). Note: in a *debug* APK, login request bodies (including the password) still appear in logcat. |
| 6 | **Anyone can register as HR**: the registration request carries `role` and the server accepts `ROLE_HR`. | **Not fixed yet.** Planned: server forces `ROLE_USER`. |
| 7 | `SecurityConfig` rules do not match the endpoints: `POST /users/**` and `PATCH /users/**` are `permitAll` before the role rules; the attendance rules say `/attendance` but the real path is `/attendances`, so `GET /attendances/company` is only protected by "authenticated" instead of "HR only". | **Not fixed yet.** Planned: rewrite the rules for the personal-user model when the company endpoints are removed from use. |
| 8 | Google Sign-In uses the original author's Google OAuth client ID (`strings.xml`, backend `web.client.id`). It will not work for your app/keystore. | **Not fixed.** Plan: use email/password for the demo and hide Google sign-in. Setting your own OAuth client is possible but takes time. |
| 9 | Token stored in plain DataStore and `allowBackup="true"`. | **Not fixed.** Planned: `allowBackup=false`. |

---

## 3. Engineering decisions (made independently, per your instructions)

1. **Keep MongoDB, Spring Boot, JWT, Retrofit, Compose.** No new language, no microservices.
2. **Do not delete the HR code.** Hiding it from navigation is faster and lower risk than deleting
   about 13,800 lines (dashboard and chat screens) without a compiler. Dead screens stay in the source but unreachable.
3. **Change only the `applicationId`** (to `com.cadenceai.app`), not the Kotlin package names.
   Renaming packages across 19,800 lines without a compiler is the single riskiest edit possible.
4. **New code is built next to the old code**, not by bending HR concepts:
   new `Event`, `SavedLocation`, `AttendanceLog`, `UploadedDocument` collections, each with a
   `userId` taken from the JWT.
5. **Gemini is called from the backend over plain HTTPS (OkHttp/RestTemplate, already in the project)**
   so no SDK is added. Model and key come from `GEMINI_MODEL` / `GEMINI_API_KEY`. I will check the
   current model names before choosing a default, because they change.
6. **The AI never touches the database.** Gemini returns a JSON "action" (create / update / cancel /
   query / ask-for-clarification); the backend validates every field, checks ownership and conflicts,
   then calls normal service methods.
7. **PDF text extraction with Apache PDFBox** (one new library). Images go straight to Gemini
   (multimodal), so no OCR library is needed.
8. **Geofencing must be built, not reused**: Google Play Services `GeofencingClient` (already
   included in `play-services-location`) plus a `BroadcastReceiver`, with the background-location
   permission flow.
9. **Map: no paid API.** MVP is "use my current location" + name + radius. A tap-on-map picker via
   osmdroid (OpenStreetMap, no key) is a stretch goal.
10. **Notifications:** local reminders (no Firebase), scheduled with Android's AlarmManager/WorkManager.

---

## 4. Plan and status

| Priority | Item | Status |
|---|---|---|
| 1 | Inspect both repos, write report | **Done** |
| 1 | Remove hardcoded secrets / third-party hosts, configurable backend URL | **Done (uncompiled)** |
| 1 | Get both projects building on your machine | **Needs you** (I cannot build here) |
| 1 | Rebrand (name, icon, splash, login/register, colours), single-user registration, force `ROLE_USER` | Next |
| 1 | CadenceAI dashboard (today, upcoming, attendance status, quick links) | Next |
| 2 | `Event` backend (model, service, REST API, per-user) | **Done** (tested in harness; not built with Maven) |
| 2 | Android calendar / event screens (manual scheduling UI) | Not started |
| 2 | Gemini assistant: create / query / update / cancel, conflicts, clarification, chat UI | **Done** (backend tested in harness; Android uncompiled; never run against live Gemini) |
| 3 | PDF extraction + review-and-confirm screen; image via Gemini | Not started |
| 3 | Saved locations (name, radius), real geofencing, attendance history | Not started |
| 4 | Reminders / notifications | Not started |
| 4 | Final UI polish, tests, APK, docs (`README`, `API_DOCUMENTATION`) | Not started |

## 5. Stage 1a change log (files changed)

Backend (`SmartHR-Backend-test`):
- `src/main/resources/application.properties` — all secrets now `${ENV_VAR}`; port from `PORT`.
- `.../security/JwtAuthenticationFilter.java` — no more token logging.
- `.../services/superbaseImageStorageService/SuperbaseImageStorageServiceImpl.java` — URL/key from config, clear error if missing.
- `.../utils/PingScheduler.java` — pings only a URL you configure (`KEEPALIVE_URL`); does nothing if unset.
- `.github/workflows/deploy.yml` — deploy hook moved to a GitHub secret.

Android (`SmartHR-App-master`):
- `app/build.gradle.kts` — `backendUrl` Gradle property, generates `BuildConfig.BACKEND_URL`, enables `buildConfig`.
- `data/remote/RetrofitInstance.kt` — base URL from `BuildConfig`, safer logging.
- `utils/chat/ChatWebSocketClient.kt` — WebSocket URL derived from the same setting.

## 6. Stage 2 report: Gemini AI assistant

### 6.1 Features implemented

- **Natural-language creation**: "Remind me to complete my DSA assignment tomorrow at 5 PM" becomes an event
  (title, date, time, optional duration/priority/category/location/description).
- **Schedule queries**: list a day or range, "am I free at 4 PM?", upcoming events, keyword/category filters.
  Answers are written by the **backend from database rows**, never by the model.
- **Update** and **cancel** with safe event identification (id verified against the caller, else title hint;
  zero or several matches become a question).
- **Cancel always asks for confirmation**; create/update ask only when they would overlap another event.
- **Conflict detection** with up to 3 suggested free times (08:00–21:00, nearest first, never in the past).
- **Clarification** instead of guessing (missing title/date/time, ambiguous event, bad dates, past times).
- **Follow-ups** ("Move it to 7 PM") through a per-user stored conversation (last 20 messages, 8 sent to
  Gemini, reset after 24 h idle, deleted by MongoDB after 7 days) and a "last event" reference.
- **Security**: user id only from the verified JWT principal; every query and write filtered by user id;
  foreign ids look like "not found"; pending actions stored server-side and bound to the user; optimistic
  locking so a double-tapped Confirm cannot run twice; per-user rate limiting; input limits; safe error messages.
- **Android chat screen**: bubbles, send button, loading bubble, error bubbles, Confirm/Cancel card,
  "Action completed" marker, history restored from the server, clear-chat, entry button on the dashboard.
- **Events REST API** (`/events`) using the same service, so you can inspect data without a calendar screen.

### 6.2 Decisions I made (and why)

| Decision | Reason |
|---|---|
| Endpoints are `/ai/...` and `/events`, not `/api/ai/...` | Every existing endpoint in this backend has no `/api` prefix; consistency beats the example in the brief. |
| Gemini model default `gemini-3.8-flash`, overridable by `GEMINI_MODEL` | Checked against Google's models page on 21 Nov 2026: stable, structured-output capable. `gemini-2.5-*` are deprecated. |
| The model's `requiresConfirmation` field is **not** in the schema | The server decides what needs confirmation; letting the model decide would mean trusting it. |
| Confirmation policy: cancel always; create/update only on conflict | Matches "require confirmation before destructive operations" without making every create a two-step chore. A one-line change in `GeminiActionService` makes creates always ask. |
| A new event with no duration is stored as 60 minutes and the reply says so | Conflict detection needs an end time; the assumption is disclosed, not hidden. |
| Pure `yes` / `no` while a proposal is pending is handled locally | Cheaper, faster, and cannot be misread by the model. |
| `RestTemplate` with explicit timeouts, no Google SDK | Reuses what the project has; the shared `RestTemplate` bean has no timeouts. |
| Events and conversations are stored in new collections `events` and `aiConversations` | Keeps the HR data model untouched. |
| `spring.data.mongodb.auto-index-creation=true` | Needed for the unique and TTL indexes; nothing else in the project declared indexes. |

### 6.3 Files

**New (51):**
- Backend main (39): `configs/AiConfig`; `controllers/{GeminiController, EventController}`;
  `dtos/ai/{AiAction, AiStatus, AiHistoryResponse, AiMessageDto, GeminiActionResponse, GeminiChatRequest, GeminiChatResponse, GeminiConfirmRequest, PendingActionDto, SuggestionDto}`;
  `dtos/entityDtos/Event/{EventRequestDto, EventResponseDto}`; `exceptions/{AiServiceException, AiExceptionHandler}`;
  `models/{Event, AiConversation, AiMessage, PendingAiAction}`; `models/enums/{EventCategory, EventPriority}`;
  `repositories/{EventRepository, AiConversationRepository}`;
  `services/aiService/{ActionOutcome, AiChatService, AiPromptContext, AiRateLimiter, AiText, GeminiActionService, GeminiClient, GeminiRestClient, GeminiService, ScheduleConflictService, Slot}`;
  `services/eventService/{EventServiceInterface, EventServiceImpl}`; `utils/CurrentUser`.
- Backend tests (8), in `src/test/java/com/hrms/backend/services/aiService/`: `AiTestSupport`, `AiChatServiceTest`,
  `AiRateLimiterTest`, `EventServiceTest`, `GeminiActionServiceTest`, `GeminiRestClientTest`, `GeminiServiceTest`,
  `ScheduleConflictServiceTest`.
- Android (4): `data/model/AiModels.kt`, `data/repository/AiRepository.kt`, `presentation/viewmodel/AiViewModel.kt`,
  `presentation/screen/ai/AiAssistantScreen.kt`.

**Modified (5):** backend `configs/SecurityConfig.java` (two rules for `/ai/**` and `/events/**`),
`resources/application.properties` (Gemini, rate-limit, index settings); Android `data/remote/ApiService.kt`
(4 endpoints), `presentation/navigation/NavGraph.kt` (route, ViewModel, logout reset),
`presentation/screen/dashboard/employee/EmployeeDashboardScreen.kt` (AI button + callback).

### 6.4 Test results (executed in the sandbox harness: **111 passed, 0 failed**)

| Area you asked for | Where it is tested | Executed? |
|---|---|---|
| 1. Successful Gemini communication | `GeminiServiceTest` (parsing, prompt), `GeminiRestClientTest` (request shape, response extraction) | Yes, with fake responses. **A real call to Gemini: no.** |
| 2. Task creation | `GeminiActionServiceTest`, `AiChatServiceTest.naturalLanguageCreationEndToEnd` | Yes |
| 3. Event retrieval | list, multi-day, keyword/category, availability tests | Yes |
| 4. Updating | move time (keeps duration), date-only, details-only, hint matching, follow-up "move it" | Yes |
| 5. Cancellation | confirmation required, buttons, no double execution, already-deleted | Yes |
| 6. Scheduling conflicts | `ScheduleConflictServiceTest` + conflict/confirm flows | Yes |
| 7. Clarification requests | missing date/time/title, bad formats, past, ambiguous event | Yes |
| 8. Unauthorized access | `CurrentUser` rejects a missing/foreign principal; rate limiting | Partly. **The HTTP 401 from the JWT filter was not tested** (needs the real Spring context). |
| 9. Cross-user isolation | prompt contents, queries, updates/cancels with foreign ids, forged pending action, foreign confirm | Yes |
| 10. Invalid Gemini responses | garbage, wrong action, empty, malformed JSON, code fences, unknown fields | Yes |
| 11. API failures | HTTP error mapping, outage, blocked prompt, database failure hidden | Yes |

Mutation check (control run: 111 passed, 0 failed). Four deliberate bugs, each in a scratch copy of the code:
conflict check ignoring "exclude itself" made 2 tests fail; the Gemini prompt built from another user's events made 1 fail
(`usersNeverSeeEachOthersEvents...`: this property is guarded by a single test, so it is worth adding more if you extend
the prompt code); past times being accepted made 3 fail; cancel executing without confirmation made 7 fail.

**Not executed / not written:** Maven/Surefire run; Spring context or MockMvc tests (HTTP status codes, JSON
shape, security filter); MongoDB-backed repository tests (derived query names, indexes, `@Version`);
Android unit/UI tests and Android compilation; any live Gemini call.

### 6.5 Remaining work and known limitations

- **Never run for real**: first Maven build, first live Gemini call and first Android build are all still ahead of you.
- **No recurring events.** The assistant says so and offers the next occurrence only. "Pending tasks" means
  upcoming events: there is no completion tracking.
- **The assistant button is on the employee (`ROLE_USER`) dashboard only.** The HR dashboard has none. The
  proper "Home / Calendar / AI Assistant / Attendance / Profile" navigation comes with the rebrand stage.
- **No calendar screens yet**: events are visible through the assistant and `GET /events` only.
- The Android chat shows text only (no event cards or tappable time chips; suggestions are in the reply text).
- Rate limiter counters are in server memory (reset on restart, not shared across instances).
- One conversation per user. Two simultaneous requests from one user: the second may get `409`, by design.
- Prompt injection: event titles are user data placed in the prompt. Mitigations: newlines stripped, text marked as
  data, all effects validated server-side, deletions confirmed. A determined user can still only affect their own data.
- Free-tier quotas of the chosen Gemini model are not known to me; a `429`/`503` from the assistant may be quota.
- Still open from Stage 1: anyone can register as HR; `SecurityConfig` rules do not match some HR endpoints;
  Google Sign-In uses someone else's OAuth client; token stored in plain DataStore.

## 7. Known limitations of the project as a whole

- Geofencing, map, documents (PDF/image), reminders/notifications, rebrand and dashboard: not built yet.
- The app will not reach any server until you set `backendUrl` (deliberate placeholder).
- The backend will not start until `MONGODB_URI` and `JWT_SECRET` are set (deliberate).
