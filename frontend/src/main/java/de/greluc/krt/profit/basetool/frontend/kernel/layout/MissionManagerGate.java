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

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.CapabilityFlagsAdvice.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Answers whether the caller holds a Mission Manager grant in the active context, for the mission
 * and operation write endpoints and pages; the backend's pin-aware {@code isMissionManagerOrAbove}
 * flag decides, never the flat role.
 *
 * <p>Referenced from SpEL as {@code @missionManagerGate}. The answer is read once per request and
 * memoised on it; any failure reads as "not granted". The backend re-checks the owning unit of
 * every write.
 */
@Component(MissionManagerGate.BEAN_NAME)
@RequiredArgsConstructor
@Slf4j
public class MissionManagerGate {

  /** The bean name the {@code @PreAuthorize} expressions reference. */
  public static final String BEAN_NAME = "missionManagerGate";

  /** The backend endpoint answering the caller's capability flags. */
  static final String CAPABILITIES_PATH = "/api/v1/me/capabilities";

  /** Request attribute holding the memoised answer of the current request. */
  static final String REQUEST_ATTRIBUTE = MissionManagerGate.class.getName() + ".granted";

  /** The single seam to the backend. */
  private final BackendApiClient backendApiClient;

  /** Answers whether the caller holds {@code ADMIN}. */
  private final FrontendAuthHelperService authHelper;

  /** The current request, which carries the memo. */
  private final HttpServletRequest request;

  /**
   * Whether the caller holds a Mission Manager grant in the pinned unit, or in any unit when none
   * is pinned; always {@code true} for an admin.
   *
   * @return {@code true} iff the caller may manage missions and operations in the active context
   */
  public boolean isGranted() {
    if (authHelper.isAdmin()) {
      return true;
    }
    if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof Boolean memo) {
      return memo;
    }
    boolean granted = fetch();
    request.setAttribute(REQUEST_ATTRIBUTE, granted);
    return granted;
  }

  /**
   * Reads the flag from the backend.
   *
   * @return the backend's answer, or {@code false} on an empty answer or a failure
   */
  private boolean fetch() {
    try {
      CapabilitiesResponse capabilities =
          backendApiClient.get(CAPABILITIES_PATH, CapabilitiesResponse.class);
      return capabilities != null && capabilities.isMissionManagerOrAbove();
    } catch (Exception ex) {
      log.debug("Failed to resolve the Mission Manager capability", ex);
      return false;
    }
  }
}
