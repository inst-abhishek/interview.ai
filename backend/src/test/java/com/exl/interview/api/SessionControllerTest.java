package com.exl.interview.api;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import java.nio.file.Files;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.sql.DriverManager;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:tests;DB_CLOSE_DELAY=-1","interview.invitation-code=test-secret","interview.reviewer-code=review-secret"})
@AutoConfigureMockMvc
class SessionControllerTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db; @Autowired EvidenceRetentionCleanup retentionCleanup;
 String login() throws Exception {return json.readTree(mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").contentType("application/json").content("{\"code\":\"test-secret\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();}
 String reviewerLogin() throws Exception {return json.readTree(mvc.perform(MockMvcRequestBuilders.post("/api/auth/reviewer-login").contentType("application/json").content("{\"code\":\"review-secret\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();}
 JsonNode post(String path,String token,String body,int status) throws Exception {return json.readTree(mvc.perform(MockMvcRequestBuilders.post("/api"+path).header("Authorization","Bearer "+token).contentType("application/json").content(body)).andExpect(status().is(status)).andReturn().getResponse().getContentAsString().isBlank()?"{}":last(path,token));}
 String last(String path,String token) throws Exception {String id=path.split("/")[2];return mvc.perform(MockMvcRequestBuilders.get("/api/sessions/"+id).header("Authorization","Bearer "+token)).andReturn().getResponse().getContentAsString();}
 String create(String token) throws Exception {String body="{\"consent\":true,\"evidenceConsent\":true,\"checks\":[{\"key\":\"camera\",\"status\":\"passed\"},{\"key\":\"microphone\",\"status\":\"passed\"},{\"key\":\"screen\",\"status\":\"passed\"},{\"key\":\"service\",\"status\":\"passed\"}]}";return json.readTree(mvc.perform(MockMvcRequestBuilders.post("/api/sessions").header("Authorization","Bearer "+token).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asText();}
 String jpegSnapshot() throws Exception {BufferedImage image=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(image,"jpeg",output);return Base64.getEncoder().encodeToString(output.toByteArray());}
 @Test void completeFlowAndRetries() throws Exception {String t=login(),id=create(t),p="/sessions/"+id;post(p+"/start",t,"{}",200);post(p+"/start",t,"{}",200);post(p+"/submit",t,"{}",400);post(p+"/answers",t,"{\"questionId\":\"q1\",\"answer\":\"HashMap\"}",200);post(p+"/answers",t,"{\"questionId\":\"q1\",\"answer\":\"HashMap\"}",200);post(p+"/answers",t,"{\"questionId\":\"q2\",\"answer\":\"Use timeouts and retries\"}",200);assertEquals(2,post(p+"/submit",t,"{}",200).get("answers").size());assertEquals("SUBMITTED",post(p+"/submit",t,"{}",200).get("state").asText());post(p+"/answers",t,"{\"questionId\":\"q2\",\"answer\":\"late\"}",400);assertEquals(Boolean.TRUE,db.queryForObject("SELECT consent FROM sessions WHERE id=?",Boolean.class,id));}
 @Test void authAndOwnership() throws Exception {mvc.perform(MockMvcRequestBuilders.get("/api/questions")).andExpect(status().isUnauthorized());mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").contentType("application/json").content("{\"code\":\"wrong\"}")).andExpect(status().isUnauthorized());String t=login(),id=create(t);mvc.perform(MockMvcRequestBuilders.get("/api/sessions/"+id).header("Authorization","Bearer "+login())).andExpect(status().isNotFound());mvc.perform(MockMvcRequestBuilders.post("/api/sessions").header("Authorization","Bearer "+t).contentType("application/json").content("{\"consent\":false}")).andExpect(status().isBadRequest());}
 @Test void deadlineRejectsAnswersButAllowsPartialSubmission() throws Exception {String t=login(),id=create(t),p="/sessions/"+id;post(p+"/start",t,"{}",200);db.update("UPDATE sessions SET deadline=? WHERE id=?",System.currentTimeMillis()-1000,id);post(p+"/answers",t,"{\"questionId\":\"q1\",\"answer\":\"HashMap\"}",409);assertEquals("SUBMITTED",post(p+"/submit",t,"{}",200).get("state").asText());}
 @Test void invalidQuestionsAndChoices() throws Exception {String t=login(),id=create(t),p="/sessions/"+id;post(p+"/start",t,"{}",200);post(p+"/answers",t,"{\"questionId\":\"unknown\",\"answer\":\"x\"}",400);post(p+"/answers",t,"{\"questionId\":\"q1\",\"answer\":\"invalid\"}",400);}
 @Test void evidenceSnapshotsRequireConsentAndAreVisibleToReviewersOnly() throws Exception {
   String candidate=login(),reviewer=reviewerLogin(),id=create(candidate),p="/sessions/"+id;
   post(p+"/start",candidate,"{}",200);
   String snapshot=jpegSnapshot();
   String body="{\"eventType\":\"TAB_HIDDEN\",\"mediaType\":\"SCREEN\",\"contentType\":\"image/jpeg\",\"imageData\":\""+snapshot+"\"}";
   JsonNode saved=json.readTree(mvc.perform(MockMvcRequestBuilders.post("/api/sessions/"+id+"/evidence").header("Authorization","Bearer "+candidate).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
   String evidenceId=saved.get("id").asText();
   JsonNode second=json.readTree(mvc.perform(MockMvcRequestBuilders.post("/api/sessions/"+id+"/evidence").header("Authorization","Bearer "+candidate).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
   String blurBody="{\"eventType\":\"WINDOW_BLUR\",\"mediaType\":\"SCREEN\",\"contentType\":\"image/jpeg\",\"imageData\":\""+snapshot+"\"}";
   mvc.perform(MockMvcRequestBuilders.post("/api/sessions/"+id+"/evidence").header("Authorization","Bearer "+candidate).contentType("application/json").content(blurBody)).andExpect(status().isOk()).andExpect(jsonPath("$.eventType").value("WINDOW_BLUR"));
   String fullscreenBody="{\"eventType\":\"FULLSCREEN_EXIT\",\"mediaType\":\"SCREEN\",\"contentType\":\"image/jpeg\",\"imageData\":\""+snapshot+"\"}";
   mvc.perform(MockMvcRequestBuilders.post("/api/sessions/"+id+"/evidence").header("Authorization","Bearer "+candidate).contentType("application/json").content(fullscreenBody)).andExpect(status().isOk()).andExpect(jsonPath("$.eventType").value("FULLSCREEN_EXIT"));
   post(p+"/answers",candidate,"{\"questionId\":\"q1\",\"answer\":\"HashMap\"}",200);post(p+"/answers",candidate,"{\"questionId\":\"q2\",\"answer\":\"Use timeouts and retries\"}",200);post(p+"/submit",candidate,"{}",200);
   mvc.perform(MockMvcRequestBuilders.get("/api/review/sessions/"+id).header("Authorization","Bearer "+reviewer)).andExpect(status().isOk()).andExpect(jsonPath("$.evidence.length()").value(4)).andExpect(jsonPath("$.evidence[0].eventType").value("TAB_HIDDEN")).andExpect(jsonPath("$.evidence[3].eventType").value("FULLSCREEN_EXIT"));
   mvc.perform(MockMvcRequestBuilders.get("/api/review/evidence/"+evidenceId).header("Authorization","Bearer "+candidate)).andExpect(status().isUnauthorized());
   mvc.perform(MockMvcRequestBuilders.get("/api/review/evidence/"+evidenceId).header("Authorization","Bearer "+reviewer)).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
   mvc.perform(MockMvcRequestBuilders.delete("/api/review/evidence/"+evidenceId).header("Authorization","Bearer "+reviewer)).andExpect(status().isOk());
   mvc.perform(MockMvcRequestBuilders.get("/api/review/evidence/"+evidenceId).header("Authorization","Bearer "+reviewer)).andExpect(status().isNotFound());
   mvc.perform(MockMvcRequestBuilders.post("/api/sessions/"+id+"/evidence").header("Authorization","Bearer "+reviewer).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
   String secondEvidenceId=second.get("id").asText();
   db.update("UPDATE integrity_evidence SET expires_at=? WHERE id=?",System.currentTimeMillis()-1,secondEvidenceId);retentionCleanup.deleteExpiredEvidence();
   mvc.perform(MockMvcRequestBuilders.get("/api/review/evidence/"+secondEvidenceId).header("Authorization","Bearer "+reviewer)).andExpect(status().isNotFound());
 }
 @Test void snapshotAcknowledgementIsRequiredBeforeSessionCreation() throws Exception {
   String candidate=login();
   String body="{\"consent\":true,\"evidenceConsent\":false,\"checks\":[{\"key\":\"camera\",\"status\":\"passed\"},{\"key\":\"microphone\",\"status\":\"passed\"},{\"key\":\"screen\",\"status\":\"passed\"},{\"key\":\"service\",\"status\":\"passed\"}]}";
   mvc.perform(MockMvcRequestBuilders.post("/api/sessions").header("Authorization","Bearer "+candidate).contentType("application/json").content(body)).andExpect(status().isBadRequest());
 }
 @Test void reviewerCanReadAndSaveSubmittedReviewsButCandidateCannot() throws Exception {
   String candidate=login(),reviewer=reviewerLogin(),id=create(candidate),p="/sessions/"+id;
   mvc.perform(MockMvcRequestBuilders.get("/api/review/sessions").header("Authorization","Bearer "+candidate)).andExpect(status().isUnauthorized());
   mvc.perform(MockMvcRequestBuilders.get("/api/review/sessions")).andExpect(status().isUnauthorized());
   mvc.perform(MockMvcRequestBuilders.get("/api/review/sessions/"+id).header("Authorization","Bearer "+reviewer)).andExpect(status().isNotFound());
   post(p+"/start",candidate,"{}",200);post(p+"/answers",candidate,"{\"questionId\":\"q1\",\"answer\":\"HashMap\"}",200);post(p+"/answers",candidate,"{\"questionId\":\"q2\",\"answer\":\"Use timeouts and retries\"}",200);post(p+"/submit",candidate,"{}",200);
   mvc.perform(MockMvcRequestBuilders.get("/api/review/sessions").param("status","ALL").param("search",id).header("Authorization","Bearer "+reviewer)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].sessionId").value(id)).andExpect(jsonPath("$.items[0].reviewedAt").value(nullValue()));
   mvc.perform(MockMvcRequestBuilders.get("/api/review/sessions/"+id).header("Authorization","Bearer "+reviewer)).andExpect(status().isOk()).andExpect(jsonPath("$.answers.length()").value(2)).andExpect(jsonPath("$.answers[0].answer").value("HashMap")).andExpect(jsonPath("$.checks.length()").value(4)).andExpect(jsonPath("$.startedAt").isNumber());
   mvc.perform(MockMvcRequestBuilders.post("/api/review/sessions/"+id).header("Authorization","Bearer "+reviewer).contentType("application/json").content("{\"score\":6,\"notes\":\"Evidence\",\"recommendation\":\"SHORTLIST\"}")).andExpect(status().isBadRequest());
   mvc.perform(MockMvcRequestBuilders.post("/api/review/sessions/"+id).header("Authorization","Bearer "+reviewer).contentType("application/json").content("{\"score\":4,\"notes\":\"Clear explanation of timeout and retry handling.\",\"recommendation\":\"SHORTLIST\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.score").value(4)).andExpect(jsonPath("$.recommendation").value("SHORTLIST"));
   mvc.perform(MockMvcRequestBuilders.get("/api/review/sessions/"+id).header("Authorization","Bearer "+candidate)).andExpect(status().isUnauthorized());
   mvc.perform(MockMvcRequestBuilders.get("/api/sessions/"+id).header("Authorization","Bearer "+reviewer)).andExpect(status().isUnauthorized());
   mvc.perform(MockMvcRequestBuilders.get("/api/review/sessions").param("status","ALL").param("search",id).header("Authorization","Bearer "+reviewer)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].score").value(4)).andExpect(jsonPath("$.items[0].recommendation").value("SHORTLIST"));
 }
 @Test void fileDatabaseSurvivesCloseAndReopen() throws Exception {var dir=Files.createTempDirectory("interview-db-test");String url="jdbc:h2:file:"+dir.resolve("interviews").toAbsolutePath().toString().replace('\\','/');String schema=Files.readString(java.nio.file.Path.of("src/main/resources/schema.sql"));try(var c=DriverManager.getConnection(url,"sa","")){for(String sql:schema.split(";"))if(!sql.isBlank())c.createStatement().execute(sql);c.createStatement().execute("INSERT INTO sessions (id,owner,state,consent,consent_at,checks,questions) VALUES ('persisted','owner','SUBMITTED',true,1,'[]','[]')");}try(var c=DriverManager.getConnection(url,"sa","");var rs=c.createStatement().executeQuery("SELECT state,consent FROM sessions WHERE id='persisted'")){assertTrue(rs.next());assertEquals("SUBMITTED",rs.getString(1));assertTrue(rs.getBoolean(2));}try(var files=Files.list(dir)){for(var f:files.toList())Files.delete(f);}Files.delete(dir);}
 @Test void applicationRestartRetainsSessionAnswersAndAuthentication() throws Exception {
   var dir=Files.createTempDirectory("interview-restart-test");String url="jdbc:h2:file:"+dir.resolve("interviews").toAbsolutePath().toString().replace('\\','/');
   String token,id;
   String[] args={"--spring.datasource.url="+url,"--interview.invitation-code=restart-secret","--logging.level.root=ERROR","--debug=false"};
   try(var context=new org.springframework.boot.builder.SpringApplicationBuilder(com.exl.interview.InterviewApplication.class).web(org.springframework.boot.WebApplicationType.NONE).run(args)){
     var controller=context.getBean(SessionController.class);token=controller.login(new SessionController.Login("restart-secret")).get("token");String h="Bearer "+token;
     var checks=java.util.stream.Stream.of("camera","microphone","screen","service").map(k->new SessionController.Check(k,"passed")).toList();
     id=controller.create(h,new SessionController.NewSession(true,true,checks)).id();controller.start(h,id);controller.answer(h,id,new SessionController.Answer("q1","HashMap"));controller.answer(h,id,new SessionController.Answer("q2","timeouts"));controller.submit(h,id);
   }
   try(var context=new org.springframework.boot.builder.SpringApplicationBuilder(com.exl.interview.InterviewApplication.class).web(org.springframework.boot.WebApplicationType.NONE).run(args)){
     var session=context.getBean(SessionController.class).get("Bearer "+token,id);assertEquals("SUBMITTED",session.state());assertTrue(session.consent());assertNotNull(session.submittedAt());assertEquals(2,session.answers().size());assertEquals(2,session.questions().size());
   }
   try(var files=Files.list(dir)){for(var f:files.toList())Files.delete(f);}Files.delete(dir);
 }
 @Test void revisitingEditingAndClearingAnswersPreservesOneAnswerPerQuestion() throws Exception {
   String token=login(),id=create(token),path="/sessions/"+id;post(path+"/start",token,"{}",200);
   post(path+"/answers",token,"{\"questionId\":\"q1\",\"answer\":\"HashMap\"}",200);
   var changed=post(path+"/answers",token,"{\"questionId\":\"q1\",\"answer\":\"Array\"}",200);assertEquals(1,changed.get("answers").size());assertEquals("Array",changed.get("answers").get(0).get("answer").asText());
   var cleared=post(path+"/answers",token,"{\"questionId\":\"q1\",\"answer\":\"\"}",200);assertEquals(0,cleared.get("answers").size());
   post(path+"/answers",token,"{\"questionId\":\"unknown\",\"answer\":\"\"}",400);
   post(path+"/answers",token,"{\"questionId\":\"q2\",\"answer\":\"My technical answer\"}",200);
   post(path+"/submit",token,"{}",400);post(path+"/submit",token,"{\"allowUnanswered\":false}",400);
   var submitted=post(path+"/submit",token,"{\"allowUnanswered\":true}",200);assertEquals("SUBMITTED",submitted.get("state").asText());assertEquals(1,submitted.get("answers").size());
   post(path+"/answers",token,"{\"questionId\":\"q2\",\"answer\":\"\"}",400);
 }
}
