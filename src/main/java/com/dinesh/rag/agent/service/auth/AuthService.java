package com.dinesh.rag.agent.service.auth;

import com.dinesh.rag.agent.domain.user.User;
import com.dinesh.rag.agent.domain.user.UserRepository;
import com.dinesh.rag.agent.dto.auth.AuthResponse;
import com.dinesh.rag.agent.dto.auth.LoginRequest;
import com.dinesh.rag.agent.dto.auth.RegisterRequest;
import com.dinesh.rag.agent.exception.DuplicateResourceException;
import com.dinesh.rag.agent.exception.InvalidCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByEmailIgnoreCase(req.email())) {
            throw new DuplicateResourceException("A user with that email already exists.");
        }

        User user = User.builder()
                .email(req.email().toLowerCase())
                .passwordHash(passwordEncoder.encode(req.password()))
                .displayName(req.displayName())
                .build();
        userRepository.save(user);

        return issueAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByEmailIgnoreCase(req.email())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password."));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password.");
        }

        return issueAuthResponse(user);
    }

    private AuthResponse issueAuthResponse(User user) {
        JwtService.IssuedToken issued = jwtService.issue(user.getId(), user.getEmail());
        return new AuthResponse(
                issued.token(),
                issued.expiresAt(),
                new AuthResponse.UserSummary(user.getId(), user.getEmail(), user.getDisplayName())
        );
    }
}
