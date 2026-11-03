package com.exl.interview.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://127.0.0.1:4200")
public class HiringProcesses {

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RecruiterController recruiters;
    private final StaffAccess staff;
    private final TemplateCatalogue catalogue;
    private final InvitationLinks links;
    private final LiveInterviewAi ai;
    private final ChatGptConnection chatgpt;
    private final Set<String> generating = ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();

    @Value("${interview.public-app-url:http://127.0.0.1:4200}")
    private String publicUrl;

    @Value("${interview.candidate-ai-demo:false}")
    private boolean candidateAiDemo;

    private boolean candidateDemoVisible() {
        var attributes =
            org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        return (
            candidateAiDemo &&
            attributes instanceof
                org.springframework.web.context.request.ServletRequestAttributes request &&
            Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(
                request.getRequest().getRemoteAddr()
            )
        );
    }

    public HiringProcesses(
        JdbcTemplate db,
        ObjectMapper json,
        RecruiterController recruiters,
        StaffAccess staff,
        TemplateCatalogue catalogue,
        InvitationLinks links,
        LiveInterviewAi ai,
        ChatGptConnection chatgpt
    ) {
        this.db = db;
        this.json = json;
        this.recruiters = recruiters;
        this.staff = staff;
        this.catalogue = catalogue;
        this.links = links;
        this.ai = ai;
        this.chatgpt = chatgpt;
    }

    public record Plan(String version, String title, List<TemplateCatalogue.Item> topics) {}

    private String demoOwner(String code) {
        if (!candidateDemoVisible()) throw ChatGptConnection.fail(
            403,
            "Candidate connection manager is disabled outside the local demo."
        );
        var candidate = bearer(code);
        active(candidate);
        return "candidate-demo:" + candidate.get("id");
    }

    @GetMapping("/process-candidate/connection")
    public Map<String, Object> demoConnection(
        @RequestHeader(value = "X-Process-Code", required = false) String code
    ) {
        String owner = demoOwner(code);
        var status = chatgpt.status(owner);
        return Map.of(
            "connected",
            status.connected(),
            "email",
            status.email(),
            "enabled",
            ai.demoEnabled(owner)
        );
    }

    @GetMapping("/process-candidate/connection/models")
    public List<ChatGptConnection.Model> demoModels(
        @RequestHeader(value = "X-Process-Code", required = false) String code
    ) {
        return chatgpt.models(demoOwner(code));
    }

    @PostMapping("/process-candidate/connection/connect")
    public Map<String, String> demoConnect(
        @RequestHeader(value = "X-Process-Code", required = false) String code,
        jakarta.servlet.http.HttpServletRequest request
    ) {
        return Map.of(
            "url",
            chatgpt.prepareLogin(
                demoOwner(code),
                code,
                request.getLocalPort(),
                "/api/process-candidate/connection"
            )
        );
    }

    @GetMapping("/process-candidate/connection/authorize")
    public org.springframework.http.ResponseEntity<Void> demoAuthorize(
        @RequestParam String ticket
    ) {
        if (!candidateDemoVisible()) throw ChatGptConnection.fail(403, "Local demo only.");
        var launch = chatgpt.launch(ticket);
        if (!demoOwner(launch.session()).equals(launch.owner())) throw ChatGptConnection.fail(
            403,
            "Invitation changed."
        );
        return org.springframework.http.ResponseEntity.status(303)
            .header("Location", launch.url())
            .header("Cache-Control", "no-store")
            .header("Referrer-Policy", "no-referrer")
            .build();
    }

    @GetMapping(value = "/process-candidate/connection/callback", produces = "text/html")
    public org.springframework.http.ResponseEntity<String> demoCallback(
        @RequestParam String state,
        @RequestParam(required = false) String code,
        @RequestParam(name = "client_id", required = false) String clientId,
        @RequestParam(required = false) String error
    ) {
        if (!candidateDemoVisible()) throw ChatGptConnection.fail(403, "Local demo only.");
        var attempt = chatgpt.consume(state);
        if (!demoOwner(attempt.session()).equals(attempt.owner())) throw ChatGptConnection.fail(
            403,
            "Invitation changed."
        );
        chatgpt.complete(attempt, code, clientId, error);
        if (error == null) {
            var models = chatgpt.models(attempt.owner());
            if (models.isEmpty()) throw ChatGptConnection.fail(
                409,
                "ChatGPT connected but no interview model is available for this account."
            );
            ai.configureDemo(attempt.owner(), attempt.session(), true, models.getFirst().slug());
        }
        return org.springframework.http.ResponseEntity.ok()
            .header("Cache-Control", "no-store")
            .header("Referrer-Policy", "no-referrer")
            .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
            .body(
                "<!doctype html><html lang='en'><meta charset='utf-8'><title>ChatGPT connection</title><h1>ChatGPT connection</h1><p>Return to your interview and refresh the connection. You may close this tab.</p></html>"
            );
    }

    public record DemoSelection(boolean enabled, String model) {}

    @PostMapping("/process-candidate/connection/select")
    public Map<String, Boolean> demoSelect(
        @RequestHeader(value = "X-Process-Code", required = false) String code,
        @RequestBody DemoSelection input
    ) {
        String owner = demoOwner(code);
        ai.configureDemo(owner, code, input.enabled(), input.model());
        return Map.of("enabled", ai.demoEnabled(owner));
    }

    @PostMapping("/process-candidate/connection/disconnect")
    public Map<String, Boolean> demoDisconnect(
        @RequestHeader(value = "X-Process-Code", required = false) String code
    ) {
        String owner = demoOwner(code);
        ai.configureDemo(owner, code, false, "");
        return Map.of("revoked", chatgpt.disconnect(owner));
    }

    public record Create(
        String title,
        List<String> interviewIds,
        String aiPlanVersion,
        int aiMinutes,
        Boolean aiEnabled,
        String aiFocus
    ) {}

    public record Invite(List<String> candidateIds, Long expiresAt) {}

    private void validateProcessExpiry(String processId, Long expiry) {
        if (expiry == null || expiry <= now()) throw ChatGptConnection.fail(
            400,
            "Choose a future invitation expiry"
        );
        var plan = rounds(processId);
        for (var round : plan)
            if (round.get("interview_id") != null) {
                var job = one(
                    "SELECT scheduled_at,duration_seconds FROM interviews WHERE id=?",
                    round.get("interview_id")
                );
                if (
                    expiry <=
                    Math.max(now(), ((Number) job.get("scheduled_at")).longValue()) +
                        ((Number) job.get("duration_seconds")).longValue() * 1000
                ) throw ChatGptConnection.fail(
                    400,
                    "Expiry must allow the complete duration of every scheduled assessment"
                );
            }
    }

    public record AddInvite(
        String name,
        String email,
        String skills,
        Double experience,
        Long expiresAt
    ) {}

    @PostMapping("/recruiter/processes/{processId}/add-invite")
    @Transactional
    public Map<String, Integer> addAndInvite(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String processId,
        @RequestBody AddInvite input
    ) {
        var process = process(h, processId, true);
        if (input == null) throw ChatGptConnection.fail(400, "Candidate details required");
        validateProcessExpiry(processId, input.expiresAt());
        var profile = recruiters.ensurePoolCandidate(
            (String) process.get("recruiter_id"),
            input.name(),
            input.email(),
            input.skills(),
            input.experience()
        );
        return invite(h, processId, new Invite(List.of(profile.id()), input.expiresAt()));
    }

    public record Skip(String reason, boolean includeFuture) {}

    public record Decision(String action) {}

    public record Start(boolean consent) {}

    public record Answer(String questionId, String text, String requestId) {}

    public record DraftAnswer(String questionId, String text, int revision) {}

    public record Turn(String id, String role, String text, int topic, boolean followUp, long at) {}

    public record Mark(String questionId, String criterionId, Integer points) {}

    public record Review(List<Mark> marks, String notes, String recommendation, boolean publish) {}

    private String id() {
        return UUID.randomUUID().toString();
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw ChatGptConnection.fail(400, "Invalid round data");
        }
    }

    private <T> T decode(String text, Class<T> type) {
        try {
            return json.readValue(text, type);
        } catch (Exception e) {
            throw ChatGptConnection.fail(500, "Saved round data could not be read");
        }
    }

    private List<Turn> turns(Map<String, Object> attempt) {
        try {
            return new ArrayList<>(
                json.readValue(
                    (String) attempt.get("transcript"),
                    new TypeReference<List<Turn>>() {}
                )
            );
        } catch (Exception e) {
            throw ChatGptConnection.fail(500, "Conversation could not be read");
        }
    }

    private String text(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw ChatGptConnection.fail(
                400,
                label + " is required (maximum " + max + " characters)"
            );
        }
        return value.trim();
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = db.queryForList(sql, args);
        if (rows.isEmpty()) throw ChatGptConnection.fail(404, "Round or candidate not found");
        return rows.getFirst();
    }

    private Map<String, Object> process(String h, String processId, boolean lock) {
        return one(
            "SELECT * FROM hiring_processes WHERE id=? AND recruiter_id=?" +
                (lock ? " FOR UPDATE" : ""),
            processId,
            recruiters.auth(h)
        );
    }

    private List<Map<String, Object>> rounds(String processId) {
        return db.queryForList(
            "SELECT * FROM hiring_rounds WHERE process_id=? ORDER BY position",
            processId
        );
    }

    private Map<String, Object> participant(String h, String processId, String participantId) {
        process(h, processId, true);
        return one(
            "SELECT * FROM process_candidates WHERE id=? AND process_id=? FOR UPDATE",
            participantId,
            processId
        );
    }

    private Map<String, Object> bearer(String code) {
        if (code == null || code.length() < 20 || code.length() > 100) throw ChatGptConnection.fail(
            401,
            "Use your hiring-process invitation link"
        );
        var p = one("SELECT * FROM process_candidates WHERE code_hash=?", StaffAccess.hash(code));
        if (((Number) p.get("expires_at")).longValue() <= now()) throw ChatGptConnection.fail(
            410,
            "This invitation expired. Contact your recruiter."
        );
        return p;
    }

    private Map<String, Object> attempt(String attemptId) {
        return one(
            "SELECT a.*,r.kind,r.position,r.title,r.plan_snapshot,r.reviewer_id,r.interview_id,r.duration_minutes,p.process_id,p.on_hold,p.closed,p.expires_at FROM process_attempts a JOIN hiring_rounds r ON r.id=a.round_id JOIN process_candidates p ON p.id=a.participant_id WHERE a.id=?",
            attemptId
        );
    }

    private boolean done(String state) {
        return Set.of("FEEDBACK_PUBLISHED", "SKIPPED").contains(state);
    }

    private void active(Map<String, Object> p) {
        if (Boolean.TRUE.equals(p.get("closed")) || Boolean.TRUE.equals(p.get("on_hold"))) {
            throw ChatGptConnection.fail(
                409,
                "This hiring process is on hold or ended. Contact your recruiter."
            );
        }
    }

    @GetMapping("/recruiter/processes/plans")
    public Map<String, Object> plans(
        @RequestHeader(value = "Authorization", required = false) String h
    ) {
        recruiters.auth(h);
        return Map.of(
            "items",
            db.queryForList(
                "SELECT version,title,approved_at FROM ai_interview_plans ORDER BY title,version"
            ),
            "aiAvailable",
            ai.available()
        );
    }

    @PostMapping("/review/ai-plans/{version}/approve")
    @Transactional
    public Map<String, String> approvePlan(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String version
    ) {
        var reviewer = staff.authenticate(h, "REVIEWER");
        staff.requireTemplate(h, version);
        if (
            db.queryForObject(
                "SELECT COUNT(*) FROM assessment_templates WHERE version=? AND status='APPROVED'",
                Integer.class,
                version
            ) != 1
        ) {
            throw ChatGptConnection.fail(400, "Approve the source assessment template first");
        }
        var definition = catalogue.definition(version);
        var topics = definition
            .items()
            .stream()
            .filter(q -> q.kind().equals("TECHNICAL"))
            .limit(6)
            .toList();
        if (
            topics.isEmpty() ||
            topics.stream().anyMatch(q -> q.text().length() > 4000 || q.rubric().isEmpty())
        ) {
            throw ChatGptConnection.fail(
                400,
                "The plan needs written technical questions with rubrics (maximum 4,000 characters per question)"
            );
        }
        String planVersion = version + "-prelim";
        if (
            db.queryForObject(
                "SELECT COUNT(*) FROM ai_interview_plans WHERE version=?",
                Integer.class,
                planVersion
            ) == 0
        ) {
            db.update(
                "INSERT INTO ai_interview_plans VALUES (?,?,?,?,?)",
                planVersion,
                definition.title() + " - preliminary",
                encode(new Plan(planVersion, definition.title(), topics)),
                reviewer.id(),
                now()
            );
            staff.audit(reviewer.id(), "AI_PLAN_APPROVED", planVersion);
        }
        return Map.of("version", planVersion);
    }

    @GetMapping("/recruiter/processes/page")
    public Map<String, Object> processPage(
        @RequestHeader(value = "Authorization", required = false) String h,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "") String search
    ) {
        String owner = recruiters.auth(h);
        if (page < 0 || search.length() > 200) throw ChatGptConnection.fail(
            400,
            "Invalid process search"
        );
        String pattern =
            "%" +
            search
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") +
            "%";
        long total = db.queryForObject(
            "SELECT COUNT(*) FROM hiring_processes WHERE recruiter_id=? AND LOWER(title) LIKE ? ESCAPE '!'",
            Long.class,
            owner,
            pattern
        );
        int pages = (int) Math.max(1, (total + 19) / 20),
            current = Math.min(page, pages - 1);
        var items = db.queryForList(
            "SELECT p.id,p.title,p.created_at,(SELECT COUNT(*) FROM process_candidates c WHERE c.process_id=p.id) AS candidate_count FROM hiring_processes p WHERE p.recruiter_id=? AND LOWER(p.title) LIKE ? ESCAPE '!' ORDER BY p.created_at DESC,p.id LIMIT 20 OFFSET ?",
            owner,
            pattern,
            current * 20
        );
        return Map.of("items", items, "page", current, "pages", pages, "total", total);
    }

    @GetMapping("/recruiter/processes/ai-status")
    public Map<String, Boolean> processAiStatus(
        @RequestHeader(value = "Authorization", required = false) String h
    ) {
        recruiters.auth(h);
        return Map.of("aiAvailable", ai.available());
    }

    @GetMapping("/recruiter/processes")
    public List<Map<String, Object>> list(
        @RequestHeader(value = "Authorization", required = false) String h
    ) {
        return db.queryForList(
            "SELECT p.id,p.title,p.skip_ai_default,p.created_at,(SELECT COUNT(*) FROM process_candidates c WHERE c.process_id=p.id) AS candidate_count FROM hiring_processes p WHERE recruiter_id=? ORDER BY created_at DESC LIMIT 100",
            recruiters.auth(h)
        );
    }

    @PostMapping("/recruiter/processes")
    @Transactional
    public Map<String, Object> create(
        @RequestHeader(value = "Authorization", required = false) String h,
        @RequestBody Create input
    ) {
        String owner = recruiters.auth(h);
        if (
            input == null ||
            input.interviewIds() == null ||
            input.interviewIds().isEmpty() ||
            input.interviewIds().size() > 5 ||
            new HashSet<>(input.interviewIds()).size() != input.interviewIds().size()
        ) {
            throw ChatGptConnection.fail(
                400,
                "Choose one to five distinct scheduled assessments in round order"
            );
        }
        var jobs = new ArrayList<Map<String, Object>>();
        long previousStart = -1;
        for (String interviewId : input.interviewIds()) {
            var job = one(
                "SELECT * FROM interviews WHERE id=? AND recruiter_id=?",
                interviewId,
                owner
            );
            long scheduled = ((Number) job.get("scheduled_at")).longValue();
            if (scheduled < previousStart) throw ChatGptConnection.fail(
                400,
                "Assessment rounds must be in scheduled-start order"
            );
            previousStart = scheduled;
            jobs.add(job);
        }
        Plan plan = null;
        String reviewerId = null;
        if (
            Boolean.TRUE.equals(input.aiEnabled()) ||
            (input.aiEnabled() == null &&
                input.aiPlanVersion() != null &&
                !input.aiPlanVersion().isBlank())
        ) {
            if (input.aiMinutes() < 5 || input.aiMinutes() > 30) throw ChatGptConnection.fail(
                400,
                "AI round duration must be 5 to 30 minutes"
            );
            if (Boolean.TRUE.equals(input.aiEnabled())) {
                String focus = input.aiFocus() == null ? "" : input.aiFocus().trim();
                if (focus.length() > 1500) throw ChatGptConnection.fail(
                    400,
                    "Keep interview focus within 1,500 characters."
                );
                String role = (String) jobs.getFirst().get("title");
                var topics = new ArrayList<TemplateCatalogue.Item>();
                var prompts = List.of(
                    "For the role " +
                        role +
                        ", describe a relevant technical implementation you have worked on. Explain your own contribution, design choices and how you verified the result.",
                    "For the role " +
                        role +
                        ", walk through how you would investigate a failing production feature. Explain the evidence you would collect, how you would narrow the cause and how you would validate a fix.",
                    "For the role " +
                        role +
                        ", describe a technical design trade-off involving reliability, performance or maintainability. Explain the alternatives, your choice and its limitations."
                );
                for (int n = 0; n < prompts.size(); n++) topics.add(
                    new TemplateCatalogue.Item(
                        "preliminary-" + (n + 1),
                        "TECHNICAL",
                        prompts.get(n) + (focus.isBlank() ? "" : " Interview focus: " + focus),
                        List.of(),
                        "",
                        "",
                        10,
                        List.of(
                            new TemplateCatalogue.Criterion(
                                "technical-evidence",
                                "Technical understanding and evidence",
                                10,
                                "0: no relevant answer. 1-3: vague or unsupported statements. 4-6: relevant reasoning with some concrete details. 7-8: clear technical reasoning, validation and trade-offs. 9-10: precise, coherent evidence and well-explained limitations. Evaluate only the submitted technical response; do not infer personal characteristics."
                            )
                        ),
                        "",
                        List.of(),
                        List.of(),
                        ""
                    )
                );
                String version = "recruiter-prelim-" + id();
                plan = new Plan(version, role + " - preliminary", topics);
                db.update(
                    "INSERT INTO ai_interview_plans VALUES (?,?,?,?,?)",
                    version,
                    plan.title(),
                    encode(plan),
                    owner,
                    now()
                );
            } else plan = decode(
                (String) one(
                    "SELECT definition FROM ai_interview_plans WHERE version=?",
                    input.aiPlanVersion()
                ).get("definition"),
                Plan.class
            );
            var assigned = db.queryForList(
                "SELECT r.id FROM reviewer_assignments a JOIN recruiters r ON r.id=a.reviewer_id WHERE a.interview_id=? AND r.active=TRUE AND r.staff_role='REVIEWER'",
                String.class,
                input.interviewIds().getFirst()
            );
            if (assigned.isEmpty()) throw ChatGptConnection.fail(
                400,
                "Ask an administrator to assign an active reviewer to Assessment 1 before adding an AI preliminary round"
            );
            reviewerId = assigned.getFirst();
        }
        String processId = id();
        db.update(
            "INSERT INTO hiring_processes(id,recruiter_id,title,created_at) VALUES(?,?,?,?)",
            processId,
            owner,
            text(input.title(), 200, "Process title"),
            now()
        );
        int position = 0;
        if (plan != null) db.update(
            "INSERT INTO hiring_rounds(id,process_id,position,kind,title,plan_version,plan_snapshot,reviewer_id,duration_minutes) VALUES(?,?,?,'AI_PRELIMINARY',?,?,?,?,?)",
            id(),
            processId,
            position++,
            "AI preliminary interview",
            plan.version(),
            encode(plan),
            reviewerId,
            input.aiMinutes()
        );
        for (var job : jobs)
            db.update(
                "INSERT INTO hiring_rounds(id,process_id,position,kind,title,interview_id,duration_minutes) VALUES(?,?,?,'ASSESSMENT',?,?,?)",
                id(),
                processId,
                position++,
                job.get("title"),
                job.get("id"),
                ((Number) job.get("duration_seconds")).intValue() / 60
            );
        staff.audit(owner, "HIRING_PROCESS_CREATED", processId);
        return view(h, processId);
    }

    private void synchronizeAssessments(String participantId) {
        var attempts = db.queryForList(
            "SELECT a.id,i.session_id,s.state,t.state AS evaluation_state,r.session_id AS legacy_review,CASE WHEN t.state='PUBLISHED' THEN t.recommendation ELSE r.recommendation END AS published_recommendation FROM process_attempts a JOIN hiring_rounds hr ON hr.id=a.round_id LEFT JOIN invitations i ON i.id=a.invitation_id LEFT JOIN sessions s ON s.id=i.session_id LEFT JOIN template_evaluations t ON t.session_id=s.id LEFT JOIN reviews r ON r.session_id=s.id WHERE a.participant_id=? AND hr.kind='ASSESSMENT' AND a.state<>'SKIPPED'",
            participantId
        );
        for (var a : attempts) {
            String state =
                "PUBLISHED".equals(a.get("evaluation_state")) || a.get("legacy_review") != null
                    ? "FEEDBACK_PUBLISHED"
                    : "SUBMITTED".equals(a.get("state"))
                      ? "SUBMITTED"
                      : "IN_PROGRESS".equals(a.get("state"))
                        ? "IN_PROGRESS"
                        : null;
            if (state != null) db.update(
                "UPDATE process_attempts SET state=?,recommendation=? WHERE id=?",
                state,
                "FEEDBACK_PUBLISHED".equals(state) ? a.get("published_recommendation") : null,
                a.get("id")
            );
        }
    }

    private List<Map<String, Object>> steps(String participantId) {
        synchronizeAssessments(participantId);
        return db.queryForList(
            "SELECT a.id,a.state,a.revision,a.started_at,a.deadline,a.submitted_at,a.published_at,a.skipped_at,a.skip_reason,a.recommendation,a.invitation_id,r.position,r.title,r.kind FROM process_attempts a JOIN hiring_rounds r ON r.id=a.round_id WHERE participant_id=? ORDER BY r.position",
            participantId
        );
    }

    @GetMapping("/recruiter/processes/{processId}")
    public Map<String, Object> view(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String processId
    ) {
        var process = process(h, processId, false);
        return Map.of(
            "process",
            process,
            "rounds",
            db.queryForList(
                "SELECT r.id,r.position,r.kind,r.title,r.interview_id,r.plan_version,COALESCE(ra.reviewer_id,r.reviewer_id) AS reviewer_id,r.duration_minutes,i.scheduled_at,a.name AS reviewer_name FROM hiring_rounds r LEFT JOIN interviews i ON i.id=r.interview_id LEFT JOIN reviewer_assignments ra ON ra.interview_id=i.id LEFT JOIN recruiters a ON a.id=COALESCE(ra.reviewer_id,r.reviewer_id) WHERE r.process_id=? ORDER BY r.position",
                processId
            )
        );
    }

    @GetMapping("/recruiter/processes/{processId}/candidates")
    public Map<String, Object> candidates(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String processId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "") String search
    ) {
        process(h, processId, false);
        if (page < 0 || search.length() > 200) throw ChatGptConnection.fail(
            400,
            "Invalid candidate search"
        );
        String pattern =
            "%" +
            search
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") +
            "%";
        long total = db.queryForObject(
            "SELECT COUNT(*) FROM process_candidates WHERE process_id=? AND (LOWER(candidate_name) LIKE ? ESCAPE '!' OR LOWER(candidate_email) LIKE ? ESCAPE '!')",
            Long.class,
            processId,
            pattern,
            pattern
        );
        int pages = (int) Math.max(1, (total + 9) / 10),
            current = Math.min(page, pages - 1);
        var result = db.queryForList(
            "SELECT id,candidate_name,candidate_email,expires_at,on_hold,closed FROM process_candidates WHERE process_id=? AND (LOWER(candidate_name) LIKE ? ESCAPE '!' OR LOWER(candidate_email) LIKE ? ESCAPE '!') ORDER BY created_at,id LIMIT 10 OFFSET ?",
            processId,
            pattern,
            pattern,
            current * 10
        );
        for (var c : result) c.put("steps", steps((String) c.get("id")));
        return Map.of("items", result, "page", current, "pages", pages, "total", total);
    }

    private String link(String code, String parameter) {
        return publicUrl.split("#", 2)[0] + "#" + parameter + "=" + code;
    }

    @GetMapping("/recruiter/processes/{processId}/candidates/{participantId}/link")
    public Map<String, String> processLink(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String processId,
        @PathVariable String participantId
    ) {
        process(h, processId, false);
        var p = one(
            "SELECT encrypted_code FROM process_candidates WHERE id=? AND process_id=?",
            participantId,
            processId
        );
        return Map.of("url", link(links.decrypt((String) p.get("encrypted_code")), "process"));
    }

    private void inviteNext(String h, Map<String, Object> p) {
        active(p);
        if (((Number) p.get("expires_at")).longValue() <= now()) throw ChatGptConnection.fail(
            409,
            "Process invitation expired"
        );
        var all = steps((String) p.get("id"));
        for (var step : all) {
            if (done((String) step.get("state"))) continue;
            if (
                !Set.of("LOCKED", "READY").contains(step.get("state"))
            ) throw ChatGptConnection.fail(
                409,
                "The current round must have published human feedback or be skipped before inviting the next round"
            );
            var round = one(
                "SELECT * FROM hiring_rounds WHERE id=(SELECT round_id FROM process_attempts WHERE id=?)",
                step.get("id")
            );
            if (round.get("kind").equals("AI_PRELIMINARY")) {
                db.update("UPDATE process_attempts SET state='READY' WHERE id=?", step.get("id"));
            } else {
                String invitationId = recruiters
                    .invite(
                        h,
                        (String) round.get("interview_id"),
                        new RecruiterController.InvitationInput(
                            (String) p.get("candidate_name"),
                            (String) p.get("candidate_email"),
                            ((Number) p.get("expires_at")).longValue()
                        )
                    )
                    .invitationId();
                db.update(
                    "UPDATE process_attempts SET state='INVITED',invitation_id=? WHERE id=?",
                    invitationId,
                    step.get("id")
                );
            }
            return;
        }
    }

    @PostMapping("/recruiter/processes/{processId}/candidates")
    @Transactional
    public Map<String, Integer> invite(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String processId,
        @RequestBody Invite input
    ) {
        var process = process(h, processId, true);
        if (
            input == null ||
            input.candidateIds() == null ||
            input.candidateIds().isEmpty() ||
            input.candidateIds().size() > 200 ||
            new HashSet<>(input.candidateIds()).size() != input.candidateIds().size() ||
            input.expiresAt() == null ||
            input.expiresAt() <= now()
        ) throw ChatGptConnection.fail(
            400,
            "Select 1 to 200 distinct pool candidates and a future expiry"
        );
        validateProcessExpiry(processId, input.expiresAt());
        int created = 0,
            duplicate = 0;
        var plan = rounds(processId);
        for (String candidateId : input.candidateIds()) {
            var c = one(
                "SELECT * FROM candidate_pool WHERE id=? AND recruiter_id=?",
                candidateId,
                process.get("recruiter_id")
            );
            if (
                db.queryForObject(
                    "SELECT COUNT(*) FROM process_candidates WHERE process_id=? AND candidate_email=?",
                    Integer.class,
                    processId,
                    c.get("email")
                ) > 0
            ) {
                duplicate++;
                continue;
            }
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                participantId = id();
            db.update(
                "INSERT INTO process_candidates(id,process_id,candidate_id,candidate_name,candidate_email,code_hash,encrypted_code,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                participantId,
                processId,
                candidateId,
                c.get("name"),
                c.get("email"),
                StaffAccess.hash(code),
                links.encrypt(code),
                input.expiresAt(),
                now()
            );
            for (var round : plan) {
                boolean skip =
                    Boolean.TRUE.equals(process.get("skip_ai_default")) &&
                    round.get("kind").equals("AI_PRELIMINARY");
                db.update(
                    "INSERT INTO process_attempts(id,participant_id,round_id,state,skipped_at,skipped_by,skip_reason) VALUES(?,?,?,?,?,?,?)",
                    id(),
                    participantId,
                    round.get("id"),
                    skip ? "SKIPPED" : "LOCKED",
                    skip ? now() : null,
                    skip ? process.get("recruiter_id") : null,
                    skip ? "Recruiter disabled AI for future invitations" : null
                );
            }
            inviteNext(h, one("SELECT * FROM process_candidates WHERE id=?", participantId));
            created++;
        }
        staff.audit((String) process.get("recruiter_id"), "PROCESS_CANDIDATES_INVITED", processId);
        return Map.of("created", created, "alreadyInvited", duplicate);
    }

    private boolean skipCandidate(String h, Map<String, Object> p, String reason) {
        var aiSteps = db.queryForList(
            "SELECT a.id,a.state FROM process_attempts a JOIN hiring_rounds r ON r.id=a.round_id WHERE a.participant_id=? AND r.kind='AI_PRELIMINARY'",
            p.get("id")
        );
        if (aiSteps.isEmpty() || "SKIPPED".equals(aiSteps.getFirst().get("state"))) return false;
        if ("FEEDBACK_PUBLISHED".equals(aiSteps.getFirst().get("state"))) return false;
        db.update(
            "UPDATE process_attempts SET state='SKIPPED',skipped_at=?,skipped_by=?,skip_reason=?,revision=revision+1 WHERE id=?",
            now(),
            recruiters.auth(h),
            reason,
            aiSteps.getFirst().get("id")
        );
        if (!Boolean.TRUE.equals(p.get("closed"))) {
            for (var step : steps((String) p.get("id"))) {
                if (done((String) step.get("state"))) continue;
                if ("LOCKED".equals(step.get("state"))) db.update(
                    "UPDATE process_attempts SET state='READY' WHERE id=?",
                    step.get("id")
                );
                break;
            }
        }
        return true;
    }

    @PostMapping("/recruiter/processes/{processId}/candidates/{participantId}/skip-ai")
    @Transactional
    public Map<String, Boolean> skip(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String processId,
        @PathVariable String participantId,
        @RequestBody Skip input
    ) {
        var p = participant(h, processId, participantId);
        var current = steps(participantId)
            .stream()
            .filter(s -> "AI_PRELIMINARY".equals(s.get("kind")))
            .findFirst();
        if (
            current.isPresent() && "FEEDBACK_PUBLISHED".equals(current.get().get("state"))
        ) throw ChatGptConnection.fail(
            409,
            "Published feedback cannot be skipped. Invite the next assessment instead."
        );
        if (input == null) throw ChatGptConnection.fail(400, "Record a skip reason");
        boolean changed = skipCandidate(h, p, text(input.reason(), 1000, "Skip reason"));
        staff.audit(
            recruiters.auth(h),
            current.isPresent() && "SUBMITTED".equals(current.get().get("state"))
                ? "AI_REVIEW_WAIVED"
                : "AI_ROUND_SKIPPED",
            participantId
        );
        return Map.of("skipped", changed);
    }

    @GetMapping("/recruiter/processes/{processId}/candidates/{participantId}/ai-feedback")
    public Map<String, Object> publishedAiFeedback(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String processId,
        @PathVariable String participantId
    ) {
        process(h, processId, false);
        return one(
            "SELECT a.review_notes,a.recommendation,a.published_at,r.name AS reviewer_name FROM process_attempts a JOIN process_candidates p ON p.id=a.participant_id JOIN hiring_rounds hr ON hr.id=a.round_id LEFT JOIN recruiters r ON r.id=a.published_by WHERE p.id=? AND p.process_id=? AND hr.kind='AI_PRELIMINARY' AND a.state='FEEDBACK_PUBLISHED'",
            participantId,
            processId
        );
    }

    @PostMapping("/recruiter/processes/{processId}/skip-ai")
    @Transactional
    public Map<String, Integer> skipAll(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String processId,
        @RequestBody Skip input
    ) {
        process(h, processId, true);
        if (input == null) throw ChatGptConnection.fail(400, "Record a skip reason");
        String reason = text(input.reason(), 1000, "Skip reason");
        int changed = 0;
        for (var p : db.queryForList(
            "SELECT * FROM process_candidates WHERE process_id=? ORDER BY id FOR UPDATE",
            processId
        ))
            if (skipCandidate(h, p, reason)) changed++;
        if (input.includeFuture()) db.update(
            "UPDATE hiring_processes SET skip_ai_default=TRUE WHERE id=?",
            processId
        );
        staff.audit(recruiters.auth(h), "AI_ROUND_SKIPPED_ALL", processId);
        return Map.of("skipped", changed);
    }

    @PostMapping("/recruiter/processes/{processId}/ai-policy")
    @Transactional
    public Map<String, Boolean> policy(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String processId,
        @RequestBody Map<String, Boolean> input
    ) {
        process(h, processId, true);
        if (input == null || !input.containsKey("skipFuture")) throw ChatGptConnection.fail(
            400,
            "Choose a policy for future invitations"
        );
        db.update(
            "UPDATE hiring_processes SET skip_ai_default=? WHERE id=?",
            Boolean.TRUE.equals(input.get("skipFuture")),
            processId
        );
        return Map.of("updated", true);
    }

    @PostMapping("/recruiter/processes/{processId}/candidates/{participantId}/decision")
    @Transactional
    public Map<String, Boolean> decision(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String processId,
        @PathVariable String participantId,
        @RequestBody Decision input
    ) {
        var p = participant(h, processId, participantId);
        if (input == null) throw ChatGptConnection.fail(400, "Choose a recruiter action");
        switch (input.action()) {
            case "HOLD" -> db.update(
                "UPDATE process_candidates SET on_hold=TRUE WHERE id=?",
                participantId
            );
            case "RESUME" -> {
                if (Boolean.TRUE.equals(p.get("closed"))) throw ChatGptConnection.fail(
                    409,
                    "An ended process cannot be resumed"
                );
                db.update("UPDATE process_candidates SET on_hold=FALSE WHERE id=?", participantId);
            }
            case "END" -> db.update(
                "UPDATE process_candidates SET closed=TRUE WHERE id=?",
                participantId
            );
            case "INVITE_NEXT" -> inviteNext(h, p);
            default -> throw ChatGptConnection.fail(400, "Unsupported recruiter action");
        }
        staff.audit(recruiters.auth(h), "PROCESS_" + input.action(), participantId);
        return Map.of("updated", true);
    }

    @Scheduled(fixedDelayString = "${interview.expiry-check-ms:1000}")
    public void expire() {
        db.update(
            "UPDATE process_attempts SET state='SUBMITTED',submitted_at=deadline,revision=revision+1 WHERE state='IN_PROGRESS' AND deadline IS NOT NULL AND deadline<=?",
            now()
        );
    }

    @GetMapping("/process-candidate")
    public Map<String, Object> candidateView(
        @RequestHeader(value = "X-Process-Code", required = false) String code
    ) {
        expire();
        var p = bearer(code);
        var process = one("SELECT title FROM hiring_processes WHERE id=?", p.get("process_id"));
        var sanitized = new ArrayList<Map<String, Object>>();
        for (var step : steps((String) p.get("id"))) {
            Map<String, Object> safe = new LinkedHashMap<>();
            for (String k : List.of(
                "id",
                "state",
                "title",
                "kind",
                "position",
                "started_at",
                "deadline",
                "revision"
            ))
                safe.put(k, step.get(k));
            if ("AI_PRELIMINARY".equals(step.get("kind"))) {
                var a = attempt((String) step.get("id"));
                safe.put("transcript", turns(a));
                safe.put("draft", a.get("draft_text"));
                safe.put("draftRevision", a.get("draft_revision"));
            } else if (step.get("invitation_id") != null) {
                var invitation = one(
                    "SELECT i.encrypted_code,i.expires_at,s.id AS submission_reference,s.submitted_at,s.state AS session_state FROM invitations i LEFT JOIN sessions s ON s.id=i.session_id WHERE i.id=?",
                    step.get("invitation_id")
                );
                if ("SUBMITTED".equals(invitation.get("session_state"))) {
                    safe.put("submittedAt", invitation.get("submitted_at"));
                    safe.put("submissionReference", invitation.get("submission_reference"));
                } else if (
                    !Set.of("SUBMITTED", "FEEDBACK_PUBLISHED").contains(
                        (String) step.get("state")
                    ) &&
                    !Boolean.TRUE.equals(p.get("on_hold")) &&
                    !Boolean.TRUE.equals(p.get("closed")) &&
                    ((Number) invitation.get("expires_at")).longValue() > now()
                ) safe.put(
                    "assessmentUrl",
                    link(links.decrypt((String) invitation.get("encrypted_code")), "invite")
                );
            }
            sanitized.add(safe);
        }
        return Map.of(
            "title",
            process.get("title"),
            "name",
            p.get("candidate_name"),
            "onHold",
            p.get("on_hold"),
            "closed",
            p.get("closed"),
            "rounds",
            sanitized,
            "aiAvailable",
            ai.available(),
            "connectionManagerEnabled",
            candidateDemoVisible(),
            "serverTime",
            now()
        );
    }

    private Map<String, Object> candidateAttempt(String code, String attemptId, boolean lock) {
        var p = bearer(code);
        if (lock) one("SELECT id FROM process_candidates WHERE id=? FOR UPDATE", p.get("id"));
        var a = attempt(attemptId);
        if (
            !p.get("id").equals(a.get("participant_id")) || !"AI_PRELIMINARY".equals(a.get("kind"))
        ) throw ChatGptConnection.fail(404, "Interview round not found");
        active(p);
        return a;
    }

    @PostMapping("/process-candidate/{attemptId}/start")
    @Transactional
    public Map<String, Object> start(
        @RequestHeader(value = "X-Process-Code", required = false) String code,
        @PathVariable String attemptId,
        @RequestBody Start input
    ) {
        var a = candidateAttempt(code, attemptId, true);
        if ("IN_PROGRESS".equals(a.get("state"))) return candidateView(code);
        if (
            !"READY".equals(a.get("state")) || input == null || !input.consent()
        ) throw ChatGptConnection.fail(
            409,
            "This round is not available or consent has not been provided"
        );
        if (!ai.available()) throw ChatGptConnection.fail(
            503,
            "AI interview service is not available. Ask your recruiter for assistance or to skip the round."
        );
        var plan = decode((String) a.get("plan_snapshot"), Plan.class);
        long deadline = Math.min(
            ((Number) a.get("expires_at")).longValue(),
            now() + ((Number) a.get("duration_minutes")).longValue() * 60000
        );
        if (deadline - now() < 60000) throw ChatGptConnection.fail(
            409,
            "Invitation expires too soon. Ask your recruiter for a new invitation."
        );
        var opening = new Turn(
            id(),
            "INTERVIEWER",
            plan.topics().getFirst().text(),
            0,
            false,
            now()
        );
        db.update(
            "UPDATE process_attempts SET state='IN_PROGRESS',started_at=?,deadline=?,transcript=?,revision=revision+1 WHERE id=?",
            now(),
            deadline,
            encode(List.of(opening)),
            attemptId
        );
        return candidateView(code);
    }

    @PostMapping("/process-candidate/{attemptId}/answer")
    @Transactional
    public Map<String, Object> answer(
        @RequestHeader(value = "X-Process-Code", required = false) String code,
        @PathVariable String attemptId,
        @RequestBody Answer input
    ) {
        expire();
        var a = candidateAttempt(code, attemptId, true);
        if (
            input == null ||
            input.requestId() == null ||
            !input.requestId().matches("[A-Za-z0-9-]{8,80}")
        ) throw ChatGptConnection.fail(400, "A valid answer request ID is required");
        if (
            db.queryForObject(
                "SELECT COUNT(*) FROM process_messages WHERE attempt_id=? AND request_id=?",
                Integer.class,
                attemptId,
                input.requestId()
            ) > 0
        ) return candidateView(code);
        if (
            !"IN_PROGRESS".equals(a.get("state")) ||
            ((Number) a.get("deadline")).longValue() <= now()
        ) throw ChatGptConnection.fail(
            409,
            "This round ended or was skipped. Refresh your round status."
        );
        var transcript = turns(a);
        var last = transcript.getLast();
        if (
            !last.role().equals("INTERVIEWER") || !last.id().equals(input.questionId())
        ) throw ChatGptConnection.fail(
            409,
            "That question was already answered. Refresh the conversation."
        );
        transcript.add(
            new Turn(
                id(),
                "CANDIDATE",
                text(input.text(), 5000, "Answer"),
                last.topic(),
                last.followUp(),
                now()
            )
        );
        db.update(
            "INSERT INTO process_messages VALUES(?,?,?)",
            attemptId,
            input.requestId(),
            now()
        );
        int saved = db.update(
            "UPDATE process_attempts SET transcript=?,draft_text='',draft_revision=draft_revision+1,revision=revision+1 WHERE id=? AND state='IN_PROGRESS' AND deadline>?",
            encode(transcript),
            attemptId,
            now()
        );
        if (saved != 1) throw ChatGptConnection.fail(
            409,
            "This round has ended. Refresh the conversation."
        );
        return candidateView(code);
    }

    @PostMapping("/process-candidate/{attemptId}/draft")
    @Transactional
    public Map<String, Object> saveDraft(
        @RequestHeader(value = "X-Process-Code", required = false) String code,
        @PathVariable String attemptId,
        @RequestBody DraftAnswer input
    ) {
        expire();
        var a = candidateAttempt(code, attemptId, true);
        if (
            !"IN_PROGRESS".equals(a.get("state")) ||
            ((Number) a.get("deadline")).longValue() <= now() ||
            input == null ||
            input.text() == null ||
            input.text().length() > 5000
        ) {
            throw ChatGptConnection.fail(409, "This round is no longer accepting draft changes");
        }
        var last = turns(a).getLast();
        if (
            !last.role().equals("INTERVIEWER") ||
            !last.id().equals(input.questionId()) ||
            ((Number) a.get("draft_revision")).intValue() != input.revision()
        ) {
            throw ChatGptConnection.fail(
                409,
                "The conversation changed in another tab. Refresh before editing."
            );
        }
        int saved = db.update(
            "UPDATE process_attempts SET draft_text=?,draft_revision=draft_revision+1 WHERE id=? AND state='IN_PROGRESS' AND deadline>? AND draft_revision=?",
            input.text(),
            attemptId,
            now(),
            input.revision()
        );
        if (saved != 1) throw ChatGptConnection.fail(
            409,
            "The round or draft changed. Refresh before editing."
        );
        return Map.of("revision", input.revision() + 1);
    }

    @PostMapping("/process-candidate/{attemptId}/next")
    public Map<String, Object> next(
        @RequestHeader(value = "X-Process-Code", required = false) String code,
        @PathVariable String attemptId
    ) {
        expire();
        var a = candidateAttempt(code, attemptId, false);
        if (!"IN_PROGRESS".equals(a.get("state"))) return candidateView(code);
        if (!generating.add(attemptId)) throw ChatGptConnection.fail(
            409,
            "The next question is already being prepared"
        );
        try {
            var transcript = turns(a);
            var last = transcript.getLast();
            if (!last.role().equals("CANDIDATE")) return candidateView(code);
            var plan = decode((String) a.get("plan_snapshot"), Plan.class);
            if (last.followUp() && last.topic() + 1 >= plan.topics().size()) {
                db.update(
                    "UPDATE process_attempts SET state='SUBMITTED',submitted_at=?,revision=revision+1 WHERE id=? AND state='IN_PROGRESS' AND revision=?",
                    now(),
                    attemptId,
                    a.get("revision")
                );
                return candidateView(code);
            }
            String question = last.followUp()
                ? plan
                      .topics()
                      .get(last.topic() + 1)
                      .text()
                : ai.clarify(plan.topics().get(last.topic()).text(), last.text());
            transcript.add(
                new Turn(
                    id(),
                    "INTERVIEWER",
                    question,
                    last.followUp() ? last.topic() + 1 : last.topic(),
                    !last.followUp(),
                    now()
                )
            );
            bearer(code);
            int changed = db.update(
                "UPDATE process_attempts SET transcript=?,revision=revision+1 WHERE id=? AND state='IN_PROGRESS' AND revision=? AND deadline>? AND NOT EXISTS (SELECT 1 FROM process_candidates p WHERE p.id=process_attempts.participant_id AND (p.on_hold=TRUE OR p.closed=TRUE))",
                encode(transcript),
                attemptId,
                a.get("revision"),
                now()
            );
            if (changed != 1) throw ChatGptConnection.fail(
                409,
                "Round status changed while the question was being prepared. Your saved answers were preserved; refresh."
            );
            return candidateView(code);
        } finally {
            generating.remove(attemptId);
        }
    }

    @PostMapping("/process-candidate/{attemptId}/finish")
    @Transactional
    public Map<String, Object> finish(
        @RequestHeader(value = "X-Process-Code", required = false) String code,
        @PathVariable String attemptId
    ) {
        var a = candidateAttempt(code, attemptId, true);
        db.update(
            "UPDATE process_attempts SET state='SUBMITTED',submitted_at=?,revision=revision+1 WHERE id=? AND state='IN_PROGRESS'",
            now(),
            attemptId
        );
        return candidateView(code);
    }

    @GetMapping("/review/ai-rounds")
    public Map<String, Object> reviewQueue(
        @RequestHeader(value = "Authorization", required = false) String h,
        @RequestParam(defaultValue = "0") int page
    ) {
        String reviewer = staff.authenticate(h, "REVIEWER").id();
        if (page < 0) throw ChatGptConnection.fail(400, "Invalid page");
        long total = db.queryForObject(
            "SELECT COUNT(*) FROM process_attempts a JOIN hiring_rounds r ON r.id=a.round_id WHERE r.reviewer_id=? AND (a.state IN ('SUBMITTED','FEEDBACK_PUBLISHED') OR (a.state='SKIPPED' AND a.started_at IS NOT NULL))",
            Long.class,
            reviewer
        );
        int pages = (int) Math.max(1, (total + 9) / 10),
            current = Math.min(page, pages - 1);
        return Map.of(
            "items",
            db.queryForList(
                "SELECT a.id,a.state,a.submitted_at,p.candidate_name,h.title FROM process_attempts a JOIN hiring_rounds r ON r.id=a.round_id JOIN process_candidates p ON p.id=a.participant_id JOIN hiring_processes h ON h.id=p.process_id WHERE r.reviewer_id=? AND (a.state IN ('SUBMITTED','FEEDBACK_PUBLISHED') OR (a.state='SKIPPED' AND a.started_at IS NOT NULL)) ORDER BY a.submitted_at,a.id LIMIT 10 OFFSET ?",
                reviewer,
                current * 10
            ),
            "total",
            total,
            "page",
            current,
            "pages",
            pages
        );
    }

    private Map<String, Object> reviewedAttempt(String h, String attemptId, boolean lock) {
        String reviewer = staff.authenticate(h, "REVIEWER").id();
        if (lock) one(
            "SELECT a.id FROM process_attempts a JOIN hiring_rounds r ON r.id=a.round_id WHERE a.id=? AND r.reviewer_id=? FOR UPDATE",
            attemptId,
            reviewer
        );
        var a = attempt(attemptId);
        if (!reviewer.equals(a.get("reviewer_id"))) throw ChatGptConnection.fail(
            404,
            "Assigned AI interview not found"
        );
        return a;
    }

    @GetMapping("/review/ai-rounds/{attemptId}")
    public Map<String, Object> reviewView(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String attemptId
    ) {
        var a = reviewedAttempt(h, attemptId, false);
        if (
            !Set.of("SUBMITTED", "FEEDBACK_PUBLISHED", "SKIPPED").contains(a.get("state"))
        ) throw ChatGptConnection.fail(409, "Wait until the interview is submitted");
        var p = one(
            "SELECT candidate_name,candidate_email FROM process_candidates WHERE id=?",
            a.get("participant_id")
        );
        return Map.of(
            "attempt",
            a,
            "candidate",
            p,
            "plan",
            decode((String) a.get("plan_snapshot"), Plan.class),
            "transcript",
            turns(a)
        );
    }

    @PostMapping("/review/ai-rounds/{attemptId}")
    @Transactional
    public Map<String, Object> saveReview(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String attemptId,
        @RequestBody Review input
    ) {
        var a = reviewedAttempt(h, attemptId, true);
        if (!"SUBMITTED".equals(a.get("state"))) throw ChatGptConnection.fail(
            409,
            "Published or skipped rounds cannot be edited"
        );
        if (
            input == null ||
            input.notes() == null ||
            input.notes().length() > 5000 ||
            input.marks() == null ||
            input.marks().size() > 60 ||
            input.marks().stream().anyMatch(Objects::isNull) ||
            (input.recommendation() != null &&
                !input.recommendation().isBlank() &&
                !Set.of("SHORTLIST", "HOLD", "REJECT").contains(input.recommendation()))
        ) throw ChatGptConnection.fail(400, "Invalid manual review");
        var plan = decode((String) a.get("plan_snapshot"), Plan.class);
        Map<String, Integer> allowed = new HashMap<>();
        for (var topic : plan.topics())
            for (var criterion : topic.rubric())
                allowed.put(topic.id() + ":" + criterion.id(), criterion.maxPoints());
        Set<String> seen = new HashSet<>();
        for (var mark : input.marks()) {
            String key = mark.questionId() + ":" + mark.criterionId();
            if (
                !allowed.containsKey(key) ||
                !seen.add(key) ||
                (mark.points() != null && (mark.points() < 0 || mark.points() > allowed.get(key)))
            ) throw ChatGptConnection.fail(
                400,
                "Score each approved rubric criterion within its range"
            );
        }
        if (
            input.publish() &&
            (seen.size() != allowed.size() ||
                input
                    .marks()
                    .stream()
                    .anyMatch(m -> m.points() == null) ||
                !Set.of("SHORTLIST", "HOLD", "REJECT").contains(
                    Objects.toString(input.recommendation(), "")
                ))
        ) throw ChatGptConnection.fail(
            400,
            "Score every criterion and select a human recommendation before publishing"
        );
        db.update(
            "UPDATE process_attempts SET review_marks=?,review_notes=?,recommendation=?,published_at=?,state=?,published_by=?,revision=revision+1 WHERE id=?",
            encode(input.marks()),
            input.notes(),
            input.recommendation(),
            input.publish() ? now() : null,
            input.publish() ? "FEEDBACK_PUBLISHED" : "SUBMITTED",
            input.publish() ? staff.authenticate(h, "REVIEWER").id() : null,
            attemptId
        );
        staff.audit(
            staff.authenticate(h, "REVIEWER").id(),
            input.publish() ? "AI_INTERVIEW_REVIEW_PUBLISHED" : "AI_INTERVIEW_REVIEW_SAVED",
            attemptId
        );
        return reviewView(h, attemptId);
    }
}
