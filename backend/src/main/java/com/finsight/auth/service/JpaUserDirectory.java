package com.finsight.auth.service;

import com.finsight.auth.repository.UserRepository;
import com.finsight.shared.exception.BadRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** {@link UserDirectory} backed by {@link UserRepository}. Internal to the auth module. */
@Service
public class JpaUserDirectory implements UserDirectory {

    private final UserRepository userRepository;

    public JpaUserDirectory(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<Long> userIdsAfter(long lastId, int limit) {
        if (lastId < 0) {
            throw new BadRequestException("lastId must not be negative");
        }
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new BadRequestException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        return userRepository.findIdsAfter(lastId, PageRequest.of(0, limit));
    }
}
