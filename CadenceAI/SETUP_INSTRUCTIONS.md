# CadenceAI — Setup Instructions

Covers only what exists in the code **today** (Stage 1a configuration + Stage 2 Gemini assistant).
Documents, geofencing and reminders will be added when they are built.

**None of the commands below have been run by me** (no build tools or internet in my environment).
If one fails, send me the exact error text and I will fix it.

---

## 1. Backend

### 1.1 Requirements
- JDK 21 (`java -version` must say 21)
- A MongoDB database. Easiest: a free MongoDB Atlas cluster ("Connect" > "Drivers" in the Atlas dashboard gives you the connection string; under Network Access, allow your IP, or `0.0.0.0/0` for a demo so your hosted backend can connect).

### 1.2 Environment variables

| Variable | Required | Meaning |
|---|---|---|
| `MONGODB_URI` | **Yes** | Your own MongoDB connection string, e.g. `mongodb+srv://USER:PASSWORD@cluster0.xxxxx.mongodb.net/cadenceai?retryWrites=true&w=majority` |
| `JWT_SECRET` | **Yes** | Random secret that signs login tokens. **At least 32 characters.** Generate one with `openssl rand -base64 48` |
| `PORT` | No | Port to listen on (default 9090). Hosts like Render set this automatically |
| `GOOGLE_WEB_CLIENT_ID` | No | Only for Google Sign-In (not planned for the demo) |
| `SUPABASE_URL`, `SUPABASE_ANON_KEY` | No | Only for profile-picture upload. If unset, upload returns "Image storage is not configured" |
| `KEEPALIVE_URL` | No | This backend's own public `/ping` URL; keeps a free host awake. If unset, nothing happens |
| `GEMINI_API_KEY` | For the AI assistant | API key from Google AI Studio (section 1.5). Without it `/ai/chat` answers `503 not configured`; the rest of the backend works |
| `GEMINI_MODEL` | No | Gemini model name. Default `gemini-3.8-flash` (see 1.5) |
| `AI_RATE_LIMIT_PER_MINUTE`, `AI_RATE_LIMIT_PER_DAY` | No | Per-user AI request limits. Defaults 10 and 300 |

The backend **refuses to start** if `MONGODB_URI` or `JWT_SECRET` is missing. That is intentional.

### 1.3 Run locally

macOS / Linux:
```bash
cd SmartHR-Backend-test
export MONGODB_URI='mongodb+srv://...'
export JWT_SECRET="$(openssl rand -base64 48)"
./mvnw spring-boot:run
```
Windows (PowerShell):
```powershell
cd SmartHR-Backend-test
$env:MONGODB_URI = 'mongodb+srv://...'
$env:JWT_SECRET  = 'put-a-long-random-string-of-32-or-more-characters-here'
.\mvnw.cmd spring-boot:run
```
Quick check: open `http://localhost:9090/ping` — you should see `{"message":"Ping success"}`.
API documentation (Swagger): `http://localhost:9090/swagger-ui/index.html`.

Build the JAR without running tests (tests need a live database):
```bash
./mvnw clean package -DskipTests
```

### 1.4 Deploy so other phones can reach it (HTTPS required)

Phones cannot use `localhost`. You need a public **HTTPS** URL. The repository already has a
`Dockerfile`, so a Docker-based host works, for example Render ("New Web Service" > connect
your repo > Environment: Docker). Then:

1. In the host's dashboard add the environment variables from the table above.
2. Wait for the deploy; open `https://YOUR-APP.onrender.com/ping`.
3. Use that URL as `backendUrl` in the Android build (next section).

Notes:
- Free tiers sleep when idle: the first request after a pause can take about 30–60 seconds. The Android client already waits up to 60 seconds. Open `/ping` in a browser a few minutes before your demo.
- Free tiers have limited memory; a Spring Boot app can be slow to start there.
- The GitHub workflow `.github/workflows/deploy.yml` now needs a repository secret named `RENDER_DEPLOY_HOOK_URL` (Render > service > Settings > Deploy Hook), or delete the workflow if you do not use it.

### 1.5 Gemini configuration

1. Create an API key in **Google AI Studio** (https://aistudio.google.com, "Get API key").
2. Set it as the environment variable `GEMINI_API_KEY` on your backend (locally in your shell; on Render
   in the service's Environment tab). **Never** put it in the Android app, in the repository, or in a chat.
3. Optionally set `GEMINI_MODEL`.

**About the model name.** The default is `gemini-3.8-flash`. I chose it on 21 Nov 2026 from Google's
official models page: it is a current, stable Flash model that supports structured JSON output.
That page also said `gemini-2.5-flash` and `gemini-2.5-pro` are deprecated (redirected from 1 Dec 2026),
so do not use them. Model names change often: check https://ai.google.dev/gemini-api/docs/models before
your demo and set `GEMINI_MODEL` if needed. Free-tier availability and rate limits differ per model
and per account; if you get `429`/`503` from the assistant, check your quota in AI Studio.

**Quick test without the phone** (replace the URL; `jq` is optional):
```bash
TOKEN=$(curl -s -X POST https://YOUR-APP.onrender.com/auth/login \
  -H 'Content-Type: application/json' -d '{"email":"you@example.com","password":"yourPassword1"}' | jq -r .token)

curl -s -X POST https://YOUR-APP.onrender.com/ai/chat \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"Remind me to complete my DSA assignment tomorrow at 5 PM","timeZone":"Asia/Kolkata"}'

curl -s "https://YOUR-APP.onrender.com/events" -H "Authorization: Bearer $TOKEN"   # the event should be there
```
If MongoDB refuses to create indexes (the property `spring.data.mongodb.auto-index-creation=true`
makes the backend create the `events` and `aiConversations` indexes at start-up), use a database user
that has the `readWrite` role on your database; Atlas's default user does.

### 1.6 Run the tests

The AI tests use fake repositories and a scripted fake Gemini: they need **no database, no network
and no API key**, and they never use API credits.
```bash
./mvnw test -Dtest='com.hrms.backend.services.aiService.*Test'
```
(`HrmsApplicationTests` and `UserControllerTests` are the original project's tests; they need a
live MongoDB and the environment variables, so run them only if you have those.)

**Honest status:** these 111 tests have been compiled and executed only in my own harness (a minimal
JUnit-compatible runner with small stand-ins for Spring/Jackson), not with Maven. Run the command
above once; if anything fails to compile or pass, send me the output.

---

## 2. Android app

### 2.1 Requirements
- Android Studio (current stable) with Android SDK Platform **35**.
- Internet for the first Gradle sync (it downloads dependencies, can take 5–15 minutes).

### 2.2 Tell the app where your backend is

The URL is **not** hardcoded any more. Add one line to `gradle.properties` in the Android project root:
```
backendUrl=https://YOUR-APP.onrender.com/
```
(or pass `-PbackendUrl=https://...` on the command line). A trailing `/` is added if missing.
If you forget, the app uses the placeholder `https://YOUR-BACKEND-HOST.example.com/` and every
request will fail with a network error. That is deliberate, so it can never silently talk to
someone else's server.

For testing against a backend on your own computer with the Android **emulator**, the URL would be
`http://10.0.2.2:9090/`, but plain `http` is blocked by the app's network security configuration
(`res/xml/network_security_config.xml`) unless you add that host there. A real phone cannot reach your
computer's `localhost` at all, which is why deploying is the recommended path.

### 2.3 Build a debug APK

Android Studio: open the `SmartHR-App-master` folder, wait for Gradle sync, then
`Build > Build Bundle(s) / APK(s) > Build APK(s)`.

Command line (macOS / Linux):
```bash
cd SmartHR-App-master
./gradlew assembleDebug
```
Windows: `gradlew.bat assembleDebug`.

Output (if the build succeeds): `app/build/outputs/apk/debug/app-debug.apk`.
Install on a phone: copy the file to the phone and open it (allow "install unknown apps"), or
`adb install -r app/build/outputs/apk/debug/app-debug.apk`.

### 2.4 Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `SDK location not found` | Create `local.properties` with `sdk.dir=/path/to/Android/sdk` (Android Studio does it for you) |
| `Unsupported class file major version` / Java errors | Gradle is using the wrong JDK. In Android Studio: Settings > Build > Gradle > Gradle JDK = the bundled JDK (17 or newer) |
| Warning/error about `kotlinCompilerExtensionVersion` | Delete the `composeOptions { ... }` block in `app/build.gradle.kts` |
| `Unresolved reference: BuildConfig` | Do a Gradle sync; `buildFeatures { buildConfig = true }` must be present in `app/build.gradle.kts` (it is) |
| App opens but login says "Network error" | `backendUrl` not set or backend asleep/unreachable. Open `<backendUrl>ping` in the phone's browser |
| Backend exits at start with a placeholder/`Could not resolve placeholder` error | `MONGODB_URI` or `JWT_SECRET` is not set in that process's environment |
| Backend: `WeakKeyException` | `JWT_SECRET` is shorter than 32 characters |

---

## 3. Before you do anything else

Treat the credentials that were inside the uploaded repositories as public: the old MongoDB
password, JWT secret, Gmail app password, Supabase key and Render deploy key. Do not reuse any of
them, and if any belong to you, rotate them. Use your own database and secrets as described above.
