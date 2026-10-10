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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The installations' links of their ship ids to the members' ships (REQ-XCH-017). */
public interface ExchangeShipLinkRepository extends JpaRepository<ExchangeShipLink, UUID> {

  /**
   * Finds the link of one external id.
   *
   * @param userId the member
   * @param clientId the client
   * @param installationKey the installation
   * @param externalId the installation's id for the ship
   * @return the link, if the id is linked
   */
  Optional<ExchangeShipLink> findByUserIdAndClientIdAndInstallationKeyAndExternalId(
      UUID userId, String clientId, String installationKey, String externalId);

  /**
   * Finds an installation's link of one server ship.
   *
   * @param userId the member
   * @param clientId the client
   * @param installationKey the installation
   * @param shipId the server ship
   * @return the link, if the ship is linked
   */
  Optional<ExchangeShipLink> findByUserIdAndClientIdAndInstallationKeyAndShipId(
      UUID userId, String clientId, String installationKey, UUID shipId);

  /**
   * Lists an installation's links of the given ships.
   *
   * @param userId the member
   * @param clientId the client
   * @param installationKey the installation
   * @param shipIds the server ships
   * @return the links among them
   */
  List<ExchangeShipLink> findByUserIdAndClientIdAndInstallationKeyAndShipIdIn(
      UUID userId, String clientId, String installationKey, Collection<UUID> shipIds);
}
