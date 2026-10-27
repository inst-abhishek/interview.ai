package com.exl.interview.api;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController @RequestMapping("/api") @CrossOrigin(origins="http://127.0.0.1:4200")
public class TemplateController {
 public record TemplateInterview(String title,Long scheduledAt){}
 public record CodeInput(String questionId,String source){}
 private final RecruiterController recruiters;private final SessionController sessions;private final TemplateCatalogue catalogue;private final TemplateAssessmentService assessments;
 public TemplateController(RecruiterController recruiters,SessionController sessions,TemplateCatalogue catalogue,TemplateAssessmentService assessments){this.recruiters=recruiters;this.sessions=sessions;this.catalogue=catalogue;this.assessments=assessments;}
 @GetMapping("/recruiter/templates") public List<TemplateCatalogue.Summary> recruiterTemplates(@RequestHeader(value="Authorization",required=false)String h){recruiters.auth(h);return catalogue.enabled()?catalogue.summaries():List.of();}
 @GetMapping("/review/templates") public List<TemplateCatalogue.Summary> reviewTemplates(@RequestHeader(value="Authorization",required=false)String h){sessions.reviewerAuth(h);return catalogue.enabled()?catalogue.summaries():List.of();}
 @GetMapping("/review/templates/{version}") public TemplateCatalogue.Definition technicalDefinition(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String version){sessions.reviewerAuth(h);return catalogue.definition(version);}
 @PostMapping("/review/templates/{version}/approve") public Map<String,String> approve(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String version){catalogue.approve(version,sessions.reviewerAuth(h));return Map.of("status","APPROVED");}
 @PostMapping("/recruiter/templates/{version}/interviews") public Map<String,String> interview(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String version,@RequestBody TemplateInterview input){if(input==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Interview details required");return Map.of("id",catalogue.createInterview(recruiters.auth(h),version,input.title(),input.scheduledAt()));}
 @GetMapping("/sessions/{id}/coding/{question}") public Map<String,Object> coding(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@PathVariable String question){sessions.get(h,id);String version=catalogue.versionForSession(id);if(version==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Coding question not found");var item=catalogue.item(version,question);if(!item.kind().equals("CODING"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Not a coding question");return Map.of("starterCode",item.starterCode(),"publicTests",item.publicTests(),"language","Java 21","runnerEnabled",catalogue.summaries().stream().filter(t->t.version().equals(version)).findFirst().orElseThrow().runnerEnabled());}
 @PostMapping("/sessions/{id}/code-runs") public TemplateAssessmentService.RunView run(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@RequestBody CodeInput input){var s=sessions.get(h,id);if(!s.state().equals("IN_PROGRESS")||s.deadline()<=System.currentTimeMillis())throw new ResponseStatusException(HttpStatus.CONFLICT,"Code can run only before the assessment deadline");if(input==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Source required");return assessments.queue(id,input.questionId(),input.source());}
 @GetMapping("/sessions/{id}/code-runs/{run}") public TemplateAssessmentService.RunView result(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@PathVariable String run){sessions.get(h,id);catalogue.requireEnabled();return assessments.publicRun(run,id);}
 @GetMapping("/review/sessions/{id}/template-evaluation") public TemplateAssessmentService.Evaluation reviewerEvaluation(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id){sessions.reviewSession(h,id);return assessments.evaluation(id);}
 @PostMapping("/review/sessions/{id}/template-evaluation") public TemplateAssessmentService.Evaluation saveReviewerEvaluation(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@RequestBody TemplateAssessmentService.EvaluationInput input){String reviewer=sessions.reviewerAuth(h);sessions.reviewSession(h,id);return assessments.save(id,reviewer,input);}
 @PostMapping("/review/sessions/{id}/retry-coding") public Map<String,String> retry(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id){sessions.reviewSession(h,id);assessments.retryCoding(id);return Map.of("state","QUEUED");}
 @GetMapping("/recruiter/submissions/{id}/template-evaluation") public TemplateAssessmentService.Evaluation recruiterEvaluation(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id){recruiters.submission(h,id);return assessments.evaluation(id);}
}
