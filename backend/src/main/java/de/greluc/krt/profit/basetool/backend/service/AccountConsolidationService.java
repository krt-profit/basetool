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

import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.ApprovalDecision;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.UserApprovalEvent;
import de.greluc.krt.profit.basetool.backend.repository.UserApprovalEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
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
 * Folds a duplicate account into the account the member keeps (REQ-SEC-055, #1828).
 *
 * <p>The remedy for a member who ended up with two accounts and whose duplicate has already been
 * <em>approved</em>. While the duplicate sits in the approval queue the cheaper action applies:
 * {@link UserRegistrationService#linkRegistrationToExistingAccount} moves the Discord identity and
 * discards a registration that cannot yet own anything (REQ-SEC-026). Approving it takes that away
 * — the queue serves {@code PENDING} and {@code REJECTED} only, and the link action guards on
 * {@code PENDING} in the service as well — and leaves an account that has since been able to
 * accumulate data. So this action does both halves: it moves what the duplicate owns, and it moves
 * the identity.
 *
 * <h2>It composes rather than reimplements</h2>
 *
 * <ul>
 *   <li>{@link UserAccountMergeService#merge} decides which rows follow the member and which stay
 *       with the act (REQ-SEC-046), and refuses two bank ledgers rather than guessing. It carries
 *       no approval-status guard, so it already worked on an active account — it simply had no UI
 *       in front of it for this case.
 *   <li>{@link KeycloakService} moves the federated identity and removes the duplicate's realm
 *       user.
 *   <li>{@link UserDeletionService} removes the emptied row through the FK-safe purge.
 * </ul>
 *
 * <h2>Non-transactional orchestrator</h2>
 *
 * <p>Mirroring {@link UserRegistrationService#linkRegistrationToExistingAccount}: the Keycloak
 * writes are external side-effects that cannot roll back with a database transaction, so this
 * method runs outside any transaction and commits the database work through the self-proxied {@link
 * #completeConsolidationTransactionally}. The duplicate's Keycloak user is deleted <em>last</em>,
 * after the database is consistent, which is what makes a retry safe: a rolled-back database half
 * leaves that user intact so the next attempt re-reads its identity cleanly. Each Keycloak write is
 * itself idempotent.
 *
 * <h2>The ordering inside the transaction is not free choice</h2>
 *
 * <p>{@code app_user.discord_user_id} is UNIQUE (V172), so the duplicate's row must be gone before
 * the survivor can claim the snowflake. That is the same reason the {@code PENDING} path deletes
 * the throwaway row first and stamps the survivor second, and it is why the belongings move, the
 * delete and the stamp all sit in one transaction in that order.
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

  /**
   * This service through the Spring proxy, so {@link #completeConsolidationTransactionally}
   * actually opens a transaction. A plain self-invocation would bypass the proxy and run the
   * database half with no transaction at all.
   */
  private final ObjectProvider<AccountConsolidationService> selfProvider;

  /**
   * Dissolves {@code duplicateId} into {@code targetUserId}: what the duplicate owns moves onto the
   * target, its Discord identity (if it has one) is re-linked onto the target in Keycloak, and both
   * the duplicate's {@code app_user} row and its Keycloak user are removed.
   *
   * <p>A duplicate with no Discord identity is a legitimate case — two credential accounts for one
   * person — and consolidates the same way, minus the identity move. What is refused is a target
   * that already carries a <em>different</em> Discord identity: that is not one member with two
   * accounts, and quietly overwriting the link would rewrite who the surviving account belongs to.
   *
   * @param duplicateId the account to dissolve
   * @param targetUserId the account the member keeps
   * @param version the duplicate's optimistic-lock version; {@code null} bypasses the check
   * @param adminId the acting admin's id, recorded in the audit
   * @return the surviving account
   * @throws NotFoundException when either account is unknown
   * @throws BusinessConflictException when the two ids are the same, the duplicate is the acting
   *     admin's own account, the target is not active, the target already carries a different
   *     Discord identity, or both accounts hold a bank ledger
   * @throws org.springframework.dao.OptimisticLockingFailureException when the supplied version is
   *     stale
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @NotNull
  public User consolidate(
      @NotNull UUID duplicateId,
      @NotNull UUID targetUserId,
      @Nullable Long version,
      @NotNull UUID adminId) {
    if (duplicateId.equals(targetUserId)) {
      throw new BusinessConflictException("An account cannot be consolidated into itself");
    }
    User duplicate =
        userRepository
            .findById(duplicateId)
            .orElseThrow(() -> new NotFoundException("Duplicate account not found"));
    OptimisticLock.checkOptionalClient(duplicate.getVersion(), version, User.class, duplicateId);
    if (duplicateId.equals(adminId)) {
      // Not paternalism: the purge reassigns shared aggregates to "some other admin", and the
      // acting admin dissolving themselves mid-operation is the one case where that fallback is
      // reasoning about the account being removed.
      throw new BusinessConflictException("An administrator cannot dissolve their own account");
    }

    User target =
        userRepository
            .findById(targetUserId)
            .orElseThrow(() -> new NotFoundException("Target account not found"));
    if (target.getApprovalStatus() != ApprovalStatus.ACTIVE) {
      throw new BusinessConflictException("The target account must be an active account");
    }

    Optional<KeycloakService.DiscordLink> link = resolveDiscordLink(duplicate);
    assertTargetCanTakeTheLink(target, link.orElse(null));
    String guildNickname = duplicate.getDiscordGuildNickname();

    // Identity first, then the database, then the duplicate's Keycloak user -- see the class
    // Javadoc for why the last step is last.
    link.ifPresent(
        resolved ->
            keycloakService.linkDiscordIdentity(
                targetUserId, resolved.userId(), resolved.userName()));
    User result =
        selfProvider
            .getObject()
            .completeConsolidationTransactionally(
                duplicateId,
                targetUserId,
                link.map(KeycloakService.DiscordLink::userId).orElse(null),
                guildNickname,
                adminId);
    keycloakService.deleteUser(duplicateId);
    return result;
  }

  /**
   * Refuses a target that already carries a Discord identity other than the one being moved onto
   * it.
   *
   * <p>The same snowflake is not a conflict but the ordinary shape of a half-finished consolidation
   * — someone linked the survivor in Keycloak before disposing of the duplicate, which is exactly
   * the state that leaves {@code discord_user_id} unwritten on the survivor because the duplicate's
   * row still holds it. A <em>different</em> snowflake is a genuine conflict: two identities and
   * two accounts is not one member with a duplicate.
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
   * Resolves the Discord identity to move off the duplicate, preferring the authoritative Keycloak
   * federated identity and falling back to the locally persisted {@code discord_user_id}.
   *
   * <p>Same two-source shape as the {@code PENDING} path (REQ-SEC-026): the Keycloak read covers a
   * link the claim mapper never persisted locally, and the local fallback recovers a consolidation
   * whose earlier attempt had already deleted the duplicate's Keycloak user — {@code
   * readDiscordLink} maps a {@code 404} to empty precisely so that fallback is reachable. Unlike
   * that path, an empty result is not an error here: a duplicate with no Discord identity at all is
   * a legitimate case.
   *
   * @param duplicate the account being dissolved; never {@code null}
   * @return the identity to move, or empty when the duplicate carries none
   */
  @NotNull
  private Optional<KeycloakService.DiscordLink> resolveDiscordLink(@NotNull User duplicate) {
    return keycloakService
        .readDiscordLink(duplicate.getId())
        .or(
            () -> {
              String local = duplicate.getDiscordUserId();
              return local == null || local.isBlank()
                  ? Optional.empty()
                  : Optional.of(
                      new KeycloakService.DiscordLink(local.trim(), duplicate.getUsername()));
            });
  }

  /**
   * The transactional database half, invoked through the self-proxy so its {@link Transactional}
   * boundary actually applies.
   *
   * <p>Three steps in a fixed order: move what the duplicate owns onto the target ({@link
   * UserAccountMergeService#merge}, which also refuses two bank ledgers), purge the emptied
   * duplicate row, then stamp the survivor's Discord link. The stamp must come last because {@code
   * discord_user_id} is UNIQUE and the duplicate's row holds the value until it is gone.
   *
   * <p>The duplicate's Keycloak user is still present at this point — the orchestrator removes it
   * after this commits — so the deletion's presence probe is waived explicitly ({@link
   * UserDeletionService.KeycloakPresenceCheck#WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER}). On a retry
   * where the duplicate row is already gone, all three steps degrade to just the stamp.
   *
   * @param duplicateId the account to empty and remove; may already be gone on a retry
   * @param targetUserId the surviving account
   * @param snowflake the Discord id to record on the survivor, or {@code null} when there is none
   * @param guildNickname the captured guild nickname to carry over, or {@code null}
   * @param adminId the acting admin's id, recorded in the audit
   * @return the surviving account
   * @throws NotFoundException when the target account is unknown
   */
  @Transactional
  @NotNull
  public User completeConsolidationTransactionally(
      @NotNull UUID duplicateId,
      @NotNull UUID targetUserId,
      @Nullable String snowflake,
      @Nullable String guildNickname,
      @NotNull UUID adminId) {
    if (userRepository.existsById(duplicateId)) {
      userAccountMergeService.merge(duplicateId, targetUserId, adminId);
      // merge() clears the persistence context, so this is a fresh read of the now-emptied row.
      userRepository
          .findById(duplicateId)
          .ifPresent(
              emptied -> {
                emptied.setInKeycloak(false);
                userRepository.saveAndFlush(emptied);
              });
      userDeletionService.deleteUser(
          duplicateId,
          UserDeletionService.KeycloakPresenceCheck.WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);
    }

    User target =
        userRepository
            .findById(targetUserId)
            .orElseThrow(() -> new NotFoundException("Target account not found"));
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
