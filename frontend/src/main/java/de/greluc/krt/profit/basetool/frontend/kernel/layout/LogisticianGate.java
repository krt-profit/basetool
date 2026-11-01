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
import org.springframework.stereotype.Component;

/**
 * Answers whether the caller holds a Logistician grant in the active context, for the inventory,
 * refinery and job-order write endpoints and pages; the backend's pin-aware {@code
 * isLogisticianOrAbove} flag decides, never the flat role.
 *
 * <p>Referenced from SpEL as {@code @logisticianGate}. The answer is read once per request and
 * memoised on it; any failure reads as "not granted". The backend re-checks the owning unit of
 * every write.
 */
@Component(LogisticianGate.BEAN_NAME)
public class LogisticianGate {

  /** The bean name the {@code @PreAuthorize} expressions reference. */
  public static final String BEAN_NAME = "logisticianGate";

  /** The backend endpoint answering the caller's capability flags. */
  static final String CAPABILITIES_PATH = CapabilityGate.CAPABILITIES_PATH;

  private final CapabilityGate gate;

  /**
   * Creates the gate.
   *
   * @param backendApiClient the single seam to the backend
   * @param authHelper answers whether the caller holds {@code ADMIN}
   * @param request the current request, which carries the memo
   */
  public LogisticianGate(
      BackendApiClient backendApiClient,
      FrontendAuthHelperService authHelper,
      HttpServletRequest request) {
    this.gate =
        new CapabilityGate(
            backendApiClient,
            authHelper,
            request,
            LogisticianGate.class.getName() + ".granted",
            CapabilitiesResponse::isLogisticianOrAbove);
  }

  /**
   * Whether the caller holds a Logistician grant in the pinned unit, or in any unit when none is
   * pinned; always {@code true} for an admin.
   *
   * @return {@code true} iff the caller may handle Lager, refinery and Auftrag data in the active
   *     context
   */
  public boolean isGranted() {
    return gate.isGranted();
  }
}
