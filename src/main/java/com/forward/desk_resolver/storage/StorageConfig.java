package com.forward.desk_resolver.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AttachmentStorageProperties}.
 *
 * <p>Follows {@code SecurityConfig}, which does the same for {@code SecurityProperties}: the binding
 * is declared by a configuration class rather than by annotating the record as a component, so the
 * record stays a plain value type with no Spring stereotype on it.
 */
@Configuration
@EnableConfigurationProperties(AttachmentStorageProperties.class)
public class StorageConfig {
}
