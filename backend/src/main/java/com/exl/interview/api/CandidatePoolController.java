package com.exl.interview.api;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recruiter/pool")
@CrossOrigin(origins = "http://127.0.0.1:4200")
public class CandidatePoolController {

    private final JdbcTemplate db;
    private final RecruiterController recruiters;
    private final StaffAccess staff;
    private final InvitationLinks links;
    private final InvitationDelivery delivery;

    @Value("${interview.public-app-url:http://127.0.0.1:4200}")
    private String publicUrl;

    public CandidatePoolController(
        JdbcTemplate db,
        RecruiterController recruiters,
        StaffAccess staff,
        InvitationLinks links,
        InvitationDelivery delivery
    ) {
        this.db = db;
        this.recruiters = recruiters;
        this.staff = staff;
        this.links = links;
        this.delivery = delivery;
    }

    public record Input(String name, String email, String skills, Double experience) {}

    public record Candidate(
        String id,
        String name,
        String email,
        String skills,
        Double experience,
        long createdAt
    ) {}

    public record Page(List<Candidate> items, int page, int size, long total, int totalPages) {}

    public record CsvInput(String csv) {}

    public record ImportRow(
        int row,
        String name,
        String email,
        String skills,
        Double experience,
        String status,
        String message
    ) {}

    public record ImportPreview(List<ImportRow> rows, long ready, long invalid, long duplicates) {}

    public record ImportResult(int imported, int skipped) {}

    public record BulkInput(
        String interviewId,
        List<String> candidateIds,
        Long expiresAt,
        boolean sendEmail,
        String requestKey
    ) {}

    public record Recipient(
        String candidateId,
        String name,
        String email,
        String status,
        String invitationId
    ) {}

    public record Preview(
        String interviewId,
        String interviewTitle,
        long scheduledAt,
        int durationSeconds,
        long expiresAt,
        List<Recipient> recipients,
        long ready,
        long alreadyInvited,
        boolean emailAvailable,
        String publicAppUrl
    ) {}

    public record BatchItem(
        String candidateId,
        String name,
        String email,
        String outcome,
        String invitationId,
        String url,
        String deliveryStatus,
        int attempts,
        String lastError,
        String assessmentState,
        long expiresAt
    ) {}

    public record Batch(
        String id,
        String interviewTitle,
        List<BatchItem> items,
        int created,
        int skipped
    ) {}

    private ResponseStatusException fail(int status, String message) {
        return new ResponseStatusException(HttpStatus.valueOf(status), message);
    }

    private String text(String v, int max, String field) {
        if (v == null || v.isBlank() || v.trim().length() > max) throw fail(
            400,
            field + " is required (maximum " + max + " characters)"
        );
        return v.trim();
    }

    private Input validate(Input in) {
        if (in == null) throw fail(400, "Candidate details required");
        String name = text(in.name(), 120, "Name"),
            email = text(in.email(), 254, "Email").toLowerCase(Locale.ROOT),
            skills = in.skills() == null ? "" : in.skills().trim();
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw fail(400, "Enter a valid email");
        if (skills.length() > 500) throw fail(400, "Skills must be at most 500 characters");
        if (
            in.experience() != null &&
            (!Double.isFinite(in.experience()) || in.experience() < 0 || in.experience() > 60)
        ) throw fail(400, "Experience must be between 0 and 60 years");
        return new Input(name, email, skills, in.experience());
    }

    private Candidate row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        return new Candidate(
            r.getString("id"),
            r.getString("name"),
            r.getString("email"),
            r.getString("skills"),
            (Double) r.getObject("experience"),
            r.getLong("created_at")
        );
    }

    private void ownerLock(String owner) {
        db.queryForObject("SELECT id FROM recruiters WHERE id=? FOR UPDATE", String.class, owner);
    }

    private String pattern(String value) {
        return (
            "%" +
            value
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") +
            "%"
        );
    }

    @GetMapping
    public Page list(
        @RequestHeader(value = "Authorization", required = false) String h,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "") String search,
        @RequestParam(defaultValue = "") String skill,
        @RequestParam(required = false) Double minExperience
    ) {
        String owner = recruiters.auth(h);
        if (
            page < 0 ||
            !Set.of(10, 25, 50).contains(size) ||
            search.length() > 200 ||
            skill.length() > 100 ||
            (minExperience != null &&
                (!Double.isFinite(minExperience) || minExperience < 0 || minExperience > 60))
        ) throw fail(400, "Invalid candidate filters");
        String where = " WHERE recruiter_id=?";
        List<Object> args = new ArrayList<>(List.of(owner));
        if (!search.isBlank()) {
            where += " AND (LOWER(name) LIKE ? ESCAPE '!' OR LOWER(email) LIKE ? ESCAPE '!')";
            args.add(pattern(search.trim()));
            args.add(pattern(search.trim()));
        }
        if (!skill.isBlank()) {
            where += " AND LOWER(skills) LIKE ? ESCAPE '!'";
            args.add(pattern(skill.trim()));
        }
        if (minExperience != null) {
            where += " AND experience>=?";
            args.add(minExperience);
        }
        long total = db.queryForObject(
            "SELECT COUNT(*) FROM candidate_pool" + where,
            Long.class,
            args.toArray()
        );
        int pages = (int) Math.max(1, (total + size - 1) / size),
            current = Math.min(page, pages - 1);
        args.add(size);
        args.add((long) current * size);
        return new Page(
            db.query(
                "SELECT * FROM candidate_pool" +
                    where +
                    " ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                this::row,
                args.toArray()
            ),
            current,
            size,
            total,
            pages
        );
    }

    private Candidate insert(String owner, Input input) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        db.update(
            "INSERT INTO candidate_pool VALUES (?,?,?,?,?,?,?)",
            id,
            owner,
            input.name(),
            input.email(),
            input.skills(),
            input.experience(),
            now
        );
        return new Candidate(
            id,
            input.name(),
            input.email(),
            input.skills(),
            input.experience(),
            now
        );
    }

    @PostMapping
    @Transactional
    public Candidate add(
        @RequestHeader(value = "Authorization", required = false) String h,
        @RequestBody Input in
    ) {
        String owner = recruiters.auth(h);
        Input input = validate(in);
        ownerLock(owner);
        if (
            db.queryForObject(
                "SELECT COUNT(*) FROM candidate_pool WHERE recruiter_id=? AND email=?",
                Integer.class,
                owner,
                input.email()
            ) > 0
        ) throw fail(409, "This email is already in your candidate pool");
        var c = insert(owner, input);
        staff.audit(owner, "CANDIDATE_ADDED", c.id());
        return c;
    }

    @PostMapping("/{id}")
    @Transactional
    public Candidate update(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String id,
        @RequestBody Input in
    ) {
        String owner = recruiters.auth(h);
        Input input = validate(in);
        ownerLock(owner);
        if (
            db.queryForObject(
                "SELECT COUNT(*) FROM candidate_pool WHERE id=? AND recruiter_id=?",
                Integer.class,
                id,
                owner
            ) == 0
        ) throw fail(404, "Candidate not found");
        if (
            db.queryForObject(
                "SELECT COUNT(*) FROM candidate_pool WHERE recruiter_id=? AND email=? AND id<>?",
                Integer.class,
                owner,
                input.email(),
                id
            ) > 0
        ) throw fail(409, "This email belongs to another candidate in your pool");
        db.update(
            "UPDATE candidate_pool SET name=?,email=?,skills=?,experience=? WHERE id=? AND recruiter_id=?",
            input.name(),
            input.email(),
            input.skills(),
            input.experience(),
            id,
            owner
        );
        staff.audit(owner, "CANDIDATE_UPDATED", id);
        return db.queryForObject(
            "SELECT * FROM candidate_pool WHERE id=? AND recruiter_id=?",
            this::row,
            id,
            owner
        );
    }

    private ImportPreview previewCsv(String owner, CsvInput input) {
        var parsed = CandidateCsv.parse(input == null ? null : input.csv());
        List<String> headers = parsed
            .getFirst()
            .stream()
            .map(v -> v.trim().toLowerCase(Locale.ROOT))
            .toList();
        if (
            new HashSet<>(headers).size() != headers.size() ||
            !headers.containsAll(List.of("name", "email")) ||
            headers
                .stream()
                .anyMatch(v -> !Set.of("name", "email", "skills", "experience").contains(v))
        ) throw fail(
            400,
            "CSV headers must include name,email; optional columns are skills,experience"
        );
        Set<String> importedEmails = new HashSet<>();
        for (int i = 1; i < parsed.size(); i++) importedEmails.add(
            cell(headers, parsed.get(i), "email").trim().toLowerCase(Locale.ROOT)
        );
        Set<String> existing = new HashSet<>();
        if (!importedEmails.isEmpty()) {
            List<Object> args = new ArrayList<>(List.of(owner));
            args.addAll(importedEmails);
            existing.addAll(
                db.queryForList(
                    "SELECT email FROM candidate_pool WHERE recruiter_id=? AND email IN (" +
                        String.join(",", Collections.nCopies(importedEmails.size(), "?")) +
                        ")",
                    String.class,
                    args.toArray()
                )
            );
        }
        Set<String> seen = new HashSet<>();
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 1; i < parsed.size(); i++) {
            var values = parsed.get(i);
            String name = cell(headers, values, "name"),
                email = cell(headers, values, "email").trim().toLowerCase(Locale.ROOT),
                skills = cell(headers, values, "skills");
            Double exp = null;
            String status = "READY",
                message = "Ready to import";
            try {
                if (values.size() != headers.size()) throw fail(
                    400,
                    "Column count does not match the header"
                );
                String years = cell(headers, values, "experience").trim();
                exp = years.isBlank() ? null : Double.valueOf(years);
                Input clean = validate(new Input(name, email, skills, exp));
                name = clean.name();
                email = clean.email();
                skills = clean.skills();
                if (!seen.add(email)) {
                    status = "DUPLICATE";
                    message = "Repeated email in this file";
                } else if (existing.contains(email)) {
                    status = "EXISTING";
                    message = "Already in your candidate pool; existing details will be kept";
                }
            } catch (NumberFormatException e) {
                status = "INVALID";
                message = "Experience must be a number between 0 and 60";
                exp = null;
            } catch (ResponseStatusException e) {
                status = "INVALID";
                message = e.getReason();
                exp = null;
            }
            rows.add(new ImportRow(i + 1, name, email, skills, exp, status, message));
        }
        return new ImportPreview(
            rows,
            rows
                .stream()
                .filter(r -> r.status().equals("READY"))
                .count(),
            rows
                .stream()
                .filter(r -> r.status().equals("INVALID"))
                .count(),
            rows
                .stream()
                .filter(r -> Set.of("DUPLICATE", "EXISTING").contains(r.status()))
                .count()
        );
    }

    private String cell(List<String> headers, List<String> values, String column) {
        int index = headers.indexOf(column);
        return index < 0 || index >= values.size() ? "" : values.get(index);
    }

    @PostMapping("/import-preview")
    public ImportPreview importPreview(
        @RequestHeader(value = "Authorization", required = false) String h,
        @RequestBody CsvInput input
    ) {
        return previewCsv(recruiters.auth(h), input);
    }

    @PostMapping("/import")
    @Transactional
    public ImportResult importCsv(
        @RequestHeader(value = "Authorization", required = false) String h,
        @RequestBody CsvInput input
    ) {
        String owner = recruiters.auth(h);
        ownerLock(owner);
        var preview = previewCsv(owner, input);
        int count = 0;
        for (var row : preview.rows())
            if (row.status().equals("READY")) {
                insert(owner, new Input(row.name(), row.email(), row.skills(), row.experience()));
                count++;
            }
        staff.audit(owner, "CANDIDATES_IMPORTED", Integer.toString(count));
        return new ImportResult(count, preview.rows().size() - count);
    }

    private Preview preview(String h, BulkInput input, boolean lock) {
        String owner = recruiters.auth(h);
        if (
            input == null ||
            input.interviewId() == null ||
            input.candidateIds() == null ||
            input.candidateIds().isEmpty() ||
            input.candidateIds().size() > 200 ||
            new HashSet<>(input.candidateIds()).size() != input.candidateIds().size() ||
            input.candidateIds().stream().anyMatch(Objects::isNull)
        ) throw fail(400, "Select 1 to 200 distinct candidates and an interview");
        var jobs = db.queryForList(
            "SELECT * FROM interviews WHERE id=? AND recruiter_id=?" + (lock ? " FOR UPDATE" : ""),
            input.interviewId(),
            owner
        );
        if (jobs.isEmpty()) throw fail(404, "Interview not found");
        var job = jobs.getFirst();
        long scheduled = ((Number) job.get("scheduled_at")).longValue();
        int seconds = ((Number) job.get("duration_seconds")).intValue();
        if (
            input.expiresAt() == null ||
            input.expiresAt() <= Math.max(System.currentTimeMillis(), scheduled) + seconds * 1000L
        ) throw fail(400, "Expiry must allow the full assessment after the scheduled start");
        if (input.sendEmail() && !delivery.available()) throw fail(
            503,
            "Email is not configured. Generate invitations for manual sharing instead."
        );
        List<Recipient> recipients = new ArrayList<>();
        for (String id : input.candidateIds()) {
            var candidates = db.query(
                "SELECT * FROM candidate_pool WHERE id=? AND recruiter_id=?",
                this::row,
                id,
                owner
            );
            if (candidates.isEmpty()) throw fail(
                404,
                "A selected candidate is not available in your pool"
            );
            var c = candidates.getFirst();
            var invited = db.queryForList(
                "SELECT i.id FROM invitations i WHERE i.interview_id=? AND (LOWER(i.candidate_email)=? OR EXISTS (SELECT 1 FROM invitation_batch_items bi WHERE bi.invitation_id=i.id AND bi.candidate_id=?)) ORDER BY i.created_at DESC LIMIT 1",
                String.class,
                input.interviewId(),
                c.email(),
                c.id()
            );
            recipients.add(
                new Recipient(
                    id,
                    c.name(),
                    c.email(),
                    invited.isEmpty() ? "READY" : "ALREADY_INVITED",
                    invited.isEmpty() ? null : invited.getFirst()
                )
            );
        }
        long ready = recipients
            .stream()
            .filter(r -> r.status().equals("READY"))
            .count();
        return new Preview(
            input.interviewId(),
            (String) job.get("title"),
            scheduled,
            seconds,
            input.expiresAt(),
            recipients,
            ready,
            recipients.size() - ready,
            delivery.available(),
            publicUrl
        );
    }

    @PostMapping("/bulk-preview")
    public Preview bulkPreview(
        @RequestHeader(value = "Authorization", required = false) String h,
        @RequestBody BulkInput input
    ) {
        return preview(h, input, false);
    }

    @PostMapping("/bulk-invitations")
    @Transactional
    public Batch createBatch(
        @RequestHeader(value = "Authorization", required = false) String h,
        @RequestBody BulkInput input
    ) {
        String owner = recruiters.auth(h);
        if (
            input == null ||
            input.candidateIds() == null ||
            input.candidateIds().isEmpty() ||
            input.candidateIds().size() > 200 ||
            new HashSet<>(input.candidateIds()).size() != input.candidateIds().size() ||
            input.candidateIds().stream().anyMatch(Objects::isNull) ||
            input.requestKey() == null ||
            !input.requestKey().matches("[a-zA-Z0-9-]{8,80}")
        ) throw fail(400, "A valid request key is required");
        ownerLock(owner);
        String payload = StaffAccess.hash(
            input.interviewId() +
                "|" +
                new TreeSet<>(input.candidateIds() == null ? List.of() : input.candidateIds()) +
                "|" +
                input.expiresAt() +
                "|" +
                input.sendEmail()
        );
        var existing = db.queryForList(
            "SELECT id,payload_hash FROM invitation_batches WHERE recruiter_id=? AND request_key=?",
            owner,
            input.requestKey()
        );
        if (!existing.isEmpty()) {
            if (!payload.equals(existing.getFirst().get("payload_hash"))) throw fail(
                409,
                "This request key was already used for another batch"
            );
            return batch(h, (String) existing.getFirst().get("id"));
        }
        var preview = preview(h, input, true);
        String batch = UUID.randomUUID().toString();
        db.update(
            "INSERT INTO invitation_batches VALUES (?,?,?,?,?,?)",
            batch,
            owner,
            input.interviewId(),
            input.requestKey(),
            payload,
            System.currentTimeMillis()
        );
        for (var r : preview.recipients()) {
            String invitation = r.invitationId();
            boolean create = r.status().equals("READY");
            if (create) {
                invitation = recruiters
                    .invite(
                        h,
                        input.interviewId(),
                        new RecruiterController.InvitationInput(
                            r.name(),
                            r.email(),
                            input.expiresAt()
                        )
                    )
                    .invitationId();
                db.update(
                    "INSERT INTO invitation_delivery VALUES (?,?,?,0,?,NULL)",
                    invitation,
                    input.sendEmail() ? "QUEUED" : "NOT_SENT",
                    "INVITATION",
                    System.currentTimeMillis()
                );
            }
            db.update(
                "INSERT INTO invitation_batch_items VALUES (?,?,?,?)",
                batch,
                r.candidateId(),
                invitation,
                create ? "CREATED" : "SKIPPED"
            );
        }
        staff.audit(owner, "BULK_INVITATIONS_CREATED", batch);
        return batch(h, batch);
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches(
        @RequestHeader(value = "Authorization", required = false) String h
    ) {
        return db.queryForList(
            "SELECT b.id,b.created_at,j.title FROM invitation_batches b JOIN interviews j ON j.id=b.interview_id WHERE b.recruiter_id=? ORDER BY b.created_at DESC LIMIT 20",
            recruiters.auth(h)
        );
    }

    @GetMapping("/batches/{id}")
    public Batch batch(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String id
    ) {
        String owner = recruiters.auth(h);
        var batch = db.queryForList(
            "SELECT b.*,j.title FROM invitation_batches b JOIN interviews j ON j.id=b.interview_id WHERE b.id=? AND b.recruiter_id=?",
            id,
            owner
        );
        if (batch.isEmpty()) throw fail(404, "Invitation batch not found");
        var items = db.query(
            "SELECT x.candidate_id,x.outcome,i.id,i.candidate_name,i.candidate_email,i.encrypted_code,i.expires_at,d.status,d.attempts,d.last_error,s.state FROM invitation_batch_items x JOIN invitations i ON i.id=x.invitation_id LEFT JOIN invitation_delivery d ON d.invitation_id=i.id LEFT JOIN sessions s ON s.id=i.session_id WHERE x.batch_id=? ORDER BY i.candidate_name,i.id",
            (r, n) -> {
                String url = "";
                if (
                    r.getLong("expires_at") > System.currentTimeMillis() &&
                    r.getString("encrypted_code") != null
                ) {
                    try {
                        url =
                            publicUrl +
                            "#invite=" +
                            java.net.URLEncoder.encode(
                                links.decrypt(r.getString("encrypted_code")),
                                java.nio.charset.StandardCharsets.UTF_8
                            );
                    } catch (IllegalStateException ignored) {}
                }
                return new BatchItem(
                    r.getString("candidate_id"),
                    r.getString("candidate_name"),
                    r.getString("candidate_email"),
                    r.getString("outcome"),
                    r.getString("id"),
                    url,
                    Objects.requireNonNullElse(r.getString("status"), "NOT_SENT"),
                    r.getInt("attempts"),
                    r.getString("last_error"),
                    Objects.requireNonNullElse(
                        r.getString("state"),
                        r.getLong("expires_at") <= System.currentTimeMillis()
                            ? "EXPIRED"
                            : "INVITED"
                    ),
                    r.getLong("expires_at")
                );
            },
            id
        );
        int created = (int) items
            .stream()
            .filter(i -> i.outcome().equals("CREATED"))
            .count();
        return new Batch(
            id,
            (String) batch.getFirst().get("title"),
            items,
            created,
            items.size() - created
        );
    }

    @GetMapping(value = "/batches/{id}/export", produces = "text/csv")
    public ResponseEntity<String> export(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String id
    ) {
        var batch = batch(h, id);
        StringBuilder csv = new StringBuilder(
            "name,email,outcome,invitation_link,expires_at,delivery_status,assessment_status\r\n"
        );
        for (var item : batch.items())
            csv.append(
                String.join(
                    ",",
                    List.of(
                        item.name(),
                        item.email(),
                        item.outcome(),
                        item.url(),
                        java.time.Instant.ofEpochMilli(item.expiresAt()).toString(),
                        item.deliveryStatus(),
                        item.assessmentState()
                    )
                        .stream()
                        .map(CandidateCsv::exportCell)
                        .toList()
                )
            ).append("\r\n");
        return ResponseEntity.ok()
            .header("Cache-Control", "no-store")
            .header("Content-Disposition", "attachment; filename=invitation-batch.csv")
            .body(csv.toString());
    }

    public record SendInput(boolean reminder, boolean acknowledgeUnknown) {}

    @PostMapping("/invitations/{id}/send")
    @Transactional
    public Map<String, Boolean> send(
        @RequestHeader(value = "Authorization", required = false) String h,
        @PathVariable String id,
        @RequestBody SendInput input
    ) {
        String owner = recruiters.auth(h);
        delivery.queue(
            owner,
            id,
            input != null && input.reminder(),
            input != null && input.acknowledgeUnknown()
        );
        staff.audit(
            owner,
            input != null && input.reminder()
                ? "INVITATION_REMINDER_QUEUED"
                : "INVITATION_EMAIL_QUEUED",
            id
        );
        return Map.of("queued", true);
    }

    @GetMapping("/email-status")
    public Map<String, Object> emailStatus(
        @RequestHeader(value = "Authorization", required = false) String h
    ) {
        recruiters.auth(h);
        return Map.of("available", delivery.available(), "publicAppUrl", publicUrl);
    }
}
