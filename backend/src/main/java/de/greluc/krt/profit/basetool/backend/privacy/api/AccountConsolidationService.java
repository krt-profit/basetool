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

package de.greluc.krt.profit.basetool.backend.privacy.api;

import de.greluc.krt.profit.basetool.backend.identity.api.AccountLinkReservation;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalDecision;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.KeycloakService;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserApprovalEvent;
import de.greluc.krt.profit.basetool.backend.identity.api.UserApprovalEventRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folds an approved duplicate account into the account the member keeps (REQ-SEC-055): moves its
 * belongings and its Discord identity, then removes it.
 *
 * <p>Reserves both account IDs through the Keycloak writes and any compensation. The merge commits
 * in a separate transaction through {@link #completeConsolidationTransactionally} before the
 * duplicate's Keycloak user is deleted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountConsolidationService {

  private final UserRepository userRepository;
  private final UserAccountMergeService userAccountMergeService;
  private final UserDeletionService userDeletionService;
  private final UserApprovalEventRepository userApprovalEventRepository;
  private final KeycloakService keycloakService;
  private final AccountLinkReservation accountLinkReservation;

  /**
   * This service through the Spring proxy, so {@link #completeConsolidationTransactionally}
   * actually opens a transaction. A plain self-invocation would bypass the proxy and run the
   * database half with no transaction at all.
   */
  private final ObjectProvider<AccountConsolidationService> selfProvider;

  /**
   * Dissolves {@code duplicateId} into {@code targetUserId}: moves its belongings and Discord
   * identity (if any) onto the target and removes the duplicate's {@code app_user} row and Keycloak
   * user.
   *
   * @param duplicateId the account to dissolve
   * @param targetUserId the account the member keeps
   * @param version the duplicate's optimistic-lock version; {@code null} bypasses the check
   * @param adminId the acting admin's id, recorded in the audit
   * @return the surviving account
   * @throws NotFoundException when either account is unknown
   * @throws BusinessConflictException when either account is already being consolidated, the ids
   *     are equal, the duplicate is the admin's own account, the target is inactive or carries a
   *     different Discord identity, or both accounts hold a bank ledger
   * @throws org.springframework.dao.OptimisticLockingFailureException when the version is stale
   */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  @NotNull
  public User consolidate(
      @NotNull UUID duplicateId,
      @NotNull UUID targetUserId,
      @Nullable Long version,
      @NotNull UUID adminId) {
    if (duplicateId.equals(targetUserId)) {
      throw new BusinessConflictException("An account cannot be consolidated into itself");
    }
    accountLinkReservation.reserve(duplicateId, targetUserId);
    User duplicate =
        Entities.require(userRepository.findById(duplicateId), "Duplicate account not found");
    OptimisticLock.checkOptionalClient(duplicate.getVersion(), version, User.class, duplicateId);
    if (duplicateId.equals(adminId)) {
      throw new BusinessConflictException("An administrator cannot dissolve their own account");
    }

    User target =
        Entities.require(userRepository.findById(targetUserId), "Target account not found");
    if (target.getApprovalStatus() != ApprovalStatus.ACTIVE) {
      throw new BusinessConflictException("The target account must be an active account");
    }

    Optional<KeycloakService.DiscordLink> link = resolveDiscordLink(duplicate);
    assertTargetCanTakeTheLink(target, link.orElse(null));
    userAccountMergeService.assertMergeable(duplicateId, targetUserId);
    String guildNickname = duplicate.getDiscordGuildNickname();

    KeycloakService.DiscordLink resolved = link.orElse(null);
    boolean linkCreated =
        resolved != null
            && keycloakService.linkDiscordIdentity(
                targetUserId, resolved.userId(), resolved.userName());
    User result;
    try {
      result =
          selfProvider
              .getObject()
              .completeConsolidationTransactionally(
                  duplicateId,
                  targetUserId,
                  resolved == null ? null : resolved.userId(),
                  guildNickname,
                  adminId);
    } catch (RuntimeException failure) {
      if (linkCreated) {
        link.ifPresent(
            created -> removeFailedConsolidationLink(duplicateId, targetUserId, created, failure));
      }
      throw failure;
    }
    keycloakService.deleteUser(duplicateId);
    return result;
  }

  /**
   * Compensates a failed database merge while the duplicate still exists.
   *
   * @param duplicateId the account that still owns the belongings
   * @param targetUserId the account to detach
   * @param link the link this consolidation created
   * @param failure the database failure to preserve
   */
  private void removeFailedConsolidationLink(
      UUID duplicateId,
      UUID targetUserId,
      KeycloakService.DiscordLink link,
      RuntimeException failure) {
    try {
      if (userRepository.existsById(duplicateId)
          && keycloakService
              .readDiscordLink(targetUserId)
              .map(current -> link.userId().equals(current.userId()))
              .orElse(false)) {
        keycloakService.unlinkDiscordIdentity(targetUserId);
      }
    } catch (RuntimeException rollbackFailure) {
      String kind = rollbackFailure.getClass().getSimpleName();
      log.warn(
          "Could not detach the Discord link after a failed consolidation of {} into {} ({})",
          duplicateId,
          targetUserId,
          kind);
      failure.addSuppressed(new IllegalStateException("Discord link compensation failed: " + kind));
    }
  }

  /**
   * Refuses a target that already carries a Discord identity other than the one being moved; the
   * same snowflake is accepted.
   *
   * @param target the surviving account
   * @param link the identity about to be moved, or {@code null} when the duplicate has none
   * @throws BusinessConflictException when the target holds a different Discord identity
   */
  private static void assertTargetCanTakeTheLink(
      @NotNull User target, @Nullable KeycloakService.DiscordLink link) {
    String existing = target.getDiscordUserId();
    if (existing == null || existing.isBlank()) {
      return;
    }
    if (link == null || !existing.equals(link.userId())) {
      throw new BusinessConflictException(
          "The target account is already linked to a different Discord account");
    }
  }

  /**
   * Resolves the duplicate's Discord identity from its Keycloak federated identity, falling back to
   * the locally persisted {@code discord_user_id}.
   *
   * @param duplicate the account being dissolved; never {@code null}
   * @return the identity to move, or empty when the duplicate carries none
   */
  @NotNull
  private Optional<KeycloakService.DiscordLink> resolveDiscordLink(@NotNull User duplicate) {
    return keycloakService
        .readDiscordLink(duplicate.getId())
        .or(() -> {
          String local = duplicate.getDiscordUserId();
          return local == null || local.isBlank()
              ? Optional.empty()
              : Optional.of(new KeycloakService.DiscordLink(local.trim(), duplicate.getUsername()));
        });
  }

  /**
   * The transactional database half, invoked through the self-proxy: merges the duplicate's
   * belongings into the target, purges the duplicate row, then stamps the survivor's Discord link.
   *
   * <p>The stamp comes last because {@code discord_user_id} is UNIQUE. On a retry where the
   * duplicate row is already gone, only the stamp runs.
   *
   * @param duplicateId the account to empty and remove; may already be gone on a retry
   * @param targetUserId the surviving account
   * @param snowflake the Discord id to record on the survivor, or {@code null} when there is none
   * @param guildNickname the guild nickname to carry over, or {@code null}
   * @param adminId the acting admin's id, recorded in the audit
   * @return the surviving account
   * @throws NotFoundException when the target account is unknown
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @NotNull
  public User completeConsolidationTransactionally(
      @NotNull UUID duplicateId,
      @NotNull UUID targetUserId,
      @Nullable String snowflake,
      @Nullable String guildNickname,
      @NotNull UUID adminId) {
    if (userRepository.existsById(duplicateId)) {
      userAccountMergeService.merge(duplicateId, targetUserId, adminId);
      userRepository
          .findById(duplicateId)
          .ifPresent(emptied -> {
            emptied.setInKeycloak(false);
            userRepository.saveAndFlush(emptied);
          });
      userDeletionService.deleteUser(
          duplicateId,
          UserDeletionService.KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);
    }

    User target =
        Entities.require(userRepository.findById(targetUserId), "Target account not found");
    if (snowflake != null && !snowflake.isBlank()) {
      target.setDiscordUserId(snowflake);
    }
    if (guildNickname != null && !guildNickname.isBlank()) {
      target.setDiscordGuildNickname(guildNickname);
    }
    User saved = userRepository.saveAndFlush(target);
    userApprovalEventRepository.save(
        new UserApprovalEvent(targetUserId, ApprovalDecision.LINKED, null, adminId));
    log.info(
        "Consolidated account {} into {} (acting admin {})", duplicateId, targetUserId, adminId);
    return saved;
  }
}
