package com.dinesh.rag.agent.service.auth;

import com.dinesh.rag.agent.config.DataInitializer;
import com.dinesh.rag.agent.domain.user.User;
import com.dinesh.rag.agent.domain.user.UserRepository;
import com.dinesh.rag.agent.exception.InvalidCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Resolves the request-scoped current user.
 *
 * <p>Auth is currently sidelined (see {@link com.dinesh.rag.agent.config.SecurityConfig}).
 * If a request happens to carry a valid JWT, the JWT filter populates the
 * security context and we use that user. Otherwise we fall back to the
 * seeded {@link DataInitializer#ANONYMOUS_EMAIL anonymous user} so that
 * {@code files.created_by} is always non-null.</p>
 *
 * <p>When auth is wired back on, the anonymous fallback path becomes
 * unreachable (Spring Security will short-circuit with 401 before any
 * controller method runs) and can be removed.</p>
 */
@Service
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** UUID of the current user (real JWT user, or anonymous fallback). */
    public UUID requireUserId() {
        return requireUser().getId();
    }

    /** Reference to the current {@link User} entity (read from DB once). */
    public User requireUser() {
        UserDetailsServiceImpl.AppUserDetails details = currentUserDetailsOrNull();
        if (details != null) {
            return userRepository.findById(details.getId())
                    .orElseThrow(() -> new InvalidCredentialsException("Authenticated user no longer exists."));
        }
        return anonymousUser();
    }

    private UserDetailsServiceImpl.AppUserDetails currentUserDetailsOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        if (auth.getPrincipal() instanceof UserDetailsServiceImpl.AppUserDetails details) {
            return details;
        }
        return null;
    }

    private User anonymousUser() {
        return userRepository.findByEmailIgnoreCase(DataInitializer.ANONYMOUS_EMAIL)
                .orElseThrow(() -> new IllegalStateException(
                        "Anonymous user has not been seeded. Check DataInitializer."));
    }
}
