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

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for User.
 *
 * <p>Post-R9 D3 (V101): the legacy {@code app_user.squadron_id} column was dropped — every Staffel
 * scope filter consults {@code org_unit_membership} instead. The squadron-scoped queries below use
 * a {@code NOT EXISTS} sub-select to detect "user has no Staffel membership" (the equivalent of the
 * pre-V101 {@code u.squadron IS NULL} branch) and an {@code EXISTS} sub-select with the {@code
 * (kind = SQUADRON AND org_unit_id IN :scopeSquadronIds)} predicate for the in-scope branch. The
 * scope parameter is a <em>collection</em> of squadron ids (REQ-ORG-017: a member may belong to up
 * to two Staffeln, so a non-admin's unpinned scope is the union of their Staffeln); {@code null}
 * still signals admin "all squadrons" mode. The collection is never empty (a caller with no Staffel
 * collapses to {@code null} in {@code OwnerScopeService#currentUserListScopeSquadronIds()}), so the
 * {@code IN} clause never degenerates to {@code IN ()}.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

  /**
   * Counts users in the given approval status, backing the {@code basetool_registration_pending_*}
   * queue-depth gauge (REQ-OBS-011).
   *
   * @param approvalStatus the bounded approval status to count (typically {@code PENDING})
   * @return the number of users in that status
   */
  long countByApprovalStatus(ApprovalStatus approvalStatus);

  /**
   * Finds the registration timestamp of the oldest user in the given approval status, for the
   * "oldest pending registration age" gauge (REQ-OBS-011).
   *
   * @param approvalStatus the bounded approval status to scan (typically {@code PENDING})
   * @return the earliest {@code createdAt} in that status, or {@code null} when none exists
   */
  @Query("SELECT MIN(u.createdAt) FROM User u WHERE u.approvalStatus = :approvalStatus")
  Instant findOldestCreatedAtByApprovalStatus(
      @Param("approvalStatus") ApprovalStatus approvalStatus);

  /**
   * Returns the registrations awaiting approval (status {@link ApprovalStatus#PENDING}), oldest
   * first, for the admin approval queue (epic #720, Track 1). Not squadron-scoped — a pending user
   * has no org unit yet.
   *
   * @param approvalStatus the status to filter on (always {@code PENDING} at the call site)
   * @return matching users, oldest registration first
   */
  List<User> findByApprovalStatusOrderByCreatedAtAsc(ApprovalStatus approvalStatus);

  /**
   * Returns slim {@link UserReferenceDto}s for every user (id, username, displayName, effective
   * name with username fallback, rank) ordered by display name. Used to populate user pickers
   * without pulling the full User aggregate.
   *
   * <p>Multi-tenant: {@code scopeSquadronIds} restricts the result to members of those squadrons
   * (REQ-ORG-017: a non-admin's unpinned scope is the union of their up-to-two Staffeln). {@code
   * null} signals admin "all squadrons" mode and falls back to the cross-staffel list. Users that
   * have no squadron assigned (admins, guests) are always included so an admin in focused mode
   * still sees the unassigned bucket alongside the squadron members.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto(u.id,
      u.username, u.displayName, CASE WHEN (u.displayName IS NOT NULL AND u.displayName <>
      '') THEN u.displayName ELSE u.username END, u.rank) FROM User u WHERE
      :scopeSquadronIds IS NULL OR NOT EXISTS (SELECT 1 FROM OrgUnitMembership ms WHERE
      ms.user.id = u.id AND ms.kind =
      de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON) OR EXISTS (SELECT 1
      FROM OrgUnitMembership ms WHERE ms.user.id = u.id AND ms.kind =
      de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON AND ms.id.orgUnitId
      IN :scopeSquadronIds) ORDER BY u.displayName
      """)
  List<UserReferenceDto> findAllReferenceScoped(
      @org.springframework.data.repository.query.Param("scopeSquadronIds")
          java.util.Collection<UUID> scopeSquadronIds);

  /**
   * Unscoped variant used internally by JWT sync flows where access is always implicit. Kept for
   * backwards compatibility — every caller-facing path should go through {@link
   * #findAllReferenceScoped(java.util.Collection)} instead.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto(u.id,
      u.username, u.displayName, CASE WHEN (u.displayName IS NOT NULL AND u.displayName <>
      '') THEN u.displayName ELSE u.username END, u.rank) FROM User u ORDER BY
      u.displayName
      """)
  List<UserReferenceDto> findAllReference();

  /**
   * Returns the ids ({@code sub}s) of every user holding the global role with the given stable
   * {@code code} (e.g. {@code ADMIN}). Backs the notification rule engine's {@code ROLE} selector.
   *
   * @param roleCode the stable role code to match (e.g. {@code ADMIN}, {@code OFFICER})
   * @return the matching user ids; never {@code null}, possibly empty
   */
  @Query("SELECT u.id FROM User u JOIN u.roles r WHERE r.code = :roleCode")
  Set<UUID> findUserIdsByRoleCode(
      @org.springframework.data.repository.query.Param("roleCode") String roleCode);

  /**
   * Returns the ids of every user who has opted into sharing their blueprints globally ({@link
   * User#isShareBlueprintsGlobally()}). The blueprint-availability aggregations union these ids
   * into their org-unit member set so an opted-in user is counted regardless of org-unit membership
   * (REQ-INV-018). The id equals the stored {@code PersonalBlueprint.owner_sub} once rendered as
   * text, so callers convert via {@link UUID#toString()}.
   *
   * @return the user ids of global blueprint sharers; never {@code null}, possibly empty
   */
  @Query("SELECT u.id FROM User u WHERE u.shareBlueprintsGlobally = true")
  Set<UUID> findIdsBySharingBlueprintsGlobally();

  /**
   * Returns the ids ({@code sub}s) of users who both hold the global role {@code roleCode} and are
   * members of the given org unit. Backs the notification rule engine's {@code ORG_RELATIVE_ROLE}
   * resolution of "officers of the responsible squadron" (role {@code OFFICER} intersected with
   * membership of that org unit).
   *
   * @param roleCode the stable role code to match (e.g. {@code OFFICER})
   * @param orgUnitId the org unit the user must be a member of
   * @return the matching user ids; never {@code null}, possibly empty
   */
  @Query(
      """
      SELECT u.id FROM User u JOIN u.roles r WHERE r.code = :roleCode AND EXISTS
      (SELECT 1 FROM OrgUnitMembership m WHERE m.id.userId = u.id AND m.id.orgUnitId =
      :orgUnitId)
      """)
  Set<UUID> findUserIdsByRoleCodeAndOrgUnitMembership(
      @org.springframework.data.repository.query.Param("roleCode") String roleCode,
      @org.springframework.data.repository.query.Param("orgUnitId") UUID orgUnitId);

  /**
   * Squadron-scoped paged listing. Filters by the user's SQUADRON-kind membership(s) in {@code
   * org_unit_membership} against the caller's scope set (REQ-ORG-017: up to two Staffeln) — users
   * without a Staffel membership (admins, guests) are always visible so the focused admin can
   * manage them.
   */
  @EntityGraph(attributePaths = {"roles"})
  @Query(
      """
      SELECT u FROM User u WHERE :scopeSquadronIds IS NULL OR NOT EXISTS (SELECT 1 FROM
      OrgUnitMembership ms WHERE ms.user.id = u.id AND ms.kind =
      de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON) OR EXISTS (SELECT 1
      FROM OrgUnitMembership ms WHERE ms.user.id = u.id AND ms.kind =
      de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON AND ms.id.orgUnitId
      IN :scopeSquadronIds)
      """)
  Page<User> findAllScoped(
      @org.springframework.data.repository.query.Param("scopeSquadronIds")
          java.util.Collection<UUID> scopeSquadronIds,
      Pageable pageable);

  /**
   * Unpaged squadron-scoped listing. Same predicate as {@link #findAllScoped(java.util.Collection,
   * Pageable)} without pagination — used by the legacy {@code findAll()} call sites that still
   * expect a plain {@link List}.
   */
  @EntityGraph(attributePaths = {"roles"})
  @Query(
      """
      SELECT u FROM User u WHERE :scopeSquadronIds IS NULL OR NOT EXISTS (SELECT 1 FROM
      OrgUnitMembership ms WHERE ms.user.id = u.id AND ms.kind =
      de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON) OR EXISTS (SELECT 1
      FROM OrgUnitMembership ms WHERE ms.user.id = u.id AND ms.kind =
      de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON AND ms.id.orgUnitId
      IN :scopeSquadronIds)
      """)
  List<User> findAllScopedList(
      @org.springframework.data.repository.query.Param("scopeSquadronIds")
          java.util.Collection<UUID> scopeSquadronIds,
      org.springframework.data.domain.Sort sort);

  /**
   * Paged squadron-scoped listing of the ordinary members a squadron may evaluate in the promotion
   * system — the row set of the Bewertungsverwaltung matrix. The promotion system assesses only the
   * <strong>simple members</strong> of a squadron (issue #817): both the {@code ADMIN} and the
   * {@code OFFICER} realm role are excluded, because their holders <em>run</em> the evaluation
   * rather than being its subject. Admins are squadron-less by design (no Staffel membership row)
   * and must not surface even when one has focused a squadron via the switcher; officers are the
   * squadron's leadership / evaluators and likewise do not belong in the matrix. The role-based
   * exclusion also guards against a manually mis-assigned admin or officer row that still carries a
   * squadron membership.
   *
   * <p>When {@code scopeSquadronIds} is {@code null} (admin "all squadrons" mode) the result spans
   * every squadron's members. A non-null set restricts to those squadrons (REQ-ORG-017: an officer
   * of two Staffeln evaluates both). Users without a squadron membership are excluded — they are
   * not part of any squadron's evaluation list, which keeps the promotion system scoped to
   * squadrons and never to other org-unit kinds.
   *
   * @param scopeSquadronIds squadron filter set; {@code null} = all squadrons.
   * @param pageable Spring Data paging and sorting parameters.
   * @return paged ordinary squadron members that an Officer / Admin may evaluate.
   */
  @EntityGraph(attributePaths = {"roles"})
  @Query(
      """
      SELECT u FROM User u WHERE EXISTS (SELECT 1 FROM OrgUnitMembership ms WHERE ms.user.id =
      u.id AND ms.kind = de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON
      AND (:scopeSquadronIds IS NULL OR ms.id.orgUnitId IN :scopeSquadronIds)) AND NOT
      EXISTS (SELECT 1 FROM u.roles r WHERE UPPER(r.name) IN ('ADMIN', 'OFFICER'))
      """)
  Page<User> findEvaluatableMembers(
      @org.springframework.data.repository.query.Param("scopeSquadronIds")
          java.util.Collection<UUID> scopeSquadronIds,
      Pageable pageable);

  /**
   * Squadron-scoped substring search. Mirrors {@link
   * #findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(String, String, Pageable)}
   * but adds the squadron-membership predicate.
   */
  @EntityGraph(attributePaths = {"roles"})
  @Query(
      """
      SELECT u FROM User u WHERE (LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%')) OR
      LOWER(u.displayName) LIKE LOWER(CONCAT('%', :query, '%'))) AND (:scopeSquadronIds IS
      NULL OR NOT EXISTS (SELECT 1 FROM OrgUnitMembership ms WHERE ms.user.id = u.id AND
      ms.kind = de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON) OR EXISTS
      (SELECT 1 FROM OrgUnitMembership ms WHERE ms.user.id = u.id AND ms.kind =
      de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON AND ms.id.orgUnitId
      IN :scopeSquadronIds))
      """)
  Page<User> searchScoped(
      @org.springframework.data.repository.query.Param("query") String query,
      @org.springframework.data.repository.query.Param("scopeSquadronIds")
          java.util.Collection<UUID> scopeSquadronIds,
      Pageable pageable);

  /**
   * Squadron-scoped substring search returning a plain list. Same predicate as {@link
   * #searchScoped(String, java.util.Collection, Pageable)} without pagination.
   */
  @EntityGraph(attributePaths = {"roles"})
  @Query(
      """
      SELECT u FROM User u WHERE (LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%')) OR
      LOWER(u.displayName) LIKE LOWER(CONCAT('%', :query, '%'))) AND (:scopeSquadronIds IS
      NULL OR NOT EXISTS (SELECT 1 FROM OrgUnitMembership ms WHERE ms.user.id = u.id AND
      ms.kind = de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON) OR EXISTS
      (SELECT 1 FROM OrgUnitMembership ms WHERE ms.user.id = u.id AND ms.kind =
      de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON AND ms.id.orgUnitId
      IN :scopeSquadronIds))
      """)
  List<User> searchScopedList(
      @org.springframework.data.repository.query.Param("query") String query,
      @org.springframework.data.repository.query.Param("scopeSquadronIds")
          java.util.Collection<UUID> scopeSquadronIds);

  /**
   * Derived Spring-Data query - returns entities matching {@code Id}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @Override
  @NotNull
  @EntityGraph(attributePaths = {"roles", "roles.permissions"})
  Optional<User> findById(@NotNull UUID id);

  /** Derived Spring-Data query - returns entities matching {@code Email}. */
  Optional<User> findByEmail(String email);

  /**
   * Derived Spring-Data query - returns entities matching {@code Username}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"roles", "roles.permissions"})
  Optional<User> findByUsername(String username);

  /**
   * Derived Spring-Data query - returns entities matching {@code
   * UsernameIgnoreCaseOrDisplayNameIgnoreCase}. Eagerly fetches the configured relations via
   * {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"roles"})
  Optional<User> findByUsernameIgnoreCaseOrDisplayNameIgnoreCase(
      String username, String displayName);

  /**
   * Returns every entity matching the derived {@code
   * findAllByUsernameIgnoreCaseOrDisplayNameIgnoreCase} criteria. Eagerly fetches the configured
   * relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"roles"})
  List<User> findAllByUsernameIgnoreCaseOrDisplayNameIgnoreCase(
      String username, String displayName);

  /**
   * Account-existence precheck for a Discord first-broker-login (REQ-SEC-022): does any user carry
   * one of the candidate names — already lower-cased by the caller — as their login {@code
   * username} <em>or</em> their in-app {@code displayName}? Returns the bare existence fact; never
   * loads a row, so no PII is materialised. The caller guarantees a non-empty set, so the {@code
   * IN} clause never degenerates to {@code IN ()}.
   *
   * @param lowerNames the lower-cased candidate names (Discord username + server nickname); never
   *     empty
   * @return {@code true} iff at least one user matches on username or display name
   */
  @Query(
      """
      SELECT (COUNT(u) > 0) FROM User u WHERE LOWER(u.username) IN :lowerNames OR
      LOWER(u.displayName) IN :lowerNames
      """)
  boolean existsByLowerUsernameOrDisplayNameIn(
      @org.springframework.data.repository.query.Param("lowerNames")
          java.util.Collection<String> lowerNames);

  /**
   * Account-existence precheck for a Discord first-broker-login (REQ-SEC-022): does any user carry
   * the given e-mail (already lower-cased by the caller)? Case-insensitive counterpart to the
   * case-sensitive {@link #findByEmail(String)}; returns the bare existence fact so no PII is
   * materialised.
   *
   * @param lowerEmail the lower-cased candidate e-mail; never blank
   * @return {@code true} iff at least one user has that e-mail
   */
  @Query("SELECT (COUNT(u) > 0) FROM User u WHERE LOWER(u.email) = :lowerEmail")
  boolean existsByLowerEmail(
      @org.springframework.data.repository.query.Param("lowerEmail") String lowerEmail);

  /**
   * Derived Spring-Data query - returns entities matching {@code
   * UsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase}. Eagerly fetches the configured
   * relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"roles"})
  List<User> findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
      String username, String displayName);

  /**
   * Derived Spring-Data query - returns entities matching {@code
   * UsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase}. Eagerly fetches the configured
   * relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"roles"})
  Page<User> findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
      String username, String displayName, Pageable pageable);

  /**
   * Lists every entity. Overridden here to attach an {@code @EntityGraph}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @Override
  @EntityGraph(attributePaths = {"roles"})
  Page<User> findAll(Pageable pageable);

  /**
   * Sets {@code inKeycloak = false} on every user whose id is not in the freshly-synced Keycloak id
   * list. Called by the periodic Keycloak sync so accounts removed upstream become flagged locally
   * without being deleted (preserves history and FK references).
   */
  @org.springframework.data.jpa.repository.Modifying
  @Query("UPDATE User u SET u.inKeycloak = false WHERE u.id NOT IN :ids")
  void markMissingUsers(@NotNull java.util.Collection<java.util.UUID> ids);

  /**
   * Returns the ids of every local user that already carries a Discord account link ({@code
   * discord_user_id} is non-null). Backs the Keycloak user sync's incremental Discord back-fill:
   * the sync reads the (expensive, per-user) federated-identity endpoint only for roster users NOT
   * in this set, so the already-linked majority is skipped each run. A lightweight scalar
   * projection: no rows are materialised, so no PII (the snowflake itself) is loaded.
   *
   * @return the ids of users with a non-null Discord link; never {@code null}, possibly empty.
   */
  @Query("SELECT u.id FROM User u WHERE u.discordUserId IS NOT NULL")
  Set<UUID> findIdsWithDiscordLink();

  /**
   * Returns every user carrying the {@code ADMIN} role (case-insensitive match), ordered by
   * username.
   */
  @Query("SELECT u FROM User u JOIN u.roles r WHERE UPPER(r.name) = 'ADMIN' ORDER BY u.username")
  List<User> findAllAdmins();

  /**
   * Bulk-nulls the denormalised {@code approved_by_id} pointer on every user approved or rejected
   * by the given admin. Called by the user-delete flow so deleting an admin who had decided
   * registrations is not blocked by the self-referential {@code fk_app_user_approved_by} foreign
   * key (V173, no {@code ON DELETE} clause). Only the convenience pointer is cleared — the decision
   * itself is preserved in {@code user_approval_event}.
   *
   * @param adminId the deciding admin being deleted; never {@code null}.
   */
  @org.springframework.data.jpa.repository.Modifying
  @Query("UPDATE User u SET u.approvedById = null WHERE u.approvedById = :adminId")
  void clearApprovedBy(
      @org.springframework.data.repository.query.Param("adminId") @NotNull UUID adminId);
}
