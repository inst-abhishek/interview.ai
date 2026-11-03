# Interview Platform

A local interview-assessment application with recruiter, candidate and reviewer workspaces. Built with Angular 18, Spring Boot, Java 21 and H2.

## What is implemented

- **Assessment authoring:** admin-granted author permission, manual/AI draft creation, question/key/rubric/test editing, validation, independent approval, return-for-changes and immutable published versions.
- **Administration:** individual staff accounts, Admin/Recruiter/Reviewer roles, activation/deactivation, interview reviewer assignments, one-time password resets and recent activity history.
- **Candidate management:** recruiter-owned candidate pool, server-side search/filtering/pagination, CSV preview/import, bulk invitation preview, saved batches and private link exports. Optional SMTP delivery, explicit retries and reminders are available but disabled by default.
- **Recruiter:** named accounts, private question bank, approved assessment selection, scheduled interviews, expiring candidate invitations and status tracking. Candidate evaluations open as compact summaries with published scores, recommendations, feedback and the actual evaluator's name, email and review date. Candidate rows show a compact evaluator name. Older shared-code reviews are labelled when a name was not recorded.
- **Candidate:** device checks, monitoring acknowledgement, grouped MCQ/Coding/Written response navigation, answer autosave, review markers and a shared assessment timer. Java questions use a Monaco editor with examples and public test runs.
- **Reviewer:** server-side search, filtering and pagination; one-question-at-a-time answers and scoring; monitoring evidence; optional notes; draft saving and final publication.
- **Assessment lifecycle:** immutable question snapshots, server-enforced deadlines and automatic submission of saved answers at expiry.

Recent changes include the main-database assessment catalogue, role-specific templates, Java coding execution, reviewer rubric scoring, clearer score labels, consistent Back navigation and a focused recruiter evaluation summary.

## Quick start

## Optional AI preliminary interviews and multiple rounds

Recruiter navigation is **Hiring processes → Candidates → Assessments → Assessment templates → Question bank**, with Hiring processes as the default. Assessments support **Invite to standalone assessment** for a single assessment outside a hiring process. When an assessment is used in a process, the invitation form reminds the recruiter to use Hiring processes for AI and round progression, with a direct navigation action. Standalone invitations do not enrol candidates into a process.

The assessment's **Assessment invitations** list shows each invitation separately, including its source (**Standalone assessment** or **Hiring process**), process name and round where applicable, invitation date and reference. Multiple rows for the same email represent separate invitations/attempts; their results and history are retained.

Both standalone interviews and hiring processes offer **Select from pool** and **Add & invite**. Name and email are required; skills and experience are optional (decimal years supported). New invitees are saved to the recruiter's candidate pool. An existing email reuses the saved profile without overwriting its name, skills or experience. Missing experience remains “Not provided”; zero means an explicitly entered zero years. Invalid invitations do not create new profiles. Hiring-process duplicates keep the original invitation.

Recruiters can use **Hiring processes** to arrange an optional text AI preliminary interview followed by one to five existing assessments. Normal standalone assessment invitations remain available.

The recruiter process selector uses backend title search and pagination (20 processes per page). Candidate progress is a compact paginated table (10 per page) with backend name/email search. Invitation links are fetched only when **Share invitation** is opened. **Details & actions** reveals round history, review/skip controls and feedback for one candidate. AI invitation policy and bulk skip live under **Process settings**. The page uses a single Refresh action and no nested candidate scrollbar.

1. The recruiter selects **Add an optional AI preliminary round** and optionally specifies a technical focus. No preliminary-plan approval is required. Three role-focused opening questions cover implementation, troubleshooting and design trade-offs, with at most one AI clarification per topic and a human scoring rubric.
2. The administrator assigns a reviewer to the first assessment. That assignment also controls the process's AI preliminary review queue.
3. The recruiter creates a hiring process, orders its scheduled assessments, and optionally enables the AI preliminary step. Candidates are selected from the recruiter-owned candidate pool and receive a private process link for manual sharing.
4. The candidate consents and answers technical questions in a timed text conversation. AI can ask one clarification per approved topic. Answers and unfinished drafts are saved; the server closes the round at its deadline.
5. The assigned reviewer uses **AI round reviews** to read the conversation and score the rubric. Notes are optional. AI does not score candidates or make hiring decisions.
6. After published feedback, the recruiter explicitly selects **Invite next assessment**. Each assessment keeps its own timer, submission and published evaluation; publication never automatically advances a candidate.

**Recruiter control:** skip AI for one candidate at any stage, or skip it for all invited candidates, including candidates on other pages. The bulk action can also apply to future invitations when explicitly selected. A separate future-invitation setting can restore AI for new candidates. Skipping retains existing conversation and review records and makes the next assessment ready for an explicit invitation.

For a **manager demonstration on this computer**, open **Template authoring → AI connection → Manage connection**, connect ChatGPT, choose a model, and select **Enable interview demo with my subscription**. The same account can then support template authoring and local preliminary interview demonstrations. Use test candidates. The switch explicitly uses that staff member's plan allowance for all local hiring processes; it is held only in memory and must be enabled again after backend restart. Sign-out, access revocation or disconnect prevents further demo requests. Other staff cannot replace an active demo owner. Disable the demo before using the API provider. No automatic paid API fallback occurs while subscription demo mode is selected.

The API key remains optional. Without subscription demo mode, live interviews require the configured `OPENAI_API_KEY` and `INTERVIEW_AI_ENABLED=true`, with API access and billing available separately. Subscription demo mode is restricted to requests from this computer; it is not a hosted candidate service. Provider failures retain submitted answers and offer an explicit retry. This initial interview is text only. Invitations are shared manually. Pausing or closing a process blocks new rounds and AI interaction, but does not revoke an already-issued standalone assessment link.

Restart the backend after updating to create the additional process tables in the existing database. Existing assessment records are retained.

For the candidate-screen connection manager during a local demo, set `INTERVIEW_CANDIDATE_AI_DEMO=true` (enabled in the main VS Code launch). Click the interviewer status on the candidate journey, connect ChatGPT directly, refresh, choose a model and select **Use subscription for interviewer**. This manager is visible only on loopback requests. No application staff sign-in is required. OAuth state and the connection are scoped to the active process invitation; OAuth credentials remain on the backend. For API-only operation set `INTERVIEW_CANDIDATE_AI_DEMO=false`, disable the subscription demo, configure the backend API key and restart; the candidate manager is then hidden.

### Requirements

- Java 21 and Maven 3.9+ on PATH.
- Node.js 20.11+ or Node.js 22, with npm.
- Docker Desktop using Linux containers **only for Java coding execution**. On Windows, complete WSL 2 setup and restart if requested. Candidates do not install Docker or WSL.

### VS Code

1. Open the repository root and install frontend dependencies:

   ```powershell
   cd candidate-app
   npm.cmd ci
   ```

2. If using Java coding, start Docker Desktop and build the runner from the repository root:

   ```powershell
   .\docs\Setup-Java-Coding.ps1 -BuildImage
   ```

3. Stop existing servers on ports 8080 and 4200. In **Run and Debug**, select **Full application (frontend + backend)** and press **F5**.
4. Open [the application](http://127.0.0.1:4200).

The launch configuration starts both servers in terminals; a Java debug extension is not required. It enables Java coding execution, so Docker and the runner image must be ready for coding tests.

### Separate terminals

Backend:

```powershell
cd backend
$env:INTERVIEW_INVITATION_CODE = 'exldemo'
$env:INTERVIEW_REVIEWER_CODE = 'reviewdemo'
$env:INTERVIEW_RECRUITER_SETUP_CODE = 'exl-recruiter-setup' # Legacy setup before first administrator.
$env:INTERVIEW_ADMIN_SETUP_CODE = 'choose-a-private-admin-setup-code'
$env:INTERVIEW_CODING_ENABLED = 'true' # Use 'false' if Docker is unavailable.
mvn.cmd spring-boot:run
```

Frontend, in another terminal:

```powershell
cd candidate-app
npm.cmd ci # First run, or after dependency changes.
npm.cmd start
```

The frontend uses `http://127.0.0.1:4200` and the API uses `http://127.0.0.1:8080`. Restart the backend after changing Java code or environment variables.

## Sign-in and testing flow

The public header has two entry points: **Staff sign in** and **Candidate sign in**. Staff sign-in routes accounts to Administration, Recruiter or Reviewer workspaces based on their server-assigned role. Refresh restores the current staff workspace; sign-out returns to sign-in. Invitation links take candidates into their own flow.

The values below are local demo credentials configured by the launch file or commands above.

| Access        | How to sign in                                                                                                                                                                                                                       |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Administrator | Open **Staff sign in > First administrator setup**. Use `exl-admin-setup` with the VS Code launch configuration, or your configured `INTERVIEW_ADMIN_SETUP_CODE`. Choose your own email and password. Setup works once per database. |
| Recruiter     | Administrator creates the account. Sign in through **Staff sign in** with email and password. Existing recruiter accounts continue to work.                                                                                          |
| Reviewer      | Administrator creates the account. Select **Staff sign in** and use email and password.                                                                                                                                              |
| Candidate     | Use the unique invitation link/code generated by the recruiter. `exldemo` remains available for the legacy shared-code demo.                                                                                                         |

After the first administrator is created, shared reviewer-code access and recruiter self-registration are disabled, including existing shared reviewer tokens. Before setup, the legacy demo API remains compatible. Use private setup values outside a local demo.

In **Administration > Staff accounts**, create accounts and manage access. Role changes or deactivation revoke sign-ins and remove reviewer assignments when applicable. Administrators cannot deactivate or change their own role.

In **Candidate review assignments**, assign one active reviewer to a scheduled interview. Assignment rows show the recruiter, scheduled date and template version. Its existing and future submissions appear only in that reviewer's queue; direct answer, evidence and evaluation links enforce the same assignment. Unassigned interviews and legacy sessions without an interview are not visible to named reviewers. Template approval is a separate assignment: in **Template approvals**, assign an active reviewer to each template version. Only that person may open its technical content and approve it. Candidate-review assignment does not grant template approval, and template-approval assignment does not grant access to candidate submissions. The same reviewer may receive both assignments. Reassignment takes effect on subsequent requests; deactivation or role changes remove both kinds of assignments.

Existing approved templates and interview assignments are preserved. Template approver assignments start empty, so administrators must explicitly assign versions before named reviewers can see them. Previously approved templates remain available to recruiters. Recruiter catalogue endpoints and screens expose approved versions only; draft and retired versions are excluded.

For password recovery, the administrator selects **Issue reset token**, shares it privately, and the user opens **Staff sign in > Reset password**. Tokens expire after 30 minutes and work once. Password reset revokes all existing sign-ins. Email delivery is not implemented. Activity history shows the latest 100 account, assignment and evaluation events.

1. **Assign and approve a template:** administrator opens **Template approvals** and assigns a template version. Its approver opens **My template approvals**, inspects the content and approves it. Java templates must pass public and hidden reference tests first.
2. **Schedule an interview:** recruiter opens **Create interview**, chooses an approved template, enters the title, brief and scheduled start, then creates it. **Build custom assessment** supports manual question-bank selection and duration.
3. **Invite a candidate:** select the interview, enter candidate details and expiry, then generate an invitation. **Copy link**, **Copy message** and **Regenerate invitation** remain available on candidate rows. Share single invitations manually, or use **Candidate pool** for bulk generation, link export and optional configured email.
4. **Complete the assessment:** candidate signs in, completes readiness checks, shares an entire screen, acknowledges monitoring and starts at or after the scheduled time.
5. **Assign, review and publish:** administrator assigns the interview to a reviewer in **Staff sign in > Candidate review assignments**. The reviewer opens **My candidate reviews**, then **Answers & scoring**. MCQs are automatically scored; coding and written responses include reviewer criteria. **Proceed to final feedback** enables when all reviewer scores are valid. Save draft before leaving a candidate or reloading. Publication requires all scores, any required coding results and a recommendation. Template reviewer notes are optional.
6. **Read the result:** recruiter selects **View evaluation** for a compact result. Technical answers, code and rubrics stay in the reviewer workspace. Unpublished template scores and feedback are not shown in the recruiter summary.

## AI-assisted template authoring

1. In **Administration > Staff accounts**, grant **Template authoring** to an active reviewer. Permission changes revoke existing sign-ins; the reviewer signs in again.
2. The reviewer opens **Template authoring**. Create a draft with a unique version slug, or copy an approved assessment / one of your own drafts into a new version.
3. Edit role details, duration, skills and reviewer instructions. Add MCQs with answer keys and explanations, written responses with point-band rubrics, and at most one Java coding question with starter/reference code and public/hidden tests. All question points must total 100. Coding is limited to Java roles.
4. For AI generation, save metadata first, enter role/seniority/question mix/requirements, acknowledge replacement of the saved draft, then select **Generate AI draft**. It sends only these author-supplied specifications to OpenAI; candidate answers, identities and the API key are never sent to the frontend. Do not put confidential information in requirements. AI output remains an unapproved editable draft.
5. Review content one question at a time. **Candidate preview** excludes answer keys, rubrics, reference solutions and hidden tests. Save changes, then **Validate saved draft**. Validation checks structure, IDs, points and answer keys; Java reference solutions must pass all tests in the Docker runner. Any edit invalidates prior validation.
6. Select a different active reviewer and **Submit for approval**. Submission freezes that revision and assigns its approver. The approver opens **My template approvals**, approves it or **Returns for changes** with feedback. Authors cannot approve their own versions, including through reassignment. Recruiters see approved versions only.
7. To revise an approved template, choose **Create new version**. Existing definitions, interview snapshots and results are preserved. Draft saves use revision checks so stale tabs cannot overwrite newer content.

### Connect a ChatGPT subscription (local use)

1. Restart the backend after updating this code. Sign in as a reviewer with **Template authoring** permission.
2. Open **Template authoring**, select the compact **Connect AI** status menu, then **Manage connection**. The AI settings drawer defaults to **ChatGPT subscription**.
3. Select **Connect ChatGPT**, then **Continue with ChatGPT**. Complete OpenAI's sign-in and grant ChatGPT plan access in the new tab.
4. Return to the AI settings drawer, select **Refresh connection status**, and choose a model returned for your account. The compact menu shows a masked account, selected model and billing source; the drawer shows full account details. The separately billed API option is under **Other connection options**.
5. Create/save a draft, set the role and question mix, acknowledge replacing its contents, then select **Generate AI draft**. Validate and submit to a different approver as usual.

Each author connects their own eligible account. Subscription usage follows that account's plan limits; there is **no automatic fallback to paid API billing**. This direct connection is restricted to loopback access on the computer running the backend. Remotely hosted or commercial deployments need the appropriate OpenAI approval before using subscription sharing. Availability and permitted models depend on the account and current OpenAI policy. See [OpenAI's ChatGPT plan usage documentation](https://developers.openai.com/siwc/token-sharing-open-source).

The app uses dynamic OAuth registration, PKCE, one-time state, nonce and signature/issuer/audience validation. Access/refresh/ID tokens remain on the backend in an encrypted, owner-access-only local store at backend/data/chatgpt (ignored by Git). Keep its encryption key and stable host ID together on this computer; never commit or share the directory. Connections persist across backend restarts. Staff authentication and author permissions are still required. Disconnect stops local use and attempts remote session revocation; if revocation is unconfirmed, remove the connection in ChatGPT Settings. To disable this feature set INTERVIEW_CHATGPT_ENABLED=false and restart.

Subscription calls use account-specific model discovery and streamed Responses with store=false, stream=true, an array input, structured JSON output, no unsupported max_output_tokens field, and no provider retries. A completed stream is required before saving the generated draft; plan-limit, refused and interrupted responses preserve the existing draft. A 180-second request timeout, question-count limit and per-author cooldown also apply. Automated OAuth tests use synthetic tokens and mocked identity verification; a real sign-in and generation must still be completed manually to confirm account eligibility.

### Configure an OpenAI API key instead (separate billing)

Create **backend/.env.local** (ignored by Git) with these properties, without quotes:

```properties
OPENAI_API_KEY=your-private-key
INTERVIEW_AI_ENABLED=true
INTERVIEW_AI_MODEL=gpt-6.1-sol
```

The backend loads this optional properties file when Maven runs from **backend**. Restart the backend after changing it. Environment variables / deployment secrets can also supply these settings. Never put the key in Angular, a tracked launch configuration or a committed file. The API-key option remains unavailable when disabled or missing credentials; the separate ChatGPT connection and manual authoring are independent.

The integration uses the OpenAI Responses API with structured JSON output and `store=false`, a 180-second timeout, 20,000 maximum output tokens, at most 20 generated questions and a one-minute per-author cooldown. It performs no automatic provider retries. The API configuration status exposes availability and the configured model, never the API key. Subscription connection status exposes connection state and the connected email, never credentials. AI provenance stores model name/time; failures and concurrent-edit conflicts preserve the saved draft. Paid model access/API credits may be needed. Application generation limits are not a provider billing cap.

Automated tests use a mocked AI generator and validate completed/refused/incomplete response handling. They do not prove live credentials, billing or generated content quality. Validate the configured provider with a small real draft before demonstrating it. The opt-in live test can be run from backend with INTERVIEW_LIVE_AI_TEST=true and mvn.cmd -Dtest=LiveAiTemplateTest test; this sends synthetic role specifications and can incur API charges. It is skipped by default. A 429 response can mean API credits/quota or a rate limit; check the project billing before retrying. This feature creates assessment content; AI does **not** evaluate candidates or make hiring decisions.

Official references: [Responses structured output](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses), [GPT-6.1 Sol](https://developers.openai.com/api/docs/models/gpt-6.1-sol).

## Candidate pool and bulk invitations

1. In the recruiter workspace, open **Candidate pool**. Add/edit candidates individually or download the CSV template and select **Import candidates from CSV**.
2. The CSV must include Name,Email; Skills,Experience are optional. Header matching is case-insensitive. Preview supports quoted commas/newlines, up to 1,000 rows and 1 MB. Invalid rows, repeated emails and existing pool candidates are identified. **Import valid candidates** revalidates on the backend and keeps existing records unchanged.
3. Search by name/email, skill or minimum experience. Select up to 200 candidates across pages. Selection persists while filtering and paging.
4. Choose a scheduled interview and invitation expiry, then **Preview invitations**. Review recipients, interview details and candidates already invited. Confirm **Generate invitations**. Existing invitations are skipped; their links, answers and reviews are preserved. Retrying a request after a lost response reuses the original batch.
5. Use **Copy link**, **Show link** or **Download invitation CSV**. Saved batches can be reopened; the latest 20 are listed. Exports contain private invitation credentials and should be shared individually. Spreadsheet formula characters in exported fields are escaped.
6. Email is disabled by default. When configured, bulk generation can queue individual emails. Batch rows show email status separately from assessment progress. **Accepted by mail server** does not prove inbox delivery. Queued/sending messages cannot be duplicated; failed or uncertain attempts reuse the same invitation. An uncertain outcome requires acknowledgement before retry. Reminders are explicit actions; expired/submitted invitations cannot be sent. There are no automatic reminder schedules or bounce/open tracking.

The existing **Invite a candidate** form remains available. **Invite from candidate pool** preselects the current interview. Candidate-pool edits do not rewrite details on previously issued invitations. No resume upload or AI screening is included.

### Optional email configuration (later)

Set the following backend environment variables when a mail provider is available, then restart:

| Variable                                          | Purpose                                                                                                                                    |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| INTERVIEW_PUBLIC_APP_URL                          | Candidate-facing deployed frontend URL, such as https://assessment.example.com/; the default localhost URL works only on the host computer |
| INTERVIEW_EMAIL_ENABLED                           | true enables sending; defaults to false                                                                                                    |
| INTERVIEW_EMAIL_FROM                              | Sender email accepted by your provider                                                                                                     |
| INTERVIEW_SMTP_HOST / INTERVIEW_SMTP_PORT         | Provider SMTP endpoint; port defaults to 587                                                                                               |
| INTERVIEW_SMTP_USERNAME / INTERVIEW_SMTP_PASSWORD | Provider credentials; keep these outside Git                                                                                               |
| INTERVIEW_SMTP_STARTTLS                           | Required STARTTLS by default; disable only for a local test mail server                                                                    |

A persistent outbox processes explicitly queued messages on a separate worker so SMTP does not block assessment expiry tasks. Sending pauses if email is disabled. Interrupted sends become **Needs verification** rather than being retried automatically. Automated tests mock the mail sender; actual provider connectivity and delivery require verification after configuration. Staff password resets still use admin-issued tokens, not email.

## Assessment catalogue and scoring

All four templates contain 11 questions, allow 60 minutes and total 100 points. New versions are seeded as **Draft** and require reviewer approval before assignment.

| Template                               | Questions                        | Point allocation                              |
| -------------------------------------- | -------------------------------- | --------------------------------------------- |
| Java Backend Developer                 | 8 MCQs, 1 Java coding, 2 written | 16 MCQ + 30 coding tests + 54 reviewer rubric |
| Java Full Stack Developer with Angular | 8 MCQs, 1 Java coding, 2 written | 16 MCQ + 30 coding tests + 54 reviewer rubric |
| React Developer                        | 8 MCQs, 3 written                | 40 MCQ + 60 reviewer rubric                   |
| Cloud Operations Developer             | 8 MCQs, 3 written                | 40 MCQ + 60 reviewer rubric                   |

The editor and runner support **Java 21 only**. React and Cloud Operations contain no coding questions and do not require Docker. Coding execution failures remain pending/error states rather than being graded as wrong answers.

**Overall assessment score** combines automatic and reviewer points. The published 1-5 **overall assessment rating** is `round(total / 20)`, bounded to 1-5. Custom assessments retain a manually assigned reviewer rating. Readiness checks and monitoring events do not contribute to scores. Recommendations are selected by reviewers; scores do not automatically make hiring decisions. Published template evaluations are read-only.

See [catalogue content and references](docs/assessment-catalogue.md) for authoring guidance. Add a new version when changing an assigned assessment; existing definitions and interview snapshots are preserved.

## Saving, timers and monitoring

- Changed candidate answers autosave every five seconds while monitoring checks pass. Navigation also saves. Failed saves retain browser drafts; the UI distinguishes pending edits from confirmed saves.
- Warnings appear at five minutes and one minute remaining. At expiry, the backend submits saved answers automatically, including partial assessments. Late/offline edits cannot be included. If the backend was offline, overdue sessions are finalized after restart. The candidate sees completion only after server confirmation.
- Manual submission allows a final review and requires acknowledgement of unanswered questions.
- Browser monitoring uses permission checks and event-triggered webcam/shared-screen snapshots for focus changes, fullscreen exits and stream interruptions. There is no continuous audio/video recording. An unavailable webcam has a visible local-demo override; microphone and screen permissions remain required.
- Snapshots are retained for 30 days by default, with reviewer deletion and scheduled cleanup. Technical events are not proof of misconduct. A browser cannot prevent tab/app switching or inspect another Windows user session.

## Data and configuration

| Item                           | Default                                       |
| ------------------------------ | --------------------------------------------- |
| Main database                  | `backend/data/interviews.mv.db`               |
| Main invitation encryption key | `backend/data/invitation-link.key`            |
| Optional preview database      | `backend/data/java-template/interviews.mv.db` |
| Local database backups         | `backend/data/backups/`                       |

Start Maven from `backend` so relative paths remain consistent. Startup applies additive schema changes and inserts missing catalogue versions without replacing existing records. Back up the database **and invitation key** with the backend stopped. Database files, keys, dependencies and build outputs are excluded from Git.

The optional **Preview: Java assessment template** launch configuration uses its own database and invitation key. Preview data is retained separately; it is not copied into main. Sign out and sign in again when switching databases because browser tokens belong to one database.

Useful environment variables:

| Variable                                     | Purpose                                                                           |
| -------------------------------------------- | --------------------------------------------------------------------------------- |
| `INTERVIEW_ADMIN_SETUP_CODE`                 | One-time first-administrator setup secret; required for bootstrap                 |
| `INTERVIEW_DB_URL` / `INTERVIEW_DB_PASSWORD` | Main JDBC connection settings                                                     |
| `INTERVIEW_INVITATION_KEY_FILE`              | Invitation encryption-key location                                                |
| `INTERVIEW_CODING_ENABLED`                   | Enable/disable Java execution; defaults to false outside the launch configuration |
| `INTERVIEW_CATALOGUE_ENABLED`                | Enable/disable catalogue operations; defaults to true                             |
| `INTERVIEW_EVIDENCE_RETENTION_DAYS`          | Snapshot retention; defaults to 30                                                |
| `JAVA_TEMPLATE_DB_URL`                       | Database override for the optional preview profile                                |

Local invitation links work only on the computer running the app. Remote use requires deployment and corresponding frontend/API configuration.

## Verification

```powershell
cd backend
mvn.cmd test
```

```powershell
cd candidate-app
npm.cmd test
npm.cmd run build
```

Tests cover role/ownership isolation, invitations, deadlines, autosave, catalogue approval, scoring, publication, non-coding assessment flows and recruiter summaries. Backend tests use isolated in-memory databases and a mocked coding worker. Java reference validation uses the real Docker runner when approving a template.

Camera/microphone permissions, screen selection and layout should also be checked in a real browser.

## Current limits

This is a local proof of concept. Native Windows integrity monitoring, continuous recording, transcripts, AI evaluation and production deployment/privacy workflows are not implemented. Staff password recovery currently needs an administrator-issued token. SSO/MFA, login rate limiting, multi-organization isolation, emailed recovery and a searchable/exportable long-term audit interface remain future work.

## Code formatting

From the repository root, run `npm ci` to install the pinned formatting tools, then `npm run format` to format Java, Angular, JavaScript, JSON, XML, Markdown and SQL files. Use `npm run format:check` to check formatting without changing files. The application dependencies remain in `candidate-app`; its existing install/build commands are unchanged.

Java and XML use four-space indentation. Angular TypeScript, templates, styles and JSON use two spaces, with the Angular template parser for HTML. SQL schema files use a dedicated SQL formatter. Automatic format-on-save is disabled. Use Format Document or the formatting commands when needed; the recommended Prettier extension supports the configured Java and XML plugins. Install the recommended extensions when opening the workspace. Build output, dependencies, local environment files, database files and recordings are excluded. Existing PowerShell scripts and properties files retain their language-specific formatting.

Invitation context is shown as **Standalone assessment** or **Hiring process**, with the process title and round number on candidate and reviewer assessment screens. Admin assessment assignments show the hiring processes using that assessment; their reviewer covers both invitation sources. Templates are reusable content, not standalone or process invitations. Older shared-code sessions show that their source was not recorded.

Admin candidate review assignments use a searchable table with backend pagination (10 assessments per page). Filter by assessment, recruiter, reviewer or assignment status. Open the reviewer selector, search active reviewers, then select **Save** to apply a change. Process usage expands on demand with pagination; unsaved reviewer selections stay available across assignment pages.
