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
   * Bulk owner lookup across several owners — backs the org-unit blueprint availability aggregation
   * (#364): given the {@code app_user.id}s of every in-scope org-unit member, returns all their
   * owned-blueprint rows for grouping by product in the service layer.
   *
   * @param ownerUserIds the {@code app_user.id}s of the in-scope owners
   * @return every owned-blueprint row whose owner is in the given set; never {@code null}
   */
  List<PersonalBlueprint> findAllByOwnerUserIdIn(Collection<UUID> ownerUserIds);

  /**
   * Projection variant of {@link #findAllByOwnerUserIdIn(Collection)} for the family-grouping
   * aggregations (availability overview #364, item-order owner drill-down): both only read the
   * owner and product name to group by variant family and count distinct owners, so this returns a
   * two-column {@link BlueprintOwnerProduct} projection instead of hydrating every full blueprint
   * row of an admin all-scope view (REQ-DATA-003).
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
   * Owner-restricted product lookup — backs the availability drill-down (#364): given one product
   * key and the {@code app_user.id}s of every in-scope member, returns the rows that pin which of
   * those members own the product.
   *
   * @param productKey the normalized product key to match
   * @param ownerUserIds the {@code app_user.id}s of the in-scope owners
   * @return the matching rows (one per owning in-scope member); never {@code null}
   */
  List<PersonalBlueprint> findAllByProductKeyAndOwnerUserIdIn(
      String productKey, Collection<UUID> ownerUserIds);

  /**
   * Unrestricted product lookup — backs the admin "all org units" branch of the availability
   * drill-down (#364). That scope spans every blueprint owner anyway, so enumerating all distinct
   * {@code owner_user_id}s first and echoing them back as an {@code IN} list (the previous
   * implementation) only added a full-table scan plus an unbounded parameter list to every expand
   * click. ADMIN-ONLY: every scoped caller must keep using {@link
   * #findAllByProductKeyAndOwnerUserIdIn(String, Collection)} so the owner-isolation rule holds.
   *
   * @param productKey the normalized product key to match
   * @return every owned-blueprint row for the product, across all owners; never {@code null}
   */
  List<PersonalBlueprint> findAllByProductKey(String productKey);

  /**
   * Unrestricted bulk product lookup — backs the admin "all org units" branch of the
   * <em>variant-family</em> owner drill-down (#364). A family expands to several product keys (a
   * base plus its cosmetic variants), so the drill-down resolves the family's product-key set once
   * (via the cached blueprint family index) and fetches every owner of any of them in one bounded
   * {@code IN} query — the family-aware generalization of {@link #findAllByProductKey(String)}.
   * ADMIN-ONLY: scoped callers must keep using {@link
   * #findAllByProductKeyInAndOwnerUserIdIn(Collection, Collection)} so the owner-isolation rule
   * holds.
   *
   * @param productKeys the normalized product keys making up the family
   * @return every owned-blueprint row for any of the products, across all owners; never {@code
   *     null}
   */
  List<PersonalBlueprint> findAllByProductKeyIn(Collection<String> productKeys);

  /**
   * Owner-restricted bulk product lookup — backs the scoped branch of the variant-family owner
   * drill-down (#364): given a family's product-key set and the {@code app_user.id}s of every
   * in-scope member, returns the rows that pin which of those members own any product in the
   * family. The family-aware generalization of {@link #findAllByProductKeyAndOwnerUserIdIn(String,
   * Collection)}; keeping the owner restriction server-side preserves the multi-user data-isolation
   * rule.
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
   * Returns the distinct {@code app_user.id} of every blueprint owner in the table. Backs the admin
   * "all org units" branch of the availability overview (#364, #371 fix): that scope spans every
   * owner — including a user with no org-unit membership (e.g. an admin without a Staffel) — which
   * a membership-derived member list silently dropped. The owned rows are still fetched through
   * {@link #findAllByOwnerUserIdIn(Collection)}, so the owner-isolation contract is unchanged.
   *
   * @return every distinct {@code owner_user_id} present in the table; never {@code null}, possibly
   *     empty.
   */
  @Query("SELECT DISTINCT pb.ownerUserId FROM PersonalBlueprint pb")
  Set<UUID> findAllDistinctOwnerUserIds();

  /**
   * Bulk-removes every <em>removable</em> owned blueprint of one user — the "delete all my
   * blueprints" clear (REQ-INV-023). Auto-granted default blueprints (REQ-INV-016) are preserved by
   * excluding any row whose {@code product_key} is in the {@code default_blueprint} set, exactly
   * mirroring the per-row {@code requireRemovable} guard so no path can strip a user's defaults
   * (they would only be re-provisioned). The {@code NOT IN (subquery)} form deletes the whole owned
   * set when the default set is empty. A single set-based statement, so no {@code @Version} bumps
   * and no per-row {@code load}; {@code clearAutomatically} detaches any owned rows loaded earlier
   * in the transaction so a later read reflects the removal.
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
   * Bulk-removes every <em>removable</em> owned blueprint of <strong>all</strong> users — the admin
   * "delete all users' blueprints" global purge (REQ-INV-024). Like {@link
   * #deleteRemovableByOwnerUserId(String)} it preserves the auto-granted default blueprints
   * (REQ-INV-016) by excluding rows whose {@code product_key} is in the {@code default_blueprint}
   * set, so the purge cannot fight the default-provisioning sweep. ADMIN-ONLY — it spans every
   * owner and is reachable only from the ADMIN-gated controller. One set-based statement (no
   * {@code @Version} bumps); {@code clearAutomatically} detaches any owned rows loaded earlier in
   * the transaction.
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
   * Bulk-removes <strong>every</strong> owned blueprint of one user, auto-granted defaults included
   * — the hard account deletion (REQ-DATA-008), not the user-facing "clear my blueprints" action.
   * The default-preserving exclusion of {@link #deleteRemovableByOwnerUserId(String)} is
   * deliberately absent: preserving defaults for an account that no longer exists is what left
   * orphaned rows behind, and every user carries at least the auto-granted starter set, so the
   * removable-only variant could never empty an owner.
   *
   * <p>Unlike its siblings this query does <em>not</em> set {@code clearAutomatically}. It runs
   * inside the {@code UserDeletionService.deleteUser} transaction, where detaching the persistence
   * context would detach the very {@code User} entity that is about to be deleted, turning the
   * subsequent {@code delete} into a {@code merge} of a detached instance. Nothing loads {@code
   * PersonalBlueprint} rows in that transaction, so there is nothing stale to evict.
   *
   * @param ownerUserId the departing owner's {@code app_user.id}
   * @return the number of rows removed, for the audit summary event
   */
  @Modifying
  @Query("DELETE FROM PersonalBlueprint b WHERE b.ownerUserId = :ownerUserId")
  int deleteAllByOwnerUserId(@Param("ownerUserId") UUID ownerUserId);

  /**
   * Materialises the admin-curated default blueprints (REQ-INV-016) for a single user: inserts one
   * {@code personal_blueprint} row per {@code default_blueprint} the user does not yet own. The
   * {@code ON CONFLICT (owner_user_id, product_key) DO NOTHING} makes it idempotent (a re-run, or a
   * race with the periodic sweep, inserts nothing); {@code version} / {@code created_at} / {@code
   * updated_at} fall to their column defaults. {@code flushAutomatically} flushes any pending
   * persistence-context writes first so a default just added in the same transaction is visible.
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
   * Materialises the admin-curated default blueprints (REQ-INV-016) for every active user in one
   * statement: a cross join of {@code app_user} (excluding soft-deleted {@code in_keycloak = false}
   * rows) with {@code default_blueprint}, inserting only the rows a user does not yet own. Backs
   * the startup backfill, the periodic provisioning sweep, and the post-add grant when an admin
   * extends the default set. Idempotent via {@code ON CONFLICT}; {@code flushAutomatically} makes a
   * default just added in the same transaction visible.
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
