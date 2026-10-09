package com.forward.desk_resolver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is enabled for exactly one task: {@code SessionService.purgeExpiredSessions}.
 *
 * <p>{@code auth.sessions} gains a row per login and nothing else removes one, so without a periodic
 * purge it is an unbounded log pretending to be operational state. The interval comes from
 * {@code app.security.session.purge-interval} and the retention window from
 * {@code app.security.session.retention}.
 *
 * <p>The task runs independently on every instance, which is harmless because it is idempotent:
 * a deleted of rows that expired before a cutoff, so two instances racing simply means one of them
 * deletes nothing.
 */
@SpringBootApplication
@EnableScheduling
public class DeskResolverApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeskResolverApplication.class, args);
    }

}
