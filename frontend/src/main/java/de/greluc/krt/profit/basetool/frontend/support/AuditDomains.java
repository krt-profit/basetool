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
 * The audit-log tabs, in display order — the frontend's single copy of the backend's {@code
 * AuditDomain} enum plus the {@code BANK} trail that predates it (REQ-AUDIT-001, ADR-0037).
 *
 * <p>It lives here rather than in a controller because two of them need it and they had already
 * drifted: the page rendered a {@code MARKET} tab that the export/purge proxy's own copy of the
 * list rejected, so that tab's PDF, JSON and retention-purge buttons answered {@code 400} while
 * every other tab worked. Sharing the list is what stops the next tab from repeating it.
 *
 * <p>The frontend holds no backend beans, so this cannot be derived from the enum. Adding a value
 * to {@code AuditDomain} means adding it here, to the event-type map the filter dropdown renders,
 * and to the {@code admin.audit.domain.*} message keys.
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
          "MARKET");

  /** Non-instantiable holder of the shared tab list. */
  private AuditDomains() {}
}
