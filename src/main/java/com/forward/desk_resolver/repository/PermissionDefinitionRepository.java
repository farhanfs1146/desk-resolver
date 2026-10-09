package com.forward.desk_resolver.repository;

import com.forward.desk_resolver.entity.PermissionDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Read access to the seeded permission catalogue.
 *
 * <p>There is no write method here on purpose. The catalogue is seeded by migration from the
 * {@code Permission} enum and verified at startup; an application that could insert into it would be
 * able to invent a permission no code ever checks, or shadow one that it does.
 */
@Repository
public interface PermissionDefinitionRepository extends JpaRepository<PermissionDefinition, Long> {

    @Query("select p.code from PermissionDefinition p")
    List<String> findAllCodes();
}
