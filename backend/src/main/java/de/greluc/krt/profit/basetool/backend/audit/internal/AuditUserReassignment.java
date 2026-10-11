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

package de.greluc.krt.profit.basetool.backend.audit.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsMover;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsReassignment;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/** The audit module's part of the account merge: the trail stays with the act (REQ-AUDIT-001). */
@Component
public class AuditUserReassignment extends OwnedRowsReassignment {

  static final int POSITION = 160;

  /**
   * Declares the module's columns.
   *
   * @param mover the engine that runs the statements
   */
  public AuditUserReassignment(@NotNull OwnedRowsMover mover) {
    super(
        mover,
        POSITION,
        List.of(),
        List.of("audit_event.actor_user_id", "audit_event.target_user_id"));
  }
}
