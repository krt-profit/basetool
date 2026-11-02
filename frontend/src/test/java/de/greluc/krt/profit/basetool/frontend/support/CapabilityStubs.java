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

import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.CapabilityFlagsAdvice.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader.MeLayoutResponse;

/**
 * Stubs the capability answers of a mocked {@link BackendApiClient}: the capabilities endpoint the
 * gate beans read and the layout endpoint the model advice reads.
 */
public final class CapabilityStubs {

  private static final String CAPABILITIES_PATH = "/api/v1/me/capabilities";

  private CapabilityStubs() {}

  /**
   * Makes the backend report a Logistician grant in the active context.
   *
   * @param backendApiClient the mocked kernel client
   */
  public static void logistician(BackendApiClient backendApiClient) {
    report(backendApiClient, LayoutResponses.logistician());
  }

  /**
   * Makes the backend report the given capability flags on both endpoints.
   *
   * @param backendApiClient the mocked kernel client
   * @param capabilities the flags to report
   */
  public static void report(BackendApiClient backendApiClient, CapabilitiesResponse capabilities) {
    when(backendApiClient.get(CAPABILITIES_PATH, CapabilitiesResponse.class))
        .thenReturn(capabilities);
    when(backendApiClient.get(LayoutResponses.PATH, MeLayoutResponse.class))
        .thenReturn(LayoutResponses.withCapabilities(capabilities));
  }
}
