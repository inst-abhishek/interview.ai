package com.exl.interview.api;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TemplateValidator {

    public List<String> errors(TemplateCatalogue.Definition d) {
        List<String> errors = new ArrayList<>();
        if (d == null) return List.of("Assessment details are required");
        if (d.version() == null || !d.version().matches("[a-z0-9][a-z0-9-]{2,79}")) errors.add(
            "Version must be 3-80 lowercase letters, digits or hyphens"
        );
        if (blank(d.title(), 200)) errors.add("Enter a title up to 200 characters");
        if (blank(d.description(), 4000)) errors.add("Enter a description up to 4,000 characters");
        if (d.durationMinutes() < 5 || d.durationMinutes() > 240) errors.add(
            "Duration must be 5-240 minutes"
        );
        if (
            d.skills() == null ||
            d.skills().isEmpty() ||
            d.skills().size() > 20 ||
            d
                .skills()
                .stream()
                .anyMatch(s -> blank(s, 100))
        ) errors.add("Provide 1-20 skills, each up to 100 characters");
        if (blank(d.reviewerInstructions(), 5000)) errors.add(
            "Provide reviewer instructions up to 5,000 characters"
        );
        if (d.items() == null || d.items().isEmpty() || d.items().size() > 50) {
            errors.add("Provide 1-50 questions");
            return errors;
        }
        Set<String> ids = new HashSet<>();
        int total = 0,
            coding = 0;
        for (int n = 0; n < d.items().size(); n++) {
            var q = d.items().get(n);
            String prefix = "Question " + (n + 1) + ": ";
            if (q == null) {
                errors.add(prefix + "question is missing");
                continue;
            }
            if (
                q.id() == null || !q.id().matches("[a-zA-Z0-9-]{1,80}") || !ids.add(q.id())
            ) errors.add(prefix + "use a unique question ID");
            if (blank(q.text(), 10000)) errors.add(
                prefix + "provide a question up to 10,000 characters"
            );
            if (q.maxPoints() < 1 || q.maxPoints() > 100) errors.add(
                prefix + "points must be 1-100"
            );
            total += q.maxPoints();
            if (
                !Set.of("MCQ", "TECHNICAL", "CODING").contains(
                    Objects.requireNonNullElse(q.kind(), "")
                )
            ) {
                errors.add(prefix + "choose MCQ, written or Java coding");
                continue;
            }
            if (q.kind().equals("MCQ")) {
                if (
                    q.choices() == null ||
                    q.choices().size() < 2 ||
                    q.choices().size() > 8 ||
                    q
                        .choices()
                        .stream()
                        .anyMatch(c -> blank(c, 1000)) ||
                    new HashSet<>(q.choices()).size() != q.choices().size() ||
                    q.correctAnswer() == null ||
                    !q.choices().contains(q.correctAnswer())
                ) errors.add(prefix + "provide 2-8 distinct choices and a matching answer key");
                if (blank(q.explanation(), 4000)) errors.add(prefix + "provide an explanation");
                if (q.rubric() != null && !q.rubric().isEmpty()) errors.add(
                    prefix + "MCQs must not have a manual rubric"
                );
            } else {
                int rubricTotal = 0;
                Set<String> criteria = new HashSet<>();
                if (
                    q.rubric() == null || q.rubric().isEmpty() || q.rubric().size() > 10
                ) errors.add(prefix + "provide 1-10 rubric criteria");
                else for (var r : q.rubric()) {
                    if (
                        r == null ||
                        r.id() == null ||
                        !r.id().matches("[a-zA-Z0-9-]{1,80}") ||
                        !criteria.add(r.id()) ||
                        blank(r.label(), 200) ||
                        blank(r.guidance(), 4000) ||
                        r.maxPoints() < 1 ||
                        r.maxPoints() > 100
                    ) errors.add(
                        prefix +
                            "each criterion needs a unique ID, label, guidance and valid points"
                    );
                    else rubricTotal += r.maxPoints();
                }
                if (q.kind().equals("TECHNICAL") && rubricTotal != q.maxPoints()) errors.add(
                    prefix + "rubric points must equal question points"
                );
                if (q.kind().equals("CODING")) {
                    coding++;
                    if (rubricTotal >= q.maxPoints()) errors.add(
                        prefix + "reserve some points for automatic coding tests"
                    );
                    if (
                        blank(q.starterCode(), 20000) ||
                        blank(q.referenceSolution(), 20000) ||
                        !q.starterCode().contains("class Main") ||
                        !q.referenceSolution().contains("class Main")
                    ) errors.add(
                        prefix + "provide Java starter/reference code with public class Main"
                    );
                    Set<String> tests = new HashSet<>();
                    checkTests(q.publicTests(), "public", prefix, tests, errors);
                    checkTests(q.hiddenTests(), "hidden", prefix, tests, errors);
                }
            }
            if (
                !q.kind().equals("MCQ") && q.choices() != null && !q.choices().isEmpty()
            ) errors.add(prefix + "only MCQs can have choices");
        }
        if (total != 100) errors.add("Question points must total 100 (currently " + total + ")");
        if (coding > 1) errors.add("Only one Java coding question is supported per assessment");
        if (
            coding > 0 &&
            (d.title() == null || !d.title().toLowerCase(Locale.ROOT).contains("java"))
        ) errors.add("Coding questions are supported only for Java roles");
        return errors;
    }

    private void checkTests(
        List<TemplateCatalogue.TestCase> cases,
        String kind,
        String prefix,
        Set<String> ids,
        List<String> errors
    ) {
        if (cases == null || cases.isEmpty() || cases.size() > 10) {
            errors.add(prefix + "provide 1-10 " + kind + " tests");
            return;
        }
        for (var t : cases)
            if (
                t == null ||
                t.id() == null ||
                !t.id().matches("[a-zA-Z0-9-]{1,80}") ||
                !ids.add(t.id()) ||
                t.input() == null ||
                t.input().length() > 10000 ||
                t.expected() == null ||
                t.expected().length() > 10000
            ) errors.add(prefix + "invalid or duplicate " + kind + " test");
    }

    private boolean blank(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }

    public void requireValid(TemplateCatalogue.Definition d) {
        var e = errors(d);
        if (!e.isEmpty()) throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            String.join("; ", e)
        );
    }
}
