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

import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.events.UserRolesChangedEvent;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRows;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsMover;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsReassignment;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The identity module's part of the account merge: the roles follow the member, joined with the
 * target's (REQ-SEC-088); the approval history, the erasure requests and the terms acceptance stay
 * with the account.
 */
@Component
public class IdentityUserReassignment extends OwnedRowsReassignment {

  static final int POSITION = 180;

  private final UserRepository userRepository;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Declares the module's columns.
   *
   * @param mover the engine that runs the statements
   * @param userRepository bumps the role-set counters of both accounts
   * @param eventPublisher announces both role changes, so the cached authorities are evicted
   */
  public IdentityUserReassignment(
      @NotNull OwnedRowsMover mover,
      @NotNull UserRepository userRepository,
      @NotNull ApplicationEventPublisher eventPublisher) {
    super(
        mover,
        POSITION,
        List.of(OwnedRows.deduped("user_roles", "user_id", "role_id")),
        List.of(
            "app_user.approved_by_id",
            "user_approval_event.user_id",
            "user_approval_event.decided_by_id",
            "deletion_request.user_id",
            "deletion_request.decided_by_id",
            "terms_acceptance.user_id"));
    this.userRepository = userRepository;
    this.eventPublisher = eventPublisher;
  }

  /**
   * Moves the source's roles onto the target; when any moved or was dropped as a duplicate, bumps
   * both role-set counters and publishes {@link UserRolesChangedEvent} for both accounts.
   *
   * @param source the account being emptied
   * @param target the account being kept
   * @return the moved columns
   */
  @Override
  public @NotNull List<Moved> reassign(@NotNull UUID source, @NotNull UUID target) {
    List<Moved> moved = super.reassign(source, target);
    if (!moved.isEmpty()) {
      for (UUID account : List.of(source, target)) {
        userRepository.bumpRolesVersion(account);
        eventPublisher.publishEvent(new UserRolesChangedEvent(account));
      }
    }
    return moved;
  }
}
