package com.forward.desk_resolver.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "applications",
        // Documents uq_applications_app_name_module_name from V15. Hibernate's `validate` does not check
        // unique constraints, so this is for the reader rather than for startup.
        uniqueConstraints = @UniqueConstraint(
                name = "uq_applications_app_name_module_name",
                columnNames = {"app_name", "module_name"}))
@Getter
@Setter
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_name", nullable = false, length = 150)
    private String appName;

    // NOT NULL since V15, matching CreateApplicationRequest's @NotBlank. An application row is an
    // application-and-module pair - HRMS/attendance, HRMS/leaves - so a row without a module is not a
    // catalogue entry at all. (app_name, module_name) is unique.
    @Column(name = "module_name", nullable = false, length = 100)
    private String moduleName;

    @Column(name = "active")
    private boolean active = true;
}