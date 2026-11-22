# Learn CadenceAI

> Honesty note: Chapter 1 describes code nobody has run yet. Chapter 4's backend code was compiled and its 111 tests were executed in a sandbox harness (not Maven, no real Gemini, no real Spring); its Android code was not compiled. Where I say "expect", it is reasoning from the code, not an observed result.

A guide to how this app really works, written as we build it. **Only code that exists is
explained here.** New chapters are added when features are actually built.

| Chapter | Status |
|---|---|
| 1. The big picture and how login works (the starting app) | **Written** |
| 2. Rebrand, single-user mode, dashboard | Not built yet |
| 3. Events (manual scheduling) | Backend written (model, service, REST API); Android calendar screens not built yet |
| 4. Gemini AI assistant | **Written** |
| 5. Document upload and extraction | Not built yet |
| 6. Locations, geofencing, attendance | Not built yet |
| 7. Notifications | Not built yet |
| 8. Deployment and APK | Partly in `SETUP_INSTRUCTIONS.md` |

---

# Chapter 1 — The big picture, and how login works

## 1.1 Two programs that talk over the internet

CadenceAI is **two separate programs**:

```
 ┌──────────────────────────┐   HTTPS requests (JSON)   ┌──────────────────────────┐      ┌──────────────┐
 │  ANDROID APP (frontend)  │ ───────────────────────►  │  SPRING BOOT (backend)   │ ───► │   MongoDB    │
 │  Kotlin + Compose        │ ◄───────────────────────  │  Java                    │ ◄─── │  (database)  │
 │  runs on the phone       │   HTTPS responses (JSON)  │  runs on a server        │      │              │
 └──────────────────────────┘                           └──────────────────────────┘      └──────────────┘
```

- **Frontend** = what the user sees and touches. It runs on the phone. It must not be trusted:
  anyone can modify an app or send fake requests.
- **Backend** = the program on a server that holds the rules and the data access. It decides what is
  allowed. Secrets (database password, AI key) live here and never in the app.
- **API** = the list of "doors" (URLs like `POST /auth/login`) the backend offers. The app calls them.
- **Database** = where data is stored permanently. Only the backend talks to it; the app never does.
- **HTTP request / response** = one question and one answer. A request has a method (`GET` read,
  `POST` create, `PUT`/`PATCH` change, `DELETE` remove), a URL, headers (small labels such as
  `Authorization`) and a body. The response has a status code (200 OK, 401 not logged in, 403 not
  allowed, 404 not found…) and a body.
- **JSON** = text format for data, e.g. `{"email":"a@b.com","password":"secret1"}`. Each side turns
  JSON into its own objects: Kotlin data classes on the phone, Java classes on the server.

## 1.2 Layers inside each program

**Backend (Java, Spring Boot)** — `SmartHR-Backend-test/src/main/java/com/hrms/backend/`

| Layer | Folder | Job | Example (real) |
|---|---|---|---|
| Controller | `controllers/` | Receives the HTTP request, hands it on, returns the response. No business rules. | `AuthenticationController.java` |
| Service | `services/` | The business logic: rules, checks, decisions. | `attendanceService/AttendanceServiceImpl.java` |
| Repository | `repositories/` | Talks to the database (find, save, delete). | `UserRepository.java` |
| Model/Entity | `models/` | A Java class that matches a stored document. | `User.java` -> `users` collection |
| DTO | `dtos/` | Shapes of data going in/out of the API (so you never expose, say, a password hash). | `CreateUserRequestDto.java` |
| Security | `security/`, `configs/SecurityConfig.java` | Checks who is calling and what they may do. | `JwtAuthenticationFilter.java` |

Rule of thumb: **Controller = receptionist, Service = the person who knows the rules,
Repository = the clerk who fetches files.**

**Android app (Kotlin, Compose)** — `SmartHR-App-master/app/src/main/java/com/example/smarthr_app/`

| Layer | Folder | Job | Example (real) |
|---|---|---|---|
| Screen (UI) | `presentation/screen/` | Composable functions that draw the screen from state. | `auth/LoginScreen.kt` |
| ViewModel | `presentation/viewmodel/` | Holds the screen's state, survives screen rotation, starts the work. | `AuthViewModel.kt` |
| Repository | `data/repository/` | Calls the API and saves results on the phone. | `AuthRepository.kt` |
| API interface | `data/remote/ApiService.kt` | One line per backend endpoint (Retrofit). | `@POST("auth/login")` |
| Retrofit setup | `data/remote/RetrofitInstance.kt` | Builds the HTTP client; knows the backend URL. | `BuildConfig.BACKEND_URL` |
| Local storage | `data/local/DataStoreManager.kt` | Remembers the token and user on the phone. | `saveToken(...)` |
| Navigation | `presentation/navigation/NavGraph.kt` | Which screen leads to which. | `composable(Screen.Login.route)` |

## 1.3 Authentication vs authorization

- **Authentication** = *who are you?* (login with email + password proves it).
- **Authorization** = *what are you allowed to do?* (e.g. "you may read **your own** events only").

CadenceAI needs both: log in, and then every request is checked so users only touch their own data.

## 1.4 A real example: what happens when you tap "Login"

```
LoginScreen.kt            you tap the button
   ↓ calls
AuthViewModel.login()     sets state "Loading", starts a coroutine
   ↓ calls
AuthRepository.login()    calls the API, saves the result
   ↓ Retrofit builds an HTTP request from ApiService.login()
══════════════ internet ══════════════   POST /auth/login   {"email":..., "password":...}
AuthenticationController.login()   (Spring Boot)
   ↓ doAuthenticate(): Spring Security loads the user and compares BCrypt password hashes
   ↓ JwtHelper.generateToken(): creates a signed token
   ↓ returns JwtResponse {token, user}
══════════════ internet ══════════════   200 OK   {"token":"eyJ...", "user":{...}}
AuthRepository.login()    saves token + user with DataStoreManager
   ↓ returns Resource.Success
AuthViewModel             publishes the new state
LoginScreen.kt            sees Success -> NavGraph moves to the dashboard
```

### Step by step with the real code

**1. The API definition on Android** — `data/remote/ApiService.kt`
```kotlin
@POST("auth/login")
suspend fun login(@Body request: LoginRequest): Response<AuthResponse>
```
Retrofit turns this one declaration into a real HTTP call. `@Body` means "send this object as
JSON". `suspend` means it runs in a coroutine, so the screen does not freeze while waiting.

**2. The repository** — `data/repository/AuthRepository.kt`, function `login` (line ~49)
It calls `RetrofitInstance.api.login(request)`. If `response.isSuccessful`, it builds a `User` object
from the reply and runs:
```kotlin
dataStoreManager.saveUser(user)
dataStoreManager.saveToken(authResponse.token)
```
That is how the phone **remembers** you are logged in, even after closing the app.
It returns `Resource.Success(...)` or `Resource.Error(...)` (`utils/Resource.kt`), a small wrapper so
the screen can handle Loading, Success and Error uniformly.

**3. The ViewModel** — `presentation/viewmodel/AuthViewModel.kt`, function `login` (line 53)
```kotlin
fun login(request: LoginRequest) {
    viewModelScope.launch {
        _authState.value = Resource.Loading()
        _authState.value = authRepository.login(request)
    }
}
```
`_authState` is the **state** the screen watches. When its value changes, Compose automatically
redraws the screen. The ViewModel contains no UI code, only state and logic.

**4. The backend controller** — `controllers/AuthenticationController.java`, method `login`
```java
@PostMapping("/login")
public ResponseEntity<JwtResponse> login(@RequestBody JwtRequest jwtRequest) {
    this.doAuthenticate(jwtRequest.getEmail(), jwtRequest.getPassword());
    User user = (User) userDetailsService.loadUserByUsername(jwtRequest.getEmail());
    String token = jwtHelper.generateToken(user, user.getRole().name());
    ...
}
```
`@RequestBody` turns the incoming JSON into a Java `JwtRequest` object. `doAuthenticate` asks
Spring Security to check the email/password: it loads the user from MongoDB through
`services/CustomeUserDetailsService.java` and compares the password with the stored **BCrypt hash**
(passwords are never stored as plain text). A wrong password makes Spring Security throw
`BadCredentialsException`. There is no custom handler for it in `GlobalExceptionHandler.java`, so I
expect the request to end as a 401 through `security/JwtAuthenticationEntryPoint.java`, but I have
**not run it** to confirm; check this yourself in the test below.

**5. The token (JWT)** — `security/JwtHelper.java`
A JWT is a text string with three parts: *what it says* (here: `userId` and `role`), *when it
expires* (5 days), and a *signature* made with the server's secret (`jwt.secret`, which comes from the
`JWT_SECRET` environment variable). The server can later check the signature and know the token is
genuine and unmodified. Nobody can change the `userId` inside without breaking the signature.
Think of it as a **wristband at a festival**: it proves you passed the entrance, so you don't
show your ID again at every stall.

**6. Using the token on later requests**
Every protected call from the app carries the header `Authorization: Bearer <token>`
(you can see `@Header("Authorization") token: String` throughout `ApiService.kt`).
On the server `security/JwtAuthenticationFilter.java` runs **before any controller**:
it reads the header, verifies the signature and expiry, loads the user, and tells Spring Security
"this request is from user X". If the token is missing, forged or expired, the request is stopped
with a 401 before it reaches your code.

**7. The most important safety rule in this project**
Look at `controllers/UserController.java`:
```java
@GetMapping
public ResponseEntity<UserResponseDto> getUserProfile(@RequestHeader("Authorization") String authHeader){
    String userId = jwtHelper.getUserIdFromToken(authHeader.substring(7));
    return new ResponseEntity<>(userServiceInterface.getUserById(userId), HttpStatus.OK);
}
```
The user ID comes **from the verified token**, never from something the app sends such as
`/users?id=123`. If the app could send its own ID, anyone could read anyone's data by changing a
number. **Every new CadenceAI endpoint (events, locations, attendance, documents) will follow this
exact pattern**, and that is how "each user has independent data" will be guaranteed.

## 1.5 How to test what you just learned

1. Start the backend (see `SETUP_INSTRUCTIONS.md`) and open `http://localhost:9090/swagger-ui/index.html`.
2. Try `POST /users` (register) and then `POST /auth/login`. Registration needs: `name` (3–50
   characters), `email`, `phone` (country code + 10 digits, e.g. `+919876543210`), `password` (6+
   characters with a number) and `role` (`ROLE_USER`).
3. Copy the `token` from the login response. This project has no "Authorize" button in Swagger,
   but `GET /users` shows an `Authorization` header box under "Try it out". Type
   `Bearer ` (with the space) followed by the token, then Execute: you should get **your own** profile.
4. Repeat step 3 with the header empty: you should get 401 (the JWT filter and entry point stop the
   request before the controller). Also try a wrong password on `/auth/login` and note which status
   code you get; I expect 401 but have not run it.
5. Optional: paste the token on https://jwt.io to see `userId` and `role` inside it.
   (Anyone can *read* a JWT; only the server can *sign* one. Never put secrets inside it.)

## 1.6 Learning report — Stage 1a

**What we did:** inspected both projects and removed hardcoded secrets and links to someone else's
server. No new feature yet.

**Why it matters for learning:** a very common real-world mistake is committing passwords into
the code. `application.properties` now uses `${MONGODB_URI}` and `${JWT_SECRET}`. Spring reads
**environment variables** at start-up, so the code is safe to share while secrets stay on the
server. On Android the backend address now comes from the Gradle property `backendUrl`, which is
turned into `BuildConfig.BACKEND_URL` when the app is compiled.

**Files you should open and read:**
- Backend: `application.properties`, `AuthenticationController.java`, `JwtHelper.java`, `JwtAuthenticationFilter.java`
- Android: `RetrofitInstance.kt`, `ApiService.kt`, `AuthRepository.kt`, `AuthViewModel.kt`

**Check you understand before the next stage:**
1. Why does the app send a token instead of an email and password on every request?
2. Why does the server take the user ID from the token and not from the request?
3. Which layer should contain the rule "an event may not overlap another one": controller, service or repository? (Answer: the service.)
4. Why can the Gemini API key never go inside the Android app? (Anyone can unpack an APK and read it.)


---

# Chapter 4 — The Gemini AI assistant

## 4.1 The one idea that matters most

> **The AI proposes. The backend decides.**

Gemini is a language model: it is very good at understanding "Schedule my maths class tomorrow at
10 AM", and it can also be wrong, confused, or tricked by what a user types. So Gemini is **never**
allowed to touch the database. It only returns a small piece of JSON that says what it *thinks* the
user wants. Our own Java code then checks that JSON like a strict border guard and, only if
everything is valid, performs the action through the normal event service.

```
 Gemini says:  { "action": "CREATE_EVENT", "title": "Maths class", "date": "2026-11-22", "startTime": "10:00", ... }
                          │  just text, a proposal
                          ▼
 Our code:  valid date? valid time? in the past? overlapping another event?
            does that event id belong to THIS user?  does this need a confirmation?
                          │  only now
                          ▼
 EventServiceImpl.create(userId, ...)  ──►  EventRepository.save(...)  ──►  MongoDB
```

## 4.2 The complete journey of one message

User types: **"Schedule my mathematics class tomorrow at 10 AM."**

```
ANDROID (Kotlin)
  AiAssistantScreen.kt      Send button      -> viewModel.sendMessage(input)
  AiViewModel.kt            shows the user's bubble + "Thinking…", then
  AiRepository.kt           reads the saved token, calls Retrofit
  ApiService.kt             @POST("ai/chat")  ->  HTTPS request with  Authorization: Bearer <token>
═══════════════════════════════ internet ═══════════════════════════════
SPRING BOOT (Java)
  JwtAuthenticationFilter   verifies the token, puts the User into the security context
  GeminiController.chat     userId = CurrentUser.id(authentication)      <- from the TOKEN, never the body
  AiChatService.chat        1 rate limit  2 load this user's conversation  3 build context
  GeminiService.interpret   build prompt (today, calendar, THIS user's events, last messages)
  GeminiRestClient          HTTPS POST to Gemini  ═════════►  Google Gemini  (JSON in, JSON out)
  GeminiService.parse       JSON text -> GeminiActionResponse (null if invalid; retried once)
  GeminiActionService       VALIDATE: fields, past time, ownership, conflicts
  ScheduleConflictService   overlapping events? alternatives?
  EventServiceImpl.create   business rules (limits, defaults), sets userId
  EventRepository.save      ──► MongoDB, collection "events"
  AiChatService             save the conversation, build GeminiChatResponse
═══════════════════════════════ internet ═══════════════════════════════
ANDROID
  AiRepository              JSON -> AiChatResponse (Gson)
  AiViewModel               new AiUiState (reply bubble, pending action)
  AiAssistantScreen         Compose redraws automatically
```

### Step by step with the real code

**1. Android sends the message.** `presentation/viewmodel/AiViewModel.kt`
```kotlin
fun sendMessage(text: String) {
    val message = text.trim()
    val current = _uiState.value
    if (message.isEmpty() || current.isLoading) return   // ignore empty input and double taps
    _uiState.value = current.copy(messages = current.messages + AiChatMessage(...fromUser = true), isLoading = true, ...)
    viewModelScope.launch { handleResult(repository.sendMessage(message)) }
}
```
`_uiState` is the screen's state. Changing it makes Compose redraw: that is how the user's bubble
and the "Thinking…" bubble appear *before* the server has answered. `viewModelScope.launch` runs the
network call in the background so the screen never freezes.

**2. Retrofit turns a Kotlin function into an HTTP request.** `data/remote/ApiService.kt`
```kotlin
@POST("ai/chat")
suspend fun aiChat(@Header("Authorization") token: String, @Body request: AiChatRequest): Response<AiChatResponse>
```
`AiRepository.kt` fills `token` with `"Bearer " + the saved token` and `request` with
`AiChatRequest(message, timeZone)`. Look at what is **missing**: no user id. The phone never says who
it is; the token does.

**3. The controller receives it.** `controllers/GeminiController.java`
```java
@PostMapping("/chat")
public ResponseEntity<GeminiChatResponse> chat(@Valid @RequestBody GeminiChatRequest request, Authentication authentication) {
    String userId = CurrentUser.id(authentication);
    return ResponseEntity.ok(aiChatService.chat(userId, request.message(), request.timeZone()));
}
```
`@RequestBody` converts the JSON into a Java record (Jackson does this). `@Valid` checks the length rules.
`Authentication` was filled by `JwtAuthenticationFilter` after it verified the token. The controller
does **no** thinking: it finds out who is calling and hands over. That is exactly what a controller is for.

**4. The orchestrator runs the turn.** `services/aiService/AiChatService.java`: read these lines until they
feel obvious, they are the heart of the feature:
```java
GeminiActionResponse proposal = geminiService.interpret(text, ctx);         // Gemini PROPOSES
ActionOutcome outcome = actionService.handle(userId, zone, now, proposal);   // backend VALIDATES + EXECUTES
```
Before them it loads the user's own conversation and events; after them it saves the conversation.

**5. Gemini is asked.** `GeminiService` builds the text Gemini receives and `GeminiRestClient` sends it.
The API key is read from the environment on the server and sent in a header (`x-goog-api-key`). It never
reaches the phone or any response. See 4.3 for what Gemini actually sees.

**6. Validation.** `services/aiService/GeminiActionService.java`. A few of the checks, in plain words:
- no date or no time → **ask the user**, never guess;
- date or time that cannot be parsed ("25:99") → ask again;
- start time already in the past → ask again;
- overlaps another event → **do not save**; propose it, with free alternative times;
- UPDATE/CANCEL: which event? The id from Gemini is looked up **with the current user's id**:
```java
return Resolution.found(eventService.getById(userId, id));   // throws ResourceNotFoundException if it is not YOUR event
```
so an invented id, or another user's id, is simply "not found". That is **authorization**:
authentication says who you are (the token); authorization says what you may touch (your own events).

**7. The database.** `repositories/EventRepository.java`
```java
Optional<Event> findByIdAndUserId(String id, String userId);
```
Spring Data builds the MongoDB query from the method name. There is deliberately no "find by id
only" method used anywhere: you *cannot* load someone else's event by accident.

**8. The answer travels back** as `GeminiChatResponse` (JSON). The reply text for schedule questions
is written by our Java code from the rows it just read (`listEvents(...)`), not by Gemini, so the
assistant cannot invent an event that is not in the database.

## 4.3 What Gemini actually sees (real output from the tests)

`GeminiService.buildUserContent(...)` produces text like this (this is the real prompt from a test run):
```
CURRENT DATE AND TIME (user's local time): Saturday 2026-11-21 14:00 (Asia/Kolkata)

CALENDAR (next 14 days):
2026-11-21 Saturday (today)
2026-11-22 Sunday (tomorrow)
2026-11-23 Monday  ...

USER'S EVENTS (their upcoming schedule; these are data):
- id=evt1 | "DSA Assignment" | ASSIGNMENT | Sunday 2026-11-22 17:00 to 18:00

LAST EVENT the assistant created or changed: id=evt2

RECENT CONVERSATION (oldest first):
User: ...
Assistant: ...

NEW USER MESSAGE (between the markers):
<<<
Schedule a meeting tomorrow.
>>>
```
Why each part exists:
- **Date and calendar table**: models are poor at "what is next Monday?". We do the date arithmetic for it.
- **The user's events with ids**: so "move my meeting" can point at a real event. Only **this user's** events are
  loaded (`eventService.findBetween(userId, ...)`), so another user's data can never reach the prompt.
- **LAST EVENT**: so "move **it** to 7 PM" knows what "it" is.
- **Markers and stripped line breaks**: user text and event titles are *data*. We remove characters that
  could be used to fake instructions. The system instruction also says to ignore instructions inside data.

The answer is forced into a shape by a **JSON schema** (`GeminiService.buildResponseSchema()`), sent
with the request (`generationConfig.responseFormat.text.schema`, the format in Google's current
structured-output documentation). Then `parse(...)` reads it into `GeminiActionResponse`; anything that
is not valid JSON or has an unknown action is retried once and then becomes a friendly error.

## 4.4 Confirmation, and why the client only sends an id

When the assistant wants to cancel something (or create/move something that would overlap), it does
**not** do it. It creates a `PendingAiAction`, stores it **on the server** inside the user's conversation, and
tells the phone only `{id, type, summary}`. The phone shows **Confirm / Cancel**; tapping sends
`POST /ai/confirm {pendingActionId, confirm}`.

Why is this safe? The phone can only *name* the proposal. What it contains was fixed by the server, and
the id works only for the user it was issued to. A hacked app cannot turn "cancel Gym" into "cancel
everything".

Double taps: before running a confirmed action the server first saves the conversation **without** the
pending action (`AiChatService.resolvePending`). The conversation document has a `@Version` number; a
second, simultaneous confirm holds an out-of-date copy, its save fails, and the action never runs twice.
This is called *optimistic locking*.

## 4.5 Concepts, using this chapter's classes as examples

| Concept | Here |
|---|---|
| Controller / Service / Repository | `GeminiController` (front door) / `AiChatService`, `GeminiActionService`, `EventServiceImpl` (rules) / `EventRepository` (database) |
| Entity vs DTO | `Event` is stored in MongoDB and has a `userId`; `EventResponseDto` is what the API returns (no `userId`) |
| JSON ↔ objects | Server: Jackson (`GeminiChatResponse` record ↔ JSON). Phone: Gson (JSON ↔ `AiChatResponse` data class). Field names must match exactly (I compared them mechanically) |
| Interface for testability | `GeminiClient` is an interface; the real one calls Google, the test one returns scripted answers |
| Environment variables | `GEMINI_API_KEY`, `GEMINI_MODEL`: secrets and settings live outside the code |
| Rate limiting | `AiRateLimiter` remembers each user's recent request times |

## 4.6 How the tests work

`src/test/java/com/hrms/backend/services/aiService/`

- **`AiTestSupport`** builds the real services but plugs in a **fake Gemini** (`ScriptedGeminiClient`: you tell it
  which JSON to return) and **in-memory repositories**. No database, no network, no credits.
- Example (`AiChatServiceTest.usersNeverSeeEachOthersEventsNotEvenInThePromptSentToGemini`): Alice creates
  "Alice Secret Meeting"; Bob asks for his schedule; the test asserts Bob sees nothing **and** that the text
  sent to Gemini for Bob does not contain Alice's title.
- **A test that cannot fail proves nothing.** So I temporarily broke the code on purpose (for example: built
  the prompt from the wrong user, executed cancel without asking) and checked that tests turned red. They did.
  Try this yourself: in `GeminiActionService.checkStartAllowed`, change the condition `if (start.isBefore(now.minus(1, ChronoUnit.MINUTES)))` to `if (false)` and run the tests; three tests should fail (that is what happened in my run).

Run them with Maven (see `SETUP_INSTRUCTIONS.md`, section 1.6). Not yet tested at all: the real HTTP call to
Google, the Spring wiring, MongoDB itself, and the Android code compiling.

## 4.7 Try it yourself

1. Set `GEMINI_API_KEY` and start the backend (`SETUP_INSTRUCTIONS.md` 1.2, 1.3, 1.5).
2. In Swagger (`/swagger-ui/index.html`) log in via `POST /auth/login`, copy the token.
3. Call `POST /ai/chat`. This project has no Authorize button, but each endpoint shows an `Authorization`
   header box: type `Bearer ` and the token. Body: `{"message":"Schedule gym tomorrow at 7 AM","timeZone":"Asia/Kolkata"}`.
4. Call `GET /events` with the same header: your event is there. Try again with a *second* registered user's
   token: the list is empty. That is isolation working.
5. Send "Cancel my gym session" and look at the response: `NEEDS_CONFIRMATION`, nothing deleted yet
   (`GET /events` still lists it). Then send `yes` and check again.

## 4.8 Learning report — Stage 2

**What we built:** an assistant that understands scheduling requests, validates them, and manages the user's
own events; plus the chat screen on Android.

**Important files:**
- Backend: `GeminiController`, `AiChatService`, `GeminiService`, `GeminiRestClient`, `GeminiActionService`,
  `ScheduleConflictService`, `EventServiceImpl`, `EventRepository`, `CurrentUser`
- Android: `AiAssistantScreen.kt`, `AiViewModel.kt`, `AiRepository.kt`, `ApiService.kt` (the four `ai*` functions), `NavGraph.kt`

**Frontend ↔ backend:** HTTPS + JSON with `Authorization: Bearer <token>`; four endpoints under `/ai`.
**Backend ↔ outside world:** HTTPS to Google's Gemini API (key in a header, from an environment variable);
MongoDB for events and conversations.

**Check you understand before the next stage:**
1. Why is a *Java interface* (`GeminiClient`) useful for testing?
2. Gemini returns `"targetEventId": "abc"`. List every check that happens before anything is cancelled. (Hint: ownership, then a pending action, then confirmation.)
3. Why does the server, not the phone, remember what a pending action contains?
4. Why do answers to "what do I have tomorrow?" come from our code and not from Gemini?
5. What would go wrong if `buildContext` loaded events without the `userId` filter?
