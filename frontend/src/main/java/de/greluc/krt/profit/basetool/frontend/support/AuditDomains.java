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

package de.greluc.krt.profit.basetool.frontend.support;

import java.util.List;

/**
 * The audit-log tabs in display order: the frontend copy of the backend's {@code AuditDomain}
 * values plus the {@code BANK} trail (REQ-AUDIT-001).
 *
 * <p>A new {@code AuditDomain} value must also be added here, to the event-type filter map and to
 * the {@code admin.audit.domain.*} message keys.
 */
public final class AuditDomains {

  /**
   * Every audit tab, in the order the page renders them: the bank trail first (it is also the
   * default tab), then the generic areas.
   */
  public static final List<String> ALL =
      List.of(
          "BANK",
          "INVENTORY",
          "JOB_ORDER",
          "REFINERY",
          "PERSONAL_INVENTORY",
          "MISSION",
          "OPERATION",
          "ROLE",
          "PROMOTION",
          "MARKET",
          "HANGAR",
          "BLUEPRINT");

  /** Non-instantiable holder of the shared tab list. */
  private AuditDomains() {}
}
