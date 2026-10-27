package com.exl.interview.api;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:template-tests;DB_CLOSE_DELAY=-1","spring.sql.init.schema-locations=classpath:schema.sql,classpath:template-schema.sql","interview.catalogue-enabled=true","interview.recruiter-setup-code=template-setup","interview.reviewer-code=template-review","interview.invitation-key-file=./target/template-test.key","interview.template-initial-delay-ms=86400000","logging.level.root=ERROR"})
@AutoConfigureMockMvc
class TemplateFlowTest {
 static final String VERSION="java-backend-mid-v1";
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired TemplateCatalogue catalogue;@Autowired TemplateAssessmentService assessments;
 @MockBean JavaCodeRunner runner;
 String recruiter,reviewer;
 JsonNode post(String path,String token,Object input,int code)throws Exception{var request=MockMvcRequestBuilders.post("/api"+path).contentType("application/json").content(json.writeValueAsString(input));if(token!=null)request.header("Authorization","Bearer "+token);String body=mvc.perform(request).andExpect(status().is(code)).andReturn().getResponse().getContentAsString();return body.isBlank()?json.createObjectNode():json.readTree(body);}
 JsonNode get(String path,String token,int code)throws Exception{var request=MockMvcRequestBuilders.get("/api"+path);if(token!=null)request.header("Authorization","Bearer "+token);String body=mvc.perform(request).andExpect(status().is(code)).andReturn().getResponse().getContentAsString();return body.isBlank()?json.createObjectNode():json.readTree(body);}
 @BeforeEach void setup()throws Exception{
  for(String table:List.of("coding_runs","template_evaluations","integrity_evidence","reviews","answers","sessions","invitations","interviews","recruiters","tokens"))db.update("DELETE FROM "+table);
  db.update("UPDATE assessment_templates SET status='DRAFT',approved_by=NULL,approved_at=NULL");
  when(runner.enabled()).thenReturn(true);when(runner.run(anyString(),anyList())).thenAnswer(inv->{List<TemplateCatalogue.TestCase> tests=inv.getArgument(1);return new JavaCodeRunner.Result("COMPLETE",tests.size(),tests.size(),tests.stream().map(t->new JavaCodeRunner.TestResult(t.id(),"PASSED",t.input(),t.expected(),t.expected(),1)).toList(),"Test worker");});
  recruiter=post("/recruiter/register",null,Map.of("name","Recruiter","email","recruiter@example.com","password","a-private-password","setupCode","template-setup"),200).get("token").asText();
  reviewer=post("/auth/reviewer-login",null,Map.of("code","template-review"),200).get("token").asText();
 }
 void approve()throws Exception{post("/review/templates/"+VERSION+"/approve",reviewer,Map.of(),200);}
 record Attempt(String token,String id,String interview){}
 Attempt start()throws Exception{
  approve();String job=post("/recruiter/templates/"+VERSION+"/interviews",recruiter,Map.of("title","Java backend preview","scheduledAt",0),200).get("id").asText();String code=post("/recruiter/interviews/"+job+"/invitations",recruiter,Map.of("candidateName","Candidate","candidateEmail","candidate@example.com","expiresAt",System.currentTimeMillis()+86400000),200).get("code").asText();String token=post("/auth/login",null,Map.of("code",code),200).get("token").asText();String id=post("/sessions",token,Map.of("consent",true,"evidenceConsent",true,"checks",List.of(Map.of("key","camera","status","passed"),Map.of("key","microphone","status","passed"),Map.of("key","screen","status","passed"),Map.of("key","service","status","passed"))),200).get("id").asText();post("/sessions/"+id+"/start",token,Map.of(),200);return new Attempt(token,id,job);
 }
 void answerAll(Attempt a)throws Exception{for(var item:catalogue.definition(VERSION).items()){String answer=item.kind().equals("MCQ")?item.correctAnswer():item.kind().equals("CODING")?item.referenceSolution():"Detailed written response";post("/sessions/"+a.id()+"/answers",a.token(),Map.of("questionId",item.id(),"answer",answer),200);}post("/sessions/"+a.id()+"/submit",a.token(),Map.of(),200);}
 List<Map<String,Object>> fullMarks(JsonNode evaluation){var marks=new ArrayList<Map<String,Object>>();for(var row:evaluation.get("rubric"))marks.add(Map.of("questionId",row.get("questionId").asText(),"criterionId",row.get("criterionId").asText(),"points",row.get("maxPoints").asInt(),"notes","Specific supporting evidence"));return marks;}
 @Test void approvedTemplateEndToEndAndKeysStayPrivate()throws Exception{
  var a=start();var candidate=get("/sessions/"+a.id(),a.token(),200);assertEquals(11,candidate.get("questions").size());assertEquals(VERSION,candidate.get("templateVersion").asText());assertFalse(candidate.toString().contains("correctAnswer"));assertFalse(candidate.toString().contains("hiddenTests"));
  var code=get("/sessions/"+a.id()+"/coding/java-v1-c1",a.token(),200);assertEquals(2,code.get("publicTests").size());assertFalse(code.has("hiddenTests"));assertFalse(code.has("referenceSolution"));get("/review/templates/"+VERSION,a.token(),401);
  answerAll(a);assessments.queueFinalRuns();assessments.process();var evaluation=get("/review/sessions/"+a.id()+"/template-evaluation",reviewer,200);assertEquals(16,evaluation.get("mcqPoints").asInt());assertEquals(30,evaluation.get("codingPoints").asInt());assertTrue(evaluation.get("total").isNull());
  var saved=post("/review/sessions/"+a.id()+"/template-evaluation",reviewer,Map.of("marks",fullMarks(evaluation),"notes","Evidence-based overall feedback","recommendation","SHORTLIST","publish",false),200);assertTrue(saved.get("complete").asBoolean());assertEquals(100,saved.get("total").asInt());
  var published=post("/review/sessions/"+a.id()+"/template-evaluation",reviewer,Map.of("marks",fullMarks(evaluation),"notes","Evidence-based overall feedback","recommendation","SHORTLIST","publish",true),200);assertEquals("PUBLISHED",published.get("state").asText());assertEquals(5,get("/review/sessions/"+a.id(),reviewer,200).get("score").asInt());
  post("/review/sessions/"+a.id()+"/template-evaluation",reviewer,Map.of("marks",fullMarks(evaluation),"notes","Changes","recommendation","HOLD","publish",false),409);
  String hidden=db.queryForObject("SELECT id FROM coding_runs WHERE session_id=? AND visibility='HIDDEN'",String.class,a.id());get("/sessions/"+a.id()+"/code-runs/"+hidden,a.token(),404);get("/review/sessions/"+a.id()+"/template-evaluation",a.token(),401);assertEquals(100,get("/recruiter/submissions/"+a.id()+"/template-evaluation",recruiter,200).get("total").asInt());
 }
 @Test void templateSchedulingKeepsBriefAndApprovedContent()throws Exception{
  approve();String id=post("/recruiter/templates/"+VERSION+"/interviews",recruiter,Map.of("title","My role","description","Team-specific brief","scheduledAt",0,"durationMinutes",5,"questionIds",List.of()),200).get("id").asText();
  assertEquals("Team-specific brief",db.queryForObject("SELECT description FROM interviews WHERE id=?",String.class,id));
  assertEquals(3600,db.queryForObject("SELECT duration_seconds FROM interviews WHERE id=?",Integer.class,id));
  assertEquals(11,json.readTree(db.queryForObject("SELECT questions FROM interviews WHERE id=?",String.class,id)).size());
  post("/recruiter/templates/"+VERSION+"/interviews",recruiter,Map.of("title","Too long","description","x".repeat(4001),"scheduledAt",0),400);
 }
 @Test void publicationAllowsOmittedReviewerNotes()throws Exception{
  var a=start();answerAll(a);assessments.process();var evaluation=get("/review/sessions/"+a.id()+"/template-evaluation",reviewer,200);
  var marks=new ArrayList<Map<String,Object>>();for(var row:evaluation.get("rubric"))marks.add(Map.of("questionId",row.get("questionId").asText(),"criterionId",row.get("criterionId").asText(),"points",0));
  var published=post("/review/sessions/"+a.id()+"/template-evaluation",reviewer,Map.of("marks",marks,"recommendation","HOLD","publish",true),200);
  assertEquals("PUBLISHED",published.get("state").asText());assertEquals("",published.get("notes").asText());assertTrue(published.get("complete").asBoolean());for(var row:published.get("rubric"))assertEquals("",row.get("notes").asText());
 }
 @Test void approvalAndPublishRequireValidation()throws Exception{
  post("/recruiter/templates/"+VERSION+"/interviews",recruiter,Map.of("title","Not approved","scheduledAt",0),409);
  when(runner.run(anyString(),anyList())).thenReturn(new JavaCodeRunner.Result("UNAVAILABLE",0,6,List.of(),"Disabled"));post("/review/templates/"+VERSION+"/approve",reviewer,Map.of(),409);assertEquals("DRAFT",catalogue.summaries().getFirst().status());
 }
 @Test void infrastructureErrorsAreNotZeroScores()throws Exception{
  var a=start();answerAll(a);when(runner.run(anyString(),anyList())).thenReturn(new JavaCodeRunner.Result("INFRASTRUCTURE_ERROR",0,6,List.of(),"Docker unavailable"));assessments.process();var evaluation=get("/review/sessions/"+a.id()+"/template-evaluation",reviewer,200);assertEquals("ERROR",evaluation.get("codingState").asText());assertTrue(evaluation.get("codingPoints").isNull());post("/review/sessions/"+a.id()+"/template-evaluation",reviewer,Map.of("marks",fullMarks(evaluation),"notes","Feedback","recommendation","HOLD","publish",true),400);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM reviews WHERE session_id=?",Integer.class,a.id()));post("/review/sessions/"+a.id()+"/retry-coding",reviewer,Map.of(),200);
 }
 @Test void sampleRunsAreOwnedAndBoundedByTheDeadline()throws Exception{
  var a=start();var run=post("/sessions/"+a.id()+"/code-runs",a.token(),Map.of("questionId","java-v1-c1","source","public class Main {}"),200);assessments.process();var result=get("/sessions/"+a.id()+"/code-runs/"+run.get("id").asText(),a.token(),200);assertEquals(2,result.get("result").get("total").asInt());db.update("UPDATE sessions SET deadline=0 WHERE id=?",a.id());post("/sessions/"+a.id()+"/code-runs",a.token(),Map.of("questionId","java-v1-c1","source","public class Main {}"),409);
 }

 @Test void expiryFreezesOnlyServerSavedCode()throws Exception{
  var a=start();String source=catalogue.item(VERSION,"java-v1-c1").referenceSolution();post("/sessions/"+a.id()+"/answers",a.token(),Map.of("questionId","java-v1-c1","answer",source),200);db.update("UPDATE sessions SET deadline=0 WHERE id=?",a.id());assertEquals("SUBMITTED",get("/sessions/"+a.id(),a.token(),200).get("state").asText());
  post("/sessions/"+a.id()+"/answers",a.token(),Map.of("questionId","java-v1-c1","answer","late source"),409);assessments.queueFinalRuns();assertEquals(source,db.queryForObject("SELECT source FROM coding_runs WHERE session_id=? AND visibility='HIDDEN'",String.class,a.id()));assessments.process();var e=get("/review/sessions/"+a.id()+"/template-evaluation",reviewer,200);assertEquals(0,e.get("mcqPoints").asInt());assertEquals(30,e.get("codingPoints").asInt());assertFalse(e.get("complete").asBoolean());
 }
 @Test void rubricValidationAndLegacyScoreOverrideAreEnforced()throws Exception{
  var a=start();answerAll(a);assessments.process();var e=get("/review/sessions/"+a.id()+"/template-evaluation",reviewer,200);var marks=fullMarks(e);var bad=new HashMap<>(marks.getFirst());bad.put("points",999);marks.set(0,bad);post("/review/sessions/"+a.id()+"/template-evaluation",reviewer,Map.of("marks",marks,"notes","Feedback","recommendation","HOLD","publish",false),400);
  post("/review/sessions/"+a.id(),reviewer,Map.of("score",5,"notes","Arbitrary score","recommendation","SHORTLIST"),400);post("/recruiter/submissions/"+a.id()+"/review",recruiter,Map.of("score",5,"notes","Arbitrary score","recommendation","SHORTLIST"),400);
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM reviews WHERE session_id=?",Integer.class,a.id()));
 }

 @Test void templateQueueUsesServerPaginationAndMetadataOnly()throws Exception{
  String definition=db.queryForObject("SELECT definition FROM assessment_templates WHERE version=?",String.class,VERSION);
  for(int i=0;i<25;i++){String status=i%3==0?"DRAFT":i%3==1?"APPROVED":"RETIRED";db.update("INSERT INTO assessment_templates (version,status,definition,title,role_name,skills,duration_minutes,question_count,updated_at,description) VALUES (?,?,?,?,?,?,?,?,?,?)",String.format("queue-%02d",i),status,definition,"Role "+i,"Service Engineer","[\"Spring\"]",60,11,(long)i+1,"Queue test");}
  var first=get("/review/template-queue",reviewer,200);assertEquals(10,first.get("items").size());assertEquals(26,first.get("total").asInt());assertEquals(3,first.get("totalPages").asInt());assertEquals(10,first.get("pendingCount").asInt());assertFalse(first.toString().contains("correctAnswer"));assertFalse(first.toString().contains("hiddenTests"));
  var last=get("/review/template-queue?page=999",reviewer,200);assertEquals(2,last.get("page").asInt());assertEquals(6,last.get("items").size());assertEquals(8,get("/review/template-queue?status=APPROVED",reviewer,200).get("total").asInt());assertEquals(25,get("/review/template-queue?search=Spring",reviewer,200).get("total").asInt());assertEquals(1,get("/review/template-queue?search=queue-01",reviewer,200).get("total").asInt());assertEquals(0,get("/review/template-queue?search=not-found",reviewer,200).get("total").asInt());
  get("/review/template-queue?size=10000",reviewer,400);get("/review/template-queue",recruiter,401);assertEquals(10,get("/recruiter/template-queue",recruiter,200).get("items").size());
 }
 @Test void retiredTemplateCannotBeApprovedAgain()throws Exception{db.update("UPDATE assessment_templates SET status='RETIRED' WHERE version=?",VERSION);post("/review/templates/"+VERSION+"/approve",reviewer,Map.of(),409);}
}
