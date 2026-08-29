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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.DefaultBlueprint;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.DefaultBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration coverage for the Postgres-specific default-blueprint grant queries against the real
 * test container (REQ-INV-016): the V157 schema validates against {@link DefaultBlueprint}, and the
 * native {@code INSERT … SELECT … ON CONFLICT} bulk grants behave as written — they materialise a
 * row per (user, default), are idempotent, and the all-users grant skips soft-deleted users. Random
 * ids / keys isolate each test from the shared container.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefaultBlueprintProvisioningIntegrationTest {

  @Autowired private UserRepository userRepository;
  @Autowired private DefaultBlueprintRepository defaultBlueprintRepository;
  @Autowired private PersonalBlueprintRepository personalBlueprintRepository;
  @Autowired private DefaultBlueprintProvisioningService provisioningService;
  @Autowired private TransactionTemplate transactionTemplate;

  private UUID createUser(boolean inKeycloak) {
    UUID id = UUID.randomUUID();
    transactionTemplate.executeWithoutResult(
        status -> {
          User u = new User();
          u.setId(id);
          u.setUsername("user-" + id);
          u.setInKeycloak(inKeycloak);
          userRepository.save(u);
        });
    return id;
  }

  private String createDefault() {
    String key = "test-default-" + UUID.randomUUID();
    transactionTemplate.executeWithoutResult(
        status -> {
          DefaultBlueprint d = new DefaultBlueprint();
          d.setProductKey(key);
          d.setProductName("Test Default " + key);
          d.setCreatedBy("system");
          defaultBlueprintRepository.save(d);
        });
    return key;
  }

  @Test
  void grantDefaultsToUser_materialisesEveryDefault_andIsIdempotent() {
    UUID user = createUser(true);
    String keyA = createDefault();
    String keyB = createDefault();

    int firstRun = provisioningService.grantDefaultsToUser(user);
    assertThat(firstRun).isGreaterThanOrEqualTo(2);
    assertThat(personalBlueprintRepository.existsByOwnerSubAndProductKey(user, keyA)).isTrue();
    assertThat(personalBlueprintRepository.existsByOwnerSubAndProductKey(user, keyB)).isTrue();

    // Re-running grants nothing new for the keys already owned (ON CONFLICT DO NOTHING).
    int beforeKeys =
        personalBlueprintRepository
            .findAllByOwnerSubAndProductKeyIn(user, java.util.List.of(keyA, keyB))
            .size();
    provisioningService.grantDefaultsToUser(user);
    int afterKeys =
        personalBlueprintRepository
            .findAllByOwnerSubAndProductKeyIn(user, java.util.List.of(keyA, keyB))
            .size();
    assertThat(afterKeys).isEqualTo(beforeKeys).isEqualTo(2);
  }

  @Test
  void grantDefaultsToAllUsers_grantsToActiveUsersAndSkipsSoftDeletedOnes() {
    UUID active = createUser(true);
    UUID inactive = createUser(false);
    String key = createDefault();

    provisioningService.grantDefaultsToAllUsers();

    assertThat(personalBlueprintRepository.existsByOwnerSubAndProductKey(active, key)).isTrue();
    assertThat(personalBlueprintRepository.existsByOwnerSubAndProductKey(inactive, key)).isFalse();
  }
}
