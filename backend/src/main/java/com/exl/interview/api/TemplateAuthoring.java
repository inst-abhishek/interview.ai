package com.exl.interview.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/author")
@CrossOrigin(origins = "http://127.0.0.1:4200")
public class TemplateAuthoring {

    private final ChatGptConnection chatgpt;
    private final JdbcTemplate db;
    private final StaffAccess staff;
    private final TemplateCatalogue catalogue;
    private final TemplateValidator validator;
    private final JavaCodeRunner runner;
    private final AiTemplateGenerator ai;
    private final ObjectMapper json;
    private final Set<String> generating = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastGeneration = new ConcurrentHashMap<>();

    public TemplateAuthoring(
        JdbcTemplate db,
        StaffAccess staff,
        TemplateCatalogue catalogue,
        TemplateValidator validator,
        JavaCodeRunner runner,
        AiTemplateGenerator ai,
        ObjectMapper json,
        ChatGptConnection chatgpt
    ) {
        this.chatgpt = chatgpt;
        this.db = db;
        this.staff = staff;
        this.catalogue = catalogue;
        this.validator = validator;
        this.runner = runner;
        this.ai = ai;
        this.json = json;
    }

    public record Draft(
        String version,
        String state,
        int revision,
        TemplateCatalogue.Definition definition,
        String parentVersion,
        String aiModel,
        Long aiGeneratedAt,
        String feedback,
        boolean validated,
        List<String> errors
    ) {}

    public record Create(String version, String title, String parentVersion) {}

    public record Save(int revision, TemplateCatalogue.Definition definition) {}

    public record Generate(
        int revision,
        AiTemplateGenerator.Brief brief,
        String provider,
        String model
    ) {}

    public record Submit(int revision, String approverId) {}

    public record ReturnInput(String reason) {}

    public record ValidationReport(
        String questionId,
        int passed,
        int total,
        String state,
        List<JavaCodeRunner.TestResult> tests
    ) {}

    static class ReferenceValidationFailure extends RuntimeException {

        final ValidationReport validation;

        ReferenceValidationFailure(String message, ValidationReport validation) {
            super(message);
            this.validation = validation;
        }
    }

    @ExceptionHandler(ReferenceValidationFailure.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> referenceFailure(
        ReferenceValidationFailure failure
    ) {
        return org.springframework.http.ResponseEntity.badRequest().body(
            Map.of("message", failure.getMessage(), "validation", failure.validation)
        );
    }

    static ReferenceValidationFailure referenceFailure(
        String questionId,
        JavaCodeRunner.Result result,
        int total
    ) {
        String message;
        if (result.state().equals("COMPLETE")) {
            message =
                "Reference code for question " +
                questionId +
                " passed " +
                result.passed() +
                " of " +
                total +
                " tests. The test run finished, but validation failed. Review the failed test results, reference code and expected outputs.";
        } else message =
            "Reference code validation could not finish for question " +
            questionId +
            ". " +
            switch (result.state()) {
                case "BUSY" -> "Coding workers are busy. Try validation again shortly.";
                case "UNAVAILABLE" -> "Start the Java coding runner before retrying.";
                default -> "Check Docker Desktop and the Java runner configuration, then try again.";
            };
        return new ReferenceValidationFailure(
            message,
            new ValidationReport(
                questionId,
                result.passed(),
                total,
                result.state(),
                result.tests() == null ? List.of() : result.tests()
            )
        );
    }

    private ResponseStatusException fail(int status, String message) {
        return new ResponseStatusException(HttpStatus.valueOf(status), message);
    }

    private String auth(String h) {
        catalogue.requireEnabled();
        var a = staff.authenticate(h, "REVIEWER");
        if (
            !Boolean.TRUE.equals(
                db.queryForObject(
                    "SELECT template_author FROM recruiters WHERE id=?",
                    Boolean.class,
                    a.id()
                )
            )
        ) throw fail(403, "Template author permission is required");
        return a.id();
    }

    private String encode(Object value) {
        try {
            String s = json.writeValueAsString(value);
            if (s.length() > 200000) throw fail(400, "Draft is too large");
            return s;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw fail(400, "Invalid draft");
        }
    }

    private Draft read(String owner, String version, boolean lock) {
        var rows = db.queryForList(
            "SELECT * FROM template_drafts WHERE version=? AND author_id=?" +
                (lock ? " FOR UPDATE" : ""),
            version,
            owner
        );
        if (rows.isEmpty()) throw fail(404, "Your draft was not found");
        var r = rows.getFirst();
        try {
            var d = json.readValue(
                (String) r.get("definition"),
                TemplateCatalogue.Definition.class
            );
            return new Draft(
                version,
                (String) r.get("state"),
                ((Number) r.get("revision")).intValue(),
                d,
                (String) r.get("parent_version"),
                (String) r.get("ai_model"),
                (Long) r.get("ai_generated_at"),
                (String) r.get("feedback"),
                Objects.equals(r.get("validated_hash"), StaffAccess.hash(encode(d))),
                validator.errors(d)
            );
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw fail(500, "Draft could not be loaded");
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status(
        @RequestHeader(value = "Authorization", required = false) String h
    ) {
        auth(h);
        return Map.of(
            "aiAvailable",
            ai.available(),
            "model",
            ai.model(),
            "codingAvailable",
            runner.enabled()
        );
    }

    @GetMapping("/chatgpt")
    public ChatGptConnection.ConnectionStatus connection(
        @RequestHeader(value = "Authorization", required = false) String h,
        jakarta.servlet.http.HttpServletRequest r
    ) {
        String owner = auth(h);
        chatgpt.requireLocal(r.getRemoteAddr());
        return chatgpt.status(owner);
    }

    @PostMapping("/chatgpt/connect")
    public Map<String, String> connect(
        @RequestHeader(value = "Authorization", required = false) String h,
        jakarta.servlet.http.HttpServletRequest r
    ) {
        String owner = auth(h);
        chatgpt.requireLocal(r.getRemoteAddr());
        return Map.of("url", chatgpt.prepareLogin(owner, h, r.getLocalPort()));
    }

    @GetMapping("/chatgpt/authorize")
    public org.springframework.http.ResponseEntity<Void> authorize(
        @RequestParam String ticket,
        jakarta.servlet.http.HttpServletRequest r
    ) {
        chatgpt.requireLocal(r.getRemoteAddr());
        var launch = chatgpt.launch(ticket);
        if (!launch.owner().equals(auth(launch.session()))) throw fail(
            403,
            "Author access changed"
        );
        return org.springframework.http.ResponseEntity.status(303)
            .header("Location", launch.url())
            .header("Cache-Control", "no-store")
            .header("Referrer-Policy", "no-referrer")
            .build();
    }

    @GetMapping("/chatgpt/models")
    public List<ChatGptConnection.Model> models(
        @RequestHeader(value = "Authorization", required = false) String h,
        jakarta.servlet.http.HttpServletRequest r
    ) {
        String owner = auth(h);
        chatgpt.requireLocal(r.getRemoteAddr());
        return chatgpt.models(owner);
    }

    @PostMapping("/chatgpt/disconnect")
    public Map<String, Boolean> disconnect(
        @RequestHeader(value = "Authorization", required = false) String h,
        jakarta.servlet.http.HttpServletRequest r
    ) {
        String owner = auth(h);
        chatgpt.requireLocal(r.getRemoteAddr());
        return Map.of("revoked", chatgpt.disconnect(owner));
    }

    @GetMapping(value = "/chatgpt/callback", produces = "text/html")
    public org.springframework.http.ResponseEntity<String> callback(
        @RequestParam(defaultValue = "") String state,
        @RequestParam(required = false) String code,
        @RequestParam(name = "client_id", required = false) String clientId,
        @RequestParam(required = false) String error,
        jakarta.servlet.http.HttpServletRequest r
    ) {
        chatgpt.requireLocal(r.getRemoteAddr());
        String message;
        try {
            var a = chatgpt.consume(state);
            if (!a.owner().equals(auth(a.session()))) throw fail(403, "Author access changed");
            chatgpt.complete(a, code, clientId, error);
            message =
                error == null
                    ? "ChatGPT connected. Return to Assessment authoring and refresh connection status."
                    : "Connection declined. Return to the application to try again.";
        } catch (ResponseStatusException e) {
            message =
                "ChatGPT could not be connected. Return to Assessment authoring and start again. Your saved drafts were preserved.";
        }
        return org.springframework.http.ResponseEntity.ok()
            .header("Cache-Control", "no-store")
            .header("Referrer-Policy", "no-referrer")
            .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
            .body(
                "<!doctype html><html lang='en'><meta charset='utf-8'><title>ChatGPT connection</title><h1>ChatGPT connection</h1><p>" +
                    message +
                    "</p><p>You may close this tab.</p></html>"
            );
    }

    @GetMapping("/sources")
    public List<Map<String, Object>> sources(
        @RequestHeader(value = "Authorization", required = false) String h
    ) {
        auth(h);
        return db.queryForList(
            "SELECT version,title FROM assessment_templates WHERE status='APPROVED' ORDER BY title,version LIMIT 100"
        );
    }

    @GetMapping("/drafts")
    public List<Map<String, Object>> list(
        @RequestHeader(value = "Authorization", required = false) String h
    ) {
        String owner = auth(h);
        return db.queryForList(
            "SELECT d.version,d.state,d.revision,d.updated_at,t.status AS published_status FROM template_drafts d LEFT JOIN assessment_templates t ON t.version=d.version WHERE d.author_id=? ORDER BY d.updated_at DESC LIMIT 100",
            owner
        );
    }

    @GetMapping("/drafts/{version}")
    public Draft get(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String version
    ) {
        return read(auth(h), version, false);
    }

    @PostMapping("/drafts")
    @Transactional
    public Draft create(
        @RequestHeader(value = "Authorization", required = false) String h,
        @RequestBody Create input
    ) {
        String owner = auth(h);
        if (
            input == null ||
            input.version() == null ||
            !input.version().matches("[a-z0-9][a-z0-9-]{2,79}") ||
            input.title() == null ||
            input.title().isBlank() ||
            input.title().length() > 200
        ) throw fail(400, "Enter a unique version slug and a title");
        db.queryForObject("SELECT id FROM recruiters WHERE id=? FOR UPDATE", String.class, owner);
        if (
            db.queryForObject(
                "SELECT COUNT(*) FROM template_drafts WHERE version=?",
                Integer.class,
                input.version()
            ) > 0 ||
            db.queryForObject(
                "SELECT COUNT(*) FROM assessment_templates WHERE version=?",
                Integer.class,
                input.version()
            ) > 0
        ) throw fail(409, "Version already exists. Choose a new version slug.");
        TemplateCatalogue.Definition d;
        if (input.parentVersion() != null && !input.parentVersion().isBlank()) {
            var own = db.queryForList(
                "SELECT version FROM template_drafts WHERE version=? AND author_id=?",
                String.class,
                input.parentVersion(),
                owner
            );
            if (!own.isEmpty()) d = read(owner, input.parentVersion(), false).definition();
            else {
                if (
                    db.queryForObject(
                        "SELECT COUNT(*) FROM assessment_templates WHERE version=? AND status='APPROVED'",
                        Integer.class,
                        input.parentVersion()
                    ) == 0
                ) throw fail(404, "Choose an approved version or one of your own drafts to copy");
                d = catalogue.definition(input.parentVersion());
            }
            d = new TemplateCatalogue.Definition(
                input.version(),
                input.title().trim(),
                d.description(),
                d.durationMinutes(),
                d.skills(),
                d.reviewerInstructions(),
                d.items()
            );
        } else d = new TemplateCatalogue.Definition(
            input.version(),
            input.title().trim(),
            "",
            60,
            List.of(),
            "",
            List.of()
        );
        db.update(
            "INSERT INTO template_drafts (version,author_id,definition,parent_version,updated_at) VALUES (?,?,?,?,?)",
            input.version(),
            owner,
            encode(d),
            input.parentVersion(),
            System.currentTimeMillis()
        );
        staff.audit(owner, "TEMPLATE_DRAFT_CREATED", input.version());
        return read(owner, input.version(), false);
    }

    private void editable(Draft d, int revision) {
        if (!d.state().equals("DRAFT")) throw fail(
            409,
            "Submitted versions are frozen. Ask the approver to return it, or create a new version."
        );
        if (d.revision() != revision) throw fail(
            409,
            "Draft changed in another session. Reload before saving."
        );
    }

    @PostMapping("/drafts/{version}")
    @Transactional
    public Draft save(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String version,
        @RequestBody Save input
    ) {
        String owner = auth(h);
        if (
            input == null ||
            input.definition() == null ||
            !version.equals(input.definition().version())
        ) throw fail(400, "Definition version must match the draft");
        var d = read(owner, version, true);
        editable(d, input.revision());
        String body = encode(input.definition());
        db.update(
            "UPDATE template_drafts SET definition=?,revision=revision+1,validated_hash=NULL,updated_at=? WHERE version=? AND author_id=?",
            body,
            System.currentTimeMillis(),
            version,
            owner
        );
        staff.audit(owner, "TEMPLATE_DRAFT_SAVED", version);
        return read(owner, version, false);
    }

    @PostMapping("/drafts/{version}/generate")
    public Draft generate(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String version,
        @RequestBody Generate input,
        jakarta.servlet.http.HttpServletRequest request
    ) {
        String owner = auth(h);
        if (input == null) throw fail(400, "Generation requirements are required");
        var d = read(owner, version, false);
        editable(d, input.revision());
        if (!generating.add(owner)) throw fail(
            409,
            "A generation request is already running for your account"
        );
        try {
            if (
                System.currentTimeMillis() - lastGeneration.getOrDefault(owner, 0L) < 60000
            ) throw fail(429, "Wait one minute between AI generation requests");
            lastGeneration.put(owner, System.currentTimeMillis());
            boolean subscription = "chatgpt".equals(input.provider());
            if (
                input.provider() != null && !Set.of("chatgpt", "api").contains(input.provider())
            ) throw fail(400, "Choose a supported AI connection");
            if (subscription) {
                chatgpt.requireLocal(request.getRemoteAddr());
                if (
                    input.model() == null ||
                    chatgpt
                        .models(owner)
                        .stream()
                        .noneMatch(m -> m.slug().equals(input.model()))
                ) throw fail(400, "Choose a model available to your ChatGPT account");
            }
            var generated = subscription
                ? ai.generateWithChatGpt(
                      version,
                      input.brief(),
                      chatgpt.access(owner),
                      input.model()
                  )
                : ai.generate(version, input.brief());
            if (!owner.equals(auth(h))) throw fail(403, "Author access changed");
            String body = encode(generated);
            int changed = db.update(
                "UPDATE template_drafts SET definition=?,revision=revision+1,validated_hash=NULL,ai_model=?,ai_generated_at=?,updated_at=? WHERE version=? AND author_id=? AND revision=? AND state='DRAFT'",
                body,
                subscription ? "ChatGPT / " + input.model() : ai.model(),
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                version,
                owner,
                input.revision()
            );
            if (changed != 1) throw fail(
                409,
                "Draft changed while AI was running. The existing draft was preserved."
            );
            staff.audit(owner, "AI_TEMPLATE_DRAFTED", version);
            return read(owner, version, false);
        } finally {
            generating.remove(owner);
        }
    }

    @PostMapping("/drafts/{version}/validate")
    @Transactional
    public Draft validate(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String version,
        @RequestBody Save input
    ) {
        String owner = auth(h);
        if (input == null) throw fail(400, "Save the draft first");
        var d = read(owner, version, true);
        editable(d, input.revision());
        validator.requireValid(d.definition());
        for (var q : d.definition().items())
            if (q.kind().equals("CODING")) {
                if (!runner.enabled()) throw fail(
                    503,
                    "Start the Java Docker runner before validating a coding assessment"
                );
                var tests = new ArrayList<>(q.publicTests());
                tests.addAll(q.hiddenTests());
                var result = runner.run(q.referenceSolution(), tests);
                if (
                    !result.state().equals("COMPLETE") || result.passed() != tests.size()
                ) throw referenceFailure(q.id(), result, tests.size());
            }
        db.update(
            "UPDATE template_drafts SET validated_hash=? WHERE version=?",
            StaffAccess.hash(encode(d.definition())),
            version
        );
        staff.audit(owner, "TEMPLATE_DRAFT_VALIDATED", version);
        return read(owner, version, false);
    }

    @GetMapping("/approvers")
    public List<Map<String, Object>> approvers(
        @RequestHeader(value = "Authorization", required = false) String h
    ) {
        String owner = auth(h);
        return db.queryForList(
            "SELECT id,name,email FROM recruiters WHERE active=TRUE AND staff_role='REVIEWER' AND id<>? ORDER BY name,id",
            owner
        );
    }

    @PostMapping("/drafts/{version}/submit")
    @Transactional
    public Draft submit(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String version,
        @RequestBody Submit input
    ) {
        String owner = auth(h);
        if (input == null) throw fail(400, "Choose an approver");
        var d = read(owner, version, true);
        editable(d, input.revision());
        validator.requireValid(d.definition());
        if (!d.validated()) throw fail(409, "Validate the saved draft before submission");
        if (
            input.approverId() == null ||
            input.approverId().equals(owner) ||
            db.queryForObject(
                "SELECT COUNT(*) FROM recruiters WHERE id=? AND active=TRUE AND staff_role='REVIEWER'",
                Integer.class,
                input.approverId()
            ) == 0
        ) throw fail(400, "Choose a different active reviewer to approve this version");
        var definition = d.definition();
        db.update(
            "INSERT INTO assessment_templates (version,status,definition,title,role_name,skills,duration_minutes,question_count,description,coding_required,updated_at,author_id) VALUES (?,'IN_REVIEW',?,?,?,?,?,?,?,?,?,?)",
            version,
            encode(definition),
            definition.title(),
            definition.title(),
            encode(definition.skills()),
            definition.durationMinutes(),
            definition.items().size(),
            definition.description(),
            definition
                .items()
                .stream()
                .anyMatch(q -> q.kind().equals("CODING")),
            System.currentTimeMillis(),
            owner
        );
        db.update(
            "INSERT INTO template_review_assignments VALUES (?,?)",
            version,
            input.approverId()
        );
        db.update(
            "UPDATE template_drafts SET state='SUBMITTED',revision=revision+1,updated_at=? WHERE version=?",
            System.currentTimeMillis(),
            version
        );
        staff.audit(owner, "TEMPLATE_SUBMITTED", version);
        return read(owner, version, false);
    }

    @PostMapping("/templates/{version}/return")
    @Transactional
    public Map<String, Boolean> returnToAuthor(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String version,
        @RequestBody ReturnInput input
    ) {
        catalogue.requireEnabled();
        var reviewer = staff.authenticate(h, "REVIEWER");
        staff.requireTemplate(h, version);
        if (
            input == null ||
            input.reason() == null ||
            input.reason().isBlank() ||
            input.reason().length() > 2000
        ) throw fail(400, "Provide feedback up to 2,000 characters");
        var rows = db.queryForList(
            "SELECT status,author_id FROM assessment_templates WHERE version=? FOR UPDATE",
            version
        );
        if (
            rows.isEmpty() ||
            rows.getFirst().get("author_id") == null ||
            !rows.getFirst().get("status").equals("IN_REVIEW")
        ) throw fail(409, "Only submitted author drafts can be returned");
        db.update("DELETE FROM template_review_assignments WHERE version=?", version);
        db.update("DELETE FROM assessment_templates WHERE version=?", version);
        db.update(
            "UPDATE template_drafts SET state='DRAFT',feedback=?,validated_hash=NULL,revision=revision+1,updated_at=? WHERE version=?",
            input.reason().trim(),
            System.currentTimeMillis(),
            version
        );
        staff.audit(reviewer.id(), "TEMPLATE_RETURNED", version);
        return Map.of("returned", true);
    }
}
