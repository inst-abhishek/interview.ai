package com.exl.interview.api;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/staff")
@CrossOrigin(origins = "http://127.0.0.1:4200")
public class StaffAccess {

    private final JdbcTemplate db;

    @Value("${interview.admin-setup-code:}")
    private String setupCode;

    public StaffAccess(JdbcTemplate db) {
        this.db = db;
    }

    public record Account(
        String id,
        String name,
        String email,
        String role,
        boolean active,
        boolean canAuthor
    ) {}

    public record Login(String email, String password) {}

    public record Create(
        String name,
        String email,
        String password,
        String role,
        String setupCode
    ) {}

    public record Change(boolean active, String role) {}

    public record Reset(String token, String password) {}

    public record Assignment(String reviewerId) {}

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String passwordHash(String value) {
        password(value);
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return HexFormat.of().formatHex(salt) + ":" + HexFormat.of().formatHex(derive(value, salt));
    }

    private static byte[] derive(String value, byte[] salt) {
        var spec = new PBEKeySpec(value.toCharArray(), salt, 210000, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    private static void password(String value) {
        if (value == null || value.length() < 12 || value.length() > 128) throw error(
            400,
            "Password must be 12 to 128 characters"
        );
    }

    private static ResponseStatusException error(int code, String message) {
        return new ResponseStatusException(HttpStatus.valueOf(code), message);
    }

    private String random() {
        return UUID.randomUUID().toString() + UUID.randomUUID();
    }

    private Account row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        return new Account(
            r.getString("id"),
            r.getString("name"),
            r.getString("email"),
            r.getString("staff_role"),
            r.getBoolean("active"),
            r.getBoolean("template_author")
        );
    }

    public Account authenticate(String header, String... roles) {
        if (header == null || !header.startsWith("Bearer ")) throw error(
            401,
            "Staff sign in required"
        );
        var rows = db.query(
            "SELECT a.* FROM recruiters a JOIN tokens t ON t.account_id=a.id WHERE t.token=? AND t.expires>? AND a.active=TRUE AND t.role=a.staff_role",
            this::row,
            hash(header.substring(7)),
            System.currentTimeMillis()
        );
        if (rows.isEmpty()) throw error(401, "Staff sign in expired");
        var a = rows.getFirst();
        if (!Arrays.asList(roles).contains(a.role())) throw error(
            403,
            "This role cannot access this workspace"
        );
        return a;
    }

    public boolean managed() {
        return (
            db.queryForObject(
                "SELECT COUNT(*) FROM recruiters WHERE staff_role='ADMIN'",
                Integer.class
            ) > 0
        );
    }

    public void audit(String actor, String action, String target) {
        db.update(
            "INSERT INTO access_activity VALUES (?,?,?,?,?)",
            UUID.randomUUID().toString(),
            actor,
            action,
            target,
            System.currentTimeMillis()
        );
    }

    private Map<String, Object> issue(Account a) {
        String token = random();
        db.update(
            "INSERT INTO tokens (token,expires,role,account_id) VALUES (?,?,?,?)",
            hash(token),
            System.currentTimeMillis() + 86400000,
            a.role(),
            a.id()
        );
        audit(a.id(), "SIGN_IN", a.id());
        return Map.of("token", token, "account", a);
    }

    @GetMapping("/setup")
    public Map<String, Boolean> setup() {
        return Map.of("required", !managed());
    }

    @PostMapping("/bootstrap")
    @Transactional
    public synchronized Map<String, Object> bootstrap(@RequestBody Create c) {
        if (managed()) throw error(409, "Administrator already configured");
        if (
            setupCode.isBlank() ||
            c == null ||
            c.setupCode() == null ||
            !MessageDigest.isEqual(
                hash(setupCode).getBytes(StandardCharsets.UTF_8),
                hash(c.setupCode()).getBytes(StandardCharsets.UTF_8)
            )
        ) throw error(
            403,
            "Invalid administrator setup code. Configure INTERVIEW_ADMIN_SETUP_CODE."
        );
        try {
            db.update("INSERT INTO staff_bootstrap (id) VALUES (1)");
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw error(409, "Administrator already configured");
        }
        Account a = createAccount(new Create(c.name(), c.email(), c.password(), "ADMIN", null));
        audit(a.id(), "ADMIN_BOOTSTRAP", a.id());
        return issue(a);
    }

    private Account createAccount(Create c) {
        if (
            c == null ||
            c.name() == null ||
            c.name().isBlank() ||
            c.name().length() > 120 ||
            c.email() == null ||
            !c.email().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") ||
            c.email().length() > 254 ||
            c.role() == null ||
            !Set.of("ADMIN", "RECRUITER", "REVIEWER").contains(c.role())
        ) throw error(400, "Enter a name, valid email and role");
        String email = c.email().trim().toLowerCase(Locale.ROOT);
        if (
            db.queryForObject(
                "SELECT COUNT(*) FROM recruiters WHERE email=?",
                Integer.class,
                email
            ) > 0
        ) throw error(409, "Email already exists");
        String id = UUID.randomUUID().toString();
        db.update(
            "INSERT INTO recruiters (id,email,name,password_hash,staff_role,active) VALUES (?,?,?,?,?,TRUE)",
            id,
            email,
            c.name().trim(),
            passwordHash(c.password()),
            c.role()
        );
        return new Account(id, c.name().trim(), email, c.role(), true, false);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Login c) {
        if (
            c == null || c.email() == null || c.password() == null || c.password().length() > 128
        ) throw error(401, "Invalid email or password");
        var rows = db.queryForList(
            "SELECT * FROM recruiters WHERE email=?",
            c.email().trim().toLowerCase(Locale.ROOT)
        );
        String stored = rows.isEmpty()
            ? "00000000000000000000000000000000:" + "0".repeat(64)
            : (String) rows.getFirst().get("password_hash");
        String[] p = stored.split(":");
        boolean valid = MessageDigest.isEqual(
            derive(c.password(), HexFormat.of().parseHex(p[0])),
            HexFormat.of().parseHex(p[1])
        );
        if (
            !valid || rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("active"))
        ) throw error(401, "Invalid email or password");
        return issue(
            db.queryForObject(
                "SELECT * FROM recruiters WHERE email=?",
                this::row,
                c.email().trim().toLowerCase(Locale.ROOT)
            )
        );
    }

    @GetMapping("/me")
    public Account me(@RequestHeader(value = "Authorization", required = false) String h) {
        return authenticate(h, "ADMIN", "RECRUITER", "REVIEWER");
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(
        @RequestHeader(value = "Authorization", required = false) String h
    ) {
        var a = me(h);
        db.update("DELETE FROM tokens WHERE token=?", hash(h.substring(7)));
        audit(a.id(), "SIGN_OUT", a.id());
        return Map.of("success", true);
    }

    @GetMapping("/accounts")
    public List<Account> accounts(
        @RequestHeader(value = "Authorization", required = false) String h
    ) {
        authenticate(h, "ADMIN");
        return db.query("SELECT * FROM recruiters ORDER BY name,id", this::row);
    }

    @PostMapping("/accounts")
    @Transactional
    public Account create(
        @RequestHeader(value = "Authorization", required = false) String h,
        @RequestBody Create c
    ) {
        var actor = authenticate(h, "ADMIN");
        var a = createAccount(c);
        audit(actor.id(), "ACCOUNT_CREATED", a.id());
        return a;
    }

    @PostMapping("/accounts/{id}")
    @Transactional
    public Account change(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String id,
        @RequestBody Change c
    ) {
        var actor = authenticate(h, "ADMIN");
        if (c == null || !Set.of("ADMIN", "RECRUITER", "REVIEWER").contains(c.role())) throw error(
            400,
            "Invalid role"
        );
        if (actor.id().equals(id)) throw error(
            400,
            "Ask another administrator to change your own access"
        );
        if (
            db.update(
                "UPDATE recruiters SET active=?,staff_role=?,template_author=CASE WHEN ?=TRUE AND ?='REVIEWER' THEN template_author ELSE FALSE END WHERE id=?",
                c.active(),
                c.role(),
                c.active(),
                c.role(),
                id
            ) == 0
        ) throw error(404, "Account not found");
        db.update("DELETE FROM tokens WHERE account_id=?", id);
        db.update("DELETE FROM password_resets WHERE account_id=?", id);
        if (!c.active() || !c.role().equals("REVIEWER")) {
            db.update("DELETE FROM reviewer_assignments WHERE reviewer_id=?", id);
            db.update("DELETE FROM template_review_assignments WHERE reviewer_id=?", id);
        }
        audit(actor.id(), "ACCESS_CHANGED", id);
        return db.queryForObject("SELECT * FROM recruiters WHERE id=?", this::row, id);
    }

    public record AuthorPermission(boolean enabled) {}

    @PostMapping("/accounts/{id}/author-permission")
    @Transactional
    public Account authorPermission(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String id,
        @RequestBody AuthorPermission input
    ) {
        var actor = authenticate(h, "ADMIN");
        if (input == null) throw error(400, "Choose the author permission");
        if (
            db.queryForObject(
                "SELECT COUNT(*) FROM recruiters WHERE id=? AND staff_role='REVIEWER' AND active=TRUE",
                Integer.class,
                id
            ) == 0
        ) throw error(400, "Choose an active reviewer");
        db.update("UPDATE recruiters SET template_author=? WHERE id=?", input.enabled(), id);
        db.update("DELETE FROM tokens WHERE account_id=?", id);
        audit(actor.id(), input.enabled() ? "AUTHOR_ACCESS_GRANTED" : "AUTHOR_ACCESS_REVOKED", id);
        return db.queryForObject("SELECT * FROM recruiters WHERE id=?", this::row, id);
    }

    @PostMapping("/accounts/{id}/reset")
    @Transactional
    public Map<String, Object> reset(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String id
    ) {
        var actor = authenticate(h, "ADMIN");
        if (
            db.queryForObject(
                "SELECT COUNT(*) FROM recruiters WHERE id=? AND active=TRUE",
                Integer.class,
                id
            ) == 0
        ) throw error(404, "Active account not found");
        String token = random();
        long expires = System.currentTimeMillis() + 1800000;
        db.update("DELETE FROM password_resets WHERE account_id=?", id);
        db.update("INSERT INTO password_resets VALUES (?,?,?)", hash(token), id, expires);
        audit(actor.id(), "RESET_ISSUED", id);
        return Map.of("token", token, "expiresAt", expires);
    }

    @PostMapping("/reset-password")
    @Transactional
    public Map<String, Boolean> resetPassword(@RequestBody Reset input) {
        if (input == null || input.token() == null) throw error(400, "Reset token required");
        password(input.password());
        var ids = db.queryForList(
            "SELECT p.account_id FROM password_resets p JOIN recruiters a ON a.id=p.account_id WHERE p.token=? AND p.expires>? AND a.active=TRUE FOR UPDATE",
            String.class,
            hash(input.token()),
            System.currentTimeMillis()
        );
        if (ids.isEmpty()) throw error(400, "Reset token invalid or expired");
        String id = ids.getFirst();
        db.update(
            "UPDATE recruiters SET password_hash=? WHERE id=?",
            passwordHash(input.password()),
            id
        );
        db.update("DELETE FROM password_resets WHERE account_id=?", id);
        db.update("DELETE FROM tokens WHERE account_id=?", id);
        audit(id, "PASSWORD_RESET", id);
        return Map.of("success", true);
    }

    @GetMapping("/activity")
    public List<Map<String, Object>> activity(
        @RequestHeader(value = "Authorization", required = false) String h
    ) {
        authenticate(h, "ADMIN");
        var events = db.queryForList(
            "SELECT l.*,a.name AS actor_name FROM access_activity l LEFT JOIN recruiters a ON a.id=l.actor ORDER BY created_at DESC,id LIMIT 100"
        );
        Map<String, String> accounts = new HashMap<>(),
            interviews = new HashMap<>(),
            submissions = new HashMap<>();
        for (var event : events) {
            String action = (String) event.get("action"),
                target = (String) event.get("target");
            if (event.get("actor_name") == null) event.put("ACTOR_NAME", "Legacy reviewer");
            String label = switch (action) {
                case "CANDIDATE_ADDED" -> "Added a candidate to the pool.";
                case "CANDIDATE_UPDATED" -> "Updated candidate details.";
                case "CANDIDATES_IMPORTED" -> "Imported candidates from CSV.";
                case "BULK_INVITATIONS_CREATED" -> "Generated a batch of candidate invitations.";
                case "INVITATION_EMAIL_QUEUED" -> "Queued an invitation email.";
                case "INVITATION_REMINDER_QUEUED" -> "Queued an assessment reminder.";
                case "AUTHOR_ACCESS_GRANTED" -> "Granted template author permission.";
                case "AUTHOR_ACCESS_REVOKED" -> "Revoked template author permission.";
                case "TEMPLATE_DRAFT_CREATED" -> "Created an assessment draft.";
                case "TEMPLATE_DRAFT_SAVED" -> "Saved an assessment draft.";
                case "AI_TEMPLATE_DRAFTED" -> "Generated an AI assessment draft.";
                case "TEMPLATE_DRAFT_VALIDATED" -> "Validated an assessment draft.";
                case "TEMPLATE_SUBMITTED" -> "Submitted a template for approval.";
                case "TEMPLATE_RETURNED" -> "Returned a template to its author.";
                case "SIGN_IN" -> "Signed in.";
                case "SIGN_OUT" -> "Signed out.";
                case "ADMIN_BOOTSTRAP" -> "Set up the first administrator account.";
                case "ACCOUNT_CREATED" -> "Created a staff account.";
                case "ACCESS_CHANGED" -> "Updated account access.";
                case "RESET_ISSUED" -> "Issued a password reset token.";
                case "PASSWORD_RESET" -> "Reset the account password.";
                case "TEMPLATE_APPROVER_ASSIGNED" -> "Updated the template approver assignment.";
                case "TEMPLATE_APPROVED" -> "Approved an assessment template.";
                case "REVIEWER_ASSIGNED" -> "Updated the reviewer assignment.";
                case "REVIEW_SAVED" -> "Saved a candidate review.";
                case "EVALUATION_SAVED" -> "Saved an evaluation draft.";
                case "EVALUATION_PUBLISHED" -> "Published a candidate evaluation.";
                case "AI_PLAN_APPROVED" -> "Approved an AI preliminary interview plan.";
                case "HIRING_PROCESS_CREATED" -> "Created a hiring process.";
                case "PROCESS_CANDIDATES_INVITED" -> "Invited candidates to a hiring process.";
                case "AI_REVIEW_WAIVED" -> "Waived human review of an AI preliminary round.";
                case "AI_ROUND_SKIPPED" -> "Skipped a candidate's AI preliminary round.";
                case "AI_ROUND_SKIPPED_ALL" -> "Skipped AI preliminary rounds for all invited candidates.";
                case "PROCESS_HOLD" -> "Paused a candidate's hiring process.";
                case "PROCESS_RESUME" -> "Resumed a candidate's hiring process.";
                case "PROCESS_END" -> "Closed a candidate's hiring process.";
                case "PROCESS_INVITE_NEXT" -> "Invited a candidate to the next assessment round.";
                case "AI_INTERVIEW_REVIEW_SAVED" -> "Saved an AI interview review draft.";
                case "AI_INTERVIEW_REVIEW_PUBLISHED" -> "Published a human review of an AI interview.";
                default -> "Recorded an activity.";
            };
            String detail;
            if (
                Set.of(
                    "TEMPLATE_DRAFT_CREATED",
                    "TEMPLATE_DRAFT_SAVED",
                    "AI_TEMPLATE_DRAFTED",
                    "TEMPLATE_DRAFT_VALIDATED",
                    "TEMPLATE_SUBMITTED",
                    "TEMPLATE_RETURNED"
                ).contains(action)
            ) {
                detail = "Assessment version: " + target;
            } else if (
                action.equals("AUTHOR_ACCESS_GRANTED") || action.equals("AUTHOR_ACCESS_REVOKED")
            ) {
                detail = accounts.computeIfAbsent(target, this::accountLabel);
            } else if (action.equals("CANDIDATE_ADDED") || action.equals("CANDIDATE_UPDATED")) {
                detail = lookup(
                    "SELECT name || ' (' || email || ')' FROM candidate_pool WHERE id=?",
                    target,
                    "Candidate no longer available"
                );
            } else if (action.equals("CANDIDATES_IMPORTED")) {
                detail = target + " candidates added";
            } else if (action.equals("BULK_INVITATIONS_CREATED")) {
                detail = lookup(
                    "SELECT j.title FROM invitation_batches b JOIN interviews j ON j.id=b.interview_id WHERE b.id=?",
                    target,
                    "Invitation batch no longer available"
                );
            } else if (
                action.equals("INVITATION_EMAIL_QUEUED") ||
                action.equals("INVITATION_REMINDER_QUEUED")
            ) {
                detail = lookup(
                    "SELECT i.candidate_name || ' - ' || j.title FROM invitations i JOIN interviews j ON j.id=i.interview_id WHERE i.id=?",
                    target,
                    "Invitation no longer available"
                );
            } else if (action.equals("TEMPLATE_APPROVED")) {
                detail =
                    lookup(
                        "SELECT title FROM assessment_templates WHERE version=?",
                        target,
                        "Template no longer available"
                    ) +
                    " - " +
                    target;
            } else if (action.equals("TEMPLATE_APPROVER_ASSIGNED")) {
                String[] parts = target.split(":", 2);
                detail =
                    lookup(
                        "SELECT title FROM assessment_templates WHERE version=?",
                        parts[0],
                        "Template no longer available"
                    ) +
                    " - " +
                    parts[0];
                if (parts.length == 2) {
                    if (parts[1].equals("unassigned")) {
                        label = "Removed the template approver assignment.";
                    } else {
                        label = "Assigned a template approver.";
                        detail += " - " + accounts.computeIfAbsent(parts[1], this::accountLabel);
                    }
                }
            } else if (action.equals("REVIEWER_ASSIGNED")) {
                String[] parts = target.split(":", 2);
                detail = interviews.computeIfAbsent(parts[0], id ->
                    lookup(
                        "SELECT title FROM interviews WHERE id=?",
                        id,
                        "Interview no longer available"
                    )
                );
                if (parts.length == 2) {
                    if (parts[1].isBlank() || parts[1].equals("unassigned")) {
                        label = "Removed the reviewer assignment.";
                        detail += " - Unassigned";
                    } else {
                        label = "Assigned a reviewer to an interview.";
                        detail += " - " + accounts.computeIfAbsent(parts[1], this::accountLabel);
                    }
                }
            } else if (
                Set.of("REVIEW_SAVED", "EVALUATION_SAVED", "EVALUATION_PUBLISHED").contains(action)
            ) {
                detail = submissions.computeIfAbsent(target, id ->
                    lookup(
                        "SELECT COALESCE(i.candidate_name,'Legacy demo candidate') || ' - ' || COALESCE(j.title,'Shared-code assessment') FROM sessions s LEFT JOIN invitations i ON i.id=s.invitation_id LEFT JOIN interviews j ON j.id=s.interview_id WHERE s.id=?",
                        id,
                        "Submission no longer available"
                    )
                );
            } else if (
                Set.of(
                    "SIGN_IN",
                    "SIGN_OUT",
                    "ADMIN_BOOTSTRAP",
                    "ACCOUNT_CREATED",
                    "ACCESS_CHANGED",
                    "RESET_ISSUED",
                    "PASSWORD_RESET"
                ).contains(action)
            ) {
                detail = accounts.computeIfAbsent(target, this::accountLabel);
            } else detail = "Additional details unavailable";
            event.put("ACTION_LABEL", label);
            event.put("TARGET_LABEL", detail);
        }
        return events;
    }

    private String lookup(String sql, String id, String fallback) {
        var rows = db.queryForList(sql, String.class, id);
        return rows.isEmpty() ? fallback : rows.getFirst();
    }

    private String accountLabel(String id) {
        return lookup(
            "SELECT name || ' (' || email || ')' FROM recruiters WHERE id=?",
            id,
            "Account no longer available"
        );
    }

    @GetMapping("/assignments")
    public List<Map<String, Object>> assignments(
        @RequestHeader(value = "Authorization", required = false) String h
    ) {
        authenticate(h, "ADMIN");
        return db.queryForList(
            "SELECT j.id,j.title,j.scheduled_at,j.template_version,(SELECT LISTAGG(DISTINCT hp.title, ' / ') WITHIN GROUP (ORDER BY hp.title) FROM hiring_rounds hr JOIN hiring_processes hp ON hp.id=hr.process_id WHERE hr.interview_id=j.id) AS process_titles,o.name AS recruiter_name,a.reviewer_id,r.name AS reviewer_name FROM interviews j JOIN recruiters o ON o.id=j.recruiter_id LEFT JOIN reviewer_assignments a ON a.interview_id=j.id LEFT JOIN recruiters r ON r.id=a.reviewer_id ORDER BY j.created_at DESC"
        );
    }

    @GetMapping("/assignments/page")
    public Map<String, Object> assignmentPage(
        @RequestHeader(value = "Authorization", required = false) String h,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "") String search,
        @RequestParam(defaultValue = "") String recruiter,
        @RequestParam(defaultValue = "") String reviewer,
        @RequestParam(defaultValue = "ALL") String status
    ) {
        authenticate(h, "ADMIN");
        if (!Set.of("ALL", "ASSIGNED", "UNASSIGNED").contains(status)) throw error(
            400,
            "Choose a valid assignment status"
        );
        String joins =
            " FROM interviews j JOIN recruiters o ON o.id=j.recruiter_id LEFT JOIN reviewer_assignments a ON a.interview_id=j.id LEFT JOIN recruiters r ON r.id=a.reviewer_id";
        String where =
            " WHERE LOWER(j.title) LIKE ? ESCAPE '!' AND LOWER(o.name) LIKE ? ESCAPE '!' AND LOWER(COALESCE(r.name,'') || ' ' || COALESCE(r.email,'')) LIKE ? ESCAPE '!'";
        if (status.equals("ASSIGNED")) where += " AND a.reviewer_id IS NOT NULL";
        if (status.equals("UNASSIGNED")) where += " AND a.reviewer_id IS NULL";
        Object[] filters = {
            assignmentPattern(search),
            assignmentPattern(recruiter),
            assignmentPattern(reviewer)
        };
        long total = db.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, filters);
        int pages = Math.max(1, (int) ((total + 9) / 10));
        int current = Math.max(0, Math.min(page, pages - 1));
        var args = new ArrayList<Object>(Arrays.asList(filters));
        args.add(10);
        args.add(current * 10);
        var items = db.queryForList(
            "SELECT j.id,j.title,j.scheduled_at,j.template_version,o.name AS recruiter_name,a.reviewer_id,r.name AS reviewer_name,r.email AS reviewer_email,(SELECT COUNT(DISTINCT hr.process_id) FROM hiring_rounds hr WHERE hr.interview_id=j.id) AS process_count" +
                joins +
                where +
                " ORDER BY j.created_at DESC,j.id LIMIT ? OFFSET ?",
            args.toArray()
        );
        return Map.of("items", items, "page", current, "pages", pages, "total", total);
    }

    private String assignmentPattern(String value) {
        if (value.length() > 200) throw error(400, "Search must be 200 characters or fewer");
        return (
            "%" +
            value
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") +
            "%"
        );
    }

    @GetMapping("/assignments/{id}/processes")
    public Map<String, Object> assignmentProcesses(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String id,
        @RequestParam(defaultValue = "0") int page
    ) {
        authenticate(h, "ADMIN");
        long total = db.queryForObject(
            "SELECT COUNT(DISTINCT process_id) FROM hiring_rounds WHERE interview_id=?",
            Long.class,
            id
        );
        int pages = Math.max(1, (int) ((total + 19) / 20));
        int current = Math.max(0, Math.min(page, pages - 1));
        var items = db.queryForList(
            "SELECT DISTINCT hp.id,hp.title FROM hiring_rounds hr JOIN hiring_processes hp ON hp.id=hr.process_id WHERE hr.interview_id=? ORDER BY hp.title,hp.id LIMIT 20 OFFSET ?",
            id,
            current * 20
        );
        return Map.of("items", items, "page", current, "pages", pages);
    }

    @GetMapping("/reviewer-options")
    public List<Map<String, Object>> reviewerOptions(
        @RequestHeader(value = "Authorization", required = false) String h,
        @RequestParam(defaultValue = "") String search
    ) {
        authenticate(h, "ADMIN");
        String pattern = assignmentPattern(search);
        return db.queryForList(
            "SELECT id,name,email FROM recruiters WHERE active=TRUE AND staff_role='REVIEWER' AND (LOWER(name) LIKE ? ESCAPE '!' OR LOWER(email) LIKE ? ESCAPE '!') ORDER BY name,id LIMIT 20",
            pattern,
            pattern
        );
    }

    @PostMapping("/assignments/{id}")
    @Transactional
    public Map<String, Boolean> assign(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String id,
        @RequestBody Assignment c
    ) {
        var actor = authenticate(h, "ADMIN");
        if (
            db.queryForObject("SELECT COUNT(*) FROM interviews WHERE id=?", Integer.class, id) == 0
        ) throw error(404, "Interview not found");
        if (c == null) throw error(400, "Reviewer required");
        if (c.reviewerId() == null || c.reviewerId().isBlank()) db.update(
            "DELETE FROM reviewer_assignments WHERE interview_id=?",
            id
        );
        else {
            if (
                db.queryForObject(
                    "SELECT COUNT(*) FROM recruiters WHERE id=? AND active=TRUE AND staff_role='REVIEWER'",
                    Integer.class,
                    c.reviewerId()
                ) == 0
            ) throw error(400, "Choose an active reviewer");
            db.update(
                "MERGE INTO reviewer_assignments KEY(interview_id) VALUES (?,?)",
                id,
                c.reviewerId()
            );
        }
        db.update(
            "UPDATE hiring_rounds SET reviewer_id=? WHERE kind='AI_PRELIMINARY' AND process_id IN (SELECT process_id FROM hiring_rounds WHERE position=1 AND interview_id=?)",
            c.reviewerId() == null || c.reviewerId().isBlank() ? null : c.reviewerId(),
            id
        );
        audit(
            actor.id(),
            "REVIEWER_ASSIGNED",
            id + ":" + (c.reviewerId() == null ? "unassigned" : c.reviewerId())
        );
        return Map.of("success", true);
    }

    @GetMapping("/template-assignments")
    public List<Map<String, Object>> templateAssignments(
        @RequestHeader(value = "Authorization", required = false) String h
    ) {
        authenticate(h, "ADMIN");
        return db.queryForList(
            "SELECT t.version,t.title,t.status,a.reviewer_id,r.name AS reviewer_name FROM assessment_templates t LEFT JOIN template_review_assignments a ON a.version=t.version LEFT JOIN recruiters r ON r.id=a.reviewer_id ORDER BY t.title,t.version"
        );
    }

    @PostMapping("/template-assignments/{version}")
    @Transactional
    public Map<String, Boolean> assignTemplate(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String version,
        @RequestBody Assignment input
    ) {
        var actor = authenticate(h, "ADMIN");
        if (input == null) throw error(400, "Choose an approver");
        if (
            db.queryForObject(
                "SELECT COUNT(*) FROM assessment_templates WHERE version=?",
                Integer.class,
                version
            ) == 0
        ) throw error(404, "Template not found");
        String reviewer = input.reviewerId();
        if (
            reviewer != null &&
            db.queryForObject(
                "SELECT COUNT(*) FROM assessment_templates WHERE version=? AND author_id=?",
                Integer.class,
                version,
                reviewer
            ) > 0
        ) throw error(400, "An author cannot approve their own version");
        if (reviewer == null || reviewer.isBlank()) db.update(
            "DELETE FROM template_review_assignments WHERE version=?",
            version
        );
        else {
            if (
                db.queryForObject(
                    "SELECT COUNT(*) FROM recruiters WHERE id=? AND active=TRUE AND staff_role='REVIEWER'",
                    Integer.class,
                    reviewer
                ) == 0
            ) throw error(400, "Choose an active reviewer");
            db.update(
                "MERGE INTO template_review_assignments KEY(version) VALUES (?,?)",
                version,
                reviewer
            );
        }
        audit(
            actor.id(),
            "TEMPLATE_APPROVER_ASSIGNED",
            version + ":" + (reviewer == null || reviewer.isBlank() ? "unassigned" : reviewer)
        );
        return Map.of("success", true);
    }

    public void requireTemplate(String h, String version) {
        String reviewer = namedReviewer(h);
        if (
            reviewer != null &&
            db.queryForObject(
                "SELECT COUNT(*) FROM template_review_assignments WHERE version=? AND reviewer_id=?",
                Integer.class,
                version,
                reviewer
            ) == 0
        ) throw error(404, "Assigned template not found");
    }

    public boolean assignedTemplate(String h, String version) {
        String reviewer = namedReviewer(h);
        return (
            reviewer == null ||
            db.queryForObject(
                "SELECT COUNT(*) FROM template_review_assignments WHERE version=? AND reviewer_id=?",
                Integer.class,
                version,
                reviewer
            ) > 0
        );
    }

    public void requireSession(String h, String id) {
        String account = namedReviewer(h);
        if (
            account != null &&
            db.queryForObject(
                "SELECT COUNT(*) FROM sessions s JOIN reviewer_assignments a ON a.interview_id=s.interview_id WHERE s.id=? AND a.reviewer_id=?",
                Integer.class,
                id,
                account
            ) == 0
        ) throw error(404, "Assigned submission not found");
    }

    public String namedReviewer(String h) {
        if (h == null || !h.startsWith("Bearer ")) return null;
        var ids = db.queryForList(
            "SELECT account_id FROM tokens WHERE token=? AND account_id IS NOT NULL",
            String.class,
            hash(h.substring(7))
        );
        return ids.isEmpty() ? null : ids.getFirst();
    }
}
