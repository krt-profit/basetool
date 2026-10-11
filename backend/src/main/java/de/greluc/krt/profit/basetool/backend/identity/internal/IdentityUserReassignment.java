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

import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsMover;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsReassignment;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The identity module's part of the account merge: the approval history, the erasure requests, the
 * roles (re-derived on every login, REQ-SEC-013) and the terms acceptance stay with the account.
 */
@Component
public class IdentityUserReassignment extends OwnedRowsReassignment {

  static final int POSITION = 180;

  /**
   * Declares the module's columns.
   *
   * @param mover the engine that runs the statements
   */
  public IdentityUserReassignment(@NotNull OwnedRowsMover mover) {
    super(
        mover,
        POSITION,
        List.of(),
        List.of(
            "app_user.approved_by_id",
            "user_approval_event.user_id",
            "user_approval_event.decided_by_id",
            "deletion_request.user_id",
            "deletion_request.decided_by_id",
            "user_roles.user_id",
            "terms_acceptance.user_id"));
  }
}
