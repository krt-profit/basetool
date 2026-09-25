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

import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.projection.BlueprintOwnerProduct;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link PersonalBlueprint}. All non-admin lookups MUST use one of the
 * {@code *ByOwnerUserId*} variants to enforce the multi-user data isolation rule: a user only ever
 * sees blueprints they own.
 */
@Repository
public interface PersonalBlueprintRepository extends JpaRepository<PersonalBlueprint, UUID> {

  /**
   * Page of the blueprints owned by one user.
   *
   * @param ownerUserId {@code app_user.id} of the owner
   * @param pageable page request with a whitelisted sort
   * @return the owner's blueprints
   */
  Page<PersonalBlueprint> findAllByOwnerUserId(UUID ownerUserId, Pageable pageable);

  /**
   * Page of the blueprints owned by one user whose product name contains the given fragment
   * (case-insensitive) — backs the owned-list filter box.
   *
   * @param ownerUserId {@code app_user.id} of the owner
   * @param nameFragment case-insensitive product-name substring
   * @param pageable page request with a whitelisted sort
   * @return the owner's matching blueprints
   */
  Page<PersonalBlueprint> findAllByOwnerUserIdAndProductNameContainingIgnoreCase(
      UUID ownerUserId, String nameFragment, Pageable pageable);

  /**
   * Owner-scoped single lookup for detail / update / delete; returns empty for a foreign or unknown
   * id so the service can answer 404 without leaking another user's ownership.
   *
   * @param id the entry id
   * @param ownerUserId {@code app_user.id} of the owner
   * @return the entry if it belongs to the owner, empty otherwise
   */
  Optional<PersonalBlueprint> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

  /**
   * Owner-scoped product lookup; used by add / import to detect an existing ownership row before
   * the {@code (owner_user_id, product_key)} unique constraint fires.
   *
   * @param ownerUserId {@code app_user.id} of the owner
   * @param productKey normalized product key
   * @return the entry if the owner already owns the product, empty otherwise
   */
  Optional<PersonalBlueprint> findByOwnerUserIdAndProductKey(UUID ownerUserId, String productKey);

  /**
   * Fast owner-scoped existence check for the product, used to short-circuit duplicate adds with a
   * 409 before hitting the unique constraint.
   *
   * @param ownerUserId {@code app_user.id} of the owner
   * @param productKey normalized product key
   * @return {@code true} if the owner already owns the product
   */
  boolean existsByOwnerUserIdAndProductKey(UUID ownerUserId, String productKey);

  /**
   * Owner-scoped bulk product lookup, used to compute the "already owned" flag for a page of search
   * results and to dedupe a batch add / import in a single query.
   *
   * @param ownerUserId {@code app_user.id} of the owner
   * @param productKeys the product keys to test
   * @return the owner's entries whose product key is in the given set
   */
  List<PersonalBlueprint> findAllByOwnerUserIdAndProductKeyIn(
      UUID ownerUserId, Collection<String> productKeys);

  /**
   * Returns all owned-blueprint rows of the given owners; backs the org-unit blueprint availability
   * aggregation.
   *
   * @param ownerUserIds the {@code app_user.id}s of the in-scope owners
   * @return every owned-blueprint row whose owner is in the given set; never {@code null}
   */
  List<PersonalBlueprint> findAllByOwnerUserIdIn(Collection<UUID> ownerUserIds);

  /**
   * Projection variant of {@link #findAllByOwnerUserIdIn(Collection)} returning only owner and
   * product name, for the family-grouping aggregations (REQ-DATA-003).
   *
   * @param ownerUserIds the {@code app_user.id}s of the in-scope owners
   * @return one {@code (ownerUserId, productName)} projection per owned-blueprint row; never {@code
   *     null}
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.BlueprintOwnerProduct(b.ownerUserId, b.productName) FROM PersonalBlueprint b WHERE b.ownerUserId IN :ownerUserIds
      """)
  List<BlueprintOwnerProduct> findOwnerProductByOwnerUserIdIn(
      @Param("ownerUserIds") Collection<UUID> ownerUserIds);

  /**
   * Returns the rows of the given owners that own one product; backs the availability drill-down.
   *
   * @param productKey the normalized product key to match
   * @param ownerUserIds the {@code app_user.id}s of the in-scope owners
   * @return the matching rows (one per owning in-scope member); never {@code null}
   */
  List<PersonalBlueprint> findAllByProductKeyAndOwnerUserIdIn(
      String productKey, Collection<UUID> ownerUserIds);

  /**
   * Returns every owner's row for one product; backs the admin "all org units" availability
   * drill-down.
   *
   * <p>ADMIN-ONLY: scoped callers use {@link #findAllByProductKeyAndOwnerUserIdIn(String,
   * Collection)} to keep owner isolation.
   *
   * @param productKey the normalized product key to match
   * @return every owned-blueprint row for the product, across all owners; never {@code null}
   */
  List<PersonalBlueprint> findAllByProductKey(String productKey);

  /**
   * Returns every owner's rows for any product of a variant family; backs the admin "all org units"
   * family drill-down.
   *
   * <p>ADMIN-ONLY: scoped callers use {@link #findAllByProductKeyInAndOwnerUserIdIn(Collection,
   * Collection)} to keep owner isolation.
   *
   * @param productKeys the normalized product keys making up the family
   * @return every owned-blueprint row for any of the products, across all owners; never {@code
   *     null}
   */
  List<PersonalBlueprint> findAllByProductKeyIn(Collection<String> productKeys);

  /**
   * Returns the rows of the given owners for any product of a variant family; backs the scoped
   * family drill-down.
   *
   * @param productKeys the normalized product keys making up the family
   * @param ownerUserIds the {@code app_user.id}s of the in-scope owners
   * @return the matching rows (one per owning in-scope member × owned family product); never {@code
   *     null}
   */
  List<PersonalBlueprint> findAllByProductKeyInAndOwnerUserIdIn(
      Collection<String> productKeys, Collection<UUID> ownerUserIds);

  /**
   * Bulk owner + product lookup — backs the item job-order blueprint-coverage view: given the
   * {@code app_user.id}s of every member of the order's responsible org unit and the set of
   * normalized product keys the order's item lines resolve to, returns exactly the owned-blueprint
   * rows that match both, so the service can group them by owner and by product in one query.
   *
   * @param ownerUserIds the {@code app_user.id}s of the responsible org unit's members
   * @param productKeys the normalized product keys of the order's required items
   * @return the matching rows (one per owning member × owned required product); never {@code null}
   */
  List<PersonalBlueprint> findAllByOwnerUserIdInAndProductKeyIn(
      Collection<UUID> ownerUserIds, Collection<String> productKeys);

  /**
   * Returns the distinct {@code app_user.id} of every blueprint owner, including owners without any
   * org-unit membership; backs the admin "all org units" availability overview.
   *
   * @return every distinct {@code owner_user_id} present in the table; never {@code null}, possibly
   *     empty.
   */
  @Query("SELECT DISTINCT pb.ownerUserId FROM PersonalBlueprint pb")
  Set<UUID> findAllDistinctOwnerUserIds();

  /**
   * Bulk-removes every removable owned blueprint of one user, keeping the auto-granted defaults
   * (REQ-INV-023, REQ-INV-016).
   *
   * <p>A single set-based statement without {@code @Version} bumps; it clears the persistence
   * context afterwards.
   *
   * @param ownerUserId the {@code app_user.id} of the owner whose removable blueprints are cleared
   * @return the number of rows removed (never counts a preserved default)
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      DELETE FROM PersonalBlueprint b
      WHERE b.ownerUserId = :ownerUserId
        AND b.productKey NOT IN (SELECT d.productKey FROM DefaultBlueprint d)
      """)
  int deleteRemovableByOwnerUserId(@Param("ownerUserId") UUID ownerUserId);

  /**
   * Bulk-removes every removable owned blueprint of all users, keeping the auto-granted defaults
   * (REQ-INV-024). ADMIN-ONLY.
   *
   * <p>A single set-based statement without {@code @Version} bumps; it clears the persistence
   * context afterwards.
   *
   * @return the number of rows removed across all users (never counts a preserved default)
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      DELETE FROM PersonalBlueprint b
      WHERE b.productKey NOT IN (SELECT d.productKey FROM DefaultBlueprint d)
      """)
  int deleteAllRemovable();

  /**
   * Bulk-removes every owned blueprint of one user, defaults included, for the hard account
   * deletion (REQ-DATA-008).
   *
   * <p>Runs inside the user-deletion transaction and therefore does not clear the persistence
   * context.
   *
   * @param ownerUserId the departing owner's {@code app_user.id}
   * @return the number of rows removed, for the audit summary event
   */
  @Modifying
  @Query("DELETE FROM PersonalBlueprint b WHERE b.ownerUserId = :ownerUserId")
  int deleteAllByOwnerUserId(@Param("ownerUserId") UUID ownerUserId);

  /**
   * Inserts one {@code personal_blueprint} row for each default blueprint the user does not yet own
   * (REQ-INV-016).
   *
   * <p>Idempotent via {@code ON CONFLICT DO NOTHING}; pending persistence-context writes are
   * flushed first.
   *
   * @param ownerUserId the {@code app_user.id} of the user to provision
   * @return the number of newly inserted rows
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name, output_item_id)
          SELECT gen_random_uuid(), CAST(:ownerUserId AS uuid), d.product_key, d.product_name, d.output_item_id
          FROM default_blueprint d
          ON CONFLICT (owner_user_id, product_key) DO NOTHING
          """,
      nativeQuery = true)
  int grantDefaultBlueprintsToUser(@Param("ownerUserId") UUID ownerUserId);

  /**
   * Inserts the missing default blueprints (REQ-INV-016) for every user still in Keycloak, in one
   * statement.
   *
   * <p>Idempotent via {@code ON CONFLICT}; pending persistence-context writes are flushed first.
   *
   * @return the number of newly inserted rows across all users
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name, output_item_id)
          SELECT gen_random_uuid(), u.id, d.product_key, d.product_name, d.output_item_id
          FROM app_user u
          CROSS JOIN default_blueprint d
          WHERE u.in_keycloak = true
          ON CONFLICT (owner_user_id, product_key) DO NOTHING
          """,
      nativeQuery = true)
  int grantDefaultBlueprintsToAllUsers();
}
