package com.exl.interview.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Finalizes saved answers independently of candidate browser activity. */
@Component
public class AssessmentExpiry {
  private final JdbcTemplate db;
  public AssessmentExpiry(JdbcTemplate db){this.db=db;}
  @Scheduled(fixedDelayString="${interview.expiry-check-ms:1000}",initialDelayString="${interview.expiry-initial-delay-ms:0}")
  public void submitExpired(){
    db.update("UPDATE sessions SET state='SUBMITTED',submitted_at=deadline,submission_reason='TIME_EXPIRED' WHERE state='IN_PROGRESS' AND deadline IS NOT NULL AND deadline<=?",System.currentTimeMillis());
  }
  public void submitExpired(String id,String owner){
    db.update("UPDATE sessions SET state='SUBMITTED',submitted_at=deadline,submission_reason='TIME_EXPIRED' WHERE id=? AND owner=? AND state='IN_PROGRESS' AND deadline IS NOT NULL AND deadline<=?",id,owner,System.currentTimeMillis());
  }
}
