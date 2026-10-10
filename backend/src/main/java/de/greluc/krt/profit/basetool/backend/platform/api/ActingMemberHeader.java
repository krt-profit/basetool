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

package de.greluc.krt.profit.basetool.backend.platform.api;

/** Holds the header naming the member the ingest gateway is acting for (ADR-0129). */
public final class ActingMemberHeader {

  /**
   * Names the member the ingest gateway is acting for.
   *
   * <p>Must equal {@code ExchangeRelay.ON_BEHALF_OF_HEADER} in the ingest module, which {@code
   * OnBehalfOfHeaderParityTest} verifies.
   */
  public static final String ON_BEHALF_OF_HEADER = "X-Ingest-On-Behalf-Of";

  /**
   * Names the external client an exchange request comes from; honoured only on an exchange path
   * from the gateway acting for a member (REQ-XCH-010).
   */
  public static final String EXCHANGE_CLIENT_HEADER = "X-Exchange-Client";

  /**
   * Lists the capabilities the gateway checked for an exchange request, as comma-separated OAuth
   * scopes; honoured under the same conditions as {@link #EXCHANGE_CLIENT_HEADER}.
   */
  public static final String EXCHANGE_CAPABILITIES_HEADER = "X-Exchange-Capabilities";

  /**
   * Carries the base64url SHA-256 thumbprint of the installation's DPoP key the gateway verified,
   * which identifies the installation (REQ-XCH-007); honoured under the same conditions as {@link
   * #EXCHANGE_CLIENT_HEADER}.
   */
  public static final String EXCHANGE_INSTALLATION_HEADER = "X-Exchange-Installation";

  /**
   * Carries, in epoch seconds, the connection time the gateway compared with the member's
   * disconnect of the client — an offline token's {@code iat}, any other token's {@code auth_time}
   * (REQ-XCH-008); honoured under the same conditions as {@link #EXCHANGE_CLIENT_HEADER}.
   */
  public static final String EXCHANGE_CONNECTED_AT_HEADER = "X-Exchange-Connected-At";

  /** Not instantiable: a constant holder, not a component. */
  private ActingMemberHeader() {}
}
