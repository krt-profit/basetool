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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ItemShopAvailabilityDto;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and stores the P4K shops (REQ-P4K-042…044). The terminal link is mapped read only on {@link
 * Shop}, so only {@link #autoLinkTerminals} and {@link #manualLinkTerminal} write it.
 */
public interface ShopRepository extends JpaRepository<Shop, UUID> {

  /**
   * Writes an APPLY's terminal links of many shops in one statement, leaving every shop an admin
   * linked.
   *
   * @param shopIds the shops, a {@code uuid[]} literal ({@link UuidArrayLiteral})
   * @param terminalIds the linked terminal of each shop in the same order, a {@code NULL} unlinking
   * @return the rows written; a manual link is not among them
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE shop s SET terminal_id = v.terminal_id
          FROM unnest(CAST(:shopIds AS UUID[]), CAST(:terminalIds AS UUID[])) AS v(id, terminal_id)
          WHERE s.id = v.id AND s.terminal_link_manual = FALSE
          """,
      nativeQuery = true)
  int autoLinkTerminals(@Param("shopIds") String shopIds, @Param("terminalIds") String terminalIds);

  /**
   * Writes an admin's terminal link of a shop when it still holds the link the admin saw.
   *
   * @param shopId the shop
   * @param terminalId the new link, or {@code null}
   * @param manual whether the link is the admin's ({@code false} hands it back to the import)
   * @param expectedTerminalId the link the admin saw, or {@code null}
   * @param expectedManual the manual flag the admin saw
   * @return {@code 1} when the shop held that link and was written, else {@code 0}
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE shop SET terminal_id = CAST(:terminalId AS UUID), terminal_link_manual = :manual
          WHERE id = :shopId AND terminal_link_manual = :expectedManual
            AND terminal_id IS NOT DISTINCT FROM CAST(:expectedTerminalId AS UUID)
          """,
      nativeQuery = true)
  int manualLinkTerminal(
      @Param("shopId") UUID shopId,
      @Param("terminalId") @Nullable UUID terminalId,
      @Param("manual") boolean manual,
      @Param("expectedTerminalId") @Nullable UUID expectedTerminalId,
      @Param("expectedManual") boolean expectedManual);

  /**
   * Lists the shops whose known item list names an item, neither stamped nor hidden, each with its
   * linked terminal's name and its place's names (REQ-P4K-044).
   *
   * @param gameItemId the item
   * @return one row per shop, ordered by star system and name
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.catalogue.api.ItemShopAvailabilityDto(
        s.id, t.name, s.registryName, s.entityName, s.starSystem, o.name, o.nameDe)
      FROM ShopItem si
      JOIN Shop s ON s.id = si.shopId
      LEFT JOIN Terminal t ON t.id = s.terminalId
      LEFT JOIN StarmapObject o ON o.id = s.starmapObjectId
      WHERE si.gameItemId = :gameItemId
        AND s.p4kDeletedAt IS NULL AND s.p4kHiddenAt IS NULL
      ORDER BY s.starSystem, s.registryName, s.entityName
      """)
  List<ItemShopAvailabilityDto> findAvailability(@Param("gameItemId") UUID gameItemId);

  /**
   * Pages the shops for the admin list, filtered by star system, a fragment of either name and the
   * link state, each filter skipped when {@code null} or {@code false}.
   *
   * @param starSystem the star system, lower-case, or {@code null}
   * @param pattern a lower-case {@code LIKE} pattern with escaped wildcards, or {@code null}
   * @param unlinkedOnly whether only shops without a terminal are listed
   * @param pageable the page and its sort
   * @return the page of shops
   */
  @Query(
      value =
          """
          SELECT s FROM Shop s
          WHERE (cast(:starSystem as string) IS NULL
                 OR LOWER(s.starSystem) = cast(:starSystem as string))
            AND (cast(:pattern as string) IS NULL
                 OR LOWER(s.entityName) LIKE cast(:pattern as string) ESCAPE '\\'
                 OR LOWER(s.registryName) LIKE cast(:pattern as string) ESCAPE '\\')
            AND (:unlinkedOnly = false OR s.terminalId IS NULL)
          """,
      countQuery =
          """
          SELECT COUNT(s) FROM Shop s
          WHERE (cast(:starSystem as string) IS NULL
                 OR LOWER(s.starSystem) = cast(:starSystem as string))
            AND (cast(:pattern as string) IS NULL
                 OR LOWER(s.entityName) LIKE cast(:pattern as string) ESCAPE '\\'
                 OR LOWER(s.registryName) LIKE cast(:pattern as string) ESCAPE '\\')
            AND (:unlinkedOnly = false OR s.terminalId IS NULL)
          """)
  Page<Shop> search(
      @Param("starSystem") @Nullable String starSystem,
      @Param("pattern") @Nullable String pattern,
      @Param("unlinkedOnly") boolean unlinkedOnly,
      Pageable pageable);
}
