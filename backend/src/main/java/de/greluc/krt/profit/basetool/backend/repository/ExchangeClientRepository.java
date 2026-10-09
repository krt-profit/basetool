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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Access to the exchange client registry (REQ-XCH-003). */
public interface ExchangeClientRepository extends JpaRepository<ExchangeClient, UUID> {

  /**
   * JPQL constructor projection pairing a registered exchange client's id with its display name
   * (REQ-INV-054).
   *
   * @param clientId the Keycloak client id the registry row carries
   * @param displayName the product name the registry shows for it
   */
  record ExchangeClientDisplayName(String clientId, String displayName) {}

  /**
   * Loads every registry client with its capabilities in one query, ordered by client id.
   *
   * @return all clients
   */
  @EntityGraph(attributePaths = "capabilities")
  @Query("SELECT c FROM ExchangeClient c ORDER BY c.clientId")
  List<ExchangeClient> findAllWithCapabilities();

  /**
   * Loads one client with its capabilities.
   *
   * @param id the registry id
   * @return the client, or empty
   */
  @EntityGraph(attributePaths = "capabilities")
  @Query("SELECT c FROM ExchangeClient c WHERE c.id = :id")
  Optional<ExchangeClient> findWithCapabilitiesById(@Param("id") UUID id);

  /**
   * Tells whether a client id is already registered.
   *
   * @param clientId the Keycloak client id
   * @return {@code true} when a row carries it
   */
  boolean existsByClientId(String clientId);

  /**
   * Loads one client by its Keycloak client id, with its capabilities.
   *
   * @param clientId the Keycloak client id
   * @return the client, or empty
   */
  @EntityGraph(attributePaths = "capabilities")
  @Query("SELECT c FROM ExchangeClient c WHERE c.clientId = :clientId")
  Optional<ExchangeClient> findWithCapabilitiesByClientId(@Param("clientId") String clientId);

  /**
   * Lists every registered client id, whatever its status.
   *
   * @return the client ids
   */
  @Query("SELECT c.clientId FROM ExchangeClient c")
  List<String> findAllClientIds();

  /**
   * Reads the display names of the registered clients among the given ids in one query; an id no
   * registry row carries yields no row.
   *
   * @param clientIds the Keycloak client ids to look up
   * @return one pair per registered id
   */
  @Query(
      "SELECT new de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository"
          + "$ExchangeClientDisplayName(c.clientId, c.displayName)"
          + " FROM ExchangeClient c WHERE c.clientId IN :clientIds")
  List<ExchangeClientDisplayName> findDisplayNamesByClientIdIn(
      @Param("clientIds") Collection<String> clientIds);
}
