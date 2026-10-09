package com.forward.desk_resolver.service;

import com.forward.desk_resolver.dto.response.RoleResponse;

import java.util.List;

public interface RoleService {

    /**
     * Every role, with the permissions it grants.
     *
     * <p>Unpaged, and deliberately so - the only collection endpoint in this application that is. The
     * ticket and user listings are paged because they grow with use; this one has seven rows and grows
     * when somebody deliberately defines a role. Paging it would add parameters a client has to handle
     * for a list that fits in one screen, and the sort order that matters is already fixed (by code) so
     * that two reads agree.
     *
     * <p>If this table ever reaches a size where that reasoning stops holding, it will have stopped
     * being a role catalogue and become something else worth rethinking.
     */
    List<RoleResponse> getAllRoles();
}
