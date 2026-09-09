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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.ApprovalDecision;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.UserApprovalEvent;
import de.greluc.krt.profit.basetool.backend.repository.UserApprovalEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for {@link AccountConsolidationService} — folding a duplicate account into the one the
 * member keeps (REQ-SEC-055, #1828).
 *
 * <p>The orchestration is the interesting part rather than the data move: {@link
 * UserAccountMergeService} already owns and tests which rows follow the member, so what these cases
 * pin down is the ordering the unique {@code discord_user_id} forces, the Keycloak writes going out
 * in the sequence that makes a retry safe, and the guards that refuse rather than guess.
 */
@ExtendWith(MockitoExtension.class)
class AccountConsolidationServiceTest {

  private static final UUID DUPLICATE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID TARGET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID ADMIN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final String SNOWFLAKE = "123456789012345678";

  @Mock private UserRepository userRepository;
  @Mock private UserAccountMergeService userAccountMergeService;
  @Mock private UserDeletionService userDeletionService;
  @Mock private UserApprovalEventRepository userApprovalEventRepository;
  @Mock private KeycloakService keycloakService;
  @Mock private ObjectProvider<AccountConsolidationService> selfProvider;

  private AccountConsolidationService service;

  @BeforeEach
  void setUp() {
    service =
        new AccountConsolidationService(
            userRepository,
            userAccountMergeService,
            userDeletionService,
            userApprovalEventRepository,
            keycloakService,
            selfProvider);
  }

  /** A duplicate carrying a Discord identity and an optimistic-lock version. */
  private User duplicate() {
    User user = new User();
    user.setId(DUPLICATE_ID);
    user.setUsername("duplicate");
    user.setVersion(0L);
    user.setDiscordGuildNickname("SquadNick");
    return user;
  }

  /** The account the member keeps. */
  private User target() {
    User user = new User();
    user.setId(TARGET_ID);
    user.setUsername("original");
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    return user;
  }

  @Test
  void consolidate_movesBelongings_thenTheIdentity_thenDisposesTheDuplicate() {
    User duplicate = duplicate();
    User target = target();
    when(userRepository.findById(DUPLICATE_ID)).thenReturn(Optional.of(duplicate));
    when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
    when(userRepository.existsById(DUPLICATE_ID)).thenReturn(true);
    when(keycloakService.readDiscordLink(DUPLICATE_ID))
        .thenReturn(Optional.of(new KeycloakService.DiscordLink(SNOWFLAKE, "duplicate")));
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(selfProvider.getObject()).thenReturn(service);

    User result = service.consolidate(DUPLICATE_ID, TARGET_ID, 0L, ADMIN_ID);

    // The identity is linked onto the survivor first, the duplicate's app_user row goes next, and
    // its Keycloak user LAST. That last step being last is what makes a retry safe: a rolled-back
    // database half leaves the Keycloak user intact for a clean re-read.
    InOrder order = inOrder(keycloakService, userAccountMergeService, userDeletionService);
    order.verify(keycloakService).linkDiscordIdentity(TARGET_ID, SNOWFLAKE, "duplicate");
    order.verify(userAccountMergeService).merge(DUPLICATE_ID, TARGET_ID, ADMIN_ID);
    order
        .verify(userDeletionService)
        .deleteUser(
            DUPLICATE_ID,
            UserDeletionService.KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);
    order.verify(keycloakService).deleteUser(DUPLICATE_ID);

    // The duplicate's in-Keycloak guard is cleared before the FK-safe purge runs.
    assertFalse(duplicate.isInKeycloak());
    // The survivor ends up carrying the link and the captured nickname.
    assertEquals(SNOWFLAKE, result.getDiscordUserId());
    assertEquals("SquadNick", result.getDiscordGuildNickname());

    ArgumentCaptor<UserApprovalEvent> audit = ArgumentCaptor.forClass(UserApprovalEvent.class);
    verify(userApprovalEventRepository).save(audit.capture());
    assertEquals(ApprovalDecision.LINKED, audit.getValue().getDecision());
    assertEquals(TARGET_ID, audit.getValue().getUserId());
  }

  /**
   * Two credential accounts for one person is a legitimate duplicate with no Discord identity at
   * all. It consolidates the same way, minus the identity move — refusing it would leave the very
   * case the member administration is the only surface for.
   */
  @Test
  void consolidate_withNoDiscordIdentity_movesBelongingsAndWritesNoLink() {
    User duplicate = duplicate();
    duplicate.setDiscordGuildNickname(null);
    User target = target();
    when(userRepository.findById(DUPLICATE_ID)).thenReturn(Optional.of(duplicate));
    when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
    when(userRepository.existsById(DUPLICATE_ID)).thenReturn(true);
    when(keycloakService.readDiscordLink(DUPLICATE_ID)).thenReturn(Optional.empty());
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(selfProvider.getObject()).thenReturn(service);

    User result = service.consolidate(DUPLICATE_ID, TARGET_ID, 0L, ADMIN_ID);

    verify(keycloakService, never()).linkDiscordIdentity(any(), any(), any());
    verify(userAccountMergeService).merge(DUPLICATE_ID, TARGET_ID, ADMIN_ID);
    verify(keycloakService).deleteUser(DUPLICATE_ID);
    assertNull(result.getDiscordUserId());
  }

  /**
   * The half-finished consolidation: someone linked the survivor in Keycloak before disposing of
   * the duplicate. That is precisely the state that leaves {@code discord_user_id} unwritten on the
   * survivor — the duplicate's row still holds it — so the same snowflake on both sides must be
   * treated as the ordinary case rather than a conflict.
   */
  @Test
  void consolidate_whenTheTargetAlreadyCarriesTheSameIdentity_proceeds() {
    User duplicate = duplicate();
    User target = target();
    target.setDiscordUserId(SNOWFLAKE);
    when(userRepository.findById(DUPLICATE_ID)).thenReturn(Optional.of(duplicate));
    when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
    when(userRepository.existsById(DUPLICATE_ID)).thenReturn(true);
    when(keycloakService.readDiscordLink(DUPLICATE_ID))
        .thenReturn(Optional.of(new KeycloakService.DiscordLink(SNOWFLAKE, "duplicate")));
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(selfProvider.getObject()).thenReturn(service);

    User result = service.consolidate(DUPLICATE_ID, TARGET_ID, 0L, ADMIN_ID);

    assertEquals(SNOWFLAKE, result.getDiscordUserId());
  }

  /**
   * A target carrying a <em>different</em> Discord identity is two identities and two accounts, not
   * one member with a duplicate. Overwriting the link would rewrite who the surviving account
   * belongs to, so the action refuses before it writes anything at all.
   */
  @Test
  void consolidate_whenTheTargetCarriesADifferentIdentity_refusesBeforeTouchingKeycloak() {
    User duplicate = duplicate();
    User target = target();
    target.setDiscordUserId("999999999999999999");
    when(userRepository.findById(DUPLICATE_ID)).thenReturn(Optional.of(duplicate));
    when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
    when(keycloakService.readDiscordLink(DUPLICATE_ID))
        .thenReturn(Optional.of(new KeycloakService.DiscordLink(SNOWFLAKE, "duplicate")));

    assertThrows(
        BusinessConflictException.class,
        () -> service.consolidate(DUPLICATE_ID, TARGET_ID, 0L, ADMIN_ID));

    verify(keycloakService, never()).linkDiscordIdentity(any(), any(), any());
    verify(keycloakService, never()).deleteUser(any());
    verifyNoInteractions(userAccountMergeService, userDeletionService);
  }

  @Test
  void consolidate_intoItself_isRefused() {
    assertThrows(
        BusinessConflictException.class,
        () -> service.consolidate(DUPLICATE_ID, DUPLICATE_ID, 0L, ADMIN_ID));
    verifyNoInteractions(userRepository, keycloakService, userAccountMergeService);
  }

  /**
   * The purge reassigns shared aggregates to "some other admin", and an admin dissolving their own
   * account mid-operation is the one case where that fallback is reasoning about the row being
   * removed.
   */
  @Test
  void consolidate_theActingAdminsOwnAccount_isRefused() {
    User duplicate = duplicate();
    duplicate.setId(ADMIN_ID);
    when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(duplicate));

    assertThrows(
        BusinessConflictException.class,
        () -> service.consolidate(ADMIN_ID, TARGET_ID, 0L, ADMIN_ID));

    verifyNoInteractions(keycloakService, userAccountMergeService, userDeletionService);
  }

  @Test
  void consolidate_intoANonActiveTarget_isRefused() {
    User duplicate = duplicate();
    User target = target();
    target.setApprovalStatus(ApprovalStatus.PENDING);
    when(userRepository.findById(DUPLICATE_ID)).thenReturn(Optional.of(duplicate));
    when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

    assertThrows(
        BusinessConflictException.class,
        () -> service.consolidate(DUPLICATE_ID, TARGET_ID, 0L, ADMIN_ID));

    verifyNoInteractions(keycloakService, userAccountMergeService, userDeletionService);
  }

  @Test
  void consolidate_withAStaleVersion_failsTheOptimisticLock() {
    User duplicate = duplicate();
    duplicate.setVersion(3L);
    when(userRepository.findById(DUPLICATE_ID)).thenReturn(Optional.of(duplicate));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> service.consolidate(DUPLICATE_ID, TARGET_ID, 0L, ADMIN_ID));

    verifyNoInteractions(keycloakService, userAccountMergeService, userDeletionService);
  }

  @Test
  void consolidate_withAnUnknownDuplicate_is404() {
    when(userRepository.findById(DUPLICATE_ID)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class, () -> service.consolidate(DUPLICATE_ID, TARGET_ID, 0L, ADMIN_ID));
  }

  /**
   * Retry after a partial failure that already removed the duplicate's row: the merge and the purge
   * are skipped and the survivor is simply stamped, so a second attempt completes instead of
   * throwing on a row that is no longer there.
   */
  @Test
  void completeConsolidation_whenTheDuplicateRowIsAlreadyGone_justStampsTheSurvivor() {
    User target = target();
    when(userRepository.existsById(DUPLICATE_ID)).thenReturn(false);
    when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    User result =
        service.completeConsolidationTransactionally(
            DUPLICATE_ID, TARGET_ID, SNOWFLAKE, "SquadNick", ADMIN_ID);

    verifyNoInteractions(userAccountMergeService, userDeletionService);
    assertEquals(SNOWFLAKE, result.getDiscordUserId());
  }
}
