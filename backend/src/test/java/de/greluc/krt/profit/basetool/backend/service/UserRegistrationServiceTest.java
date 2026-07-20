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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.event.UserApprovalDecidedEvent;
import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.model.ApprovalDecision;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.UserApprovalEvent;
import de.greluc.krt.profit.basetool.backend.repository.UserApprovalEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Mockito unit tests for {@link UserRegistrationService} — the registration approval lifecycle
 * (approve / reject / decide, the pending-queue read), the admin-driven {@link
 * UserRegistrationService#linkRegistrationToExistingAccount} merge (REQ-SEC-026), plus the shared
 * {@link UserRegistrationService#stampNewPendingRegistration} fail-safe PENDING stamping, extracted
 * out of {@code UserService} (audit Thema&nbsp;7, #1252).
 */
@ExtendWith(MockitoExtension.class)
class UserRegistrationServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private UserApprovalEventRepository userApprovalEventRepository;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private KeycloakService keycloakService;
  @Mock private UserDeletionService userDeletionService;
  @Mock private ObjectProvider<UserRegistrationService> selfProvider;

  @InjectMocks private UserRegistrationService userRegistrationService;

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID ADMIN_ID = UUID.randomUUID();
  private static final UUID TARGET_ID = UUID.randomUUID();
  private static final String SNOWFLAKE = "123456789012345678";

  private static User pendingUser(long version) {
    User u = new User();
    u.setId(USER_ID);
    u.setApprovalStatus(ApprovalStatus.PENDING);
    u.setVersion(version);
    return u;
  }

  private static User activeTarget() {
    User u = new User();
    u.setId(TARGET_ID);
    u.setApprovalStatus(ApprovalStatus.ACTIVE);
    u.setDisplayName("MadrukSedras");
    u.setVersion(0L);
    return u;
  }

  private static Role roleWithCode(String code) {
    Role r = new Role();
    r.setName(code);
    r.setCode(code);
    return r;
  }

  @Test
  void approveUser_setsActive_stampsAdmin_andAuditsApproved() {
    User user = pendingUser(3L);
    user.setEmail("pilot@example.test");
    user.setDisplayName("Maverick");
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    when(userRepository.saveAndFlush(user)).thenReturn(user);

    User result = userRegistrationService.approveUser(USER_ID, 3L, ADMIN_ID);

    assertEquals(ApprovalStatus.ACTIVE, result.getApprovalStatus());
    assertEquals(ADMIN_ID, result.getApprovedById());
    assertNotNull(result.getApprovedAt());
    ArgumentCaptor<UserApprovalEvent> audit = ArgumentCaptor.forClass(UserApprovalEvent.class);
    verify(userApprovalEventRepository).save(audit.capture());
    assertEquals(ApprovalDecision.APPROVED, audit.getValue().getDecision());
    assertEquals(USER_ID, audit.getValue().getUserId());
    assertEquals(ADMIN_ID, audit.getValue().getDecidedById());
    // REQ-NOTIF-014: an approval publishes the decision-mail event carrying the recipient's
    // address + name and no reason.
    ArgumentCaptor<UserApprovalDecidedEvent> mail =
        ArgumentCaptor.forClass(UserApprovalDecidedEvent.class);
    verify(eventPublisher).publishEvent(mail.capture());
    assertTrue(mail.getValue().approved());
    assertEquals(USER_ID, mail.getValue().userId());
    assertEquals("pilot@example.test", mail.getValue().recipientEmail());
    assertEquals("Maverick", mail.getValue().recipientName());
    assertNull(mail.getValue().reason());
  }

  @Test
  void rejectUser_setsRejected_andAuditsReason() {
    User user = pendingUser(0L);
    user.setEmail("reject@example.test");
    user.setDisplayName("Goose");
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    when(userRepository.saveAndFlush(user)).thenReturn(user);

    User result = userRegistrationService.rejectUser(USER_ID, "not a real member", 0L, ADMIN_ID);

    assertEquals(ApprovalStatus.REJECTED, result.getApprovalStatus());
    ArgumentCaptor<UserApprovalEvent> audit = ArgumentCaptor.forClass(UserApprovalEvent.class);
    verify(userApprovalEventRepository).save(audit.capture());
    assertEquals(ApprovalDecision.REJECTED, audit.getValue().getDecision());
    assertEquals("not a real member", audit.getValue().getReason());
    // REQ-NOTIF-014: a rejection publishes the decision-mail event carrying the admin's reason.
    ArgumentCaptor<UserApprovalDecidedEvent> mail =
        ArgumentCaptor.forClass(UserApprovalDecidedEvent.class);
    verify(eventPublisher).publishEvent(mail.capture());
    assertFalse(mail.getValue().approved());
    assertEquals("reject@example.test", mail.getValue().recipientEmail());
    assertEquals("not a real member", mail.getValue().reason());
  }

  @Test
  void approveUser_staleVersion_throws409_andWritesNoAudit() {
    User user = pendingUser(5L);
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> userRegistrationService.approveUser(USER_ID, 3L, ADMIN_ID));

    verify(userApprovalEventRepository, never()).save(any());
    // No decision-mail event on a rejected (409) decision.
    verify(eventPublisher, never()).publishEvent(any());
    assertEquals(ApprovalStatus.PENDING, user.getApprovalStatus());
  }

  @Test
  void decide_onNonPendingUser_throwsConflict_andWritesNoAudit() {
    // PR review #3: an already-ACTIVE member must not be reject-able into a lockout.
    User active = pendingUser(2L);
    active.setApprovalStatus(ApprovalStatus.ACTIVE);
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(active));

    assertThrows(
        BusinessConflictException.class,
        () -> userRegistrationService.rejectUser(USER_ID, "oops", 2L, ADMIN_ID));

    verify(userApprovalEventRepository, never()).save(any());
    // No decision-mail event when the decision itself conflicts (409).
    verify(eventPublisher, never()).publishEvent(any());
    assertEquals(ApprovalStatus.ACTIVE, active.getApprovalStatus());
  }

  /**
   * Tests for {@link UserRegistrationService#stampNewPendingRegistration}, the shared fail-safe
   * PENDING stamping applied by both Keycloak reconciliation sync paths.
   */
  @Nested
  class StampNewPendingRegistrationTests {

    @Test
    void stampsPending_andReturnsTrue_forBrandNewNonAdmin() {
      User user = new User();

      boolean stamped =
          userRegistrationService.stampNewPendingRegistration(
              user, true, Set.of(roleWithCode("Guest")));

      assertTrue(stamped);
      assertEquals(ApprovalStatus.PENDING, user.getApprovalStatus());
    }

    @Test
    void doesNotStamp_forBrandNewAdmin() {
      User user = new User();
      ApprovalStatus before = user.getApprovalStatus();

      boolean stamped =
          userRegistrationService.stampNewPendingRegistration(
              user, true, Set.of(roleWithCode("ADMIN")));

      assertFalse(stamped);
      assertEquals(before, user.getApprovalStatus(), "an admin keeps the entity-default status");
    }

    @Test
    void doesNotStamp_forExistingUser() {
      User user = new User();
      ApprovalStatus before = user.getApprovalStatus();

      boolean stamped =
          userRegistrationService.stampNewPendingRegistration(
              user, false, Set.of(roleWithCode("Guest")));

      assertFalse(stamped);
      assertEquals(before, user.getApprovalStatus());
    }

    @Test
    void doesNotStamp_whenApprovalGateDisabled() {
      // The e2e carve-out (APP_REGISTRATION_REQUIRE_APPROVAL=false): a brand-new non-admin keeps
      // the ACTIVE entity default so the fixture seeder is not blocked on an interactive approval.
      ReflectionTestUtils.setField(userRegistrationService, "requireApproval", false);
      User user = new User();
      ApprovalStatus before = user.getApprovalStatus();

      boolean stamped =
          userRegistrationService.stampNewPendingRegistration(
              user, true, Set.of(roleWithCode("Guest")));

      assertFalse(stamped);
      assertEquals(before, user.getApprovalStatus());
    }
  }

  /**
   * Tests for {@link UserRegistrationService#linkRegistrationToExistingAccount} — the admin-driven
   * merge of a pending Discord registration onto an existing account (REQ-SEC-026).
   */
  @Nested
  class LinkRegistrationTests {

    @Test
    void linkRegistration_movesIdentity_deletesDuplicate_setsFieldsAndAuditsLinked() {
      User pending = pendingUser(0L);
      pending.setDiscordGuildNickname("MadrukSedras");
      User target = activeTarget();
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(pending));
      when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
      when(keycloakService.readDiscordLink(USER_ID))
          .thenReturn(Optional.of(new KeycloakService.DiscordLink(SNOWFLAKE, "conrad7247")));
      when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
      when(selfProvider.getObject()).thenReturn(userRegistrationService);

      User result =
          userRegistrationService.linkRegistrationToExistingAccount(
              USER_ID, TARGET_ID, 0L, ADMIN_ID);

      // Keycloak side-effects: the identity is read from Keycloak, moved onto the target, and the
      // throwaway user deleted.
      verify(keycloakService).linkDiscordIdentity(TARGET_ID, SNOWFLAKE, "conrad7247");
      verify(keycloakService).deleteUser(USER_ID);
      // The duplicate app_user is disposed FK-safely, its in-Keycloak guard cleared first.
      assertFalse(pending.isInKeycloak());
      verify(userDeletionService).deleteUser(USER_ID);
      // The surviving account carries the Discord link + the captured nickname.
      assertEquals(SNOWFLAKE, result.getDiscordUserId());
      assertEquals("MadrukSedras", result.getDiscordGuildNickname());
      // The LINKED audit is recorded against the surviving account.
      ArgumentCaptor<UserApprovalEvent> audit = ArgumentCaptor.forClass(UserApprovalEvent.class);
      verify(userApprovalEventRepository).save(audit.capture());
      assertEquals(ApprovalDecision.LINKED, audit.getValue().getDecision());
      assertEquals(TARGET_ID, audit.getValue().getUserId());
      assertEquals(ADMIN_ID, audit.getValue().getDecidedById());
    }

    @Test
    void linkRegistration_staleVersion_throws409_andTouchesNothing() {
      User pending = pendingUser(5L);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(pending));

      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () ->
              userRegistrationService.linkRegistrationToExistingAccount(
                  USER_ID, TARGET_ID, 3L, ADMIN_ID));

      verifyNoInteractions(keycloakService);
      verify(userApprovalEventRepository, never()).save(any());
    }

    @Test
    void linkRegistration_targetAlreadyDiscordLinked_throwsConflict_andWritesNothing() {
      User pending = pendingUser(0L);
      User target = activeTarget();
      target.setDiscordUserId("999999999999999999");
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(pending));
      when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

      assertThrows(
          BusinessConflictException.class,
          () ->
              userRegistrationService.linkRegistrationToExistingAccount(
                  USER_ID, TARGET_ID, 0L, ADMIN_ID));

      verifyNoInteractions(keycloakService);
      verify(userApprovalEventRepository, never()).save(any());
    }

    @Test
    void linkRegistration_pendingHasNoDiscordIdentity_throwsConflict_andDoesNotWrite() {
      User pending = pendingUser(0L);
      User target = activeTarget();
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(pending));
      when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
      when(keycloakService.readDiscordLink(USER_ID)).thenReturn(Optional.empty());

      assertThrows(
          BusinessConflictException.class,
          () ->
              userRegistrationService.linkRegistrationToExistingAccount(
                  USER_ID, TARGET_ID, 0L, ADMIN_ID));

      verify(keycloakService, never()).linkDiscordIdentity(any(), any(), any());
      verify(keycloakService, never()).deleteUser(any());
    }

    @Test
    void linkRegistration_targetNotActive_throwsConflict() {
      User pending = pendingUser(0L);
      User target = activeTarget();
      target.setApprovalStatus(ApprovalStatus.PENDING);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(pending));
      when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

      assertThrows(
          BusinessConflictException.class,
          () ->
              userRegistrationService.linkRegistrationToExistingAccount(
                  USER_ID, TARGET_ID, 0L, ADMIN_ID));

      verifyNoInteractions(keycloakService);
    }

    @Test
    void linkRegistration_toItself_throwsConflict() {
      User pending = pendingUser(0L);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(pending));

      assertThrows(
          BusinessConflictException.class,
          () ->
              userRegistrationService.linkRegistrationToExistingAccount(
                  USER_ID, USER_ID, 0L, ADMIN_ID));

      verifyNoInteractions(keycloakService);
    }
  }
}
