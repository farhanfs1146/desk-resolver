package com.forward.desk_resolver.dto.request;

import com.forward.desk_resolver.enums.BusinessImpact;
import com.forward.desk_resolver.enums.IssueType;
import com.forward.desk_resolver.enums.Priority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;

@Data
public class CreateTicketRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 100, message = "Title must be at most 100 characters")
    @Schema(description = "Shorter & Descriptive title of which problem you're facing")
    private String title;

    @NotBlank(message = "Description is required")
    @Schema(description = "Descriptive of which problem you're facing in explained-form")
    private String description;

    @NotNull(message = "Issue type is required")
    @Schema(description = "Select in the drop-down whether issue is new, bug, improvement etc.")
    private IssueType issueType;

    @NotNull(message = "Priority is required")
    @Schema(description = "Select in the drop-down whether ticket priority is low, high or critical very urgent etc.")
    private Priority priority;

    /**
     * Optional, and no longer free text - see {@link BusinessImpact}.
     *
     * <p>No {@code @NotNull}: it stays optional, because a requester who does not know how far a
     * problem reaches should not be made to guess. An unset value means they did not say, which is a
     * different thing from any of the four constants.
     *
     * <p>An unknown constant is a 400 naming this field, produced by Jackson before validation runs
     * - which is why there is no {@code @Size} anymore, and nothing here to replace it.
     */
    @Schema(description = "How far the problem reaches: SINGLE_USER, TEAM, DEPARTMENT, "
            + "MULTIPLE_DEPARTMENTS or COMPANY_WIDE. Optional. How soon it needs fixing is "
            + "'priority', not this.")
    private BusinessImpact businessImpact;

    /**
     * When the requester needs this resolved by, as an absolute instant.
     *
     * <p><strong>This is a breaking input change, made on purpose.</strong> The field was
     * {@code LocalDateTime}, so {@code "2026-10-20T17:00:00"} was accepted and silently interpreted in
     * whatever zone the server's JVM happened to run in - a deadline whose meaning depended on where
     * the application was deployed rather than on what the requester asked for. It is now an
     * {@code Instant}, so a value must carry an offset ({@code "2026-10-20T17:00:00+05:00"}) or be
     * UTC ({@code "...Z"}), and an offset-less datetime is a 400 naming the field rather than a guess.
     *
     * <p>Refusing it is the point. There is no zone this API could substitute that would be right more
     * often than it was wrong, and the client always knows the one the person typing meant.
     */
    @Schema(description = "When this ticket should be resolved by, as an ISO-8601 instant. Must carry "
            + "an offset or Z - an offset-less datetime is refused rather than assigned a zone.",
            example = "2026-10-20T17:00:00+05:00")
    private Instant expectedBy;

    @NotNull(message = "Application id is required")
    @Schema(description = "Applicant have to mention clearly for which application they're creating/rasing ticket")
    private Long applicationId;

    @NotBlank(message = "Module name is required")
    @Size(max = 100, message = "Module name must be at most 100 characters")
    @Schema(description = "Which module of selected application facing issue.")
    private String moduleName;
}
