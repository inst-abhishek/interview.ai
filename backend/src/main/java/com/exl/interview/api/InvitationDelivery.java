package com.exl.interview.api;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Durable outbox. SMTP acceptance is not proof of inbox delivery. Ambiguous failures need manual review. */
@Component
public class InvitationDelivery {
 private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"invitation-email-worker");t.setDaemon(true);return t;});
 private final java.util.concurrent.atomic.AtomicBoolean working=new java.util.concurrent.atomic.AtomicBoolean();
 @jakarta.annotation.PreDestroy public void stop(){worker.shutdownNow();}
 private final JdbcTemplate db;private final InvitationMailer mailer;private final InvitationLinks links;
 public InvitationDelivery(JdbcTemplate db,InvitationMailer mailer,InvitationLinks links){this.db=db;this.mailer=mailer;this.links=links;}
 public boolean available(){return mailer.available();}
 private ResponseStatusException fail(int status,String message){return new ResponseStatusException(HttpStatus.valueOf(status),message);}
 public void queue(String owner,String id,boolean reminder,boolean acknowledgeUnknown){
  if(!available())throw fail(503,"Email is not configured. Copy or download invitation links instead.");
  var rows=db.queryForList("SELECT i.*,s.state FROM invitations i JOIN interviews j ON j.id=i.interview_id LEFT JOIN sessions s ON s.id=i.session_id WHERE i.id=? AND j.recruiter_id=? FOR UPDATE",id,owner);if(rows.isEmpty())throw fail(404,"Invitation not found");var row=rows.getFirst();if(((Number)row.get("expires_at")).longValue()<=System.currentTimeMillis()||"SUBMITTED".equals(row.get("state")))throw fail(409,"Do not send expired or submitted invitations");
  var existing=db.queryForList("SELECT status FROM invitation_delivery WHERE invitation_id=?",String.class,id);String status=existing.isEmpty()?"NOT_SENT":existing.getFirst();if(Set.of("QUEUED","SENDING").contains(status))throw fail(409,"An email is already queued or being sent");if(status.equals("UNKNOWN")&&!acknowledgeUnknown)throw fail(409,"Email may already have been sent. Check with the candidate and acknowledge before retrying.");if(status.equals("SENT")&&!reminder)throw fail(409,"Email already sent. Use Send reminder if another message is needed.");if(reminder&&!status.equals("SENT"))throw fail(409,"Send an invitation before sending a reminder");
  db.update("MERGE INTO invitation_delivery (invitation_id,status,kind,attempts,updated_at,last_error) KEY(invitation_id) VALUES (?,'QUEUED',?,COALESCE((SELECT attempts FROM invitation_delivery WHERE invitation_id=?),0),?,NULL)",id,reminder?"REMINDER":"INVITATION",id,System.currentTimeMillis());
 }
 @Scheduled(fixedDelay=10000) public void dispatch(){
  db.update("UPDATE invitation_delivery SET status='UNKNOWN',last_error='Previous send was interrupted. Verify whether the email arrived before retrying.',updated_at=? WHERE status='SENDING' AND updated_at<?",System.currentTimeMillis(),System.currentTimeMillis()-600000);
  if(!available()||!working.compareAndSet(false,true))return;try{worker.submit(()->{try{for(String id:db.queryForList("SELECT invitation_id FROM invitation_delivery WHERE status='QUEUED' ORDER BY updated_at LIMIT 10",String.class))dispatchOne(id);}finally{working.set(false);}});}catch(java.util.concurrent.RejectedExecutionException ignored){working.set(false);}
 }
 void dispatchOne(String id){
  if(db.update("UPDATE invitation_delivery SET status='SENDING',attempts=attempts+1,updated_at=? WHERE invitation_id=? AND status='QUEUED'",System.currentTimeMillis(),id)!=1)return;
  var rows=db.queryForList("SELECT i.*,j.title,j.scheduled_at,s.state,d.kind FROM invitations i JOIN interviews j ON j.id=i.interview_id JOIN invitation_delivery d ON d.invitation_id=i.id LEFT JOIN sessions s ON s.id=i.session_id WHERE i.id=?",id);
  if(rows.isEmpty())return;var r=rows.getFirst();long expires=((Number)r.get("expires_at")).longValue();
  if(expires<=System.currentTimeMillis()||"SUBMITTED".equals(r.get("state"))){finish(id,"FAILED","Invitation expired or assessment already submitted; email was not sent.");return;}
  String code;try{code=links.decrypt((String)r.get("encrypted_code"));}catch(Exception e){finish(id,"FAILED","Invitation key is unavailable. Regenerate the invitation before sending.");return;}
  try{mailer.send((String)r.get("candidate_email"),(String)r.get("candidate_name"),(String)r.get("title"),((Number)r.get("scheduled_at")).longValue(),expires,code,"REMINDER".equals(r.get("kind")));finish(id,"SENT",null);}
  catch(org.springframework.mail.MailAuthenticationException e){finish(id,"FAILED","Mail server authentication failed. Ask the administrator to check email settings.");}
  catch(Exception e){finish(id,"UNKNOWN","Mail server did not confirm the outcome. Verify whether the email arrived before retrying.");}
 }
 private void finish(String id,String status,String error){db.update("UPDATE invitation_delivery SET status=?,last_error=?,updated_at=? WHERE invitation_id=?",status,error,System.currentTimeMillis(),id);}
}
