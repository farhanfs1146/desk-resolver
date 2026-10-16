package com.forward.desk_resolver.service;

import com.forward.desk_resolver.entity.Application;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.enums.BusinessImpact;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code businessImpact} as a constrained value rather than free text.
 *
 * <p><strong>This is the test file for a breaking input change</strong>, which is why it exists
 * separately from {@code TicketLifecycleIT}. Until {@code V23} the field took 50 characters of
 * anything, and the four tickets in the database used it to say the same thing two ways -
 * {@code Organization-Level} and {@code Organization Level}. Both are now 400s. A client that was
 * sending prose gets an error rather than silently losing the value, and the tests below are what
 * say so out loud.
 *
 * <p>The other half of what is pinned here is that it stayed <em>optional</em>. Converting a field
 * is the moment somebody adds {@code @NotNull} to it on the grounds that a constrained field may as
 * well be required, and that would make every existing client's create request fail - for a question
 * a requester is often not in a position to answer.
 */
class BusinessImpactIT extends AbstractPostgresIT {

    private static final String TICKETS = "/api/tickets";

    /** A create body with whatever {@code businessImpact} fragment the test wants to send. */
    private String body(long applicationId, String impactFragment) {
        return """
                {"title":"Payslip incorrect",
                 "description":"Net pay miscalculated for March",
                 "issueType":"BUG",
                 "priority":"HIGH",
                 %s
                 "applicationId":%d,
                 "moduleName":"Salary"}""".formatted(impactFragment, applicationId);
    }

    private String withImpact(String raw) {
        return "\"businessImpact\":\"" + raw + "\",";
    }

    // ---------------------------------------------------------------- what is accepted

    @ParameterizedTest
    @EnumSource(BusinessImpact.class)
    @DisplayName("every constant is accepted and comes back as itself")
    void everyConstantRoundTrips(BusinessImpact impact) throws Exception {
        User employee = givenUser("EMPLOYEE");
        Application application = givenApplication();

        // Every constant, from the enum itself rather than a list repeated here - so adding a fifth
        // one cannot be done without this test covering it.
        mockMvc.perform(authenticated(post(TICKETS), tokenFor(employee))
                        .content(body(application.getId(), withImpact(impact.name()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessImpact").value(impact.name()));
    }

    /**
     * The read path as well as the write path. The list and the detail screen are served by a
     * projection query rather than by the entity, so the column is mapped twice in the application
     * and only one of those is exercised by a create.
     */
    @Test
    @DisplayName("it survives the projection the list and detail endpoints read through")
    void readBackThroughTheProjection() throws Exception {
        User employee = givenUser("EMPLOYEE");
        Application application = givenApplication();

        mockMvc.perform(authenticated(post(TICKETS), tokenFor(employee))
                        .content(body(application.getId(), withImpact("COMPANY_WIDE"))))
                .andExpect(status().isCreated());

        mockMvc.perform(authenticated(get(TICKETS), tokenFor(employee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].businessImpact").value("COMPANY_WIDE"));
    }

    // ---------------------------------------------------------------- it is still optional

    @Test
    @DisplayName("omitting it is fine, and it comes back null rather than as a guess")
    void omittingItIsFine() throws Exception {
        User employee = givenUser("EMPLOYEE");
        Application application = givenApplication();

        // Null, not SINGLE_USER. An unset impact means the requester did not say, which is a
        // different claim from the narrowest one - and defaulting it would put a number on the
        // triage board that nobody chose.
        mockMvc.perform(authenticated(post(TICKETS), tokenFor(employee))
                        .content(body(application.getId(), "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessImpact").doesNotExist());
    }

    @Test
    @DisplayName("sending it as null is the same as omitting it")
    void explicitNullIsFine() throws Exception {
        User employee = givenUser("EMPLOYEE");
        Application application = givenApplication();

        mockMvc.perform(authenticated(post(TICKETS), tokenFor(employee))
                        .content(body(application.getId(), "\"businessImpact\":null,")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessImpact").doesNotExist());
    }

    // ---------------------------------------------------------------- what is refused

    /**
     * The values that used to work. Every one of these would have been stored verbatim before
     * {@code V23}, and the first two are what was actually in the table.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "Organization-Level",
            "Organization Level",
            "Department",
            "department",
            "COMPANY WIDE",
            "Whole company, urgently",
            "",
            " "
    })
    @DisplayName("free text is now refused rather than stored")
    void freeTextIsRefused(String raw) throws Exception {
        User employee = givenUser("EMPLOYEE");
        Application application = givenApplication();

        // Including the lower-case and space-separated spellings of real constants: Jackson matches
        // enum names exactly, and a client that guesses the casing should be told so rather than have
        // its value dropped. And including the empty string, which is not "no answer" - it is an
        // answer that is not one of the four.
        mockMvc.perform(authenticated(post(TICKETS), tokenFor(employee))
                        .content(body(application.getId(), withImpact(raw))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed request"));
    }
}
