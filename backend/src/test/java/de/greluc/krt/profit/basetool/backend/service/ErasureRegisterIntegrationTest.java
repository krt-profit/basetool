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

import de.greluc.krt.profit.basetool.backend.identity.api.ErasedAccountRegister;
import de.greluc.krt.profit.basetool.backend.identity.api.KeycloakService;
import de.greluc.krt.profit.basetool.backend.identity.api.KeycloakUserDto;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReconciliationService;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserDeletionService;
import de.greluc.krt.profit.basetool.backend.privacy.internal.ErasureReapplication;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** The erasure register against Postgres (REQ-DATA-048). */
@SpringBootTest
@Transactional
class ErasureRegisterIntegrationTest {

  @Autowired private UserDeletionService userDeletionService;
  @Autowired private ErasureReapplication reapplication;
  @Autowired private ErasedAccountRegister register;
  @Autowired private UserReconciliationService reconciliation;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  @MockitoBean private KeycloakService keycloakService;

  @Test
  void aDeletedAccountIsRegistered_andARestoredRowIsDeletedAgain() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    admin(tag);
    UUID leaver = member("leaver-" + tag, UUID.randomUUID()).getId();

    userDeletionService.deleteUser(leaver);
    entityManager.flush();
    entityManager.clear();

    assertThat(register.isErased(leaver)).isTrue();
    assertThat(userRepository.existsById(leaver)).isFalse();
    long pendingBefore = register.pendingReapplications();

    member("leaver-" + tag, leaver);
    entityManager.flush();
    entityManager.clear();
    assertThat(register.pendingReapplications()).isEqualTo(pendingBefore + 1);

    assertThat(reapplication.deleteAccountRow(leaver)).isTrue();
    entityManager.flush();
    entityManager.clear();

    assertThat(userRepository.existsById(leaver)).isFalse();
    assertThat(register.pendingReapplications()).isEqualTo(pendingBefore);
    assertThat(reapplication.deleteAccountRow(leaver)).isFalse();
  }

  @Test
  void theRosterSyncDoesNotRecreateADeletedAccount() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    admin(tag);
    UUID leaver = member("leaver-" + tag, UUID.randomUUID()).getId();
    userDeletionService.deleteUser(leaver);
    entityManager.flush();
    entityManager.clear();

    reconciliation.syncUser(
        new KeycloakUserDto(leaver, "leaver-" + tag, "leaver@example.com", true, null));
    entityManager.flush();

    assertThat(userRepository.existsById(leaver)).isFalse();
  }

  @Test
  void entriesOlderThanTheWindowExpire() {
    Instant now = Instant.now();
    UUID old = registered(now.minus(400, ChronoUnit.DAYS));
    UUID fresh = registered(now.minus(10, ChronoUnit.DAYS));

    assertThat(register.expireBefore(now.minus(365, ChronoUnit.DAYS))).isGreaterThanOrEqualTo(1);

    assertThat(register.isErased(old)).isFalse();
    assertThat(register.isErased(fresh)).isTrue();
  }

  private UUID registered(Instant erasedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO erased_account (id, erased_at) VALUES (?, ?)", id, Timestamp.from(erasedAt));
    return id;
  }

  private void admin(String tag) {
    Role adminRole =
        roleRepository
            .findByNameIgnoreCase("ADMIN")
            .orElseGet(() -> {
              Role fresh = new Role();
              fresh.setName("ADMIN");
              fresh.setCode("ADMIN");
              return roleRepository.save(fresh);
            });
    User admin = new User();
    admin.setId(UUID.randomUUID());
    admin.setUsername("register-admin-" + tag);
    admin.setRank(1);
    admin.setInKeycloak(true);
    admin.getRoles().add(adminRole);
    userRepository.save(admin);
  }

  private User member(String username, UUID id) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    user.setRank(1);
    user.setInKeycloak(false);
    return userRepository.saveAndFlush(user);
  }
}
