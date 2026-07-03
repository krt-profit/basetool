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
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.event.UserApprovalDecidedEvent;
import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.model.ApprovalDecision;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.UserApprovalEvent;
import de.greluc.krt.profit.basetool.backend.repository.UserApprovalEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** Mockito unit tests for the registration approve/reject path on {@link UserService}. */
@ExtendWith(MockitoExtension.class)
class UserServiceApprovalTest {

  @Mock private UserRepository userRepository;
  @Mock private UserApprovalEventRepository userApprovalEventRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  @InjectMocks private UserService userService;

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID ADMIN_ID = UUID.randomUUID();

  private static User pendingUser(long version) {
    User u = new User();
    u.setId(USER_ID);
    u.setApprovalStatus(ApprovalStatus.PENDING);
    u.setVersion(version);
    return u;
  }

  @Test
  void approveUser_setsActive_stampsAdmin_andAuditsApproved() {
    User user = pendingUser(3L);
    user.setEmail("pilot@example.test");
    user.setDisplayName("Maverick");
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    when(userRepository.saveAndFlush(user)).thenReturn(user);

    User result = userService.approveUser(USER_ID, 3L, ADMIN_ID);

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

    User result = userService.rejectUser(USER_ID, "not a real member", 0L, ADMIN_ID);

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
        () -> userService.approveUser(USER_ID, 3L, ADMIN_ID));

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
        () -> userService.rejectUser(USER_ID, "oops", 2L, ADMIN_ID));

    verify(userApprovalEventRepository, never()).save(any());
    // No decision-mail event when the decision itself conflicts (409).
    verify(eventPublisher, never()).publishEvent(any());
    assertEquals(ApprovalStatus.ACTIVE, active.getApprovalStatus());
  }
}
