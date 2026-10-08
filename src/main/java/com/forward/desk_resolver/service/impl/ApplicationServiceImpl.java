package com.forward.desk_resolver.service.impl;

import com.forward.desk_resolver.common.exception.DuplicateResourceException;
import com.forward.desk_resolver.common.exception.ResourceNotFoundException;
import com.forward.desk_resolver.dto.request.CreateApplicationRequest;
import com.forward.desk_resolver.dto.response.ApplicationResponse;
import com.forward.desk_resolver.entity.Application;
import com.forward.desk_resolver.repository.ApplicationRepository;
import com.forward.desk_resolver.service.ApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApplicationServiceImpl implements ApplicationService {

    private final ApplicationRepository applicationRepository;

    @Override
    @Transactional
    public ApplicationResponse createApplication(CreateApplicationRequest request) {

        if (applicationRepository.existsByAppNameAndModuleName(
                request.getAppName(), request.getModuleName())) {
            throw new DuplicateResourceException(
                    "An application module already exists with this name: "
                            + request.getAppName() + " / " + request.getModuleName());
        }

        Application app = new Application();
        app.setAppName(request.getAppName());
        app.setModuleName(request.getModuleName());
        app.setActive(request.getActive());

        Application saved = applicationRepository.save(app);

        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ApplicationResponse getApplicationById(Long id) {

        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Application", id));

        return mapToResponse(app);
    }

    /**
     * One page of applications.
     *
     * <p>No projection here, deliberately: {@code Application} has no associations, and its four columns
     * are exactly the four the response carries, so loading the entity already reads nothing spare. A
     * projection would add a type for no measurable benefit. Contrast {@code UserRow}, which exists
     * specifically so a listing never reads password hashes.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ApplicationResponse> searchApplications(Pageable pageable) {
        return applicationRepository.findAll(pageable).map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApplicationResponse> searchActiveApplications(Pageable pageable) {
        return applicationRepository.findByActiveTrue(pageable).map(this::mapToResponse);
    }

    @Override
    @Transactional
    public ApplicationResponse updateApplication(Long id, CreateApplicationRequest request) {

        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Application", id));

        if (applicationRepository.existsByAppNameAndModuleNameAndIdNot(
                request.getAppName(), request.getModuleName(), id)) {
            throw new DuplicateResourceException(
                    "Another application module already exists with this name: "
                            + request.getAppName() + " / " + request.getModuleName());
        }

        app.setAppName(request.getAppName());
        app.setModuleName(request.getModuleName());
        app.setActive(request.getActive());

        // No save() call: app is a managed entity inside this transaction, so Hibernate flushes the
        // change on commit. save() on a managed entity is a no-op merge that only looks like it is
        // doing the work.
        return mapToResponse(app);
    }

    @Override
    @Transactional
    public void deactivateApplication(Long id) {

        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Application", id));

        app.setActive(false);
    }

    private ApplicationResponse mapToResponse(Application app) {

        return ApplicationResponse.builder()
                .id(app.getId())
                .appName(app.getAppName())
                .moduleName(app.getModuleName())
                .active(app.isActive())
                .build();
    }
}
