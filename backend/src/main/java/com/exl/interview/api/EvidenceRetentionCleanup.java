package com.exl.interview.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EvidenceRetentionCleanup {
  private final JdbcTemplate db;
  public EvidenceRetentionCleanup(JdbcTemplate db){this.db=db;}
  @Scheduled(fixedDelay=3600000,initialDelay=3600000)
  public void deleteExpiredEvidence(){db.update("DELETE FROM integrity_evidence WHERE expires_at<=?",System.currentTimeMillis());}
}
