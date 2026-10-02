/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend;

import static org.junit.jupiter.api.Assertions.assertThrows;

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.validation.ConstraintViolationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class UserValidationTest {

  @Autowired private UserRepository userRepository;

  @MockitoBean private JwtDecoder jwtDecoder;

  @Test
  void testRankValidation_Valid() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("rankTesterValid");
    user.setRank(1);
    userRepository.saveAndFlush(user);

    User user2 = new User();
    user2.setId(UUID.randomUUID());
    user2.setUsername("rankTesterValid2");
    user2.setRank(20);
    userRepository.saveAndFlush(user2);
  }

  @Test
  void testRankValidation_TooHigh() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("rankTesterHigh");
    user.setRank(21);

    assertThrows(ConstraintViolationException.class, () -> userRepository.saveAndFlush(user));
  }

  @Test
  void testRankValidation_TooLow() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("rankTesterLow");
    user.setRank(0);

    assertThrows(ConstraintViolationException.class, () -> userRepository.saveAndFlush(user));
  }
}
