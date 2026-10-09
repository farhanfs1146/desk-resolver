package com.forward.desk_resolver.security;

import com.forward.desk_resolver.repository.PermissionDefinitionRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Refuses to start if {@code auth.permissions} and the {@link Permission} enum have drifted apart.
 *
 * <p><strong>This is the guard that makes the split between code and data safe.</strong> The design
 * deliberately keeps the permission vocabulary in the enum - because
 * {@code @PreAuthorize("hasAuthority('TICKET_ASSIGN'))"} names it as a literal and code is what
 * enforces it - while putting every <em>assignment</em> in the database. The risk that split creates is
 * the two halves disagreeing, and both directions of disagreement are quiet:
 *
 * <ul>
 *   <li><strong>A row with no enum constant.</strong> A permission granted to roles that no endpoint
 *       ever checks. It grants nothing, so nothing breaks and nobody notices - until someone concludes
 *       the permission works and builds on it.
 *   <li><strong>An enum constant with no row.</strong> Much worse. No role can be granted it, so the
 *       endpoints guarding that permission are unreachable for every user including administrators.
 *       A typo in a seed migration is enough.
 * </ul>
 *
 * <p>Neither produces a test failure anywhere, which is exactly why this check exists and why it fails
 * startup rather than logging a warning. A deployment that cannot authorize correctly should not
 * accept traffic; it should refuse to boot with the two lists in the message.
 *
 * <p>It runs as a {@code @PostConstruct} rather than an {@code ApplicationRunner}, because a runner
 * executes after the web server has started accepting requests - too late to be a refusal to boot.
 * Flyway has already migrated by then: Spring Boot orders the migration ahead of the
 * {@code EntityManagerFactory}, and this bean depends on a repository, so it is constructed after
 * both. The read needs no {@code @Transactional} of its own; the repository call supplies one, and
 * {@code @Transactional} on a {@code @PostConstruct} method would silently do nothing anyway, the
 * proxy not being in place yet.
 */
@Component
public class PermissionCatalogueValidator {

    private static final Logger log = LoggerFactory.getLogger(PermissionCatalogueValidator.class);

    private final PermissionDefinitionRepository permissionDefinitionRepository;

    public PermissionCatalogueValidator(PermissionDefinitionRepository permissionDefinitionRepository) {
        this.permissionDefinitionRepository = permissionDefinitionRepository;
    }

    @PostConstruct
    public void verifyCatalogueMatchesEnum() {
        Set<String> expected = Permission.names();
        List<String> storedCodes = permissionDefinitionRepository.findAllCodes();
        Set<String> stored = new TreeSet<>(storedCodes);

        if (stored.size() != storedCodes.size()) {
            // The UNIQUE constraint on code makes this impossible, so if it ever happens the schema is
            // not the one this code was written against.
            throw new IllegalStateException(
                    "auth.permissions contains duplicate codes; the unique constraint from V17 is missing");
        }

        Set<String> missingFromTable = new TreeSet<>(expected);
        missingFromTable.removeAll(stored);

        Set<String> unknownToCode = new TreeSet<>(stored);
        unknownToCode.removeAll(expected);

        if (!missingFromTable.isEmpty() || !unknownToCode.isEmpty()) {
            throw new IllegalStateException(
                    "The permission catalogue does not match the Permission enum. "
                            + "Declared in code but absent from auth.permissions: "
                            + describe(missingFromTable)
                            + ". Present in auth.permissions but unknown to code: "
                            + describe(unknownToCode)
                            + ". Add a migration that seeds the missing rows, or remove the stale ones; "
                            + "the enum is the source of truth for which permissions exist.");
        }

        log.debug("Permission catalogue verified: {} permissions in both the enum and auth.permissions",
                expected.size());
    }

    private static String describe(Set<String> codes) {
        return codes.isEmpty() ? "none" : codes.stream().collect(Collectors.joining(", ", "[", "]"));
    }
}
