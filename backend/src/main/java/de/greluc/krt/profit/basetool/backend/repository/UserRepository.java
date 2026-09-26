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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link User}.
 *
 * <p>Squadron-scoped queries filter by SQUADRON memberships in {@code org_unit_membership} against
 * a scope set of squadron ids (REQ-ORG-017); a {@code null} set means admin "all squadrons" mode,
 * and the set is never empty.
 */
@Repository
public interface UserRepository
    extends JpaRepository<User, UUID>, UserRepositoryPlainLookupFragment {

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
   * Returns the registrations with the given approval status, oldest first, for the admin approval
   * queue. Not squadron-scoped.
   *
   * @param approvalStatus the status to filter on ({@code PENDING} at the call site)
   * @return matching users, oldest registration first
   */
  List<User> findByApprovalStatusOrderByCreatedAtAsc(ApprovalStatus approvalStatus);

  /**
   * Returns the ids of registrations rejected before {@code cutoff}, measured from the decision
   * time {@code approvedAt}, for the rejected-registration retention sweep (REQ-SEC-057).
   *
   * @param cutoff return registrations rejected strictly before this instant
   * @return the matching user ids, oldest rejection first
   */
  @Query(
      "SELECT u.id FROM User u WHERE u.approvalStatus ="
          + " de.greluc.krt.profit.basetool.backend.model.ApprovalStatus.REJECTED"
          + " AND u.approvedAt IS NOT NULL AND u.approvedAt < :cutoff ORDER BY u.approvedAt ASC")
  List<UUID> findRejectedDecidedBefore(@Param("cutoff") Instant cutoff);

  /**
   * Returns slim {@link UserReferenceDto}s for the user pickers, ordered by display name.
   *
   * <p>{@code scopeSquadronIds} restricts the result to members of those squadrons ({@code null}:
   * all); users without a squadron are always included.
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
      @Param("scopeSquadronIds") Collection<UUID> scopeSquadronIds);

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
  Set<UUID> findUserIdsByRoleCode(@Param("roleCode") String roleCode);

  /**
   * Returns the ids of every user who shares their blueprints globally ({@link
   * User#isShareBlueprintsGlobally()}), for the blueprint-availability aggregations (REQ-INV-018).
   *
   * @return the user ids of global blueprint sharers; never {@code null}, possibly empty
   */
  @Query("SELECT u.id FROM User u WHERE u.shareBlueprintsGlobally = true")
  Set<UUID> findIdsBySharingBlueprintsGlobally();

  /**
   * Returns the ids of users who hold the global role {@code roleCode} and are members of the given
   * org unit, for the notification rule engine's {@code ORG_RELATIVE_ROLE} resolution.
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
      @Param("roleCode") String roleCode, @Param("orgUnitId") UUID orgUnitId);

  /**
   * Paged squadron-scoped user listing (REQ-ORG-017); users without a Staffel membership are always
   * included. Roles are batch-loaded rather than fetch-joined (REQ-DATA-003).
   */
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
      @Param("scopeSquadronIds") Collection<UUID> scopeSquadronIds, Pageable pageable);

  /**
   * Unpaged variant of {@link #findAllScoped(java.util.Collection, Pageable)} with the same
   * predicate.
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
      @Param("scopeSquadronIds") Collection<UUID> scopeSquadronIds, Sort sort);

  /**
   * Paged squadron-scoped listing of the ordinary members the promotion system evaluates; users
   * holding {@code ADMIN} or {@code OFFICER} and users without a squadron membership are excluded.
   * Roles are batch-loaded rather than fetch-joined (REQ-DATA-003).
   *
   * @param scopeSquadronIds squadron filter set; {@code null} = all squadrons
   * @param pageable paging and sorting
   * @return paged ordinary squadron members an officer or admin may evaluate
   */
  @Query(
      """
      SELECT u FROM User u WHERE EXISTS (SELECT 1 FROM OrgUnitMembership ms WHERE ms.user.id =
      u.id AND ms.kind = de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON
      AND (:scopeSquadronIds IS NULL OR ms.id.orgUnitId IN :scopeSquadronIds)) AND NOT
      EXISTS (SELECT 1 FROM u.roles r WHERE UPPER(r.name) IN ('ADMIN', 'OFFICER'))
      """)
  Page<User> findEvaluatableMembers(
      @Param("scopeSquadronIds") Collection<UUID> scopeSquadronIds, Pageable pageable);

  /**
   * Squadron-scoped variant of {@link
   * #findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(String, String,
   * Pageable)}. Roles are batch-loaded rather than fetch-joined (REQ-DATA-003).
   */
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
      @Param("query") String query,
      @Param("scopeSquadronIds") Collection<UUID> scopeSquadronIds,
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
      @Param("query") String query, @Param("scopeSquadronIds") Collection<UUID> scopeSquadronIds);

  /**
   * Squadron-scoped substring search projected to {@link UserReferenceDto}, backing the user
   * pickers; same predicate as {@link #searchScoped(String, java.util.Collection, Pageable)}
   * without loading entities.
   *
   * @param query the LIKE-escaped substring to match against username or display name
   * @param scopeSquadronIds squadron filter set; {@code null} = all squadrons
   * @param pageable page request; its sort applies to the {@code u} alias
   * @return one page of matching user references
   */
  @Query(
      value =
          """
          SELECT new de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto(u.id,
          u.username, u.displayName, CASE WHEN (u.displayName IS NOT NULL AND u.displayName <>
          '') THEN u.displayName ELSE u.username END, u.rank) FROM User u WHERE
          (LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%')) OR
          LOWER(u.displayName) LIKE LOWER(CONCAT('%', :query, '%'))) AND (:scopeSquadronIds IS
          NULL OR NOT EXISTS (SELECT 1 FROM OrgUnitMembership ms WHERE ms.user.id = u.id AND
          ms.kind = de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON) OR EXISTS
          (SELECT 1 FROM OrgUnitMembership ms WHERE ms.user.id = u.id AND ms.kind =
          de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON AND ms.id.orgUnitId
          IN :scopeSquadronIds))
          """,
      countQuery =
          """
          SELECT COUNT(u) FROM User u WHERE (LOWER(u.username) LIKE LOWER(CONCAT('%', :query,
          '%')) OR LOWER(u.displayName) LIKE LOWER(CONCAT('%', :query, '%'))) AND
          (:scopeSquadronIds IS NULL OR NOT EXISTS (SELECT 1 FROM OrgUnitMembership ms WHERE
          ms.user.id = u.id AND ms.kind =
          de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON) OR EXISTS (SELECT 1
          FROM OrgUnitMembership ms WHERE ms.user.id = u.id AND ms.kind =
          de.greluc.krt.profit.basetool.backend.model.OrgUnitKind.SQUADRON AND ms.id.orgUnitId
          IN :scopeSquadronIds))
          """)
  Page<UserReferenceDto> searchScopedReferences(
      @Param("query") String query,
      @Param("scopeSquadronIds") Collection<UUID> scopeSquadronIds,
      Pageable pageable);

  /**
   * Loads a user by id with {@code roles} and {@code roles.permissions} fetched in the same query —
   * for the authentication path and the caller's own {@code /users/me}, which assemble authorities
   * from both. A caller that only needs the user as a foreign-key target or reads a scalar uses the
   * graph-free {@link #findPlainById(UUID)} instead (BE-PERF-12).
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
   * Returns the ids of every account holding {@code username}, without loading the entity; used at
   * login to detect a callsign collision (ADR-0142). A list, since the username is not unique.
   *
   * @param username the {@code preferred_username} to look for; exact match
   * @return the ids of the accounts holding it, empty when none does
   */
  @Query("SELECT u.id FROM User u WHERE u.username = :username")
  List<UUID> findIdsByUsername(@Param("username") String username);

  /**
   * Returns those of the given usernames that more than one account holds, compared
   * case-insensitively, for the registration queue's collision marker.
   *
   * @param usernames the lower-cased usernames to check; must not be empty
   * @return the lower-cased subset held by two or more accounts
   */
  @Query(
      """
      SELECT LOWER(u.username) FROM User u
      WHERE LOWER(u.username) IN :usernames
      GROUP BY LOWER(u.username)
      HAVING COUNT(u.id) > 1
      """)
  Set<String> findUsernamesHeldByMoreThanOneAccount(
      @Param("usernames") Collection<String> usernames);

  /**
   * Returns every entity matching the derived {@code
   * findAllByUsernameIgnoreCaseOrDisplayNameIgnoreCase} criteria. Eagerly fetches the configured
   * relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"roles"})
  List<User> findAllByUsernameIgnoreCaseOrDisplayNameIgnoreCase(
      String username, String displayName);

  /**
   * Checks whether any user carries one of the candidate names as username or display name, for the
   * Discord first-broker-login precheck (REQ-SEC-022). Loads no rows.
   *
   * @param lowerNames the lower-cased candidate names; never empty
   * @return {@code true} iff at least one user matches
   */
  @Query(
      """
      SELECT (COUNT(u) > 0) FROM User u WHERE LOWER(u.username) IN :lowerNames OR
      LOWER(u.displayName) IN :lowerNames
      """)
  boolean existsByLowerUsernameOrDisplayNameIn(@Param("lowerNames") Collection<String> lowerNames);

  /**
   * Checks whether another account already uses this name as username, display name or RSI handle,
   * compared case-insensitively (REQ-SEC-062, REQ-SEC-072). The account being edited is excluded.
   *
   * @param lowerName the candidate name, lower-cased and trimmed
   * @param selfId the account being edited, excluded from the comparison
   * @return {@code true} when somebody else already answers to this name
   */
  @Query(
      """
      SELECT (COUNT(u) > 0) FROM User u
      WHERE u.id <> :selfId
        AND (LOWER(u.username) = :lowerName OR LOWER(u.displayName) = :lowerName
             OR LOWER(u.rsiHandle) = :lowerName)
      """)
  boolean existsOtherAccountWithName(
      @Param("lowerName") String lowerName, @Param("selfId") UUID selfId);

  /**
   * Checks case-insensitively whether any user carries the given e-mail, for the Discord
   * first-broker-login precheck (REQ-SEC-022). Loads no rows.
   *
   * @param lowerEmail the lower-cased candidate e-mail; never blank
   * @return {@code true} iff at least one user has that e-mail
   */
  @Query("SELECT (COUNT(u) > 0) FROM User u WHERE LOWER(u.email) = :lowerEmail")
  boolean existsByLowerEmail(@Param("lowerEmail") String lowerEmail);

  /**
   * Derived Spring-Data query - returns entities matching {@code
   * UsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase}. Eagerly fetches the configured
   * relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"roles"})
  List<User> findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
      String username, String displayName);

  /**
   * Paged case-insensitive substring search over username or display name. Roles are batch-loaded
   * rather than fetch-joined (REQ-DATA-003).
   */
  Page<User> findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
      String username, String displayName, Pageable pageable);

  /**
   * Flags every user still marked {@code inKeycloak} whose id is missing from the synced Keycloak
   * roster and stamps {@code keycloakAbsentSince}. Already-flagged rows are untouched, so the stamp
   * records the first observation.
   *
   * @param ids the ids in the current Keycloak roster; never {@code null} or empty
   * @param absentSince the instant recorded as the first observed absence
   * @return the number of users newly flagged by this call
   */
  @Modifying
  @Query(
      "UPDATE User u SET u.inKeycloak = false, u.keycloakAbsentSince = :absentSince"
          + " WHERE u.inKeycloak = true AND u.id NOT IN :ids")
  int markMissingUsers(
      @Param("ids") @NotNull Collection<UUID> ids,
      @Param("absentSince") @NotNull Instant absentSince);

  /**
   * Counts orphaned member accounts, excluding Keycloak service-account rows matched by the {@code
   * service-account-} username prefix (REQ-SEC-059). The prefix is a convention only and is used
   * solely for this gauge.
   *
   * @return the number of orphaned member accounts
   */
  @Query(
      """
      SELECT COUNT(u) FROM User u
      WHERE u.inKeycloak = false AND LOWER(u.username) NOT LIKE 'service-account-%'
      """)
  long countOrphanedMemberAccounts();

  /**
   * Returns the earliest absence stamp among the rows counted by {@link
   * #countOrphanedMemberAccounts()} (REQ-SEC-059).
   *
   * @return the earliest {@code keycloakAbsentSince}, or {@code null} when none is waiting
   */
  @Query(
      """
      SELECT MIN(u.keycloakAbsentSince) FROM User u
      WHERE u.inKeycloak = false AND u.keycloakAbsentSince IS NOT NULL
        AND LOWER(u.username) NOT LIKE 'service-account-%'
      """)
  Instant findOldestOrphanedMemberAbsenceStamp();

  /**
   * Returns the ids of every user with a Discord link, so the Keycloak sync back-fills the link
   * only for the others.
   *
   * @return the ids of users with a non-null Discord link; never {@code null}, possibly empty
   */
  @Query("SELECT u.id FROM User u WHERE u.discordUserId IS NOT NULL")
  Set<UUID> findIdsWithDiscordLink();

  /**
   * Returns the id of the user holding {@code discordUserId}, checked before writing a link because
   * the column is unique.
   *
   * @param discordUserId the Discord snowflake to look up; never {@code null}
   * @return the holding user's id, or {@link Optional#empty()} when nobody holds it
   */
  @Query("SELECT u.id FROM User u WHERE u.discordUserId = :discordUserId")
  Optional<UUID> findIdByDiscordUserId(@NotNull @Param("discordUserId") String discordUserId);

  /**
   * Returns every user carrying the {@code ADMIN} role (case-insensitive match), ordered by
   * username.
   */
  @Query("SELECT u FROM User u JOIN u.roles r WHERE UPPER(r.name) = 'ADMIN' ORDER BY u.username")
  List<User> findAllAdmins();

  /**
   * Bulk-nulls {@code approved_by_id} on every user decided by the given admin, so deleting that
   * admin is not blocked by the self-referential foreign key. The decision stays in {@code
   * user_approval_event}.
   *
   * @param adminId the deciding admin being deleted; never {@code null}
   */
  @Modifying
  @Query("UPDATE User u SET u.approvedById = null WHERE u.approvedById = :adminId")
  void clearApprovedBy(@Param("adminId") @NotNull UUID adminId);
}
