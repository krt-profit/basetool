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
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEvent;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.AccountErasure;
import de.greluc.krt.profit.basetool.backend.identity.api.KeycloakPresence;
import de.greluc.krt.profit.basetool.backend.identity.api.KeycloakService;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.UserDeletionService.KeycloakPresenceCheck;
import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins the identity-owned {@link AccountErasure} seam in front of {@link UserDeletionService}: the
 * presence mode maps one-to-one for every constant, and the deletion the registration flow asks for
 * through the seam leaves the same rows and audit entries and fails with the same exception type
 * and message as the orchestrator's own method (real schema, one rolled-back transaction).
 */
@SpringBootTest
@Transactional
class UserAccountErasureSeamTest {

  private static final Pattern UUID_TEXT =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  @Autowired private AccountErasure accountErasure;
  @Autowired private UserDeletionService userDeletionService;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private EntityManager entityManager;

  @MockitoBean private KeycloakService keycloakService;

  @Test
  @DisplayName("every presence mode maps onto the orchestrator's own mode of the same name")
  void everyConstantMapsOneToOne() {
    for (KeycloakPresence presence : KeycloakPresence.values()) {
      assertThat(UserDeletionService.toCheck(presence).name()).isEqualTo(presence.name());
    }
    assertThat(KeycloakPresence.values()).hasSameSizeAs(KeycloakPresenceCheck.values());
  }

  @Test
  @DisplayName("the seam is the orchestrator itself, not a second bean")
  void theSeamIsTheOrchestrator() {
    assertThat(accountErasure).isSameAs(userDeletionService);
  }

  @Test
  @DisplayName("an unknown user fails alike under both modes")
  void unknownUser() {
    for (KeycloakPresence presence : KeycloakPresence.values()) {
      UUID ghost = UUID.randomUUID();
      assertThat(failureOf(() -> accountErasure.deleteAccount(ghost, presence)))
          .isEqualTo(
              failureOf(
                  () ->
                      userDeletionService.deleteUser(
                          ghost, UserDeletionService.toCheck(presence))));
    }
  }

  @Test
  @DisplayName("a user still flagged as in Keycloak is refused alike under both modes")
  void stillInKeycloak() {
    admin();
    for (KeycloakPresence presence : KeycloakPresence.values()) {
      User viaSeam = member(true);
      User direct = member(true);
      String seamFailure = failureOf(() -> accountErasure.deleteAccount(viaSeam.getId(), presence));
      String directFailure =
          failureOf(
              () ->
                  userDeletionService.deleteUser(
                      direct.getId(), UserDeletionService.toCheck(presence)));
      assertThat(seamFailure).startsWith("BadRequestException:").isEqualTo(directFailure);
      assertThat(userRepository.existsById(viaSeam.getId())).isTrue();
    }
  }

  @Test
  @DisplayName("an ex-member is deleted alike when the probe is waived")
  void waivedProbeDeletes() {
    admin();
    assertSameDeletion(KeycloakPresence.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);
  }

  @Test
  @DisplayName("the enforced probe refuses a live Keycloak user and passes a gone one, alike")
  void enforcedProbe() {
    admin();
    User liveViaSeam = member(false);
    User liveDirect = member(false);
    when(keycloakService.userExists(liveViaSeam.getId())).thenReturn(true);
    when(keycloakService.userExists(liveDirect.getId())).thenReturn(true);
    String seamFailure =
        failureOf(
            () -> accountErasure.deleteAccount(liveViaSeam.getId(), KeycloakPresence.ENFORCED));
    String directFailure =
        failureOf(
            () ->
                userDeletionService.deleteUser(liveDirect.getId(), KeycloakPresenceCheck.ENFORCED));
    assertThat(seamFailure).startsWith("BadRequestException:").isEqualTo(directFailure);

    assertSameDeletion(KeycloakPresence.ENFORCED);
  }

  private void assertSameDeletion(KeycloakPresence presence) {
    User viaSeam = member(false);
    User direct = member(false);
    int auditBefore = auditEventRepository.findAll().size();

    accountErasure.deleteAccount(viaSeam.getId(), presence);
    List<String> seamAudit = auditSince(auditBefore, viaSeam.getId());
    int auditMid = auditEventRepository.findAll().size();
    userDeletionService.deleteUser(direct.getId(), UserDeletionService.toCheck(presence));
    List<String> directAudit = auditSince(auditMid, direct.getId());
    entityManager.flush();
    entityManager.clear();

    assertThat(userRepository.existsById(viaSeam.getId())).isFalse();
    assertThat(userRepository.existsById(direct.getId())).isFalse();
    assertThat(seamAudit).isNotEmpty().isEqualTo(directAudit);
  }

  private List<String> auditSince(int skip, UUID userId) {
    entityManager.flush();
    return auditEventRepository.findAll().stream()
        .sorted(Comparator.comparing(AuditEvent::getOccurredAt))
        .skip(skip)
        .map(
            e ->
                e.getDomain()
                    + "|"
                    + e.getEventType()
                    + "|"
                    + mask(String.valueOf(e.getSubjectLabel()), userId)
                    + "|"
                    + mask(String.valueOf(e.getDetails()), userId))
        .toList();
  }

  private static String mask(String text, UUID userId) {
    return UUID_TEXT.matcher(text.replace(userId.toString(), "<user>")).replaceAll("<id>");
  }

  private String failureOf(Runnable action) {
    try {
      action.run();
      return null;
    } catch (RuntimeException e) {
      return e.getClass().getSimpleName()
          + ": "
          + UUID_TEXT.matcher(String.valueOf(e.getMessage())).replaceAll("<id>");
    }
  }

  private User admin() {
    Role adminRole =
        roleRepository
            .findByNameIgnoreCase("ADMIN")
            .orElseGet(
                () -> {
                  Role fresh = new Role();
                  fresh.setName("ADMIN");
                  fresh.setCode("ADMIN");
                  return roleRepository.save(fresh);
                });
    User admin = new User();
    admin.setId(UUID.randomUUID());
    admin.setUsername("erasure-admin-" + admin.getId());
    admin.setRank(1);
    admin.setInKeycloak(true);
    admin.getRoles().add(adminRole);
    return userRepository.saveAndFlush(admin);
  }

  private User member(boolean inKeycloak) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("erasure-member-" + user.getId());
    user.setRank(1);
    user.setInKeycloak(inKeycloak);
    return userRepository.saveAndFlush(user);
  }
}
