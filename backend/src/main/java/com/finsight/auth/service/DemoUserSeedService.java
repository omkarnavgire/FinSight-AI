package com.finsight.auth.service;

import com.finsight.auth.domain.User;
import com.finsight.auth.repository.UserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the synthetic demo user for {@code DataSeeder} (T-61). Exists only in the {@code dev} profile.
 *
 * <p>The password is supplied by the caller from {@code FINSIGHT_DEMO_PASSWORD}; nothing here holds
 * a credential. It is stored only as a BCrypt hash (NFR-01-3) and never logged.
 */
@Service
@Profile("dev")
public class DemoUserSeedService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DemoUserSeedService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates the demo user in the caller's transaction.
     *
     * @return the new user's ID
     */
    @Transactional
    public Long createDemoUser(String email, String rawPassword, String fullName) {
        return userRepository.save(User.create(email, passwordEncoder.encode(rawPassword), fullName)).getId();
    }
}
