package com.forward.desk_resolver.service.impl;

import com.forward.desk_resolver.dto.response.RoleResponse;
import com.forward.desk_resolver.entity.PermissionDefinition;
import com.forward.desk_resolver.entity.Role;
import com.forward.desk_resolver.repository.RoleRepository;
import com.forward.desk_resolver.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;

    @Override
    @Transactional(readOnly = true)
    public List<RoleResponse> getAllRoles() {
        return roleRepository.findAllWithPermissions().stream()
                .map(RoleServiceImpl::mapToResponse)
                .toList();
    }

    /**
     * Permission codes come out sorted, not in whatever order the join returned them.
     *
     * <p>The set is a {@code LinkedHashSet} filled by a fetch join, so its order is the database's and
     * could change with a plan change. Sorting makes the response stable, which matters for a list a
     * human reads to compare two roles.
     */
    private static RoleResponse mapToResponse(Role role) {
        return RoleResponse.builder()
                .id(role.getId())
                .code(role.getCode())
                .name(role.getName())
                .description(role.getDescription())
                .active(role.getActive())
                .permissions(role.getPermissions().stream()
                        .map(PermissionDefinition::getCode)
                        .sorted()
                        .toList())
                .build();
    }
}
