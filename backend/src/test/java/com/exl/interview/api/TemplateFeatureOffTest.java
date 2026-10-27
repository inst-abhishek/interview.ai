package com.exl.interview.api;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:feature-off;DB_CLOSE_DELAY=-1","logging.level.root=ERROR","interview.catalogue-enabled=false"})
class TemplateFeatureOffTest {
 @Autowired TemplateCatalogue catalogue;@Autowired JdbcTemplate db;
 @Test void disabledCatalogueDoesNotSeedTemplates(){assertFalse(catalogue.enabled());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM assessment_templates",Integer.class));}
}
