package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Category;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CategoryRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    void rejectsCaseInsensitiveDuplicateNameForSameUser() {
        User user = new User();
        user.setEmail("case-insensitive-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Case Insensitive Test");
        user = userRepository.save(user);
        UUID userId = user.getId();

        Category sip = new Category();
        sip.setUserId(userId);
        sip.setName("SIP");
        categoryRepository.saveAndFlush(sip);

        Category dup = new Category();
        dup.setUserId(userId);
        dup.setName("sip");

        assertThatThrownBy(() -> categoryRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
