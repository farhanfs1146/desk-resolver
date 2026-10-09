package com.forward.desk_resolver.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A role and what it grants.
 *
 * <p>Exists so that whoever assigns roles can see what they are choosing from, and what each one would
 * actually confer. Before this, the role-to-permission table was readable only in a Java source file
 * and in the README - which meant the authoritative answer and the documented answer could differ
 * without anyone noticing.
 */
@Data
@Builder
public class RoleResponse {

    private Long id;

    @Schema(description = "Stable identifier used when assigning the role", example = "IT_SUPPORT")
    private String code;

    @Schema(description = "Display name", example = "IT Support")
    private String name;

    private String description;

    @Schema(description = "A deactivated role grants nothing to the users who hold it")
    private Boolean active;

    @Schema(description = "Permission codes this role grants")
    private List<String> permissions;
}
