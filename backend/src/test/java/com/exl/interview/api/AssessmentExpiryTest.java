package com.exl.interview.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:expiry-tests;DB_CLOSE_DELAY=-1","interview.invitation-code=expiry-demo","interview.expiry-check-ms=50","logging.level.root=ERROR"})
class AssessmentExpiryTest {
 @Autowired JdbcTemplate db; @Autowired SessionController controller; @Autowired AssessmentExpiry expiry;
 private record Started(String token,String id) {}
 private Started start(){String token="Bearer "+controller.login(new SessionController.Login("expiry-demo")).get("token");var checks=java.util.stream.Stream.of("camera","microphone","screen","service").map(k->new SessionController.Check(k,"passed")).toList();String id=controller.create(token,new SessionController.NewSession(true,true,checks)).id();controller.start(token,id);return new Started(token,id);}
 @Test void schedulerSubmitsSavedAnswersWithoutCandidateRequests() throws Exception {
   var s=start();controller.answer(s.token(),s.id(),new SessionController.Answer("q1","HashMap"));long deadline=System.currentTimeMillis()-10;db.update("UPDATE sessions SET deadline=? WHERE id=?",deadline,s.id());
   long waitUntil=System.nanoTime()+5_000_000_000L;
   while(!"SUBMITTED".equals(db.queryForObject("SELECT state FROM sessions WHERE id=?",String.class,s.id()))&&System.nanoTime()<waitUntil)Thread.sleep(20);
   assertEquals("SUBMITTED",db.queryForObject("SELECT state FROM sessions WHERE id=?",String.class,s.id()));assertEquals(deadline,db.queryForObject("SELECT submitted_at FROM sessions WHERE id=?",Long.class,s.id()));assertEquals("TIME_EXPIRED",db.queryForObject("SELECT submission_reason FROM sessions WHERE id=?",String.class,s.id()));
   assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM answers WHERE session_id=?",Integer.class,s.id()));var saved=controller.get(s.token(),s.id());assertEquals("TIME_EXPIRED",saved.submissionReason());
   assertThrows(ResponseStatusException.class,()->controller.answer(s.token(),s.id(),new SessionController.Answer("q2","Too late")));
   expiry.submitExpired();assertEquals(deadline,controller.submit(s.token(),s.id()).submittedAt());
 }
 @Test void futureAndReadySessionsAreNotFinalizedAndManualSubmissionIsPreserved(){
   var future=start();var manual=start();controller.submit(manual.token(),manual.id(),new SessionController.SubmissionInput(true));Long manualTime=controller.get(manual.token(),manual.id()).submittedAt();
   String ready=UUID.randomUUID().toString();db.update("INSERT INTO sessions (id,owner,state,deadline,questions,checks,consent_at) VALUES (?,?,'READY',0,'[]','[]',0)",ready,"test-owner");
   expiry.submitExpired();assertEquals("IN_PROGRESS",controller.get(future.token(),future.id()).state());assertEquals("READY",db.queryForObject("SELECT state FROM sessions WHERE id=?",String.class,ready));var result=controller.get(manual.token(),manual.id());assertEquals("MANUAL",result.submissionReason());assertEquals(manualTime,result.submittedAt());
 }
 @Test void readFinalizesAnOverdueSessionWithoutWaitingForScheduler(){var s=start();db.update("UPDATE sessions SET deadline=0 WHERE id=?",s.id());var result=controller.get(s.token(),s.id());assertEquals("SUBMITTED",result.state());assertEquals("TIME_EXPIRED",result.submissionReason());}

 @Test void backendRestartFinalizesAssessmentsThatExpiredWhileItWasOffline() throws Exception {
   var dir=java.nio.file.Files.createTempDirectory("expiry-restart");String url="jdbc:h2:file:"+dir.resolve("interviews").toAbsolutePath().toString().replace('\\','/');
   String[] args={"--spring.datasource.url="+url,"--interview.invitation-code=restart-expiry","--interview.expiry-check-ms=50","--logging.level.root=ERROR","--debug=false"};String id;
   try(var context=new org.springframework.boot.builder.SpringApplicationBuilder(com.exl.interview.InterviewApplication.class).web(org.springframework.boot.WebApplicationType.NONE).run(args)){
     var api=context.getBean(SessionController.class);String token="Bearer "+api.login(new SessionController.Login("restart-expiry")).get("token");var checks=java.util.stream.Stream.of("camera","microphone","screen","service").map(k->new SessionController.Check(k,"passed")).toList();id=api.create(token,new SessionController.NewSession(true,true,checks)).id();api.start(token,id);api.answer(token,id,new SessionController.Answer("q1","HashMap"));
   }
   long deadline=System.currentTimeMillis()-1000;
   try(var c=java.sql.DriverManager.getConnection(url,"sa","");var statement=c.prepareStatement("UPDATE sessions SET deadline=? WHERE id=?")){statement.setLong(1,deadline);statement.setString(2,id);statement.executeUpdate();}
   try(var context=new org.springframework.boot.builder.SpringApplicationBuilder(com.exl.interview.InterviewApplication.class).web(org.springframework.boot.WebApplicationType.NONE).run(args)){
     var database=context.getBean(JdbcTemplate.class);long waitUntil=System.nanoTime()+5_000_000_000L;while(!"SUBMITTED".equals(database.queryForObject("SELECT state FROM sessions WHERE id=?",String.class,id))&&System.nanoTime()<waitUntil)Thread.sleep(20);
     assertEquals("SUBMITTED",database.queryForObject("SELECT state FROM sessions WHERE id=?",String.class,id));assertEquals("TIME_EXPIRED",database.queryForObject("SELECT submission_reason FROM sessions WHERE id=?",String.class,id));assertEquals(deadline,database.queryForObject("SELECT submitted_at FROM sessions WHERE id=?",Long.class,id));assertEquals("HashMap",database.queryForObject("SELECT answer FROM answers WHERE session_id=?",String.class,id));
   }
   try(var files=java.nio.file.Files.list(dir)){for(var f:files.toList())java.nio.file.Files.delete(f);}java.nio.file.Files.delete(dir);
 }
}
