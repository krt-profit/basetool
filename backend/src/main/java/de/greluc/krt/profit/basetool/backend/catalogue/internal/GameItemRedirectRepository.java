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

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Resolves retained local ids without changing source identifiers. */
public interface GameItemRedirectRepository extends JpaRepository<GameItemRedirect, UUID> {

  /**
   * Resolves a retired local id in one hop.
   *
   * @param id caller's local catalogue id
   * @return its surviving local id when retired
   */
  @Query("SELECT r.canonicalItem.id FROM GameItemRedirect r WHERE r.id = :id")
  Optional<UUID> findCanonicalId(@Param("id") UUID id);

  /**
   * Captures source-owned catalogue evidence before either identity is changed.
   *
   * @param retiredId duplicate row
   * @param survivorId canonical row
   * @return JSON preserving both items and their prices and stat values
   */
  @Query(
      value =
          """
          SELECT jsonb_build_object(
            'retiredItem', to_jsonb(old_item), 'survivingItem', to_jsonb(new_item),
            'retiredPrices', (SELECT COALESCE(jsonb_agg(to_jsonb(p)), '[]'::jsonb)
                              FROM game_item_price p WHERE p.game_item_id = :retiredId),
            'survivingPrices', (SELECT COALESCE(jsonb_agg(to_jsonb(p)), '[]'::jsonb)
                                FROM game_item_price p WHERE p.game_item_id = :survivorId),
            'retiredStats', (SELECT COALESCE(jsonb_agg(to_jsonb(s)), '[]'::jsonb)
                             FROM game_item_stat s WHERE s.game_item_id = :retiredId),
            'survivingStats', (SELECT COALESCE(jsonb_agg(to_jsonb(s)), '[]'::jsonb)
                               FROM game_item_stat s WHERE s.game_item_id = :survivorId)
          )::text FROM game_item old_item JOIN game_item new_item ON new_item.id = :survivorId
          WHERE old_item.id = :retiredId
          """,
      nativeQuery = true)
  String snapshot(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Flattens existing aliases when their survivor is itself merged.
   *
   * @param retiredId survivor being retired
   * @param survivorId new surviving item
   * @return changed aliases
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE game_item_redirect SET canonical_id = :survivorId,
            version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE canonical_id = :retiredId
          """,
      nativeQuery = true)
  int repoint(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);
}
