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
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

/**
 * Answers one pin-aware capability flag of the backend for the current request: an admin passes
 * without a call, the answer is read once and memoised on the request, and any failure reads as
 * "not granted".
 *
 * <p>The shared core of the SpEL gate beans ({@link MissionManagerGate}, {@link LogisticianGate}).
 */
@Slf4j
final class CapabilityGate {

  /** The backend endpoint answering the caller's capability flags. */
  static final String CAPABILITIES_PATH = "/api/v1/me/capabilities";

  private final BackendApiClient backendApiClient;
  private final FrontendAuthHelperService authHelper;
  private final HttpServletRequest request;
  private final String requestAttribute;
  private final Predicate<CapabilitiesResponse> flag;

  /**
   * Creates the gate for one flag.
   *
   * @param backendApiClient the single seam to the backend
   * @param authHelper answers whether the caller holds {@code ADMIN}
   * @param request the current request, which carries the memo
   * @param requestAttribute the request attribute holding the memoised answer
   * @param flag selects the flag out of the backend's answer
   */
  CapabilityGate(
      BackendApiClient backendApiClient,
      FrontendAuthHelperService authHelper,
      HttpServletRequest request,
      String requestAttribute,
      Predicate<CapabilitiesResponse> flag) {
    this.backendApiClient = backendApiClient;
    this.authHelper = authHelper;
    this.request = request;
    this.requestAttribute = requestAttribute;
    this.flag = flag;
  }

  /**
   * Whether the flag is on for the caller in the active context; always {@code true} for an admin.
   *
   * @return {@code true} iff the caller holds the capability
   */
  boolean isGranted() {
    if (authHelper.isAdmin()) {
      return true;
    }
    if (request.getAttribute(requestAttribute) instanceof Boolean memo) {
      return memo;
    }
    boolean granted = fetch();
    request.setAttribute(requestAttribute, granted);
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
      return capabilities != null && flag.test(capabilities);
    } catch (Exception ex) {
      log.debug("Failed to resolve the capability flag {}", requestAttribute, ex);
      return false;
    }
  }
}
