package com.dinesh.rag.agent.service.auth;

import com.dinesh.rag.agent.domain.user.User;
import com.dinesh.rag.agent.domain.user.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Loads users by email for username/password login and by id from the JWT filter.
 *
 * <p>We deliberately expose the application's domain {@link User} as the
 * principal (wrapped in {@link AppUserDetails}) so controllers can do
 * {@code (AppUserDetails) auth.getPrincipal()} and reach the id/email
 * directly — no extra DB hit per request.</p>
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    public UserDetailsServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("No user with email: " + email));
        return new AppUserDetails(user);
    }

    public UserDetails loadUserById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UsernameNotFoundException("No user with id: " + id));
        return new AppUserDetails(user);
    }

    /**
     * Wrapper around our domain {@link User} that satisfies Spring Security's
     * {@link UserDetails} contract.
     */
    public static final class AppUserDetails implements UserDetails {

        private final User user;

        public AppUserDetails(User user) {
            this.user = user;
        }

        public UUID getId() { return user.getId(); }
        public String getEmail() { return user.getEmail(); }
        public String getDisplayName() { return user.getDisplayName(); }
        public User getUser() { return user; }

        @Override public String getUsername()                  { return user.getEmail(); }
        @Override public String getPassword()                  { return user.getPasswordHash(); }
        @Override public List<SimpleGrantedAuthority> getAuthorities() {
            return List.of(new SimpleGrantedAuthority("ROLE_USER"));
        }
        @Override public boolean isAccountNonExpired()         { return true; }
        @Override public boolean isAccountNonLocked()          { return true; }
        @Override public boolean isCredentialsNonExpired()     { return true; }
        @Override public boolean isEnabled()                   { return true; }
    }
}
