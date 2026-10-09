package com.forward.desk_resolver.controller;

import com.forward.desk_resolver.dto.response.RoleResponse;
import com.forward.desk_resolver.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The role catalogue.
 *
 * <p>Read-only. Creating, renaming and deactivating roles is deliberately not exposed: the seven
 * seeded roles cover what this application does, and an endpoint for defining new ones would be an
 * endpoint for defining roles nothing has decided the meaning of. The schema supports them - that was
 * the point of moving roles out of an enum - so when a real requirement for an eighth role appears it
 * is a migration, or later an endpoint, and not a redeploy of the authorization model.
 *
 * <p>Guarded by {@code USER_MANAGE} rather than a permission of its own, because this list exists for
 * exactly one purpose: so that whoever assigns roles can see what they are choosing from and what each
 * would confer. A separate {@code ROLE_READ} would be a permission granted to precisely the same
 * people, and an unused permission is an untested one.
 */
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @Operation(summary = "List every role and the permissions it grants",
            description = "Unpaged: this is a catalogue of seven rows, not a growing collection. "
                    + "Ordered by code so two reads agree.")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @GetMapping
    public List<RoleResponse> getAllRoles() {
        return roleService.getAllRoles();
    }
}
