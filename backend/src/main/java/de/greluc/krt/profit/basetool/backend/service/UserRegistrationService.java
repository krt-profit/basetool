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
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
}
