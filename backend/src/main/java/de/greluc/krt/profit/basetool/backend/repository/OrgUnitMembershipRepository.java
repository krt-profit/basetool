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

import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link OrgUnitMembership}, keyed by {@link OrgUnitMembershipId}. */
@Repository
public interface OrgUnitMembershipRepository
    extends JpaRepository<OrgUnitMembership, OrgUnitMembershipId> {

  /**
   * Returns every membership of the given user, in insertion order; callers sort for display.
   *
   * @param userId the user whose memberships to list; never {@code null}.
   * @return every membership of this user; never {@code null}, empty when the user has none.
   */
  List<OrgUnitMembership> findAllByIdUserId(UUID userId);

  /**
   * Counts the org-unit memberships of the given user. Used by {@code OrgUnitMembershipService} to
   * detect the two inventory-relevant boundary transitions — gaining the first membership
   * (membershipless → member) and losing the last (member → membershipless) — so {@link
   * de.greluc.krt.profit.basetool.backend.service.InventoryOrgUnitReconciler} can re-stamp the
   * user's inventory accordingly.
   *
   * @param userId the user whose memberships to count; never {@code null}.
   * @return the number of org-unit membership rows of this user (0 when membershipless).
   */
  long countByIdUserId(UUID userId);

  /**
   * Returns the given user's memberships of one kind.
   *
   * <p>A user may hold up to two {@code SQUADRON} memberships (REQ-ORG-017), so always use this for
   * that kind.
   *
   * @param userId the user whose memberships to list; never {@code null}.
   * @param kind the discriminator value to match; never {@code null}.
   * @return memberships of the requested kind; never {@code null}, possibly empty.
   */
  List<OrgUnitMembership> findAllByIdUserIdAndKind(UUID userId, OrgUnitKind kind);

  /**
   * Batch variant of {@link #findAllByIdUserIdAndKind(UUID, OrgUnitKind)} for several users and
   * kinds in one query; backs the Materialbörse affiliation badges (REQ-MARKET-001).
   *
   * @param userIds the users whose memberships to list; never {@code null}; empty yields an empty
   *     result
   * @param kinds the discriminator values to match; never {@code null}; empty yields an empty
   *     result
   * @return the matching membership rows; never {@code null}, possibly empty.
   */
  List<OrgUnitMembership> findAllByIdUserIdInAndKindIn(
      Collection<UUID> userIds, Collection<OrgUnitKind> kinds);

  /**
   * Returns every membership belonging to the given org unit. Used by the admin roster page for an
   * SK ("list every member of SK ALPHA") and by the Lead-management endpoints to verify "is this
   * caller a Lead of SK ALPHA before letting them edit memberships there".
   *
   * @param orgUnitId the org unit whose members to list; never {@code null}.
   * @return memberships of this org unit; never {@code null}, possibly empty.
   */
  List<OrgUnitMembership> findAllByIdOrgUnitId(UUID orgUnitId);

  /**
   * Existence check used by the membership-management endpoints to short-circuit before issuing a
   * full SELECT: "does the caller already have a membership row in org unit X?" determines whether
   * the membership-add request is an idempotent retry or a legitimate new join.
   *
   * @param userId the user to check; never {@code null}.
   * @param orgUnitId the org unit to check; never {@code null}.
   * @return {@code true} iff a membership row already exists for this {@code (user, org_unit)}
   *     pair.
   */
  boolean existsByIdUserIdAndIdOrgUnitId(UUID userId, UUID orgUnitId);

  /**
   * Whether any membership references the given Kommandogruppe; backs the Kommandogruppe-delete
   * guard, which rejects the delete until its members are reassigned (REQ-ROLE-003).
   *
   * @param kommandoGroupId the Kommandogruppe to check; never {@code null}.
   * @return {@code true} iff at least one membership is assigned to that group.
   */
  boolean existsByKommandoGroupId(UUID kommandoGroupId);

  /**
   * Returns the distinct ids of every user who is a member of any of the given org units; backs the
   * scoped blueprint availability overview.
   *
   * @param orgUnitIds the org units whose members to collect; never {@code null}; empty yields an
   *     empty result
   * @return the distinct member user ids across the given org units; never {@code null}.
   */
  @Query("SELECT DISTINCT m.id.userId FROM OrgUnitMembership m WHERE m.id.orgUnitId IN :orgUnitIds")
  Set<UUID> findDistinctUserIdsByOrgUnitIdIn(@Param("orgUnitIds") Collection<UUID> orgUnitIds);

  /**
   * Returns the ids of the org units the given user belongs to, without loading membership
   * entities.
   *
   * <p>Safe inside the user-deletion transaction, where managed membership entities would make the
   * flush after the user delete fail.
   *
   * @param userId the user whose org units to collect; never {@code null}.
   * @return the ids of the org units this user is a member of; never {@code null}, possibly empty.
   */
  @Query("SELECT m.id.orgUnitId FROM OrgUnitMembership m WHERE m.id.userId = :userId")
  Set<UUID> findOrgUnitIdsByUserId(@Param("userId") UUID userId);

  /**
   * Returns the ids of users who are Leads of the given org unit. Backs the notification rule
   * engine's {@code ORG_RELATIVE_ROLE = LEAD} resolution (leads of a responsible Spezialkommando).
   *
   * @param orgUnitId the org unit whose leads to collect; never {@code null}
   * @return the lead user ids; never {@code null}, possibly empty
   */
  @Query(
      """
      SELECT m.id.userId FROM OrgUnitMembership m WHERE m.id.orgUnitId = :orgUnitId AND m.role =
      de.greluc.krt.profit.basetool.backend.model.MembershipRole.SK_LEAD
      """)
  Set<UUID> findLeadUserIdsByOrgUnit(@Param("orgUnitId") UUID orgUnitId);

  /**
   * Returns the ids of users who are Logisticians of the given org unit. Backs the notification
   * rule engine's {@code ORG_RELATIVE_ROLE = LOGISTICIAN} resolution.
   *
   * @param orgUnitId the org unit whose logisticians to collect; never {@code null}
   * @return the logistician user ids; never {@code null}, possibly empty
   */
  @Query(
      """
      SELECT m.id.userId FROM OrgUnitMembership m WHERE m.id.orgUnitId = :orgUnitId AND
      m.isLogistician = true
      """)
  Set<UUID> findLogisticianUserIdsByOrgUnit(@Param("orgUnitId") UUID orgUnitId);

  /**
   * Returns the ids of users who are Mission Managers of the given org unit. Backs the notification
   * rule engine's {@code ORG_RELATIVE_ROLE = MISSION_MANAGER} resolution.
   *
   * @param orgUnitId the org unit whose mission managers to collect; never {@code null}
   * @return the mission-manager user ids; never {@code null}, possibly empty
   */
  @Query(
      """
      SELECT m.id.userId FROM OrgUnitMembership m WHERE m.id.orgUnitId = :orgUnitId AND
      m.isMissionManager = true
      """)
  Set<UUID> findMissionManagerUserIdsByOrgUnit(@Param("orgUnitId") UUID orgUnitId);

  /**
   * Returns the ids of users who hold exactly the given {@link MembershipRole} on the given org
   * unit. Backs the org-unit bank seam's reverse resolution of a bank account's <em>responsible
   * holder(s)</em> (REQ-BANK-034) for the notification engine — the {@code STAFFELLEITER} of a
   * Staffel, the {@code SK_LEAD} of a Spezialkommando, the {@code BEREICHSLEITER} of a Bereich, the
   * {@code OL_MEMBER}s of the Organisationsleitung — without a current-principal context (the
   * after-commit notification thread has none).
   *
   * @param orgUnitId the org unit whose role holders to collect; never {@code null}
   * @param role the membership role to match; never {@code null}
   * @return the matching user ids; never {@code null}, possibly empty
   */
  @Query(
      """
      SELECT m.id.userId FROM OrgUnitMembership m WHERE m.id.orgUnitId = :orgUnitId AND m.role =
      :role
      """)
  Set<UUID> findUserIdsByOrgUnitAndRole(
      @Param("orgUnitId") UUID orgUnitId, @Param("role") MembershipRole role);

  /**
   * Batch variant of {@link #findUserIdsByOrgUnitAndRole(UUID, MembershipRole)}: the users holding
   * {@code role} on any of the given org units, in one statement (REQ-DATA-003).
   *
   * @param orgUnitIds the org units to collect role holders of; never {@code null}, never empty
   * @param role the membership role to match; never {@code null}
   * @return the matching user ids, each once; never {@code null}, possibly empty
   */
  @Query(
      """
      SELECT DISTINCT m.id.userId FROM OrgUnitMembership m WHERE m.id.orgUnitId IN :orgUnitIds
      AND m.role = :role
      """)
  Set<UUID> findUserIdsByOrgUnitIdsAndRole(
      @Param("orgUnitIds") Collection<UUID> orgUnitIds, @Param("role") MembershipRole role);
}
