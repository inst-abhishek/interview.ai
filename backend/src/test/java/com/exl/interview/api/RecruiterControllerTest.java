package com.exl.interview.api;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:recruiter-tests;DB_CLOSE_DELAY=-1","interview.recruiter-setup-code=setup-secret","interview.invitation-code=legacy-demo","logging.level.root=ERROR","interview.invitation-key-file=./target/test-invitation-link.key"})
@AutoConfigureMockMvc
class RecruiterControllerTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
 JsonNode post(String path,String token,Object body,int expected) throws Exception {var request=MockMvcRequestBuilders.post("/api"+path).contentType("application/json").content(json.writeValueAsString(body));if(token!=null)request.header("Authorization","Bearer "+token);var result=mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();return result.isBlank()?json.createObjectNode():json.readTree(result);}
 JsonNode get(String path,String token,int expected) throws Exception {var request=MockMvcRequestBuilders.get("/api"+path);if(token!=null)request.header("Authorization","Bearer "+token);String result=mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();return result.isBlank()?json.createObjectNode():json.readTree(result);}
 String account() throws Exception {String email=UUID.randomUUID()+"@example.com";return post("/recruiter/register",null,Map.of("name","Recruiter","email",email,"password","a-private-password","setupCode","setup-secret"),200).get("token").asText();}
 JsonNode interview(String token,List<String> questions,long schedule) throws Exception {return post("/recruiter/interviews",token,Map.of("title","Java Engineer","description","Backend development assessment","durationMinutes",15,"scheduledAt",schedule,"questionIds",questions),200);}
 JsonNode invite(String token,String interview) throws Exception {return post("/recruiter/interviews/"+interview+"/invitations",token,Map.of("candidateName","Candidate One","candidateEmail","candidate@example.com","expiresAt",System.currentTimeMillis()+172800000),200);}
 String candidate(String code) throws Exception {return post("/auth/login",null,Map.of("code",code),200).get("token").asText();}
 JsonNode session(String token,int expected) throws Exception {return post("/sessions",token,Map.of("consent",true,"evidenceConsent",true,"checks",List.of(Map.of("key","camera","status","passed"),Map.of("key","microphone","status","passed"),Map.of("key","screen","status","passed"),Map.of("key","service","status","passed"))),expected);}
 @Test void fullRecruiterAndCandidateJourney() throws Exception {
   String recruiter=account();JsonNode q=post("/recruiter/questions",recruiter,Map.of("kind","TECHNICAL","text","Explain transaction isolation","choices",List.of()),200);
   JsonNode job=interview(recruiter,List.of(q.get("id").asText()),System.currentTimeMillis()-60000);String jobId=job.get("id").asText();
   JsonNode invitation=invite(recruiter,jobId);String code=invitation.get("code").asText();assertNotEquals(code,db.queryForObject("SELECT code_hash FROM invitations WHERE interview_id=?",String.class,jobId));
   String token=candidate(code);assertEquals(1,get("/questions",token,200).size());assertEquals("Java Engineer",get("/invitation",token,200).get("title").asText());String id=session(token,200).get("id").asText();assertEquals(id,session(token,200).get("id").asText());
   var started=post("/sessions/"+id+"/start",token,Map.of(),200);assertEquals(1,started.get("questions").size());assertTrue(Math.abs(started.get("deadline").asLong()-started.get("serverTime").asLong()-900000)<2000);
   post("/sessions/"+id+"/answers",token,Map.of("questionId",q.get("id").asText(),"answer","Use appropriate isolation for consistency"),200);post("/sessions/"+id+"/submit",token,Map.of(),200);
   var candidates=get("/recruiter/interviews/"+jobId+"/candidates",recruiter,200);assertEquals("SUBMITTED",candidates.get(0).get("state").asText());assertEquals("Candidate One",candidates.get(0).get("candidateName").asText());
   String other=account();get("/recruiter/submissions/"+id,other,404);post("/recruiter/submissions/"+id+"/review",other,Map.of("score",1,"notes","Not allowed","recommendation","REJECT"),404);
   var review=get("/recruiter/submissions/"+id,recruiter,200);assertEquals(1,review.get("answers").size());post("/recruiter/submissions/"+id+"/review",recruiter,Map.of("score",4,"notes","Clear explanation","recommendation","SHORTLIST"),200);
   assertEquals(4,get("/recruiter/interviews/"+jobId+"/candidates",recruiter,200).get(0).get("score").asInt());
   var relogin=post("/auth/login",null,Map.of("code",code),200);assertEquals(id,relogin.get("sessionId").asText());get("/sessions/"+id,relogin.get("token").asText(),200);
 }
 @Test void recruitersAndCandidatesAreIsolated() throws Exception {
   String a=account(),b=account();var q=post("/recruiter/questions",a,Map.of("kind","TECHNICAL","text","Private question","choices",List.of()),200);String qid=q.get("id").asText();
   assertFalse(get("/recruiter/questions",b,200).toString().contains(qid));post("/recruiter/questions/"+qid,b,Map.of("kind","TECHNICAL","text","Overwrite","choices",List.of()),404);
   post("/recruiter/interviews",b,Map.of("title","Other","description","Other","durationMinutes",15,"scheduledAt",0,"questionIds",List.of(qid)),400);
   String job=interview(a,List.of("q1"),0).get("id").asText();get("/recruiter/interviews/"+job+"/candidates",b,404);post("/recruiter/interviews/"+job+"/invitations",b,Map.of("candidateName","x","candidateEmail","x@example.com","expiresAt",System.currentTimeMillis()+86400000),404);
   String c=candidate(invite(a,job).get("code").asText());get("/recruiter/interviews",c,401);get("/review/sessions",a,401);get("/questions",a,401);
   String id=session(c,200).get("id").asText();get("/recruiter/submissions/"+id,b,404);get("/sessions/"+id,candidate(invite(a,job).get("code").asText()),404);
 }
 @Test void schedulingExpiryAndQuestionSnapshots() throws Exception {
   String a=account();String q=post("/recruiter/questions",a,Map.of("kind","TECHNICAL","text","Original question","choices",List.of()),200).get("id").asText();
   String job=interview(a,List.of(q),System.currentTimeMillis()+3600000).get("id").asText();String code=invite(a,job).get("code").asText();String token=candidate(code);session(token,409);
   post("/recruiter/questions/"+q,a,Map.of("kind","TECHNICAL","text","Changed question","choices",List.of()),200);db.update("UPDATE interviews SET scheduled_at=0 WHERE id=?",job);
   var s=session(token,200);assertEquals("Original question",s.get("questions").get(0).get("text").asText());db.update("UPDATE invitations SET expires_at=0 WHERE interview_id=?",job);
   post("/auth/login",null,Map.of("code",code),401);post("/sessions/"+s.get("id").asText()+"/start",token,Map.of(),403);
 }
 @Test void accountLoginLogoutAndValidation() throws Exception {
   get("/recruiter/interviews",null,401);post("/recruiter/register",null,Map.of("name","X","email","x@example.com","password","a-private-password","setupCode","wrong"),403);
   String email=UUID.randomUUID()+"@example.com";post("/recruiter/register",null,Map.of("name","X","email",email,"password","short","setupCode","setup-secret"),400);
   post("/recruiter/register",null,Map.of("name","X","email",email,"password","a-private-password","setupCode","setup-secret"),200);
   post("/recruiter/login",null,Map.of("email",email,"password","incorrect"),401);String t=post("/recruiter/login",null,Map.of("email",email,"password","a-private-password"),200).get("token").asText();assertEquals(email,get("/recruiter/me",t,200).get("email").asText());
   post("/recruiter/questions",t,Map.of("kind","MCQ","text","Invalid options","choices",List.of("same","same")),400);post("/recruiter/questions",t,Map.of("text","No type"),400);
   post("/recruiter/logout",t,Map.of(),200);get("/recruiter/me",t,401);
 }

 @Test void recruiterWorkflowPersistsAcrossApplicationRestart() throws Exception {
   var dir=java.nio.file.Files.createTempDirectory("recruiter-restart");String url="jdbc:h2:file:"+dir.resolve("interviews").toAbsolutePath().toString().replace('\\','/');
   String[] args={"--spring.datasource.url="+url,"--interview.recruiter-setup-code=restart-setup","--interview.invitation-key-file=./target/test-invitation-link.key","--logging.level.root=ERROR","--debug=false"};
   String jobId,sessionId,invitationId,invitationCode;
   try(var context=new org.springframework.boot.builder.SpringApplicationBuilder(com.exl.interview.InterviewApplication.class).web(org.springframework.boot.WebApplicationType.NONE).run(args)){
     var recruiter=context.getBean(RecruiterController.class);var candidate=context.getBean(SessionController.class);
     String h="Bearer "+recruiter.register(new RecruiterController.Registration("Hiring manager","hiring@example.com","a-private-password","restart-setup")).token();
     String q=recruiter.addQuestion(h,new RecruiterController.QuestionInput("TECHNICAL","Persist this question",List.of())).id();
     jobId=recruiter.createInterview(h,new RecruiterController.InterviewInput("Persisted interview","Backend role",20,0L,List.of(q))).id();
     var created=recruiter.invite(h,jobId,new RecruiterController.InvitationInput("Test Candidate","candidate@example.com",System.currentTimeMillis()+86400000));String invitation=created.code();invitationId=created.invitationId();invitationCode=invitation;
     String c="Bearer "+candidate.login(new SessionController.Login(invitation)).get("token");
     var checks=java.util.stream.Stream.of("camera","microphone","screen","service").map(k->new SessionController.Check(k,"passed")).toList();
     sessionId=candidate.create(c,new SessionController.NewSession(true,true,checks)).id();candidate.start(c,sessionId);candidate.answer(c,sessionId,new SessionController.Answer(q,"Persisted answer"));candidate.submit(c,sessionId);
     recruiter.review(h,sessionId,new SessionController.ReviewSubmission(4,"Persisted feedback","HOLD"));
   }
   try(var context=new org.springframework.boot.builder.SpringApplicationBuilder(com.exl.interview.InterviewApplication.class).web(org.springframework.boot.WebApplicationType.NONE).run(args)){
     var recruiter=context.getBean(RecruiterController.class);String h="Bearer "+recruiter.login(new RecruiterController.Credentials("hiring@example.com","a-private-password")).token();
     assertEquals(invitationCode,recruiter.invitationLink(h,invitationId).code());assertEquals(jobId,recruiter.interviews(h).getFirst().id());assertEquals("SUBMITTED",recruiter.candidates(h,jobId).getFirst().state());
     var submission=recruiter.submission(h,sessionId);assertEquals("Persisted answer",submission.answers().getFirst().answer());assertEquals(4,submission.score());assertEquals("Persisted feedback",submission.notes());
   }
   try(var files=java.nio.file.Files.list(dir)){for(var f:files.toList())java.nio.file.Files.delete(f);}java.nio.file.Files.delete(dir);
 }

 @Test void invitationRecoveryRotationOwnershipAndExpiry() throws Exception {
   String recruiter=account(),other=account(),job=interview(recruiter,List.of("q1"),0).get("id").asText();
   var invitation=invite(recruiter,job);String id=invitation.get("invitationId").asText(),code=invitation.get("code").asText();
   assertEquals(code,get("/recruiter/invitations/"+id,recruiter,200).get("code").asText());
   String stored=db.queryForObject("SELECT encrypted_code FROM invitations WHERE id=?",String.class,id);assertNotEquals(code,stored);assertFalse(stored.contains(code));
   get("/recruiter/invitations/"+id,other,404);post("/recruiter/invitations/"+id+"/regenerate",other,Map.of("expiresAt",System.currentTimeMillis()+86400000),404);
   String c=candidate(code);get("/recruiter/invitations/"+id,c,401);String sessionId=session(c,200).get("id").asText();
   post("/sessions/"+sessionId+"/start",c,Map.of(),200);post("/sessions/"+sessionId+"/answers",c,Map.of("questionId","q1","answer","HashMap"),200);post("/sessions/"+sessionId+"/submit",c,Map.of(),200);
   post("/recruiter/submissions/"+sessionId+"/review",recruiter,Map.of("score",4,"notes","Good answer","recommendation","HOLD"),200);
   var replacement=post("/recruiter/invitations/"+id+"/regenerate",recruiter,Map.of("expiresAt",System.currentTimeMillis()+172800000),200);String newCode=replacement.get("code").asText();assertNotEquals(code,newCode);
   post("/auth/login",null,Map.of("code",code),401);var resumed=post("/auth/login",null,Map.of("code",newCode),200);assertEquals(sessionId,resumed.get("sessionId").asText());
   var saved=get("/sessions/"+sessionId,resumed.get("token").asText(),200);assertEquals("SUBMITTED",saved.get("state").asText());assertEquals("HashMap",saved.get("answers").get(0).get("answer").asText());assertEquals(4,get("/recruiter/submissions/"+sessionId,recruiter,200).get("score").asInt());
   db.update("UPDATE invitations SET encrypted_code=NULL WHERE id=?",id);get("/recruiter/invitations/"+id,recruiter,409);
   post("/recruiter/invitations/"+id+"/regenerate",recruiter,Map.of("expiresAt",0),400);post("/recruiter/invitations/"+id+"/regenerate",recruiter,Map.of("expiresAt",System.currentTimeMillis()+86400000),200);
   db.update("UPDATE invitations SET expires_at=0 WHERE id=?",id);get("/recruiter/invitations/"+id,recruiter,410);
 }
}
