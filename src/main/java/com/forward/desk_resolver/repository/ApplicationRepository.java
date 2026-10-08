package com.forward.desk_resolver.repository;

import com.forward.desk_resolver.entity.Application;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    /**
     * Paged active-application lookup (Phase 6).
     *
     * <p>Spring Data derives the matching count query from the derived method name, so a page costs two
     * statements whatever the catalogue size. No projection is needed: Application has no associations and
     * every column it has appears in the response.
     */
    Page<Application> findByActiveTrue(Pageable pageable);

    /**
     * Catalogue uniqueness pre-check, backed by {@code uq_applications_app_name_module_name} (V15).
     *
     * <p>The constraint is what actually prevents a duplicate - two concurrent creates can both pass this
     * check. It exists so the common case answers a clear 409 naming the conflict, instead of the generic
     * "conflicts with existing data" that a {@code DataIntegrityViolationException} produces.
     */
    boolean existsByAppNameAndModuleName(String appName, String moduleName);

    /** As above, for an update: the row being edited is not a conflict with itself. */
    boolean existsByAppNameAndModuleNameAndIdNot(String appName, String moduleName, Long id);
}
