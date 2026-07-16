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
   * Upsert key for the blueprint sync: match an inbound Wiki blueprint to its local row.
   *
   * @param scwikiUuid the SC Wiki blueprint UUID
   * @return the blueprint if a previous sync persisted it
   */
  Optional<Blueprint> findByScwikiUuid(UUID scwikiUuid);

  /**
   * Secondary upsert key for the P4K import: match an inbound P4K blueprint by its human-readable
   * {@code BP_CRAFT_*} key (= {@code scwiki_key}) when the {@code scwiki_uuid} lookup missed.
   * {@code scwiki_key} is not declared UNIQUE, so {@code findFirst} returns at most one row rather
   * than risking a {@code NonUniqueResultException}. The result is ordered by the NOT-NULL UNIQUE
   * {@code scwiki_uuid} so the pick is deterministic across runs when duplicate keys exist (rather
   * than an arbitrary database row order).
   *
   * @param scwikiKey the blueprint key (e.g. {@code "BP_CRAFT_AMRS_LaserCannon_S1"})
   * @return the lowest-{@code scwiki_uuid} blueprint with that key if any exists
   */
  Optional<Blueprint> findFirstByScwikiKeyOrderByScwikiUuidAsc(String scwikiKey);

  /**
   * Returns the distinct Wiki item UUIDs referenced by blueprint ITEM ingredient lines. Feeds the
   * R4 closure-mode item sync (§8.4): a blueprint may reference an item UEX never placed in {@code
   * game_item}, so the closure run must also fetch those uuids and create the {@code WIKI_ONLY}
   * rows. The query reads {@code BlueprintIngredient.wikiItemUuid} (always persisted, even for
   * unresolved lines).
   *
   * @return distinct non-null Wiki item UUIDs referenced by ingredient lines
   */
  @Query(
      "SELECT DISTINCT i.wikiItemUuid FROM BlueprintIngredient i WHERE i.wikiItemUuid IS NOT NULL")
  java.util.List<UUID> findReferencedItemUuids();

  /**
   * Soft-deletes every blueprint whose {@code scwiki_uuid} is NOT in {@code seenScwikiUuids} and is
   * not already marked. Gated by the caller on a non-empty seen set so a failed / empty Wiki fetch
   * never wipes the recipe graph (§8.7).
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
   * Counts the live (non-soft-deleted) blueprints — every {@code Blueprint} carries a {@code
   * scwiki_uuid} (its upsert key), so {@code scwiki_deleted_at IS NULL} is the whole live
   * population {@link #markScwikiDeleted} maintains. The R4 blueprint sync reports this as the
   * representative non-zero count when the Wiki blueprint list comes back {@code 304 Not Modified},
   * so a fully-cached healthy run is not read as a zero-item outage by {@code SyncZeroItems}
   * (#1182).
   *
   * @return the number of non-tombstoned blueprints
   */
  @Query("SELECT COUNT(b) FROM Blueprint b WHERE b.scwikiDeletedAt IS NULL")
  long countLiveScwikiBlueprints();

  /**
   * Unfiltered page of active (non-soft-deleted) blueprints for the admin blueprint page when no
   * search term is supplied. Kept separate from {@link #searchActive(String, Pageable)} so the
   * common no-filter load stays a plain indexed predicate and never binds a {@code null} into a
   * string function: passing a {@code null} named parameter into {@code LOWER(CONCAT(...))} makes
   * PostgreSQL infer {@code bytea} and fail the whole request with {@code function lower(bytea)
   * does not exist}. Sorting comes from the caller's whitelisted {@code Pageable}; the owned
   * collections stay lazy (batched on access by their {@code @BatchSize}).
   *
   * @param pageable page request (whitelisted sort)
   * @return a page of active blueprints
   */
  Page<Blueprint> findByScwikiDeletedAtIsNull(Pageable pageable);

  /**
   * Page of active (non-soft-deleted) blueprints whose output-item name or Wiki key contains {@code
   * q}, matched case-insensitively. The caller guarantees {@code q} is non-null and non-blank, so
   * the {@code LOWER(CONCAT(...))} predicate never receives a {@code null} bind (which PostgreSQL
   * would type as {@code bytea}). Sorting comes from the caller's whitelisted {@code Pageable}; the
   * owned collections stay lazy (batched on access by their {@code @BatchSize}).
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
   * Active (non-soft-deleted) blueprints that produce the given game item — the candidate recipes
   * an item-order line may pick. Used both to validate a chosen blueprint and to feed the create
   * UI's blueprint picker when an item has more than one recipe (issue #304 decision 2).
   *
   * @param gameItemId the produced item's id
   * @return active blueprints whose {@code outputItem} is that game item
   */
  @Query(
      "SELECT b FROM Blueprint b WHERE b.scwikiDeletedAt IS NULL AND b.outputItem.id = :gameItemId")
  java.util.List<Blueprint> findByOutputItemId(@Param("gameItemId") UUID gameItemId);

  /**
   * Returns the subset of {@code gameItemIds} that an active (non-soft-deleted) blueprint produces
   * — i.e. the game items that are themselves <em>craftable</em>. The batched counterpart of {@link
   * #findByOutputItemId(UUID)}: the blueprint craftability calculation (#781) resolves the bridge
   * of a recipe's ITEM ingredients across the caller's whole owned set in one query, so a craftable
   * sub-assembly (handled as its own line, never as a raw material) is told apart from a
   * non-craftable component that bridges to a {@code material} — exactly the distinction {@code
   * JobOrderItemService.bridgedMaterial} makes per ingredient.
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
  java.util.List<UUID> findCraftableOutputItemIds(
      @Param("gameItemIds") Collection<UUID> gameItemIds);

  /**
   * Page of distinct game items that are orderable as item-order lines: the output item of at least
   * one active blueprint that has a resolved RESOURCE ingredient (so a non-empty material list can
   * be derived). Items whose every blueprint resolves no usable material are excluded (issue #304
   * decision 3). An optional case-insensitive name filter narrows the picker; {@code q} must be
   * non-null and non-blank when supplied (a {@code null} bind into {@code LOWER(CONCAT(...))} types
   * as {@code bytea} on PostgreSQL).
   *
   * @param q case-insensitive item-name substring, or {@code null} for no filter
   * @param pageable page request (whitelisted sort)
   * @return a page of orderable game items
   */
  // GameItem is the query ROOT (not Blueprint) so the caller's whitelisted Pageable sort on
  // `name` resolves against gi.name — selecting b.outputItem with a Blueprint root made Spring
  // Data append `order by b.name`, which Blueprint has no attribute for (UnknownPathException).
  // The EXISTS subquery also dedups naturally, so no DISTINCT is needed. {@code q} must be
  // non-null (the caller passes "" for "no filter"): a NULL bind into LOWER(CONCAT(...)) makes
  // PostgreSQL infer bytea and fail with {@code function lower(bytea) does not exist}; an empty
  // string matches every row via the {@code %%} pattern.
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
  Page<de.greluc.krt.profit.basetool.backend.model.GameItem> findOrderableItems(
      @Param("q") String q, Pageable pageable);

  /**
   * Page of distinct game items that are <em>bookable as Lager item stock</em>: the output item of
   * at least one active (non-soft-deleted) blueprint — the item-catalog predicate of REQ-INV-029
   * (design §5.4). Deliberately a <strong>superset</strong> of {@link #findOrderableItems(String,
   * Pageable)}: no resolved-RESOURCE-ingredient requirement, because an item crafted purely from
   * sub-components is still physical stock worth tracking — such an item may simply have zero
   * allocatable orders, same as a material no order requests. An optional case-insensitive name
   * filter narrows the picker; {@code q} must be non-null ({@code ""} = no filter — a {@code null}
   * bind into {@code LOWER(CONCAT(...))} types as {@code bytea} on PostgreSQL and fails). The
   * {@code manufacturer} is eager-fetched because the Lager item reference DTO renders its name.
   *
   * @param q case-insensitive item-name substring; must be non-null ({@code ""} = no filter)
   * @param pageable page request (whitelisted {@code name} / {@code id} sort — {@code id} is the
   *     controller default's tiebreaker for equal-named UEX variants)
   * @return a page of game items with at least one active blueprint
   */
  // Mirrors findOrderableItems: GameItem is the query ROOT so the whitelisted `name` sort
  // resolves against gi.name, and the EXISTS subquery dedups without DISTINCT. The manufacturer
  // is eager-fetched via JOIN FETCH rather than @EntityGraph: Spring Data resolves an
  // @EntityGraph against the repository's domain type (Blueprint), where a `manufacturer`
  // attribute does not exist, so the annotation form throws at runtime for this foreign-root
  // query.
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
  Page<de.greluc.krt.profit.basetool.backend.model.GameItem> findItemsWithActiveBlueprint(
      @Param("q") String q, Pageable pageable);

  /**
   * Projection of active (non-soft-deleted) blueprint recipes for the user-facing product search
   * (#327), reduced to the scalars the service needs to group recipes into products: output name,
   * Wiki key, the resolved manufacturer name, and the resolved output-item id (the last two via a
   * {@code LEFT JOIN}, so they are {@code null} when the output item / manufacturer is unresolved).
   *
   * <p>The grouping into products happens in the service (the {@code product_key} is a normalized
   * form of {@code output_name} that PostgreSQL cannot compute), so this query loads the matching
   * rows and leaves the de-duplication to {@code BlueprintNameNormalizer}. The caller passes an
   * empty string for "no filter": a {@code null} bind into {@code LOWER(CONCAT(...))} would be
   * typed as {@code bytea} by PostgreSQL and fail, whereas {@code ''} matches every row via {@code
   * %%}.
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
   * Projection of every active (non-soft-deleted) blueprint recipe reduced to {@code (id,
   * outputName)}, feeding {@code BlueprintProductService}'s resolution of a normalized {@code
   * product_key} to a representative recipe id for the Personal Inventory recipe view (#327). The
   * {@code product_key} is a Java-computed normalization of {@code output_name} (see {@code
   * BlueprintNameNormalizer}) that PostgreSQL cannot reproduce, so the grouping happens in the
   * service; the {@code ORDER BY} makes the chosen representative deterministic across calls.
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
