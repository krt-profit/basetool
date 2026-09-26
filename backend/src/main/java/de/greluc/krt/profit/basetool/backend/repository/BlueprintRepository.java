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

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintIdNameRow;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintProductRow;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link Blueprint}. The R4 blueprint sync upserts by {@link
 * #findByScwikiUuid(UUID)} and soft-deletes vanished recipes via {@link
 * #markScwikiDeleted(Collection, Instant)}.
 */
@Repository
public interface BlueprintRepository extends JpaRepository<Blueprint, UUID> {

  /**
   * Finds the local blueprint for an SC Wiki blueprint UUID, the blueprint sync's upsert key.
   *
   * @param scwikiUuid the SC Wiki blueprint UUID
   * @return the blueprint if a previous sync persisted it
   */
  Optional<Blueprint> findByScwikiUuid(UUID scwikiUuid);

  /**
   * Finds a blueprint by its {@code BP_CRAFT_*} key, the P4K import's fallback upsert key.
   *
   * <p>The key is not unique; the lowest {@code scwiki_uuid} wins deterministically.
   *
   * @param scwikiKey the blueprint key (e.g. {@code "BP_CRAFT_AMRS_LaserCannon_S1"})
   * @return the lowest-{@code scwiki_uuid} blueprint with that key if any exists
   */
  Optional<Blueprint> findFirstByScwikiKeyOrderByScwikiUuidAsc(String scwikiKey);

  /**
   * Returns the distinct Wiki item UUIDs referenced by blueprint ingredient lines, so the item sync
   * can also fetch items not yet in {@code game_item}.
   *
   * @return distinct non-null Wiki item UUIDs referenced by ingredient lines
   */
  @Query(
      "SELECT DISTINCT i.wikiItemUuid FROM BlueprintIngredient i WHERE i.wikiItemUuid IS NOT NULL")
  List<UUID> findReferencedItemUuids();

  /**
   * Soft-deletes every unmarked blueprint whose {@code scwiki_uuid} is not in {@code
   * seenScwikiUuids}. Callers must pass a non-empty seen set.
   *
   * @param seenScwikiUuids the blueprint UUIDs successfully processed in the current run
   * @param now timestamp to stamp on the soft-deleted rows
   * @return number of rows marked deleted
   */
  @Modifying
  @Query(
      """
      UPDATE Blueprint b SET b.scwikiDeletedAt = :now
      WHERE b.scwikiUuid IS NOT NULL
      AND b.scwikiUuid NOT IN :seenScwikiUuids
      AND b.scwikiDeletedAt IS NULL
      """)
  int markScwikiDeleted(
      @Param("seenScwikiUuids") Collection<UUID> seenScwikiUuids, @Param("now") Instant now);

  /**
   * Counts the live (non-soft-deleted) blueprints; reported as the sync's item count when the Wiki
   * answers {@code 304 Not Modified}.
   *
   * @return the number of non-tombstoned blueprints
   */
  @Query("SELECT COUNT(b) FROM Blueprint b WHERE b.scwikiDeletedAt IS NULL")
  long countLiveScwikiBlueprints();

  /**
   * Pages active blueprints without a search term.
   *
   * <p>Separate from {@link #searchActive(String, Pageable)} so no {@code null} is bound into
   * {@code LOWER(CONCAT(...))}, which PostgreSQL rejects as {@code bytea}.
   *
   * @param pageable page request (whitelisted sort)
   * @return a page of active blueprints
   */
  Page<Blueprint> findByScwikiDeletedAtIsNull(Pageable pageable);

  /**
   * Pages active blueprints whose output-item name or Wiki key contains {@code q}, ignoring case.
   *
   * @param q case-insensitive output-name / Wiki-key substring; must be non-null and non-blank
   * @param pageable page request (whitelisted sort)
   * @return a page of matching active blueprints
   */
  @Query(
      """
      SELECT b FROM Blueprint b WHERE b.scwikiDeletedAt IS NULL
      AND (LOWER(b.outputName) LIKE LOWER(CONCAT('%', :q, '%'))
      OR LOWER(b.scwikiKey) LIKE LOWER(CONCAT('%', :q, '%')))
      """)
  Page<Blueprint> searchActive(@Param("q") String q, Pageable pageable);

  /**
   * Returns the active blueprints that produce the given game item, i.e. the recipes an item-order
   * line may pick.
   *
   * @param gameItemId the produced item's id
   * @return active blueprints whose {@code outputItem} is that game item
   */
  @Query(
      "SELECT b FROM Blueprint b WHERE b.scwikiDeletedAt IS NULL AND b.outputItem.id = :gameItemId")
  List<Blueprint> findByOutputItemId(@Param("gameItemId") UUID gameItemId);

  /**
   * Returns the subset of {@code gameItemIds} that an active blueprint produces, i.e. the craftable
   * ones; the batched counterpart of {@link #findByOutputItemId(UUID)}.
   *
   * @param gameItemIds the candidate game-item ids to test
   * @return the distinct ids among them that an active blueprint outputs; empty when none are
   *     craftable or {@code gameItemIds} is empty
   */
  @Query(
      """
      SELECT DISTINCT b.outputItem.id FROM Blueprint b WHERE b.scwikiDeletedAt IS NULL
      AND b.outputItem.id IN :gameItemIds
      """)
  List<UUID> findCraftableOutputItemIds(@Param("gameItemIds") Collection<UUID> gameItemIds);

  /**
   * Pages the distinct game items orderable as item-order lines: outputs of an active blueprint
   * with at least one resolved RESOURCE ingredient.
   *
   * @param q case-insensitive item-name substring, or {@code null} for no filter
   * @param pageable page request (whitelisted sort)
   * @return a page of orderable game items
   */
  @Query(
      value =
          """
          SELECT gi FROM de.greluc.krt.profit.basetool.backend.model.GameItem gi WHERE EXISTS
          (SELECT 1 FROM Blueprint b JOIN b.ingredients i WHERE b.outputItem = gi AND
          b.scwikiDeletedAt IS NULL AND i.kind =
          de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredientKind.RESOURCE
          AND i.material IS NOT NULL) AND LOWER(gi.name) LIKE LOWER(CONCAT('%', :q, '%'))
          """,
      countQuery =
          """
          SELECT COUNT(gi) FROM de.greluc.krt.profit.basetool.backend.model.GameItem gi WHERE
          EXISTS (SELECT 1 FROM Blueprint b JOIN b.ingredients i WHERE b.outputItem = gi
          AND b.scwikiDeletedAt IS NULL AND i.kind =
          de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredientKind.RESOURCE
          AND i.material IS NOT NULL) AND LOWER(gi.name) LIKE LOWER(CONCAT('%', :q, '%'))
          """)
  Page<GameItem> findOrderableItems(@Param("q") String q, Pageable pageable);

  /**
   * Pages the distinct game items bookable as Lager item stock: outputs of at least one active
   * blueprint (REQ-INV-029); a superset of {@link #findOrderableItems(String, Pageable)}.
   *
   * @param q case-insensitive item-name substring; must be non-null ({@code ""} = no filter)
   * @param pageable page request (whitelisted {@code name} / {@code id} sort)
   * @return a page of game items with at least one active blueprint
   */
  @Query(
      value =
          """
          SELECT gi FROM de.greluc.krt.profit.basetool.backend.model.GameItem gi
          LEFT JOIN FETCH gi.manufacturer WHERE EXISTS
          (SELECT 1 FROM Blueprint b WHERE b.outputItem = gi AND b.scwikiDeletedAt IS NULL)
          AND LOWER(gi.name) LIKE LOWER(CONCAT('%', :q, '%'))
          """,
      countQuery =
          """
          SELECT COUNT(gi) FROM de.greluc.krt.profit.basetool.backend.model.GameItem gi WHERE
          EXISTS (SELECT 1 FROM Blueprint b WHERE b.outputItem = gi AND b.scwikiDeletedAt IS
          NULL) AND LOWER(gi.name) LIKE LOWER(CONCAT('%', :q, '%'))
          """)
  Page<GameItem> findItemsWithActiveBlueprint(@Param("q") String q, Pageable pageable);

  /**
   * Projects active blueprint recipes to output name, Wiki key, manufacturer name and output-item
   * id for the product search; the latter two are {@code null} when unresolved.
   *
   * @param q case-insensitive output-name substring; must be non-null ({@code ""} = no filter)
   * @return projection rows for every matching active recipe
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.BlueprintProductRow(b.outputName, b.scwikiKey, m.name, oi.id)
      FROM Blueprint b LEFT JOIN b.outputItem oi LEFT JOIN oi.manufacturer m
      WHERE b.scwikiDeletedAt IS NULL AND b.outputName IS NOT NULL
      AND LOWER(b.outputName) LIKE LOWER(CONCAT('%', :q, '%'))
      """)
  List<BlueprintProductRow> findActiveProductRows(@Param("q") String q);

  /**
   * Projects every active blueprint recipe to {@code (id, outputName)} for resolving a product key
   * to a representative recipe.
   *
   * @return id + output name for every active recipe with a non-null output name, ordered by name
   *     then Wiki key then id
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.BlueprintIdNameRow(b.id,
      b.outputName) FROM Blueprint b WHERE b.scwikiDeletedAt IS NULL AND b.outputName IS
      NOT NULL ORDER BY b.outputName ASC, b.scwikiKey ASC, b.id ASC
      """)
  List<BlueprintIdNameRow> findActiveIdNameRows();
}
