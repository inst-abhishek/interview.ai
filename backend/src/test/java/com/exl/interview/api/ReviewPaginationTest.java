package com.exl.interview.api;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:review-pages;DB_CLOSE_DELAY=-1","interview.reviewer-code=page-reviewer","interview.invitation-code=page-candidate","logging.level.root=ERROR"})
@AutoConfigureMockMvc
class ReviewPaginationTest {
 @Autowired JdbcTemplate db; @Autowired MockMvc mvc; @Autowired ObjectMapper json;
 static final long BASE=1700000000000L;String reviewer;
 @BeforeEach void seed() throws Exception {
   db.update("DELETE FROM reviews");db.update("DELETE FROM sessions");db.update("DELETE FROM invitations");db.update("DELETE FROM interviews");db.update("DELETE FROM recruiters");db.update("DELETE FROM tokens");
   db.update("INSERT INTO recruiters VALUES ('r','hiring@example.com','Recruiter','not-used')");
   for(String job:new String[]{"job-a","job-b"})db.update("INSERT INTO interviews (id,recruiter_id,title,description,duration_seconds,scheduled_at,questions,created_at) VALUES (?,'r',?,'Description',1800,0,'[]',0)",job,job.equals("job-a")?"Backend Engineer":"Frontend Engineer");
   for(int i=0;i<35;i++){
     String id=String.format("list-%03d",i),inv="inv-"+i,job=i%2==0?"job-a":"job-b";
     db.update("INSERT INTO invitations (id,interview_id,candidate_name,candidate_email,code_hash,expires_at,session_id,created_at) VALUES (?,?,?,?,?,?,?,?)",inv,job,i==34?"Percent %_ Candidate":"Candidate "+i,"candidate"+i+"@example.com","hash-"+i,BASE+99999999,id,BASE);
     db.update("INSERT INTO sessions (id,owner,state,consent,consent_at,checks,questions,started_at,submitted_at,interview_id,invitation_id) VALUES (?,'owner','SUBMITTED',true,0,'[]','[]',?,?,?,?)",id,BASE-1000,BASE+i*1000,job,inv);
     if(i<12)db.update("INSERT INTO reviews VALUES (?,'reviewer',4,'Notes','HOLD',?)",id,BASE+100000+i);
   }
   db.update("INSERT INTO sessions (id,owner,state,consent,consent_at,checks,questions,submitted_at) VALUES ('legacy','old-owner','SUBMITTED',true,0,'[]','[]',?)",BASE+40000);
   reviewer=token("/auth/reviewer-login","page-reviewer");
 }
 String token(String path,String code)throws Exception{return json.readTree(mvc.perform(MockMvcRequestBuilders.post("/api"+path).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("code",code)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();}
 JsonNode page(String query)throws Exception{return json.readTree(mvc.perform(MockMvcRequestBuilders.get("/api/review/sessions"+query).header("Authorization","Bearer "+reviewer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
 @Test void defaultsAndPagesAreBoundedOrderedAndCounted()throws Exception{
   var first=page("");assertEquals(10,first.get("items").size());assertEquals("list-012",first.get("items").get(0).get("sessionId").asText());assertEquals(24,first.get("total").asInt());assertEquals(36,first.get("allCount").asInt());assertEquals(12,first.get("reviewedCount").asInt());assertEquals(3,first.get("totalPages").asInt());
   var second=page("?page=1");assertEquals("list-022",second.get("items").get(0).get("sessionId").asText());var last=page("?page=999");assertEquals(2,last.get("page").asInt());assertEquals(4,last.get("items").size());assertEquals(24,page("?size=25").get("items").size());
   assertEquals("legacy",page("?sort=NEWEST").get("items").get(0).get("sessionId").asText());var reviewed=page("?status=REVIEWED");assertEquals(12,reviewed.get("total").asInt());assertEquals("list-000",reviewed.get("items").get(0).get("sessionId").asText());assertEquals("list-011",page("?status=REVIEWED&sort=REVIEWED_NEWEST").get("items").get(0).get("sessionId").asText());
 }
 @Test void searchIdentityInterviewDatesAndLiteralWildcards()throws Exception{
   var identity=page("?status=ALL&search=CANDIDATE20@EXAMPLE.COM");assertEquals(1,identity.get("total").asInt());assertEquals("Candidate 20",identity.get("items").get(0).get("candidateName").asText());assertEquals("Backend Engineer",identity.get("items").get(0).get("interviewTitle").asText());assertFalse(identity.get("items").get(0).has("notes"));
   assertEquals(18,page("?status=ALL&interview=Backend").get("total").asInt());assertEquals(1,page("?status=ALL&search=list-007").get("total").asInt());
   var literal=mvc.perform(MockMvcRequestBuilders.get("/api/review/sessions").param("status","ALL").param("search","%_").header("Authorization","Bearer "+reviewer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertEquals(1,json.readTree(literal).get("total").asInt());
   assertEquals(3,page("?status=ALL&from="+(BASE+20000)+"&to="+(BASE+23000)).get("total").asInt());assertEquals(0,page("?search=not-found").get("total").asInt());assertEquals(1,page("?search=not-found").get("totalPages").asInt());
 }
 @Test void validatesQueryAndEnforcesRole()throws Exception{
   for(String query:new String[]{"?page=-1","?size=10000","?status=INVALID","?sort=DROP","?from=50&to=20"})mvc.perform(MockMvcRequestBuilders.get("/api/review/sessions"+query).header("Authorization","Bearer "+reviewer)).andExpect(status().isBadRequest());
   mvc.perform(MockMvcRequestBuilders.get("/api/review/sessions")).andExpect(status().isUnauthorized());mvc.perform(MockMvcRequestBuilders.get("/api/review/sessions").header("Authorization","Bearer "+token("/auth/login","page-candidate"))).andExpect(status().isUnauthorized());
 }
}
