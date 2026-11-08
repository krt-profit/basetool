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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.identity.api.IdentityMetricNames;
import de.greluc.krt.profit.basetool.backend.identity.api.RolesChangedObserver;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.events.RoleNotices;
import de.greluc.krt.profit.basetool.backend.identity.api.events.UserRolesChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leaves the traces of one role written in the Basetool, inside the writing transaction
 * (REQ-SEC-088): the observers, the audit row, the counter, the {@link UserRolesChangedEvent} that
 * evicts the member's cached authorities and the member's notice.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoleChangeRecorder {

  /** The audit detail value that marks the login-time grant of {@code app.bootstrap.admin-subs}. */
  private static final String SOURCE_BOOTSTRAP = "bootstrap";

  private final AuditRecorder auditRecorder;
  private final MeterRegistry meterRegistry;
  private final ApplicationEventPublisher eventPublisher;
  private final List<RolesChangedObserver> rolesChangedObservers;

  /**
   * Records a role an admin or the bank management assigned or withdrew.
   *
   * @param user the member, already holding the new role set
   * @param role the role
   * @param assigned whether the role was assigned rather than withdrawn
   * @param actorId the member who changed it, or {@code null} when unknown
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void changed(
      @NotNull User user, @NotNull AssignableRole role, boolean assigned, @Nullable UUID actorId) {
    record(user, role, assigned, actorId, AuditDetails.of("role", role.getCode()));
  }

  /**
   * Records the Admin role a bootstrap subject received at login (REQ-SEC-088).
   *
   * @param user the member, already holding the Admin role
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void bootstrapGranted(@NotNull User user) {
    record(
        user,
        AssignableRole.ADMIN,
        true,
        null,
        AuditDetails.of("role", AssignableRole.ADMIN.getCode()).with("source", SOURCE_BOOTSTRAP));
  }

  private void record(
      User user,
      AssignableRole role,
      boolean assigned,
      @Nullable UUID actorId,
      AuditDetails details) {
    rolesChangedObservers.forEach(
        observer -> observer.onRolesChanged(user.getId(), user.getRoles()));
    auditRecorder.record(
        assigned ? AuditEventType.USER_ROLE_ASSIGNED : AuditEventType.USER_ROLE_WITHDRAWN,
        user.getId(),
        null,
        user.getId(),
        details);
    String action =
        assigned
            ? IdentityMetricNames.ROLE_ACTION_ASSIGNED
            : IdentityMetricNames.ROLE_ACTION_WITHDRAWN;
    meterRegistry
        .counter(
            IdentityMetricNames.USER_ROLE_CHANGES,
            IdentityMetricNames.TAG_ROLE,
            role.getCode(),
            IdentityMetricNames.TAG_ACTION,
            action)
        .increment();
    eventPublisher.publishEvent(new UserRolesChangedEvent(user.getId()));
    eventPublisher.publishEvent(
        RoleNotices.roleChanged(user.getId(), role.getCode(), assigned, actorId));
    log.info("Role {} {} for user {} by actor {}", role.getCode(), action, user.getId(), actorId);
  }
}
