package com.forward.desk_resolver.dto.response;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A user record as the API returns it.
 *
 * <p>No {@code passwordHash} field, and never one: the projection a listing reads does not even select
 * the column.
 *
 * <p>{@code roles} replaces the former single {@code role} string. It is always present and may be
 * empty - an account with no roles authenticates and can change its own password, and can do nothing
 * else.
 */
@Data
@Builder
public class UserResponse {

    private Long id;
    private Long employeeCode;
    private String fullName;
    private String email;
    private Long departmentId;
    private Long designationId;

    @Schema(description = "Codes of every role this user holds; empty if none")
    private List<String> roles;

    private Boolean active;
}
