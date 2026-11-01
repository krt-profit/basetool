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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.CapabilityFlagsAdvice.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Method-security gate for the officer-only surfaces, answering from the backend's officer flags of
 * the active context (REQ-SEC-047) instead of a role in the token.
 *
 * <p>Presentation-level only: the backend re-checks officer reach in the owning unit of every
 * target. Used in {@code @PreAuthorize} as {@code @officerSurfaceGate.canOpenLeitung()}; an admin
 * passes, an anonymous caller or a failed backend read does not.
 */
@Component("officerSurfaceGate")
@RequiredArgsConstructor
public class OfficerSurfaceGate {

  /** {@code @PreAuthorize} expression for {@link #canOpenLeitung()}. */
  public static final String CAN_OPEN_LEITUNG = "@officerSurfaceGate.canOpenLeitung()";

  /** {@code @PreAuthorize} expression for {@link #canManagePromotions()}. */
  public static final String CAN_MANAGE_PROMOTIONS = "@officerSurfaceGate.canManagePromotions()";

  /** {@code @PreAuthorize} expression for {@link #hasOfficerReach()}. */
  public static final String HAS_OFFICER_REACH = "@officerSurfaceGate.hasOfficerReach()";

  private final LayoutContextLoader layoutContextLoader;
  private final FrontendAuthHelperService authHelper;
  private final HttpServletRequest request;

  /**
   * Whether the caller may open the Leitung page and its appointment actions.
   *
   * @return {@code true} for an admin and for a caller the backend offers the Leitung page
   */
  public boolean canOpenLeitung() {
    return authHelper.isAdmin() || capabilities().canOpenLeitung();
  }

  /**
   * Whether the caller may use promotion management (evaluations, topics, rank requirements).
   *
   * @return {@code true} for an admin and for a caller the backend offers promotion management
   */
  public boolean canManagePromotions() {
    return authHelper.isAdmin() || capabilities().canManagePromotions();
  }

  /**
   * Whether the caller holds officer reach in the active context.
   *
   * @return {@code true} for an admin and for a caller holding reach in the pinned unit, or in any
   *     unit when none is pinned
   */
  public boolean hasOfficerReach() {
    return authHelper.isAdmin() || capabilities().hasOfficerReach();
  }

  private CapabilitiesResponse capabilities() {
    return layoutContextLoader.capabilities(request);
  }
}
