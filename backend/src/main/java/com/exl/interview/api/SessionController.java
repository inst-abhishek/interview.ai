package com.exl.interview.api;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController @RequestMapping("/api")
@CrossOrigin(origins="http://127.0.0.1:4200")
public class SessionController {
  private final JdbcTemplate db; private final ObjectMapper json; private final AssessmentExpiry expiry;private final TemplateCatalogue catalogue;
  @Value("${interview.invitation-code:}") private String code;
  @Value("${interview.reviewer-code:}") private String reviewerCode;
  @Value("${interview.duration-seconds:1800}") private long duration;
  @Value("${interview.evidence-retention-days:30}") private long evidenceRetentionDays;
  public SessionController(JdbcTemplate db,ObjectMapper json,AssessmentExpiry expiry,TemplateCatalogue catalogue){this.db=db;this.json=json;this.expiry=expiry;this.catalogue=catalogue;}
  public record Login(String code) {}
  public record ReviewSubmission(Integer score,String notes,String recommendation) {}
  public record ReviewSummary(String sessionId,Long submittedAt,Long startedAt,Integer score,String recommendation,Long reviewedAt,String candidateName,String candidateEmail,String interviewTitle) {}
  public record ReviewPage(List<ReviewSummary> items,int page,int size,long total,int totalPages,long allCount,long awaitingCount,long reviewedCount) {}
  public record EvidenceCapture(String eventType,String mediaType,String contentType,String imageData) {}
  public record EvidenceSummary(String id,String eventType,String mediaType,Long capturedAt,Long expiresAt) {}
  public record EvidenceImage(String id,String contentType,String imageData) {}
  public record ReviewDetail(String sessionId,String state,Long submittedAt,Long startedAt,List<Question> questions,List<Answer> answers,List<Check> checks,boolean evidenceConsent,long evidenceRetentionDays,List<EvidenceSummary> evidence,Integer score,String notes,String recommendation,Long reviewedAt,String templateVersion) {}
  private record StoredReviewSession(String id,String state,Long submittedAt,Long startedAt,List<Question> questions,List<Check> checks,boolean evidenceConsent) {}
  private record StoredReview(Integer score,String notes,String recommendation,Long reviewedAt) {}
  public record Check(String key,String status) {}
  public record NewSession(boolean consent,boolean evidenceConsent,List<Check> checks) {}
  public record Answer(String questionId,String answer) {}
  public record SubmissionInput(boolean allowUnanswered) {}
  public record Question(String id,String kind,String text,List<String> choices) {}
  public record Session(String id,String state,boolean consent,long consentAt,boolean evidenceConsent,Long deadline,Long submittedAt,List<Question> questions,List<Answer> answers,long serverTime,String submissionReason,String templateVersion) {}
  private long now(){return System.currentTimeMillis();}
  private ResponseStatusException fail(HttpStatus status,String message){return new ResponseStatusException(status,message);}
  private ResponseStatusException bad(String message){return fail(HttpStatus.BAD_REQUEST,message);}
  private String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
  private String encode(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
  private <T>T decode(String s,TypeReference<T> t){try{return json.readValue(s,t);}catch(Exception e){throw new IllegalStateException(e);}}
  String auth(String header){
    if(header==null||!header.startsWith("Bearer "))throw fail(HttpStatus.UNAUTHORIZED,"Sign in required");
    String owner=hash(header.substring(7));
    if(db.queryForObject("SELECT COUNT(*) FROM tokens WHERE token=? AND expires>? AND role='CANDIDATE'",Integer.class,owner,now())==0)throw fail(HttpStatus.UNAUTHORIZED,"Candidate sign in required");
    var invitations=db.queryForList("SELECT invitation_id FROM tokens WHERE token=? AND invitation_id IS NOT NULL",String.class,owner);
    return invitations.isEmpty()?owner:invitations.getFirst();
  }
  String reviewerAuth(String header){
    if(header==null||!header.startsWith("Bearer "))throw fail(HttpStatus.UNAUTHORIZED,"Reviewer sign in required");
    String reviewer=hash(header.substring(7));
    if(db.queryForObject("SELECT COUNT(*) FROM tokens WHERE token=? AND expires>? AND role='REVIEWER'",Integer.class,reviewer,now())==0)throw fail(HttpStatus.UNAUTHORIZED,"Reviewer sign in required");
    return reviewer;
  }
  private Map<String,String> issueToken(String role){
    String token=UUID.randomUUID().toString()+UUID.randomUUID();
    db.update("INSERT INTO tokens (token,expires,role) VALUES (?,?,?)",hash(token),now()+86400000,role);
    return Map.of("token",token);
  }
  @GetMapping("/health") public Map<String,Object> health(){db.queryForObject("SELECT 1",Integer.class);return Map.of("status","UP","milestone","M2","evidenceRetentionDays",Math.max(1,evidenceRetentionDays));}
  @PostMapping("/auth/login") public Map<String,String> login(@RequestBody Login input){
    if(input==null||input.code()==null||input.code().isBlank())throw fail(HttpStatus.UNAUTHORIZED,"Invitation code required");
    var invitations=db.queryForList("SELECT id,expires_at,session_id FROM invitations WHERE code_hash=?",hash(input.code()));
    if(!invitations.isEmpty()){
      var invitation=invitations.getFirst();
      if(((Number)invitation.get("expires_at")).longValue()<=now())throw fail(HttpStatus.UNAUTHORIZED,"Invitation expired; contact your recruiter");
      var result=issueToken("CANDIDATE");
      db.update("UPDATE tokens SET invitation_id=? WHERE token=?",invitation.get("id"),hash(result.get("token")));
      var response=new HashMap<>(result);if(invitation.get("session_id")!=null)response.put("sessionId",(String)invitation.get("session_id"));return response;
    }
    if(code.isBlank()||!MessageDigest.isEqual(hash(code).getBytes(StandardCharsets.UTF_8),hash(input.code()).getBytes(StandardCharsets.UTF_8)))throw fail(HttpStatus.UNAUTHORIZED,"Invalid invitation code");
    return issueToken("CANDIDATE");
  }
  @PostMapping("/auth/reviewer-login") public Map<String,String> reviewerLogin(@RequestBody Login input){
    if(reviewerCode.isBlank())throw fail(HttpStatus.SERVICE_UNAVAILABLE,"Set INTERVIEW_REVIEWER_CODE on the backend");
    if(input==null||input.code()==null||!MessageDigest.isEqual(hash(reviewerCode).getBytes(StandardCharsets.UTF_8),hash(input.code()).getBytes(StandardCharsets.UTF_8)))throw fail(HttpStatus.UNAUTHORIZED,"Invalid reviewer code");
    return issueToken("REVIEWER");
  }
  private List<Question> bank(){return db.query("SELECT * FROM questions WHERE recruiter_id IS NULL ORDER BY id",(r,n)->new Question(r.getString("id"),r.getString("kind"),r.getString("text"),decode(r.getString("choices"),new TypeReference<List<String>>(){})));}
  @GetMapping("/questions") public List<Question> questions(@RequestHeader(value="Authorization",required=false) String h){String owner=auth(h);var assigned=db.queryForList("SELECT j.questions FROM invitations i JOIN interviews j ON j.id=i.interview_id WHERE i.id=?",String.class,owner);return assigned.isEmpty()?bank():decode(assigned.getFirst(),new TypeReference<List<Question>>(){});}
  @GetMapping("/invitation") public Map<String,Object> invitation(@RequestHeader(value="Authorization",required=false) String h){String owner=auth(h);var rows=db.query("SELECT i.candidate_name,i.expires_at,j.title,j.scheduled_at,j.duration_seconds FROM invitations i JOIN interviews j ON j.id=i.interview_id WHERE i.id=?",(r,n)->Map.<String,Object>of("candidateName",r.getString("candidate_name"),"expiresAt",r.getLong("expires_at"),"title",r.getString("title"),"scheduledAt",r.getLong("scheduled_at"),"durationSeconds",r.getInt("duration_seconds")),owner);return rows.isEmpty()?Map.of():rows.getFirst();}
  private Session require(String id,String owner,boolean lock){
    expiry.submitExpired(id,owner);
    var rows=db.query("SELECT * FROM sessions WHERE id=? AND owner=?"+(lock?" FOR UPDATE":""),(r,n)->new Session(r.getString("id"),r.getString("state"),r.getBoolean("consent"),r.getLong("consent_at"),r.getBoolean("evidence_consent"),(Long)r.getObject("deadline"),(Long)r.getObject("submitted_at"),decode(r.getString("questions"),new TypeReference<List<Question>>(){}),db.query("SELECT question_id,answer FROM answers WHERE session_id=? ORDER BY question_id",(a,i)->new Answer(a.getString(1),a.getString(2)),id),now(),r.getString("submission_reason"),catalogue.versionForSession(id)),id,owner);
    if(rows.isEmpty())throw fail(HttpStatus.NOT_FOUND,"Unknown session");return rows.getFirst();
  }
  @Transactional @PostMapping("/sessions") public Session create(@RequestHeader(value="Authorization",required=false) String h,@RequestBody NewSession input){
    String owner=auth(h);if(input==null||!input.consent())throw bad("Consent required");
    if(!input.evidenceConsent())throw bad("Acknowledgement of required event-based evidence capture is required");
    Set<String> passed=new HashSet<>();if(input.checks()!=null)for(Check c:input.checks())if(c!=null&&"passed".equals(c.status()))passed.add(c.key());
    if(!passed.containsAll(Set.of("camera","microphone","screen","service")))throw bad("Required checks not complete");
    var invitations=db.queryForList("SELECT i.*,j.questions AS assigned_questions,j.duration_seconds,j.scheduled_at FROM invitations i JOIN interviews j ON j.id=i.interview_id WHERE i.id=? FOR UPDATE",owner);
    if(!invitations.isEmpty()){
      var invitation=invitations.getFirst();
      if(invitation.get("session_id")!=null)return require((String)invitation.get("session_id"),owner,false);
      if(((Number)invitation.get("expires_at")).longValue()<=now())throw fail(HttpStatus.FORBIDDEN,"Invitation expired");
      if(((Number)invitation.get("scheduled_at")).longValue()>now())throw fail(HttpStatus.CONFLICT,"Interview has not reached its scheduled start");
      String id=UUID.randomUUID().toString();String assigned=db.queryForObject("SELECT questions FROM interviews WHERE id=?",String.class,invitation.get("interview_id"));
      db.update("INSERT INTO sessions (id,owner,state,consent,consent_at,checks,questions,evidence_consent,interview_id,invitation_id,duration_seconds) VALUES (?,?,'READY',true,?,?,?,?,?,?,?)",id,owner,now(),encode(input.checks()),assigned,input.evidenceConsent(),invitation.get("interview_id"),owner,invitation.get("duration_seconds"));
      db.update("UPDATE invitations SET session_id=? WHERE id=?",id,owner);return require(id,owner,false);
    }
    var questions=bank();if(questions.isEmpty())throw bad("No questions configured");String id=UUID.randomUUID().toString();
    db.update("INSERT INTO sessions (id,owner,state,consent,consent_at,checks,questions,evidence_consent) VALUES (?,?,'READY',true,?,?,?,?)",id,owner,now(),encode(input.checks()),encode(questions),input.evidenceConsent());return require(id,owner,false);
  }
  @Transactional @PostMapping("/sessions/{id}/start") public Session start(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){
    String owner=auth(h);Session s=require(id,owner,true);if(!s.evidenceConsent())throw fail(HttpStatus.FORBIDDEN,"Acknowledgement of required event-based evidence capture is required");if("IN_PROGRESS".equals(s.state()))return s;if(!"READY".equals(s.state()))throw bad("Session must be READY");
    var invitations=db.queryForList("SELECT i.expires_at,j.scheduled_at FROM invitations i JOIN interviews j ON j.id=i.interview_id WHERE i.id=?",owner);
    if(!invitations.isEmpty()){var invitation=invitations.getFirst();if(((Number)invitation.get("expires_at")).longValue()<=now())throw fail(HttpStatus.FORBIDDEN,"Invitation expired");if(((Number)invitation.get("scheduled_at")).longValue()>now())throw fail(HttpStatus.CONFLICT,"Interview has not reached its scheduled start");}
    Integer assignedDuration=db.queryForObject("SELECT duration_seconds FROM sessions WHERE id=?",Integer.class,id);long seconds=assignedDuration==null?duration:assignedDuration;
    long startedAt=now();db.update("UPDATE sessions SET state='IN_PROGRESS',started_at=?,deadline=? WHERE id=?",startedAt,startedAt+seconds*1000,id);return require(id,owner,false);
  }
  @Transactional @PostMapping("/sessions/{id}/answers") public Session answer(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody Answer answer){
    String owner=auth(h);Session s=require(id,owner,true);if(s.deadline()!=null&&now()>=s.deadline())throw fail(HttpStatus.CONFLICT,"Time expired; saved answers are submitted automatically");if(!"IN_PROGRESS".equals(s.state()))throw bad("Session is not in progress");
    if(now()>=s.deadline())throw fail(HttpStatus.CONFLICT,"Time expired; saved answers are submitted automatically");
    if(answer==null||answer.answer()==null||answer.answer().length()>20000)throw bad("Invalid answer");
    Question q=s.questions().stream().filter(x->x.id().equals(answer.questionId())).findFirst().orElseThrow(()->bad("Unknown question"));
    if(answer.answer().isBlank()){db.update("DELETE FROM answers WHERE session_id=? AND question_id=?",id,answer.questionId());return require(id,owner,false);}
    if(!q.choices().isEmpty()&&!q.choices().contains(answer.answer()))throw bad("Invalid choice");
    db.update("MERGE INTO answers KEY(session_id,question_id) VALUES (?,?,?)",id,answer.questionId(),answer.answer());return require(id,owner,false);
  }
  @Transactional @PostMapping("/sessions/{id}/submit") public Session submit(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody(required=false) SubmissionInput input){
    String owner=auth(h);Session s=require(id,owner,true);if("SUBMITTED".equals(s.state()))return s;if(!"IN_PROGRESS".equals(s.state()))throw bad("Session is not in progress");
    if(now()<s.deadline()&&s.answers().size()!=s.questions().size()&&(input==null||!input.allowUnanswered()))throw bad("Answer all questions before submitting");
    db.update("UPDATE sessions SET state='SUBMITTED',submitted_at=?,submission_reason='MANUAL' WHERE id=?",now(),id);return require(id,owner,false);
  }
  public Session submit(String h,String id){return submit(h,id,null);}
  @GetMapping("/sessions/{id}") public Session get(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){return require(id,auth(h),false);}
  @GetMapping("/review/sessions") public ReviewPage reviewSessions(
      @RequestHeader(value="Authorization",required=false) String h,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,
      @RequestParam(defaultValue="") String search,@RequestParam(defaultValue="AWAITING") String status,
      @RequestParam(defaultValue="") String interview,@RequestParam(required=false) Long from,
      @RequestParam(required=false) Long to,@RequestParam(defaultValue="OLDEST") String sort){
    reviewerAuth(h);
    if(page<0||!Set.of(10,25,50).contains(size))throw bad("Page must be nonnegative and page size must be 10, 25 or 50");
    if(!Set.of("ALL","AWAITING","REVIEWED").contains(status))throw bad("Invalid review status");
    if(search.length()>200||interview.length()>200)throw bad("Search values must be at most 200 characters");
    if((from!=null&&from<0)||(to!=null&&to<0)||(from!=null&&to!=null&&from>=to))throw bad("Invalid submission date range");
    String order=switch(sort){case "OLDEST"->"s.submitted_at ASC,s.id ASC";case "NEWEST"->"s.submitted_at DESC,s.id ASC";case "REVIEWED_NEWEST"->"r.reviewed_at DESC NULLS LAST,s.submitted_at DESC,s.id ASC";default->throw bad("Invalid sort order");};
    String joins=" FROM sessions s LEFT JOIN reviews r ON r.session_id=s.id LEFT JOIN invitations i ON i.id=s.invitation_id LEFT JOIN interviews j ON j.id=s.interview_id";
    StringBuilder where=new StringBuilder(" WHERE s.state='SUBMITTED'");List<Object> args=new ArrayList<>();
    if(!search.isBlank()){String pattern=searchPattern(search.trim());where.append(" AND (LOWER(COALESCE(i.candidate_name,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(i.candidate_email,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(j.title,'')) LIKE ? ESCAPE '!' OR LOWER(s.id) LIKE ? ESCAPE '!')");for(int n=0;n<4;n++)args.add(pattern);}
    if(!interview.isBlank()){where.append(" AND LOWER(COALESCE(j.title,'Shared-code assessment')) LIKE ? ESCAPE '!'");args.add(searchPattern(interview.trim()));}
    if(from!=null){where.append(" AND s.submitted_at>=?");args.add(from);}if(to!=null){where.append(" AND s.submitted_at<?");args.add(to);}
    var counts=db.queryForMap("SELECT COUNT(*) AS total,COALESCE(SUM(CASE WHEN r.reviewed_at IS NULL THEN 1 ELSE 0 END),0) AS awaiting"+joins+where,args.toArray());
    long all=((Number)counts.get("total")).longValue(),awaiting=((Number)counts.get("awaiting")).longValue(),reviewed=all-awaiting;
    long total=status.equals("ALL")?all:status.equals("AWAITING")?awaiting:reviewed;
    if(status.equals("AWAITING"))where.append(" AND r.reviewed_at IS NULL");else if(status.equals("REVIEWED"))where.append(" AND r.reviewed_at IS NOT NULL");
    int pages=(int)Math.max(1,Math.min(Integer.MAX_VALUE,(total+size-1)/size)),current=Math.min(page,pages-1);args.add(size);args.add((long)current*size);
    var items=db.query("SELECT s.id,s.submitted_at,s.started_at,r.score,r.recommendation,r.reviewed_at,i.candidate_name,i.candidate_email,j.title"+joins+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",(r,n)->new ReviewSummary(r.getString("id"),(Long)r.getObject("submitted_at"),(Long)r.getObject("started_at"),(Integer)r.getObject("score"),r.getString("recommendation"),(Long)r.getObject("reviewed_at"),r.getString("candidate_name"),r.getString("candidate_email"),r.getString("title")),args.toArray());
    return new ReviewPage(items,current,size,total,pages,all,awaiting,reviewed);
  }
  private String searchPattern(String value){return "%"+value.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
  @GetMapping("/review/sessions/{id}") public ReviewDetail reviewSession(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){
    reviewerAuth(h);
    var rows=db.query("SELECT id,state,submitted_at,started_at,questions,checks,evidence_consent FROM sessions WHERE id=? AND state='SUBMITTED'",(r,n)->new StoredReviewSession(r.getString("id"),r.getString("state"),(Long)r.getObject("submitted_at"),(Long)r.getObject("started_at"),decode(r.getString("questions"),new TypeReference<List<Question>>(){}),decode(r.getString("checks"),new TypeReference<List<Check>>(){}),r.getBoolean("evidence_consent")),id);
    if(rows.isEmpty())throw fail(HttpStatus.NOT_FOUND,"Submitted session not found");
    StoredReviewSession session=rows.getFirst();
    var answers=db.query("SELECT question_id,answer FROM answers WHERE session_id=? ORDER BY question_id",(r,n)->new Answer(r.getString(1),r.getString(2)),id);
    var evidence=db.query("SELECT id,event_type,media_type,captured_at,expires_at FROM integrity_evidence WHERE session_id=? AND expires_at>? ORDER BY captured_at",(r,n)->new EvidenceSummary(r.getString("id"),r.getString("event_type"),r.getString("media_type"),r.getLong("captured_at"),r.getLong("expires_at")),id,now());
    var reviews=db.query("SELECT score,notes,recommendation,reviewed_at FROM reviews WHERE session_id=?",(r,n)->new StoredReview(r.getInt("score"),r.getString("notes"),r.getString("recommendation"),r.getLong("reviewed_at")),id);
    StoredReview review=reviews.isEmpty()?null:reviews.getFirst();
    return new ReviewDetail(session.id(),session.state(),session.submittedAt(),session.startedAt(),session.questions(),answers,session.checks(),session.evidenceConsent(),Math.max(1,evidenceRetentionDays),evidence,review==null?null:review.score(),review==null?null:review.notes(),review==null?null:review.recommendation(),review==null?null:review.reviewedAt(),catalogue.versionForSession(id));
  }
  @Transactional @PostMapping("/review/sessions/{id}") public ReviewDetail saveReview(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody ReviewSubmission input){
    String reviewer=reviewerAuth(h);
    if(catalogue.versionForSession(id)!=null)throw bad("Use the template rubric evaluation instead of an unstructured overall score");
    if(input==null||input.score()==null||input.score()<1||input.score()>5)throw bad("Score must be between 1 and 5");
    if(input.notes()==null||input.notes().isBlank()||input.notes().length()>5000)throw bad("Review notes are required and must be at most 5000 characters");
    if(input.recommendation()==null||!Set.of("SHORTLIST","HOLD","REJECT").contains(input.recommendation()))throw bad("Choose a valid recommendation");
    if(db.queryForObject("SELECT COUNT(*) FROM sessions WHERE id=? AND state='SUBMITTED'",Integer.class,id)==0)throw fail(HttpStatus.NOT_FOUND,"Submitted session not found");
    db.update("MERGE INTO reviews (session_id,reviewer,score,notes,recommendation,reviewed_at) KEY(session_id) VALUES (?,?,?,?,?,?)",id,reviewer,input.score(),input.notes().trim(),input.recommendation(),now());
    return reviewSession(h,id);
  }
  @PostMapping("/sessions/{id}/evidence") public EvidenceSummary captureEvidence(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody EvidenceCapture input){
    String owner=auth(h);
    Session session=require(id,owner,false);
    if(!"IN_PROGRESS".equals(session.state()))throw fail(HttpStatus.CONFLICT,"Evidence can only be added during an active assessment");
    Boolean consent=db.queryForObject("SELECT evidence_consent FROM sessions WHERE id=? AND owner=?",Boolean.class,id,owner);
    if(!Boolean.TRUE.equals(consent))throw fail(HttpStatus.FORBIDDEN,"Candidate did not consent to event-based evidence snapshots");
    if(input==null||input.eventType()==null||input.mediaType()==null||!Set.of("TAB_HIDDEN","WINDOW_BLUR","FULLSCREEN_EXIT","CAMERA_STOPPED","SCREEN_STOPPED").contains(input.eventType())||!Set.of("CAMERA","SCREEN").contains(input.mediaType()))throw bad("Unsupported evidence event or media type");
    if(!"image/jpeg".equals(input.contentType())||input.imageData()==null||input.imageData().isBlank()||input.imageData().length()>700_000)throw bad("A JPEG snapshot under 500 KB is required");
    byte[] image;
    try{image=Base64.getDecoder().decode(input.imageData());}catch(IllegalArgumentException e){throw bad("Snapshot data is not valid base64");}
    if(image.length==0||image.length>500_000)throw bad("Snapshot must be between 1 byte and 500 KB");
    try{
      BufferedImage decoded=ImageIO.read(new ByteArrayInputStream(image));
      if(decoded==null||decoded.getWidth()>1280||decoded.getHeight()>720)throw bad("Snapshot must be a valid image no larger than 1280 by 720 pixels");
      ByteArrayOutputStream normalized=new ByteArrayOutputStream();
      if(!ImageIO.write(decoded,"jpeg",normalized))throw bad("Snapshot could not be normalized as JPEG");
      image=normalized.toByteArray();
    }catch(IOException e){throw bad("Snapshot image could not be decoded");}
    if(image.length>500_000)throw bad("Normalized snapshot exceeds the 500 KB upload limit");
    if(db.queryForObject("SELECT COUNT(*) FROM integrity_evidence WHERE session_id=?",Integer.class,id)>=50)throw fail(HttpStatus.TOO_MANY_REQUESTS,"Snapshot limit reached for this assessment");
    long capturedAt=now(),expiresAt=capturedAt+Math.max(1,evidenceRetentionDays)*86400000L;
    String evidenceId=UUID.randomUUID().toString();
    db.update("INSERT INTO integrity_evidence (id,session_id,event_type,media_type,captured_at,expires_at,content_type,image) VALUES (?,?,?,?,?,?,?,?)",evidenceId,id,input.eventType(),input.mediaType(),capturedAt,expiresAt,input.contentType(),image);
    return new EvidenceSummary(evidenceId,input.eventType(),input.mediaType(),capturedAt,expiresAt);
  }
  @GetMapping("/review/evidence/{id}") public ResponseEntity<byte[]> reviewEvidence(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){
    reviewerAuth(h);
    var rows=db.query("SELECT content_type,image FROM integrity_evidence WHERE id=? AND expires_at>?",(r,n)->ResponseEntity.ok().contentType(MediaType.parseMediaType(r.getString("content_type"))).body(r.getBytes("image")),id,now());
    if(rows.isEmpty())throw fail(HttpStatus.NOT_FOUND,"Evidence not found or retention period expired");
    return rows.getFirst();
  }
  @DeleteMapping("/review/evidence/{id}") public Map<String,String> deleteReviewEvidence(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){
    reviewerAuth(h);
    int deleted=db.update("DELETE FROM integrity_evidence WHERE id=?",id);
    if(deleted==0)throw fail(HttpStatus.NOT_FOUND,"Evidence not found or already deleted");
    return Map.of("status","deleted");
  }
}
