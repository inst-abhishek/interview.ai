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
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:main-catalogue;DB_CLOSE_DELAY=-1","interview.recruiter-setup-code=catalogue-setup","interview.reviewer-code=catalogue-review","interview.invitation-key-file=./target/catalogue-test.key","interview.template-initial-delay-ms=86400000","logging.level.root=ERROR"})
@AutoConfigureMockMvc
class MainCatalogueTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired TemplateCatalogue catalogue;@Autowired TemplateAssessmentService assessments;@MockBean JavaCodeRunner runner;
 JsonNode post(String path,String token,Object body)throws Exception{var request=MockMvcRequestBuilders.post("/api"+path).contentType("application/json").content(json.writeValueAsString(body));if(token!=null)request.header("Authorization","Bearer "+token);return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
 @Test void mainCatalogueSeedsFourRolesWithValidContentAndNeverOverwritesVersions()throws Exception{
  assertTrue(catalogue.enabled());assertEquals(4,catalogue.summaries().size());
  for(var summary:catalogue.summaries()){var definition=catalogue.definition(summary.version());assertEquals(100,definition.items().stream().mapToInt(TemplateCatalogue.Item::maxPoints).sum());assertEquals(11,definition.items().size());assertEquals(11,definition.items().stream().map(TemplateCatalogue.Item::id).distinct().count());for(var q:definition.items()){if(q.kind().equals("MCQ")){assertTrue(q.choices().contains(q.correctAnswer()));assertFalse(q.explanation().isBlank());}else if(q.kind().equals("TECHNICAL"))assertEquals(q.maxPoints(),q.rubric().stream().mapToInt(TemplateCatalogue.Criterion::maxPoints).sum());}assertEquals(summary.version().startsWith("java-"),summary.codingRequired());}
  String original=db.queryForObject("SELECT definition FROM assessment_templates WHERE version='java-backend-mid-v1'",String.class);db.update("UPDATE assessment_templates SET status='APPROVED' WHERE version='java-backend-mid-v1'");catalogue.seed();assertEquals(original,db.queryForObject("SELECT definition FROM assessment_templates WHERE version='java-backend-mid-v1'",String.class));assertEquals("APPROVED",db.queryForObject("SELECT status FROM assessment_templates WHERE version='java-backend-mid-v1'",String.class));
 }
 @Test void nonCodingRolesCompleteRecruiterCandidateReviewerFlowWithoutDocker()throws Exception{
  when(runner.enabled()).thenReturn(false);
  String recruiter=post("/recruiter/register",null,Map.of("name","Catalogue recruiter","email","catalogue@example.com","password","catalogue-password-123","setupCode","catalogue-setup")).get("token").asText();String reviewer=post("/auth/reviewer-login",null,Map.of("code","catalogue-review")).get("token").asText();
  for(String version:List.of("react-developer-mid-v1","cloud-ops-mid-v1")){
   post("/review/templates/"+version+"/approve",reviewer,Map.of());String interview=post("/recruiter/templates/"+version+"/interviews",recruiter,Map.of("title",version,"scheduledAt",0)).get("id").asText();String code=post("/recruiter/interviews/"+interview+"/invitations",recruiter,Map.of("candidateName","Test candidate","candidateEmail","candidate@example.com","expiresAt",System.currentTimeMillis()+86400000)).get("code").asText();String token=post("/auth/login",null,Map.of("code",code)).get("token").asText();String session=post("/sessions",token,Map.of("consent",true,"evidenceConsent",true,"checks",List.of(Map.of("key","camera","status","passed"),Map.of("key","microphone","status","passed"),Map.of("key","screen","status","passed"),Map.of("key","service","status","passed")))).get("id").asText();post("/sessions/"+session+"/start",token,Map.of());
   for(var q:catalogue.definition(version).items())post("/sessions/"+session+"/answers",token,Map.of("questionId",q.id(),"answer",q.kind().equals("MCQ")?q.correctAnswer():"Written scenario response"));post("/sessions/"+session+"/submit",token,Map.of());var evaluation=assessments.evaluation(session);assertEquals("NOT_REQUIRED",evaluation.codingState());assertEquals(0,evaluation.codingMax());assertEquals(0.0,evaluation.codingPoints());assertEquals(40,evaluation.mcqPoints());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM coding_runs WHERE session_id=?",Integer.class,session));
   var marks=evaluation.rubric().stream().map(r->Map.of("questionId",r.questionId(),"criterionId",r.criterionId(),"points",r.maxPoints())).toList();var published=post("/review/sessions/"+session+"/template-evaluation",reviewer,Map.of("marks",marks,"recommendation","SHORTLIST","publish",true));assertEquals(100,published.get("total").asInt());assertEquals("PUBLISHED",published.get("state").asText());
  }
  verify(runner,never()).run(anyString(),anyList());
 }
 @Test void fullStackKeepsJavaReferenceValidation()throws Exception{
  when(runner.enabled()).thenReturn(true);when(runner.run(anyString(),anyList())).thenAnswer(inv->{List<TemplateCatalogue.TestCase> tests=inv.getArgument(1);return new JavaCodeRunner.Result("COMPLETE",tests.size(),tests.size(),List.of(),"Passed");});catalogue.approve("java-angular-mid-v1","reviewer");verify(runner).run(contains("public class Main"),argThat(tests->tests.size()==6));
 }
}
