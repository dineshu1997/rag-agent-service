package com.dinesh.rag.agent.config;

import com.dinesh.rag.agent.domain.user.User;
import com.dinesh.rag.agent.domain.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Seeds a single {@code anonymous@local} user on application start.
 *
 * <p>Auth/register/login are deferred until the file + chat APIs are stable.
 * In the meantime every {@code files.created_by} foreign key still has to
 * point at a real {@link User} row, so {@link com.dinesh.rag.agent.service.auth.CurrentUserService}
 * falls back to this seeded user whenever a request comes in without a JWT.</p>
 *
 * <p>Idempotent: the runner short-circuits if the email already exists, so
 * repeated restarts (and Hibernate {@code ddl-auto: create} rebuilding the
 * schema) keep working.</p>
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    public static final String ANONYMOUS_EMAIL = "anonymous@local";
    public static final String ANONYMOUS_DISPLAY_NAME = "Anonymous";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmailIgnoreCase(ANONYMOUS_EMAIL)) {
            return;
        }

        // Random password — the anonymous user can never log in by design.
        // We only need a non-null hash to satisfy the schema constraint.
        String randomPassword = UUID.randomUUID().toString();

        User anonymous = User.builder()
                .email(ANONYMOUS_EMAIL)
                .passwordHash(passwordEncoder.encode(randomPassword))
                .displayName(ANONYMOUS_DISPLAY_NAME)
                .build();

        userRepository.save(anonymous);
        log.info("Seeded anonymous user: {}", ANONYMOUS_EMAIL);
    }
}
