package com.forward.desk_resolver.service;

import com.forward.desk_resolver.entity.Application;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Catalogue integrity: the V15 constraints and the V16 drop.
 *
 * <p>Duplicate catalogue rows are not a cosmetic problem. A ticket references one {@code application_id},
 * so two rows for the same real module silently split that module's tickets into two queues that no
 * report joins back together - and nothing reports it, because nothing was violated.
 */
class ApplicationCatalogueIT extends AbstractPostgresIT {

    @Autowired private EntityManager entityManager;

    private String body(String appName, String moduleName) {
        return "{\"appName\":\"" + appName + "\",\"moduleName\":\"" + moduleName + "\",\"active\":true}";
    }

    @Test
    @DisplayName("a duplicate app and module pair is a 409 naming the conflict")
    void duplicatePairIsRejected() throws Exception {
        String token = tokenFor(givenUser("ADMIN"));

        mockMvc.perform(authenticated(post("/api/applications"), token)
                        .content(body("HRMS", "attendance")))
                .andExpect(status().isCreated());

        mockMvc.perform(authenticated(post("/api/applications"), token)
                        .content(body("HRMS", "attendance")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("attendance")));

        assertThat(applicationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same application with a different module is allowed")
    void sameAppDifferentModuleIsAllowed() throws Exception {
        String token = tokenFor(givenUser("ADMIN"));

        // This is exactly how the real catalogue is shaped: one application, many modules.
        for (String module : new String[]{"attendance", "leaves", "short term loan", "long term loan"}) {
            mockMvc.perform(authenticated(post("/api/applications"), token).content(body("HRMS", module)))
                    .andExpect(status().isCreated());
        }

        assertThat(applicationRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("an update cannot collide with another row, but may keep its own name")
    void updateRespectsUniqueness() throws Exception {
        String token = tokenFor(givenUser("ADMIN"));
        Application first = applicationRepository.save(catalogue("HRMS", "attendance"));
        Application second = applicationRepository.save(catalogue("HRMS", "leaves"));

        mockMvc.perform(authenticated(put("/api/applications/" + second.getId()), token)
                        .content(body("HRMS", "attendance")))
                .andExpect(status().isConflict());

        // A row is not a conflict with itself, so re-saving unchanged values must succeed.
        mockMvc.perform(authenticated(put("/api/applications/" + first.getId()), token)
                        .content(body("HRMS", "attendance")))
                .andExpect(status().isOk());
    }

    /**
     * The service pre-check turns the common case into a clear 409; the constraint is what actually
     * prevents a duplicate, including when two requests race past the check.
     */
    /**
     * The service pre-check turns the common case into a clear 409; the constraint is what actually
     * prevents a duplicate, including when two requests race past the check.
     *
     * <p>Not {@code @Transactional}, and asserting on {@code save} rather than on a later {@code flush}:
     * the id is {@code GenerationType.IDENTITY}, so Hibernate has to run the INSERT during
     * {@code persist} to learn the generated key. The statement therefore fails inside {@code save},
     * which also means each call needs its own transaction - sharing one would leave a poisoned
     * persistence context behind for the rollback to trip over.
     */
    @Test
    @DisplayName("the database constraint backs the service check")
    void constraintIsEnforcedInTheDatabase() {
        applicationRepository.save(catalogue("HRMS", "attendance"));

        assertThatThrownBy(() -> applicationRepository.save(catalogue("HRMS", "attendance")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(applicationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("module_name is NOT NULL, so the rule is not only in the DTO")
    void moduleNameIsNotNullInTheDatabase() {
        assertThatThrownBy(() -> applicationRepository.save(catalogue("HRMS", null)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(applicationRepository.count()).isZero();
    }

    @Test
    @DisplayName("a blank module name is rejected by validation before it reaches the column")
    void blankModuleNameIsRejected() throws Exception {
        mockMvc.perform(authenticated(post("/api/applications"), tokenFor(givenUser("ADMIN")))
                        .content(body("HRMS", "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.moduleName").exists());
    }

    /**
     * V6 created {@code ticket_history}; V7 superseded it with {@code ticket_history_tracking} and its
     * entity was deleted. It had been "scheduled for removal" ever since without being removed, which is
     * how a dead table starts looking deliberate.
     */
    @Test
    @DisplayName("the superseded ticket_history table is gone")
    @Transactional
    void deadHistoryTableIsDropped() {
        Object exists = entityManager.createNativeQuery("""
                        select count(*) from information_schema.tables
                        where table_name = 'ticket_history'
                        """)
                .getSingleResult();

        assertThat(((Number) exists).intValue())
                .describedAs("ticket_history should have been dropped by V16")
                .isZero();
    }

    @Test
    @DisplayName("the live audit table is still there")
    @Transactional
    void liveHistoryTableSurvives() {
        Object exists = entityManager.createNativeQuery("""
                        select count(*) from information_schema.tables
                        where table_name = 'ticket_history_tracking'
                        """)
                .getSingleResult();

        assertThat(((Number) exists).intValue()).isEqualTo(1);
    }

    /**
     * Comments and attachments are unimplemented rather than superseded, so their tables are kept
     * deliberately - dropping them would discard a usable schema for a feature that is still wanted.
     */
    @Test
    @DisplayName("the unimplemented feature tables are kept on purpose")
    @Transactional
    void unimplementedFeatureTablesAreKept() {
        Object count = entityManager.createNativeQuery("""
                        select count(*) from information_schema.tables
                        where table_name in ('ticket_comments', 'ticket_attachments')
                        """)
                .getSingleResult();

        assertThat(((Number) count).intValue()).isEqualTo(2);
    }

    private static Application catalogue(String appName, String moduleName) {
        Application application = new Application();
        application.setAppName(appName);
        application.setModuleName(moduleName);
        application.setActive(true);
        return application;
    }
}
