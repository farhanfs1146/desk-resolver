package com.forward.desk_resolver.dto.request;

import com.forward.desk_resolver.enums.IssueType;
import com.forward.desk_resolver.enums.Priority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

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

    @Size(max = 50, message = "Business impact must be at most 50 characters")
    @Schema(description = "select the impact of this ticket whether it is department-level, user-level")
    private String businessImpact;

    @Schema(description = "select the date of when this ticket should resolve.")
    private LocalDateTime expectedBy;

    @NotNull(message = "Application id is required")
    @Schema(description = "Applicant have to mention clearly for which application they're creating/rasing ticket")
    private Long applicationId;

    @NotBlank(message = "Module name is required")
    @Size(max = 100, message = "Module name must be at most 100 characters")
    @Schema(description = "Which module of selected application facing issue.")
    private String moduleName;
}
