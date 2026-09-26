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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link RejectedRegistrationRetentionService} (REQ-SEC-057): the database purge
 * precedes the Keycloak write, a concurrently reopened registration is spared, and one failing row
 * does not abort the sweep.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RejectedRegistrationRetentionServiceTest {

  private static final Instant CUTOFF = Instant.now().minus(90, ChronoUnit.DAYS);

  @Mock private UserRepository userRepository;
  @Mock private UserDeletionService userDeletionService;
  @Mock private KeycloakService keycloakService;
  @Mock private ObjectProvider<RejectedRegistrationRetentionService> selfProvider;

  @InjectMocks private RejectedRegistrationRetentionService service;

  @BeforeEach
  void wireSelfProxy() {
    when(selfProvider.getObject()).thenReturn(service);
  }

  private User rejected(UUID id, Instant decidedAt) {
    User user = new User();
    user.setId(id);
    user.setApprovalStatus(ApprovalStatus.REJECTED);
    user.setApprovedAt(decidedAt);
    user.setInKeycloak(true);
    return user;
  }

  @Test
  void purgesARejectedRegistrationPastTheWindow() {
    UUID id = UUID.randomUUID();
    when(userRepository.findRejectedDecidedBefore(CUTOFF)).thenReturn(List.of(id));
    when(userRepository.findPlainById(id))
        .thenReturn(Optional.of(rejected(id, CUTOFF.minus(1, ChronoUnit.DAYS))));

    assertThat(service.purgeRejectedOlderThan(CUTOFF)).isEqualTo(1);

    verify(userDeletionService)
        .deleteUser(
            id, UserDeletionService.KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);
    verify(keycloakService).deleteUser(id);
  }

  @Test
  void clearsTheCachedKeycloakFlagBeforeDelegating() {
    UUID id = UUID.randomUUID();
    when(userRepository.findRejectedDecidedBefore(CUTOFF)).thenReturn(List.of(id));
    when(userRepository.findPlainById(id))
        .thenReturn(Optional.of(rejected(id, CUTOFF.minus(1, ChronoUnit.DAYS))));

    service.purgeRejectedOlderThan(CUTOFF);

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(userRepository).saveAndFlush(saved.capture());
    assertThat(saved.getValue().isInKeycloak()).isFalse();
  }

  @Test
  void deletesTheKeycloakUserOnlyAfterTheDatabaseHalf() {
    UUID id = UUID.randomUUID();
    when(userRepository.findRejectedDecidedBefore(CUTOFF)).thenReturn(List.of(id));
    when(userRepository.findPlainById(id))
        .thenReturn(Optional.of(rejected(id, CUTOFF.minus(1, ChronoUnit.DAYS))));

    service.purgeRejectedOlderThan(CUTOFF);

    InOrder inOrder = Mockito.inOrder(userDeletionService, keycloakService);
    inOrder.verify(userDeletionService).deleteUser(any(), any());
    inOrder.verify(keycloakService).deleteUser(id);
  }

  @Test
  void skipsARegistrationReopenedAfterTheCandidateQuery() {
    UUID id = UUID.randomUUID();
    User reopened = rejected(id, null);
    reopened.setApprovalStatus(ApprovalStatus.PENDING);
    when(userRepository.findRejectedDecidedBefore(CUTOFF)).thenReturn(List.of(id));
    when(userRepository.findPlainById(id)).thenReturn(Optional.of(reopened));

    assertThat(service.purgeRejectedOlderThan(CUTOFF)).isZero();

    verifyNoInteractions(userDeletionService);
    verify(keycloakService, never()).deleteUser(any());
  }

  @Test
  void skipsARejectionThatIsNoLongerPastTheCutoff() {
    UUID id = UUID.randomUUID();
    when(userRepository.findRejectedDecidedBefore(CUTOFF)).thenReturn(List.of(id));
    when(userRepository.findPlainById(id))
        .thenReturn(Optional.of(rejected(id, CUTOFF.plus(1, ChronoUnit.DAYS))));

    assertThat(service.purgeRejectedOlderThan(CUTOFF)).isZero();

    verifyNoInteractions(userDeletionService);
  }

  @Test
  void onePurgeFailureDoesNotAbortTheRun() {
    UUID failing = UUID.randomUUID();
    UUID ok = UUID.randomUUID();
    when(userRepository.findRejectedDecidedBefore(CUTOFF)).thenReturn(List.of(failing, ok));
    when(userRepository.findPlainById(failing))
        .thenReturn(Optional.of(rejected(failing, CUTOFF.minus(1, ChronoUnit.DAYS))));
    when(userRepository.findPlainById(ok))
        .thenReturn(Optional.of(rejected(ok, CUTOFF.minus(1, ChronoUnit.DAYS))));
    Mockito.doThrow(new IllegalStateException("no admin to reassign to"))
        .when(userDeletionService)
        .deleteUser(
            Mockito.eq(failing),
            Mockito.eq(
                UserDeletionService.KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER));

    assertThat(service.purgeRejectedOlderThan(CUTOFF)).isEqualTo(1);

    verify(keycloakService, never()).deleteUser(failing);
    verify(keycloakService).deleteUser(ok);
  }

  @Test
  void aFailingKeycloakDeleteStillCountsTheCommittedPurge() {
    UUID id = UUID.randomUUID();
    when(userRepository.findRejectedDecidedBefore(CUTOFF)).thenReturn(List.of(id));
    when(userRepository.findPlainById(id))
        .thenReturn(Optional.of(rejected(id, CUTOFF.minus(1, ChronoUnit.DAYS))));
    Mockito.doThrow(new IllegalStateException("keycloak down"))
        .when(keycloakService)
        .deleteUser(id);

    assertThat(service.purgeRejectedOlderThan(CUTOFF)).isEqualTo(1);
  }

  @Test
  void doesNothingWhenNoRegistrationQualifies() {
    when(userRepository.findRejectedDecidedBefore(CUTOFF)).thenReturn(List.of());

    assertThat(service.purgeRejectedOlderThan(CUTOFF)).isZero();

    verifyNoInteractions(userDeletionService, keycloakService);
  }
}
