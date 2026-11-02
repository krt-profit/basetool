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

package de.greluc.krt.profit.basetool.backend.app.api;

import de.greluc.krt.profit.basetool.backend.identity.api.IdentityMetricNames;

/**
 * Backend {@code basetool_*} metric names, tag keys and bounded tag values of the app module:
 * refused-subject windows of the security configuration (REQ-OBS-011). The names and values are
 * part of the dashboards and alert rules under {@code monitoring/}.
 */
public final class AppMetricNames {

  /**
   * Gauge: how many distinct subjects the consent gate refused in the last 15 minutes
   * (REQ-SEC-028); the counterpart to {@link IdentityMetricNames#TERMS_ACCEPTED_USERS}.
   *
   * <p>Untagged (REQ-OBS-011). Per process — read it with {@code max()}, never {@code sum()}.
   */
  public static final String TERMS_REFUSED_SUBJECTS = "basetool.terms.refused.subjects";

  /**
   * Gauge: distinct subjects the role gate refused with {@code 403 NO_ROLE} in the last 15 minutes
   * (REQ-SEC-053); the counterpart of {@link #TERMS_REFUSED_SUBJECTS}.
   *
   * <p>Untagged (REQ-OBS-011). Per process — read it with {@code max()}, never {@code sum()}.
   */
  public static final String NO_ROLE_REFUSED_SUBJECTS = "basetool.norole.refused.subjects";

  private AppMetricNames() {}
}
