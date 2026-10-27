package com.exl.interview.api;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:feature-off;DB_CLOSE_DELAY=-1","logging.level.root=ERROR"})
class TemplateFeatureOffTest {
 @Autowired TemplateCatalogue catalogue;@Autowired JdbcTemplate db;
 @Test void defaultApplicationDoesNotCreateThePreviewSchema(){assertFalse(catalogue.enabled());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='ASSESSMENT_TEMPLATES'",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='INTERVIEWS' AND COLUMN_NAME='TEMPLATE_VERSION'",Integer.class));}
}
