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

import de.greluc.krt.profit.basetool.backend.event.UserApprovalDecidedEvent;
import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.ApprovalDecision;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.UserApprovalEvent;
import de.greluc.krt.profit.basetool.backend.repository.UserApprovalEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the Discord registration approval lifecycle (epic #720, Track&nbsp;1 / REQ-SEC-017) —
 * extracted out of {@link UserService} (audit Thema&nbsp;7, #1252) so the fail-safe approval gate
 * lives on its own seam. Covers the admin queue read ({@link #findPendingRegistrations()}) and the
 * two optimistic-locked, audited decisions ({@link #approveUser} / {@link #rejectUser} on top of
 * the shared {@link #decide}).
 *
 * <p>It also owns the reversal of an erroneous rejection (REQ-SEC-034): {@link
 * #findRejectedRegistrations()} makes a rejected row findable and {@link #reopenRegistration}
 * returns it to the queue as PENDING. The reversal is deliberately a separate action rather than a
 * widening of {@link #decide}, so the "only a still-PENDING registration may be decided" invariant
 * that protects an active member's authorities stays exactly as narrow as it is.
 *
 * <p>It also owns the shared entry side of the lifecycle: {@link #stampNewPendingRegistration}, the
 * fail-safe PENDING stamping that both Keycloak reconciliation sync paths apply to a brand-new
 * non-admin registration. That is the one place the {@code app.registration.require-approval} gate
 * is evaluated, so the JWT-login and the scheduled Admin-API paths can never drift on whether a new
 * non-admin lands PENDING.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserRegistrationService {

  private final UserRepository userRepository;
  private final UserApprovalEventRepository userApprovalEventRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final KeycloakService keycloakService;
  private final UserDeletionService userDeletionService;

  /**
   * Self-injection (lazy {@link ObjectProvider} to avoid an eager construction cycle) so {@link
   * #linkRegistrationToExistingAccount} — a non-transactional orchestrator around external Keycloak
   * writes — can invoke its transactional database half {@link #completeLinkTransactionally}
   * through the Spring proxy rather than by self-invocation (which would bypass the
   * {@code @Transactional} advice).
   */
  private final ObjectProvider<UserRegistrationService> selfProvider;

  /**
   * Whether a brand-new non-admin registration must be approved by an admin before it is granted
   * any authorities (REQ-SEC-017 fail-safe default). {@code true} in prod and by default. Set to
   * {@code false} ONLY in controlled non-prod stacks (the Playwright e2e stack, via {@code
   * APP_REGISTRATION_REQUIRE_APPROVAL=false}) where fixture users are provisioned on the fly and an
   * interactive approval step would deadlock the seeder — there, a new non-admin keeps the {@code
   * ACTIVE} entity default. Field-injected with a {@code true} initializer so Mockito unit tests
   * (no Spring) exercise the secure default without extra wiring.
   */
  @Value("${app.registration.require-approval:true}")
  private boolean requireApproval = true;

  /**
   * Stamps a brand-new non-admin registration {@link ApprovalStatus#PENDING} under the fail-safe
   * approval gate (REQ-SEC-017). Shared by both Keycloak reconciliation sync paths (JWT login and
   * the scheduled Admin-API sync) so they cannot diverge on whether a new non-admin is gated: a
   * missing {@code discord_user_id} claim/mapper must never let a federated login inherit the
   * {@code ACTIVE} entity default and skip approval.
   *
   * <p>Operates in place on the already-managed {@code user} and relies on the caller's transaction
   * to flush the change — hence {@link Propagation#MANDATORY}; it performs no {@code save} / {@code
   * flush} of its own. Only a brand-new row ({@code created}) that is not an admin is touched;
   * existing rows and admins (bootstrap carve-out, entity default {@code ACTIVE}) are left
   * untouched. The caller is responsible for marking its change flag and publishing the admin
   * notification (REQ-NOTIF-012) when this returns {@code true}.
   *
   * @param user the managed user being reconciled; never {@code null}.
   * @param created whether this reconciliation just created the row (only new rows are gated).
   * @param localRoles the mapped local roles; an ADMIN among them suppresses the gate; never {@code
   *     null}.
   * @return {@code true} when this call stamped a new PENDING registration, {@code false}
   *     otherwise.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean stampNewPendingRegistration(
      @NotNull User user, boolean created, @NotNull Set<Role> localRoles) {
    if (created
        && requireApproval
        && localRoles.stream().noneMatch(r -> Roles.ADMIN.equalsIgnoreCase(r.getCode()))) {
      user.setApprovalStatus(ApprovalStatus.PENDING);
      return true;
    }
    return false;
  }

  /**
   * Returns the registrations awaiting an admin decision (status {@link ApprovalStatus#PENDING}),
   * oldest first. Admin-only at the controller boundary; not squadron-scoped because a pending user
   * has no org unit yet.
   *
   * @return the pending registrations, oldest registration first
   */
  @NotNull
  public List<User> findPendingRegistrations() {
    return userRepository.findByApprovalStatusOrderByCreatedAtAsc(ApprovalStatus.PENDING);
  }

  /**
   * Returns the registrations an admin has rejected (status {@link ApprovalStatus#REJECTED}),
   * oldest first. This is the read surface that makes an erroneous rejection findable at all: the
   * approval queue itself is PENDING-only, so before this existed an admin had no way to see a
   * rejected row — let alone reverse it — without querying the database directly (REQ-SEC-034).
   *
   * <p>Ordered oldest-first to match {@link #findPendingRegistrations()} rather than
   * newest-rejection-first: both lists are small (rejections are rare in a squadron-sized realm)
   * and one ordering across the page keeps the two tables readable side by side.
   *
   * @return the rejected registrations, oldest registration first
   */
  @NotNull
  public List<User> findRejectedRegistrations() {
    return userRepository.findByApprovalStatusOrderByCreatedAtAsc(ApprovalStatus.REJECTED);
  }

  /**
   * Reopens a rejected registration: moves it from {@link ApprovalStatus#REJECTED} back to {@link
   * ApprovalStatus#PENDING} so it re-enters the approval queue and can be decided again through the
   * normal {@link #approveUser}/{@link #rejectUser} path. This is the supported reversal of an
   * erroneous rejection (REQ-SEC-034); without it the only recoveries were a manual production
   * {@code UPDATE} (which bypasses the audit trail) or deleting the account outright (which
   * destroys its data and history).
   *
   * <p><strong>Deliberately not a REJECTED → ACTIVE shortcut.</strong> Routing the reversal back
   * through PENDING keeps the "only a still-PENDING registration may be decided" invariant in
   * {@link #decide} intact — the guard that stops an already-ACTIVE member from being silently
   * stripped of their authorities by a re-decision. The admin therefore performs two explicit acts,
   * and the audit records both.
   *
   * <p>The previous decision stamp ({@code approvedAt} / {@code approvedById}) is <em>cleared</em>
   * so the row is indistinguishable from a fresh pending registration: a PENDING row still carrying
   * a decision stamp would render a decision time for an undecided account. Nothing is lost — who
   * rejected it and when survives in {@code user_approval_event}, which is the history of record.
   *
   * <p>No notification fires. The rejection mail (REQ-NOTIF-014) announces a verdict and a reopen
   * is not one, and the new-registration admin mail (REQ-NOTIF-012) would page the whole admin body
   * about a months-old registration that the acting admin is already looking at.
   *
   * @param userId the rejected registration to reopen
   * @param reason optional free-text note recorded in the audit (e.g. why the rejection was wrong);
   *     may be {@code null}
   * @param version the optimistic-lock version echoed back from the rejected list; {@code null}
   *     bypasses the check
   * @param adminId the acting admin's id (for the audit row)
   * @return the now-pending user (with its bumped version)
   * @throws NotFoundException when the user is unknown
   * @throws BusinessConflictException when the registration is not {@link ApprovalStatus#REJECTED}
   *     — a PENDING row needs no reopening and an ACTIVE member must never be pushed back into the
   *     queue, which would strip their access
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  @NotNull
  public User reopenRegistration(
      @NotNull UUID userId,
      @Nullable String reason,
      @Nullable Long version,
      @NotNull UUID adminId) {
    User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, userId);
    if (user.getApprovalStatus() != ApprovalStatus.REJECTED) {
      throw new BusinessConflictException(
          "Only a rejected registration can be reopened; current status is "
              + user.getApprovalStatus());
    }
    user.setApprovalStatus(ApprovalStatus.PENDING);
    user.setApprovedAt(null);
    user.setApprovedById(null);
    // saveAndFlush so the bumped @Version reaches the response for the no-reload admin queue.
    User saved = userRepository.saveAndFlush(user);
    userApprovalEventRepository.save(
        new UserApprovalEvent(userId, ApprovalDecision.REOPENED, reason, adminId));
    log.info("Registration {} reopened by admin {} (REJECTED -> PENDING)", userId, adminId);
    return saved;
  }

  /**
   * Approves a pending registration: moves it to {@link ApprovalStatus#ACTIVE}, stamps the
   * approving admin + time, and writes an audit row. The user's Basetool roles/units stay manually
   * managed (Track 1) — approval grants no roles by itself. Optimistic-locking via {@code version}.
   *
   * @param userId the registration to approve
   * @param version the optimistic-lock version echoed back from the admin queue; {@code null}
   *     bypasses the check
   * @param adminId the approving admin's id (for the audit row)
   * @return the now-active user
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user is
   *     unknown
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  @NotNull
  public User approveUser(@NotNull UUID userId, @Nullable Long version, @NotNull UUID adminId) {
    User user = decide(userId, version, ApprovalStatus.ACTIVE, adminId);
    userApprovalEventRepository.save(
        new UserApprovalEvent(userId, ApprovalDecision.APPROVED, null, adminId));
    // REQ-NOTIF-014: notify the user by e-mail after commit. Best-effort and off-thread — the
    // after-commit listener swallows any mail failure so it never affects this approval.
    eventPublisher.publishEvent(
        new UserApprovalDecidedEvent(userId, true, user.getEmail(), user.getEffectiveName(), null));
    return user;
  }

  /**
   * Rejects a pending registration: moves it to {@link ApprovalStatus#REJECTED} (the user keeps no
   * authorities and is routed to the waiting page), stamps the deciding admin + time, and writes an
   * audit row carrying the optional reason. Optimistic-locking via {@code version}.
   *
   * @param userId the registration to reject
   * @param reason optional free-text reason recorded in the audit; may be {@code null}
   * @param version the optimistic-lock version echoed back from the admin queue; {@code null}
   *     bypasses the check
   * @param adminId the deciding admin's id (for the audit row)
   * @return the now-rejected user
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user is
   *     unknown
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  @NotNull
  public User rejectUser(
      @NotNull UUID userId,
      @Nullable String reason,
      @Nullable Long version,
      @NotNull UUID adminId) {
    User user = decide(userId, version, ApprovalStatus.REJECTED, adminId);
    userApprovalEventRepository.save(
        new UserApprovalEvent(userId, ApprovalDecision.REJECTED, reason, adminId));
    // REQ-NOTIF-014: notify the user by e-mail after commit, including the admin's reason. Best-
    // effort and off-thread — the after-commit listener swallows any mail failure.
    eventPublisher.publishEvent(
        new UserApprovalDecidedEvent(
            userId, false, user.getEmail(), user.getEffectiveName(), reason));
    return user;
  }

  /**
   * Shared approve/reject body: loads the user, checks the optimistic-lock version, stamps the new
   * status + deciding admin + time, and persists (saveAndFlush so the bumped {@code @Version}
   * reaches the response for the no-reload admin queue).
   *
   * @param userId the registration to decide
   * @param version the optimistic-lock version; {@code null} bypasses the check
   * @param newStatus the target status ({@link ApprovalStatus#ACTIVE} or {@link
   *     ApprovalStatus#REJECTED})
   * @param adminId the deciding admin's id
   * @return the persisted user
   */
  private User decide(UUID userId, @Nullable Long version, ApprovalStatus newStatus, UUID adminId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "User not found"));
    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, userId);
    // State-transition guard (PR review #3): only a still-PENDING registration may be approved or
    // rejected. Acting on an already-ACTIVE member would silently strip their authorities and trap
    // them on the waiting page; an already-REJECTED row is a stale double-action. Either is a 409.
    if (user.getApprovalStatus() != ApprovalStatus.PENDING) {
      throw new BusinessConflictException(
          "Only a pending registration can be decided; current status is "
              + user.getApprovalStatus());
    }
    user.setApprovalStatus(newStatus);
    user.setApprovedAt(Instant.now());
    user.setApprovedById(adminId);
    return userRepository.saveAndFlush(user);
  }

  /**
   * Links a pending Discord registration onto an existing account (REQ-SEC-026): the Discord
   * federated identity is moved from the throwaway Discord-registered user onto {@code
   * targetUserId} in Keycloak, the throwaway Keycloak + {@code app_user} rows are removed, and the
   * surviving account gains the {@code discord_user_id} (+ captured guild nickname). This is the
   * admin-driven resolution for a member who already had an account but registered anew via Discord
   * — typically because their Discord handle differs from their in-app name, so the automatic
   * collision check never recognised them and let the registration reach the queue.
   *
   * <p><strong>Non-transactional orchestrator.</strong> The Keycloak writes are external
   * side-effects that cannot roll back with a database transaction, so this method runs outside any
   * transaction ({@link Propagation#NOT_SUPPORTED}). It moves the identity onto the target, commits
   * the database merge through the self-proxied {@link #completeLinkTransactionally}, and only
   * <em>then</em> deletes the throwaway Keycloak user. Deleting it <em>last</em> — after the DB is
   * consistent — is what makes a retry safe: if the DB merge fails and rolls back, the throwaway
   * Keycloak user still exists, so the next attempt re-reads its identity cleanly rather than
   * stranding the registration. Each Keycloak write is itself idempotent (a re-link of the same
   * identity and a delete of an already-gone user both succeed).
   *
   * <p>The incoming Discord snowflake is resolved <em>authoritatively from Keycloak</em> ({@link
   * KeycloakService#readDiscordLink}) with a fallback to the persisted local {@code
   * discord_user_id} (see {@link #resolveDiscordLink}): the Keycloak read makes linking work even
   * when the claim mapper never persisted the id locally, and the local fallback recovers a
   * registration whose throwaway Keycloak user was already deleted by an earlier partial failure
   * (the reported conrad7247/MardukSedras case, stranded by the missing {@code LINKED}
   * check-constraint value — see V223).
   *
   * @param pendingId the pending Discord registration to link away
   * @param targetUserId the existing account to link the Discord identity into
   * @param version the pending registration's optimistic-lock version; {@code null} bypasses the
   *     check
   * @param adminId the acting admin's id (recorded in the audit)
   * @return the surviving target account, now carrying the Discord link
   * @throws NotFoundException when the pending registration or the target account is unknown
   * @throws BusinessConflictException when the pending row is no longer PENDING, the target is not
   *     a distinct active account, the target is already Discord-linked, or the pending
   *     registration has no Discord identity to move
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @NotNull
  public User linkRegistrationToExistingAccount(
      @NotNull UUID pendingId,
      @NotNull UUID targetUserId,
      @Nullable Long version,
      @NotNull UUID adminId) {
    User pending =
        userRepository
            .findById(pendingId)
            .orElseThrow(() -> new NotFoundException("Pending registration not found"));
    OptimisticLock.checkOptionalClient(pending.getVersion(), version, User.class, pendingId);
    if (pending.getApprovalStatus() != ApprovalStatus.PENDING) {
      throw new BusinessConflictException(
          "Only a pending registration can be linked; current status is "
              + pending.getApprovalStatus());
    }
    if (targetUserId.equals(pendingId)) {
      throw new BusinessConflictException("A registration cannot be linked to itself");
    }
    User target =
        userRepository
            .findById(targetUserId)
            .orElseThrow(() -> new NotFoundException("Target account not found"));
    if (target.getApprovalStatus() != ApprovalStatus.ACTIVE) {
      throw new BusinessConflictException("The target account must be an active account");
    }
    if (target.getDiscordUserId() != null && !target.getDiscordUserId().isBlank()) {
      throw new BusinessConflictException(
          "The target account is already linked to a Discord account");
    }

    // Resolve the incoming Discord identity: authoritatively from Keycloak (works even when the
    // discord_user_id claim mapper never persisted it onto app_user — the original motivation),
    // falling back to the persisted local discord_user_id when Keycloak no longer knows the pending
    // user. The fallback makes the flow recoverable after a partial failure that already removed
    // the throwaway Keycloak user (the delete below is non-transactional and does not roll back),
    // so a retry can still complete instead of stranding the registration on an empty Keycloak
    // read.
    KeycloakService.DiscordLink link = resolveDiscordLink(pending);
    String guildNickname = pending.getDiscordGuildNickname();

    // Move the identity onto the target (idempotent: a re-link of the same snowflake is a no-op),
    // then commit the database merge, and only THEN drop the throwaway Keycloak user. Deleting the
    // throwaway user LAST — after the DB is consistent — is what makes a retry safe: if the DB
    // merge fails and rolls back, the pending Keycloak user still exists, so the next attempt reads
    // its identity cleanly. The delete is itself idempotent (a 404 for an already-gone user = ok).
    keycloakService.linkDiscordIdentity(targetUserId, link.userId(), link.userName());
    User result =
        selfProvider
            .getObject()
            .completeLinkTransactionally(
                pendingId, targetUserId, link.userId(), guildNickname, adminId);
    keycloakService.deleteUser(pendingId);
    return result;
  }

  /**
   * Resolves the Discord identity to move off a pending registration, preferring the authoritative
   * Keycloak federated identity and falling back to the locally persisted {@code discord_user_id}
   * when Keycloak no longer knows the pending user. The Keycloak read covers the case where the
   * {@code discord_user_id} claim mapper never persisted the snowflake locally; the local fallback
   * covers recovery after a partial link failure has already deleted the throwaway Keycloak user
   * (its {@code app_user} row, and thus the snowflake, survives the rolled-back DB half). The
   * fallback carries the pending row's {@code username} as the Discord username — for a Discord
   * registration that is the Discord handle — which {@link KeycloakService#linkDiscordIdentity}
   * treats as optional.
   *
   * @param pending the managed pending registration being linked away; never {@code null}
   * @return the Discord identity to link onto the target account
   * @throws BusinessConflictException when neither Keycloak nor the local row carries a Discord
   *     identity
   */
  @NotNull
  private KeycloakService.DiscordLink resolveDiscordLink(@NotNull User pending) {
    return keycloakService
        .readDiscordLink(pending.getId())
        .or(() -> localDiscordLink(pending))
        .orElseThrow(
            () ->
                new BusinessConflictException(
                    "The pending registration has no Discord identity to link"));
  }

  /**
   * Builds a {@link KeycloakService.DiscordLink} from the pending row's locally persisted {@code
   * discord_user_id}, or {@link Optional#empty()} when it carries none. Used only as the recovery
   * fallback of {@link #resolveDiscordLink} when Keycloak no longer knows the pending user.
   *
   * @param pending the pending registration whose local Discord snowflake to read; never {@code
   *     null}
   * @return the local Discord identity, or empty when {@code discord_user_id} is absent/blank
   */
  @NotNull
  private static Optional<KeycloakService.DiscordLink> localDiscordLink(@NotNull User pending) {
    String local = pending.getDiscordUserId();
    if (local == null || local.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new KeycloakService.DiscordLink(local.trim(), pending.getUsername()));
  }

  /**
   * The transactional database half of {@link #linkRegistrationToExistingAccount}, invoked through
   * the self-proxy so its {@link Transactional} boundary actually applies (a plain self-invocation
   * would bypass the proxy). Deletes the throwaway {@code app_user} <em>first</em> — freeing the
   * unique {@code discord_user_id} before the target claims it — via the FK-safe {@link
   * UserDeletionService#deleteUser}, after clearing its {@code inKeycloak} flag (its Keycloak user
   * was already removed in the orchestrator, and the deletion service refuses a still-in-Keycloak
   * account). It then stamps the surviving target account's {@code discord_user_id} (+ guild
   * nickname) and writes the {@link ApprovalDecision#LINKED} audit row. On a retry where the
   * throwaway row is already gone, the delete step is simply skipped.
   *
   * @param pendingId the throwaway pending registration to delete (may already be gone on a retry)
   * @param targetUserId the surviving account to stamp with the Discord link
   * @param snowflake the Discord user id (snowflake) to record on the surviving account
   * @param guildNickname the captured guild nickname to carry over, or {@code null}
   * @param adminId the acting admin's id (recorded in the audit)
   * @return the surviving target account, now carrying the Discord link
   * @throws NotFoundException when the target account is unknown
   */
  @Transactional
  @NotNull
  public User completeLinkTransactionally(
      @NotNull UUID pendingId,
      @NotNull UUID targetUserId,
      @NotNull String snowflake,
      @Nullable String guildNickname,
      @NotNull UUID adminId) {
    userRepository
        .findById(pendingId)
        .ifPresent(
            pending -> {
              // Its Keycloak user is already deleted; clear the guard so the FK-safe delete runs,
              // and flush so the row (and its unique discord_user_id) is gone before the target
              // claims the snowflake below.
              pending.setInKeycloak(false);
              userRepository.saveAndFlush(pending);
              userDeletionService.deleteUser(pendingId);
            });

    User target =
        userRepository
            .findById(targetUserId)
            .orElseThrow(() -> new NotFoundException("Target account not found"));
    target.setDiscordUserId(snowflake);
    if (guildNickname != null && !guildNickname.isBlank()) {
      target.setDiscordGuildNickname(guildNickname);
    }
    User saved = userRepository.saveAndFlush(target);
    userApprovalEventRepository.save(
        new UserApprovalEvent(targetUserId, ApprovalDecision.LINKED, null, adminId));
    log.info("Linked pending registration {} onto existing account {}", pendingId, targetUserId);
    return saved;
  }
}
