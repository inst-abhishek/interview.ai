# Interview platform: Recruiter setup and interview management

The candidate app supports permission checks, consent, timed MCQ/technical assessments, recoverable submission, and completion after server confirmation. The Spring Boot API persists sessions, consent timestamps, readiness snapshots, question snapshots, deadlines, answers and submission timestamps in a local H2 database. Named recruiter accounts can manage their own interviews, question banks, invitations and candidate submissions. The existing shared reviewer role can still review submitted assessments and integrity evidence.

## Run locally (PowerShell)

Backend terminal (Java 21 and Maven 3.9+):

```powershell
cd backend
$env:INTERVIEW_INVITATION_CODE = 'exldemo'
$env:INTERVIEW_REVIEWER_CODE = 'reviewdemo'
$env:INTERVIEW_RECRUITER_SETUP_CODE = 'exl-recruiter-setup'
mvn spring-boot:run
```

Candidate terminal (Node 20+):

```powershell
cd candidate-app
npm.cmd install
npm.cmd start
```

Open http://127.0.0.1:4200, sign in with the configured invitation code, run device checks, share an entire screen, accept consent, and start. For Electron, run `npm.cmd run desktop` in another candidate-app terminal.

The database defaults to `backend/data/interviews.mv.db` when starting from backend. Restart from the same directory to retain it. `INTERVIEW_DB_URL` overrides the JDBC URL; `INTERVIEW_DB_PASSWORD` configures its password. Database files are excluded from source control. Back up the database with the backend stopped.

Recruiter-created interviews use their configured duration (1 to 240 minutes). `INTERVIEW_DURATION_SECONDS` sets the duration of legacy shared-code assessments (default 1800). The backend fixes the deadline at start and rejects late answers regardless of browser timer changes. At expiry, the backend automatically submits saved answers, including partial assessments; candidates do not need to click submit or acknowledge unanswered questions. Start, answer saves and submission support safe retries. Answers are upserted per question; a submitted session is immutable. The frontend stops camera, microphone and screen tracks at timer expiry, confirmed manual completion, or component destruction.

Candidate and reviewer sign-in each issue a random bearer token valid for 24 hours; only its hash and role are stored. Candidate tokens own their sessions and cannot access reviewer endpoints; reviewer tokens cannot access candidate session endpoints. Set both `INTERVIEW_INVITATION_CODE` and `INTERVIEW_REVIEWER_CODE` before use; no default credentials are supplied. The reviewer code is a shared demo credential, not named reviewer accounts or production role management.

After starting the app, select **Reviewer sign in** and use the separately configured reviewer code. The reviewer workspace lists submitted assessments, displays question/answer pairs and readiness-check snapshots, and lets the reviewer save/update a 1–5 score, evidence-based notes, and a shortlist/hold/reject recommendation. Assessment duration and submission time are shown when available. Before starting, candidates must acknowledge the monitoring notice. The start action requests browser fullscreen; exiting fullscreen while an assessment is active records a `FULLSCREEN_EXIT` event, displays a warning, and offers a user-clicked way to re-enter fullscreen. This is a deterrent only: a normal browser cannot disable tab switching, other apps, or operating-system controls. During the assessment, focus-loss events are debounced for one second and snapshots are taken from fresh frames on the permitted entire-screen and webcam (when available) tracks using the browser's `ImageCapture` API. For focus-loss events, the screen frame is captured and uploaded before optional webcam evidence so webcam processing cannot delay the screen snapshot. Tab-hidden captures are attempted while away and skipped if the candidate returns before capture. Solid black/white frames are retried briefly and skipped rather than uploaded. The local demo marks an unavailable webcam as passed with a visible demo override; in that case, only actual shared-screen frames can be captured. These technical signals can have benign causes and are not proof of misconduct. Snapshots are reviewer-visible and automatically deleted after 30 days by default (`INTERVIEW_EVIDENCE_RETENTION_DAYS` changes the retention period). The database also caps uploads at 50 snapshots per session and 500 KB per image. Microphone and screen access require browser permission; continuous recording, transcripts, AI scoring, gaze/face detection and automated hiring decisions are not implemented. Review access is global to reviewers in this local POC, so do not use it for real candidate data.

The database question bank includes two read-only sample questions. Recruiters can add, edit, search and filter their own MCQ and technical questions. Other recruiters cannot see these private questions. Interviews snapshot their selected questions at creation; later bank edits cannot change assigned assessments.

## Verification

```powershell
cd backend
mvn test
```

```powershell
cd candidate-app
npm.cmd test
npm.cmd run build
```

API tests cover consent/readiness, candidate/reviewer role isolation, reviewer submission listing and review saving, question validation, idempotent retries, submission, deadlines, and persistence through an actual application restart. Component tests use mocked browser media and HTTP responses to cover the candidate journey, denial, interruption, backend failure, duplicate clicks, submission retry and stream cleanup.

Before a Windows demo, manually verify real camera/microphone permissions and the OS screen picker in Chrome/Edge and Electron. Automated mocks do not validate physical devices or Electron capture support. Verify denial blocks progress, stopping sharing blocks answering, restore controls work, backend interruption preserves answers, and successful completion turns off device indicators.

## Remaining milestones

Individually managed reviewer accounts, invitation email delivery, password reset, interview editing/cancellation, native Windows integrity monitoring, continuous recording, AI screening, automated grading, code execution and production privacy/retention workflows are not implemented. Readiness remains client-reported; native monitoring is shown as pending. After the candidate acknowledges the monitoring notice and grants browser media permissions, the prototype stores still webcam/shared-screen frames at specified technical events; reviewers can delete them early, and scheduled cleanup removes them after the configured retention period. Browser focus/visibility events do not reliably reveal Windows account switching, and the POC cannot monitor another OS user session. This is not a production privacy/retention system.

## Where to get the invitation code

For recruiter-managed interviews, open **Recruiter portal**, select an interview, and use **Generate invitation**. Copy the generated code and share it with that candidate. Each invitation is unique, expires at its configured time, and permits one assessment session. Codes are hashed for sign-in and also stored encrypted so the owning recruiter can copy them again from the candidate row. No email is sent automatically.

The legacy shared `exldemo` flow remains available for earlier demos. To enable that flow, stop the backend and run:

```powershell
$env:INTERVIEW_INVITATION_CODE = 'exldemo'
mvn spring-boot:run
```

This is an example local demo code; choose your own private value. Use the same value in **Candidate sign in** at the top right of the app. The environment variable must be set in the same terminal that starts Maven. If you already configured a code, use that value. Candidates should receive it from the interview coordinator. The UI keeps errors inside the sign-in dialog and shows a signed-in indicator on success.

## Where to get the reviewer code

The reviewer code is set by the local demo administrator in `INTERVIEW_REVIEWER_CODE` in the same backend terminal. If the backend is already running, stop it with Ctrl+C first; environment changes do not update an already-running server. Then set the code and restart the backend:

```powershell
$env:INTERVIEW_INVITATION_CODE = 'exldemo'
$env:INTERVIEW_REVIEWER_CODE = 'reviewdemo'
$env:INTERVIEW_RECRUITER_SETUP_CODE = 'exl-recruiter-setup'
mvn spring-boot:run
```

Use a private value and share it only with demo reviewers. `reviewdemo` works only when it is the configured value at backend startup. The reviewer code is separate from the candidate invitation code. If sign-in still fails, read the error in the reviewer sign-in dialog: an unconfigured code returns a backend configuration error, while an incorrect code returns “Invalid reviewer code.” Since reviewers share this code in the POC, individual reviewer attribution and production-grade access management are not available.

## Recruiter workflow

1. Start the backend with `INTERVIEW_RECRUITER_SETUP_CODE` set to a private administrator code. The example above uses `exl-recruiter-setup`. This code authorizes account creation; it is not a recruiter password or a candidate invitation.
2. Open **Recruiter portal** in the header and choose **Create a recruiter account**. Enter your name, work email, a password of 12 to 128 characters, and the setup code. Future sign-ins need only email and password. Passwords use salted PBKDF2-HMAC-SHA256 (210,000 iterations); bearer tokens are hashed, role-scoped, and expire after 24 hours. Sign-out revokes the recruiter token on the backend.
3. Add questions in **Question bank**, or use the two sample questions. MCQs require 2 to 8 distinct choices. Technical questions accept typed answers. Existing interview snapshots are unaffected by edits.
4. Choose **Create interview**. Enter the job/interview title, brief, duration, local scheduled start, and selected questions. The backend enforces the schedule and assigned duration.
5. Select that interview and enter a candidate's name, email and invitation expiry. Expiry must allow the entire assessment after the later of the scheduled start and invitation creation. Generate the invitation, then use **Copy link** or **Copy message** in that candidate row whenever needed. Share it manually; the local URL only works on the machine running this demo.
6. The candidate opens the shared invitation link, which prefills **Candidate sign in**, and clicks **Sign in**. They can also enter their unique code manually. Their name, interview, schedule and duration appear above readiness checks. Before the schedule, starting is blocked. Signing in again with the same unexpired invitation resumes the existing session rather than creating another attempt.
7. In the recruiter workspace, select **Refresh** to track invited, ready, in-progress and submitted candidates. Open **Review answers** for a submitted candidate, then save a human score, notes and recommendation. Recruiter access is limited to that recruiter's interviews and submissions. The legacy shared reviewer role retains global review access for the local demo.

Recruiter accounts, assigned interviews, invitations, question snapshots, and candidate identities persist in the same H2 database. Existing databases are upgraded with additive schema changes at startup. The old shared candidate and reviewer flows remain supported; named recruiter tokens cannot call those candidate/reviewer endpoints.

Additional tests cover recruiter registration/login/logout, account and role isolation, private question ownership, schedule/expiry enforcement, assigned-question snapshots, invitation reuse/resume, per-interview deadlines, candidate dashboard status, and saved human reviews. Frontend tests cover sign-in, failed creation/review retries, invitation preservation during refresh failure and expired-token handling. Real browser media permissions and visual layout still require a manual check on the demo machine.

## Run both apps from VS Code

Open this repository's root folder in VS Code. Use Java 21 and Maven on your PATH. The Java extension pack is optional for this terminal-based launch. Install frontend dependencies once with `npm.cmd install` inside `candidate-app`.

1. Stop any existing backend on port 8080 and Angular server on port 4200 to avoid port and database-file conflicts.
2. Open **Run and Debug** (Ctrl+Shift+D).
3. Select **Full application (frontend + backend)** and press **F5**.
4. Wait for both servers to start, then open http://127.0.0.1:4200.

`.vscode/launch.json` starts Spring Boot with `mvn.cmd spring-boot:run` and Angular with `npm.cmd start`, each in an integrated terminal using the built-in JavaScript terminal launcher. No Java debug extension is required to start the servers. This configuration does not attach a Java debugger; Java breakpoints require a separately configured Java debugger. The launch file supplies local demo codes: candidate `exldemo`, reviewer `reviewdemo`, and recruiter setup `exl-recruiter-setup`. Generated recruiter invitations still use their own unique codes. Change the launch environment values for your own local setup; do not put production credentials in this file.

You can also choose **Backend: Spring Boot** or **Frontend: Angular** individually. Backend working directory is fixed to `backend` so it uses the same persistent H2 database as the documented Maven command. The compound starts both independently; wait for backend readiness before signing in.


## Copying and replacing candidate invitations

Every candidate row has **Copy link**, **Copy message**, and **Regenerate invitation**. Copies remain available after refreshing, signing in again, or restarting the backend. If clipboard access is blocked, the invitation panel shows a selectable link for manual copying. The URL uses a fragment (`#invite=...`); the app removes it from the address bar after prefilling the sign-in dialog. Opening a link does not automatically sign in or request camera access.

Invitations created before recoverable links were added cannot be copied initially because only their hashes exist. Choose **Regenerate invitation**, select a new expiry, then **Replace invitation**. This updates the same invitation, invalidates its previous code for future sign-ins, and preserves the existing session, answers, and human review. Already signed-in candidate sessions stay active. Expired invitations must also be regenerated.

Recoverable invitation codes use AES-256-GCM encryption. The backend creates a separate key file at `backend/data/invitation-link.key` when first needed (with backend as the working directory). Keep this file with database backups; it is excluded from source control. `INTERVIEW_INVITATION_KEY_FILE` can override the location. All backend instances sharing a database must use the same key. If the key is lost, old links cannot be recovered and must be regenerated; assessment data is retained.

Links use the frontend address currently open in the browser. A `127.0.0.1` or `localhost` link works only on that computer. Use a reachable deployed application for candidates on other machines; the current local API is not configured for remote hosting. No invitation email is sent automatically.


## Autosave and deadline submission

While an assessment is active and monitoring checks pass, changed answers autosave every 5 seconds. Typing remains enabled during autosave. Navigation and manual saving still save immediately. Failed saves retain local drafts and retry; the saved-to-server count and last-saved time distinguish confirmed answers from pending edits. Local drafts and review markers recover on reload in the same browser tab.

Dismissible warnings appear at 5 minutes and 1 minute remaining (or immediately when entering an assessment with less time left). Each threshold is announced once per session and is remembered across reloads. The countdown continues independently of navigation or review.

At zero, the candidate cannot edit, media streams stop, and the backend finalizes the saved answers without a confirmation checkbox. A scheduled job checks every second; session reads also finalize overdue sessions immediately. Finalization is conditional and idempotent, records the deadline as submission time and TIME_EXPIRED as the reason, and does not change manual submissions. It works even when the candidate browser is closed. If the backend itself is offline, overdue sessions are finalized when it starts again.

The candidate screen checks every 2 seconds after expiry until the server confirms SUBMITTED. While disconnected it shows a pending confirmation message, never a false success message. Server confirmation then shows automatic completion and stops polling. Unsent or offline edits cannot be accepted after the deadline: only answers received by the backend in time are included. Autosave reduces this gap, but cannot guarantee delivery of the last few seconds of typing or changes made during an outage. Manual submission before expiry retains the review summary and explicit acknowledgement for unanswered questions.

Tests cover scheduled submission without browser requests, restart catch-up, immutable manual submissions, warning thresholds, autosave cadence and retries, typing during a delayed save, and offline confirmation recovery.

## Reviewer detail navigation

A candidate review uses four sections: **Overview**, **Answers**, **Timeline & Evidence**, and **Feedback**. Overview previews are bounded to three question responses and three recent snapshots, with a compact readiness and feedback summary. Full responses are not expanded on the overview.

Answers displays one response at a time with question navigation, type/search filters and answered/unanswered filtering. Timeline & Evidence filters technical signals by event and media type; timeline pages show up to eight snapshot entries, and evidence pages show six cards. Snapshot images open in a dialog rather than expanding the page. Feedback drafts remain intact when switching sections. Existing scoring, evidence deletion, candidate assessment and server APIs are unchanged by this layout update.

## Reviewer queue at larger volumes

The reviewer landing page defaults to **Awaiting review**, **Oldest submission first**, and **10 records per page**. The table uses the available page width and displays candidate names, emails and interview titles when those identities are linked to an invitation. Older shared-code sessions are explicitly labelled **Legacy demo candidate / Shared-code assessment**.

Search by candidate name, email, interview title or session reference. Filter by review status, interview title and submission date range; the end date includes the selected local calendar day. Apply filters to start at the first page, or reset to defaults. Page sizes are 10, 25 and 50. Total, awaiting and reviewed counts reflect the applied search/interview/date constraints, independent of the selected review-status filter.

`GET /api/review/sessions` now returns a paginated object (`items`, `page`, `size`, `total`, `totalPages`, `allCount`, `awaitingCount`, `reviewedCount`) rather than an unbounded array. SQL performs the search, filtering, stable ordering, counting and LIMIT/OFFSET pagination. Notes, question bodies and answers are loaded through the existing detail endpoint when a submission is opened. Query parameters are `page` (zero-based), `size`, `search`, `status` (AWAITING/REVIEWED/ALL), `interview`, `from` (inclusive epoch milliseconds), `to` (exclusive epoch milliseconds), and `sort` (OLDEST/NEWEST/REVIEWED_NEWEST).

Filters and the current page survive opening a candidate and returning, and are stored in the browser tab's session storage for reload recovery. Signing out clears that state. After a review is saved, the same filtered page refreshes; if removing the reviewed row makes the last page disappear, the backend returns the nearest valid page. Failed page loads retain the previously displayed records and page. Restart the backend and refresh the frontend together when upgrading to this response format.

## Assessment catalogue on main

The assessment workflow is enabled in the normal application. Use **Full application (frontend + backend)** in VS Code. The main H2 database is **backend/data/interviews.mv.db**. Startup applies additive, repeatable schema changes and inserts missing catalogue versions as Draft; it preserves existing accounts, questions, interviews, submissions, reviews and template versions.

| Assessment | Duration | Questions | Coding |
|---|---|---|---|
| Java Backend Developer | 60 minutes | 8 MCQs, 1 coding, 2 written | Java 21 |
| Java Full Stack Developer with Angular | 60 minutes | 8 MCQs, 1 coding, 2 written | Java 21 |
| React Developer | 60 minutes | 8 MCQs, 3 written | None |
| Cloud Operations Developer | 60 minutes | 8 MCQs, 3 written | None |

Each version totals 100 points. Java assessments allocate 16 MCQ points, 30 execution-test points and 54 reviewer-rubric points. React and Cloud Operations allocate 40 MCQ points and 60 reviewer-rubric points. New templates require technical review and approval before recruiter assignment. Only Java assessments require Docker/reference validation. React and Cloud Operations do not queue coding jobs or wait for a runner; their coding score is Not applicable.

### Coding runner setup

Install/start Docker Desktop with Linux containers, then run:

```powershell
.\docs\Setup-Java-Coding.ps1 -BuildImage
$env:INTERVIEW_CODING_ENABLED = 'true'
```

Restart the backend after setting the variable. The main VS Code launch configuration enables coding execution locally. Candidates never install Docker or WSL; execution takes place on the backend. The editor and runner support Java only.

### Recruiter and reviewer workflow

1. Sign in as reviewer using the configured reviewer code. Open **Assessment templates**, inspect each version and approve it. Java approval runs its public and hidden reference tests; non-coding approval does not require Docker.
2. Recruiter opens **Create interview**, chooses an approved template, enters interview details and creates invitations. Choosing from the catalogue opens the same form.
3. Candidate completes the assessment with autosave and server deadline submission.
4. Reviewer uses **Answers & scoring** for automatic results and question-level rubric scoring, then **Final feedback** to publish. Scores, completed required coding tests and a recommendation are required. Reviewer notes are optional. Recruiters can read the evaluation but cannot override template scores.
5. The overall rating is round(total / 20), bounded to 1?5. It combines automatic and reviewer points; it is not a separate human score. Published evaluations remain read-only.

### Preview isolation and rollback

The optional **Preview: Java assessment template** configuration still uses **backend/data/java-template/interviews.mv.db** and its own invitation key. Preview records are not copied into main. Sign out and sign in again when changing databases because stored browser tokens belong to one database.

Before promotion, the completed feature was checkpointed at **checkpoint/pre-main-catalogue-promotion**. The original baseline remains **checkpoint/pre-java-template**. Database backups under **backend/data/backups/** include each database and invitation key. Keep code and database versions paired when rolling back; do not overwrite the main database with preview files.

Set INTERVIEW_CATALOGUE_ENABLED=false to disable catalogue operations without deleting the schema or existing records. Existing custom question-bank interviews continue to work. Source and scoring guidance for the new catalogue are in [docs/assessment-catalogue.md](docs/assessment-catalogue.md).

## Separate reviewer workspaces

Reviewer navigation now has two destinations: **Candidate reviews** and **Assessment templates**. Opening templates no longer inserts a catalogue above the candidate queue. Candidate answers, evidence and feedback remain in the selected candidate's detail tabs. Switching sidebar destinations retains candidate context, feedback drafts and list position.

Assessment templates opens a separate queue with title/role/skill/version search, lifecycle filters, updated/title/version sorting, counts and 10/25/50-item server pages. Open one template to inspect it, then use **Back to template queue** to return to the same filters and page. Recruiters use the same bounded catalogue in their Assessment templates tab; answer keys and hidden tests remain restricted to technical review.

The queue uses `/api/review/template-queue` or `/api/recruiter/template-queue` with `page`, `size`, `search`, `status` and `sort`. Only public summary metadata is queried for list rows; complete question definitions are fetched when a technical reviewer opens a version. Old records without update timestamps display **Not recorded**. The current publishing workflow creates DRAFT and APPROVED states; filtering also supports imported/future IN_REVIEW and RETIRED states. No new authoring or retirement workflow is implied. Retired templates cannot be reapproved through the approval endpoint.

### Candidate coding workspace
Coding questions use a split problem/Monaco Java editor with public examples, reset confirmation and run results. Starter code loads automatically without replacing saved drafts. Source is autosaved through the existing answer flow; coding questions have no separate reasoning field. Monaco assets are served locally by Angular, with no CDN dependency. On smaller screens, candidates switch between Problem and Code. Save & next retains the solution; Submit assessment remains the final action for all questions.

### Reviewer answers and scoring
For template assessments, Answers & scoring shows one question at a time: read-only MCQ results, submitted code and final test outcomes, or written responses with their rubric. Filters include Needs scoring. Draft scoring is retained when switching questions and review tabs; use Save draft before leaving the candidate or reloading. Overview summarizes automatic and human points. Final feedback holds overall notes, recommendation, draft save and publication, with explicit missing requirements. Published evaluations remain read-only.
