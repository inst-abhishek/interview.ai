package com.exl.interview.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class TemplateCatalogue {
 public record Criterion(String id,String label,int maxPoints,String guidance){}
 public record TestCase(String id,String input,String expected){}
 public record Item(String id,String kind,String text,List<String> choices,String correctAnswer,String explanation,int maxPoints,List<Criterion> rubric,String starterCode,List<TestCase> publicTests,List<TestCase> hiddenTests,String referenceSolution){}
 public record Definition(String version,String title,String description,int durationMinutes,List<String> skills,String reviewerInstructions,List<Item> items){}
 public record Summary(String version,String title,String description,int durationMinutes,List<String> skills,int questionCount,String status,boolean runnerEnabled){}
 private final JdbcTemplate db;private final ObjectMapper json;private final boolean enabled;private final JavaCodeRunner runner;
 public TemplateCatalogue(JdbcTemplate db,ObjectMapper json,JavaCodeRunner runner,@Value("${interview.catalogue-enabled:false}") boolean enabled){this.db=db;this.json=json;this.runner=runner;this.enabled=enabled;}
 public boolean enabled(){return enabled;}
 public void requireEnabled(){if(!enabled)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Start the Java template preview profile to use this feature");}
 @PostConstruct public void seed()throws Exception{if(!enabled)return;String content=new String(new ClassPathResource("java-backend-v1.json").getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);Definition d=json.readValue(content,Definition.class);if(db.queryForObject("SELECT COUNT(*) FROM assessment_templates WHERE version=?",Integer.class,d.version())==0)db.update("INSERT INTO assessment_templates (version,status,definition) VALUES (?,'DRAFT',?)",d.version(),content);}
 public Definition definition(String version){requireEnabled();var rows=db.queryForList("SELECT definition FROM assessment_templates WHERE version=?",String.class,version);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Template not found");try{return json.readValue(rows.getFirst(),Definition.class);}catch(Exception e){throw new IllegalStateException(e);}}
 public List<Summary> summaries(){requireEnabled();return db.query("SELECT version,status FROM assessment_templates ORDER BY version",(r,n)->{var d=definition(r.getString(1));return new Summary(d.version(),d.title(),d.description(),d.durationMinutes(),d.skills(),d.items().size(),r.getString(2),runner.enabled());});}
 public String versionForSession(String id){if(!enabled)return null;var rows=db.queryForList("SELECT j.template_version FROM sessions s JOIN interviews j ON j.id=s.interview_id WHERE s.id=?",String.class,id);return rows.isEmpty()?null:rows.getFirst();}
 public Item item(String version,String question){return definition(version).items().stream().filter(q->q.id().equals(question)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Question not part of this template"));}
 public List<SessionController.Question> publicQuestions(String version){return definition(version).items().stream().map(q->new SessionController.Question(q.id(),q.kind(),q.text(),q.choices())).toList();}
 public void approve(String version,String reviewer){var d=definition(version);if(d.items().stream().mapToInt(Item::maxPoints).sum()!=100)throw new IllegalStateException("Template weights must total 100");for(var q:d.items()){if(q.kind().equals("MCQ")&&!q.choices().contains(q.correctAnswer()))throw new IllegalStateException("Invalid answer key");if(q.kind().equals("CODING")){var tests=new ArrayList<>(q.publicTests());tests.addAll(q.hiddenTests());var result=runner.run(q.referenceSolution(),tests);if(!result.state().equals("COMPLETE")||result.passed()!=tests.size())throw new ResponseStatusException(HttpStatus.CONFLICT,"Reference solution validation failed: "+result.state()+". Configure Docker and validate again.");}}db.update("UPDATE assessment_templates SET status='APPROVED',approved_by=?,approved_at=? WHERE version=?",reviewer,System.currentTimeMillis(),version);}
 public String createInterview(String owner,String version,String title,Long scheduledAt){var d=definition(version);if(!"APPROVED".equals(db.queryForObject("SELECT status FROM assessment_templates WHERE version=?",String.class,version)))throw new ResponseStatusException(HttpStatus.CONFLICT,"A technical reviewer must validate and approve this template first");if(scheduledAt==null||scheduledAt<0||title==null||title.isBlank()||title.length()>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Title and schedule are required");String id=UUID.randomUUID().toString();try{db.update("INSERT INTO interviews (id,recruiter_id,title,description,duration_seconds,scheduled_at,questions,created_at,template_version) VALUES (?,?,?,?,?,?,?,?,?)",id,owner,title.trim(),d.description(),d.durationMinutes()*60,scheduledAt,json.writeValueAsString(publicQuestions(version)),System.currentTimeMillis(),version);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}return id;}
}
