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
import de.greluc.krt.profit.basetool.backend.exception.Entities;
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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * Owns the Discord registration approval lifecycle (REQ-SEC-017): the admin queue, the audited,
 * optimistic-locked approve and reject decisions, reopening an erroneous rejection (REQ-SEC-034),
 * and the fail-safe PENDING stamping both Keycloak sync paths apply to new registrations.
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
   * Lazy self-reference so {@link #linkRegistrationToExistingAccount} can call {@link
   * #completeLinkTransactionally} through the Spring proxy.
   */
  private final ObjectProvider<UserRegistrationService> selfProvider;

  /**
   * Whether a new non-admin registration needs admin approval before gaining any authorities
   * (REQ-SEC-017); {@code true} by default. Only controlled non-prod stacks such as the e2e stack
   * set it to {@code false}.
   */
  @Value("${app.registration.require-approval:true}")
  private boolean requireApproval = true;

  /**
   * Stamps a brand-new non-admin registration {@link ApprovalStatus#PENDING} under the fail-safe
   * approval gate (REQ-SEC-017), for both Keycloak sync paths.
   *
   * <p>Works on the managed {@code user} inside the caller's transaction and saves nothing itself.
   * The caller marks its change flag and publishes the admin notification (REQ-NOTIF-012) when this
   * returns {@code true}.
   *
   * @param user the managed user being reconciled; never {@code null}
   * @param created whether this reconciliation just created the row (only new rows are gated)
   * @param localRoles the mapped local roles; an ADMIN among them suppresses the gate; never {@code
   *     null}
   * @return {@code true} when this call stamped a new PENDING registration
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
   * Of the given registrations' callsigns, the ones a second account already holds, for the admin
   * queue's collision marker (ADR-0142).
   *
   * <p>One query for the whole page, matching {@code username} case-insensitively.
   *
   * @param users the registrations being rendered
   * @return the lower-cased colliding callsigns; empty when none collides
   */
  @NotNull
  public Set<String> findCollidingCallsigns(@NotNull Collection<User> users) {
    Set<String> names =
        users.stream()
            .map(User::getUsername)
            .filter(Objects::nonNull)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    return names.isEmpty() ? Set.of() : userRepository.findUsernamesHeldByMoreThanOneAccount(names);
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
   * Returns the registrations an admin has rejected, oldest registration first, so an erroneous
   * rejection can be found and reversed (REQ-SEC-034).
   *
   * @return the rejected registrations, oldest registration first
   */
  @NotNull
  public List<User> findRejectedRegistrations() {
    return userRepository.findByApprovalStatusOrderByCreatedAtAsc(ApprovalStatus.REJECTED);
  }

  /**
   * Reopens a rejected registration: moves it from {@link ApprovalStatus#REJECTED} back to {@link
   * ApprovalStatus#PENDING} so it can be decided again (REQ-SEC-034).
   *
   * <p>Goes through PENDING rather than straight to ACTIVE, so {@link #decide} still decides only
   * pending rows. Clears the previous decision stamp (the history stays in {@code
   * user_approval_event}) and sends no notification.
   *
   * @param userId the rejected registration to reopen
   * @param reason optional free-text note recorded in the audit; may be {@code null}
   * @param version the optimistic-lock version echoed back from the rejected list; {@code null}
   *     bypasses the check
   * @param adminId the acting admin's id (for the audit row)
   * @return the now-pending user (with its bumped version)
   * @throws NotFoundException when the user is unknown
   * @throws BusinessConflictException when the registration is not {@link ApprovalStatus#REJECTED}
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  @NotNull
  public User reopenRegistration(
      @NotNull UUID userId,
      @Nullable String reason,
      @Nullable Long version,
      @NotNull UUID adminId) {
    User user = Entities.require(userRepository.findById(userId), "User not found");
    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, userId);
    if (user.getApprovalStatus() != ApprovalStatus.REJECTED) {
      throw new BusinessConflictException(
          "Only a rejected registration can be reopened; current status is "
              + user.getApprovalStatus());
    }
    user.setApprovalStatus(ApprovalStatus.PENDING);
    user.setApprovedAt(null);
    user.setApprovedById(null);
    User saved = userRepository.saveAndFlush(user);
    userApprovalEventRepository.save(
        new UserApprovalEvent(userId, ApprovalDecision.REOPENED, reason, adminId));
    log.info("Registration {} reopened by admin {} (REJECTED -> PENDING)", userId, adminId);
    return saved;
  }

  /**
   * Approves a pending registration: moves it to {@link ApprovalStatus#ACTIVE}, stamps the
   * approving admin and time, and writes an audit row. Grants no roles by itself.
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
    User user = Entities.require(userRepository.findById(userId), "User not found");
    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, userId);
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
   * Links a pending Discord registration onto an existing account (REQ-SEC-026): moves the Discord
   * identity to {@code targetUserId} in Keycloak, removes the throwaway Keycloak user and {@code
   * app_user} row, and stamps the target's {@code discord_user_id}.
   *
   * <p>Runs outside any transaction: after moving the identity it commits the database half through
   * {@link #completeLinkTransactionally} and deletes the throwaway Keycloak user last, so a failed
   * attempt can be retried. The snowflake is read from Keycloak, falling back to the local {@code
   * discord_user_id} (see {@link #resolveDiscordLink}).
   *
   * @param pendingId the pending Discord registration to link away
   * @param targetUserId the existing account to link the Discord identity into
   * @param version the pending registration's optimistic-lock version; {@code null} bypasses the
   *     check
   * @param adminId the acting admin's id (recorded in the audit)
   * @return the surviving target account, now carrying the Discord link
   * @throws NotFoundException when the pending registration or the target account is unknown
   * @throws BusinessConflictException when the pending row is no longer PENDING, the target is not
   *     a distinct active account or is already Discord-linked, or there is no Discord identity to
   *     move
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
        Entities.require(userRepository.findById(pendingId), "Pending registration not found");
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
        Entities.require(userRepository.findById(targetUserId), "Target account not found");
    if (target.getApprovalStatus() != ApprovalStatus.ACTIVE) {
      throw new BusinessConflictException("The target account must be an active account");
    }
    if (target.getDiscordUserId() != null && !target.getDiscordUserId().isBlank()) {
      throw new BusinessConflictException(
          "The target account is already linked to a Discord account");
    }

    KeycloakService.DiscordLink link = resolveDiscordLink(pending);
    String guildNickname = pending.getDiscordGuildNickname();

    keycloakService.unlinkDiscordIdentity(pendingId);
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
   * Resolves the Discord identity to move off a pending registration: the Keycloak federated
   * identity, else the locally persisted {@code discord_user_id} with the row's {@code username} as
   * Discord username.
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
   * the self-proxy.
   *
   * <p>Deletes the throwaway {@code app_user} first, freeing the unique {@code discord_user_id},
   * via {@link UserDeletionService#deleteUser(UUID, UserDeletionService.KeycloakPresenceCheck)}
   * with the presence probe waived, then stamps the target's {@code discord_user_id} and guild
   * nickname and writes the {@link ApprovalDecision#LINKED} audit row. On a retry where the
   * throwaway row is already gone, the delete is skipped.
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
              pending.setInKeycloak(false);
              userRepository.saveAndFlush(pending);
              userDeletionService.deleteUser(
                  pendingId,
                  UserDeletionService.KeycloakPresenceCheck
                      .WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER);
            });

    User target =
        Entities.require(userRepository.findById(targetUserId), "Target account not found");
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
