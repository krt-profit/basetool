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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.OrgUnitMembershipMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.KommandoGroup;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BereichLeadershipRole;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipFlagsPatchRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipLeadToggleRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.backend.repository.KommandoGroupRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.OrgUnitLabels;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Membership-management service for Spezialkommandos — adds / removes / patches members of an SK
 * through the endpoints under {@code /api/v1/special-commands/{id}/members}. The service is
 * intentionally scoped to {@link OrgUnitKind#SPECIAL_COMMAND} memberships: every entry point loads
 * the parent SK through {@link SpecialCommandService#getSpecialCommandById(UUID)} first, which
 * already filters via the JPA discriminator. A Squadron UUID accidentally routed through the SK
 * endpoints therefore lands as a clean 404 before any membership row is touched, never as a
 * corrupted Staffel membership.
 *
 * <p>Staffel membership add / remove / flag changes flow through {@link
 * #reconcileStaffelMemberships(User, java.util.List)} (the member-edit membership-delta path, up to
 * two Staffeln per REQ-ORG-017) and the version-aware {@link #patchSquadronMemberFlags(UUID, UUID,
 * MembershipFlagsPatchRequest)}. The legacy {@code app_user.is_logistician} / {@code
 * app_user.is_mission_manager} columns were dropped in V101 (R9 Step 5) — the membership row is the
 * single source of truth.
 *
 * <p>Concurrency: every write method checks the inbound {@code version} against the membership
 * row's {@code @Version} field, throwing {@link ObjectOptimisticLockingFailureException} → 409 on
 * mismatch so two concurrent admin edits do not silently lose either flag flip.
 *
 * <p>The read-only picker/option enumerations and the membership accessors that back the
 * authorization gates were split into {@link OrgUnitMembershipQueryService} (audit Thema 7, #14);
 * this service keeps only the audit-, {@code @Version}- and bank-responsibility-bearing writes plus
 * the {@code …Dto} projections of those writes.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgUnitMembershipService {

  private final OrgUnitMembershipRepository membershipRepository;
  private final SpecialCommandService specialCommandService;
  private final UserRepository userRepository;
  private final SquadronRepository squadronRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final KommandoGroupRepository kommandoGroupRepository;
  private final InventoryOrgUnitReconciler inventoryReconciler;
  private final AuditService auditService;
  private final OrgChartService orgChartService;
  private final OrgUnitMembershipMapper orgUnitMembershipMapper;

  /**
   * The OwnerScope-free responsible-holder audit seam, injected as an {@link ObjectProvider} and
   * resolved lazily around each leadership mutation to audit a change of the affected accounts'
   * derived responsible holder(s) (Kontoverantwortliche/r, REQ-BANK-034/ADR-0070). All bank access
   * stays inside {@link OrgUnitBankResponsibilityService} — this service only brackets its mutation
   * with a before/after snapshot.
   */
  private final ObjectProvider<OrgUnitBankResponsibilityService>
      orgUnitBankResponsibilityServiceProvider;

  /**
   * Adds the given user as a member of the given Spezialkommando. Returns the persisted membership
   * row with the V95 trigger-derived {@code kind} value pre-populated on the in-memory entity (the
   * actual DB column is written by the BEFORE-INSERT trigger; we mirror the value on the entity so
   * the immediate DTO mapping reads the right discriminator without an extra refresh).
   *
   * <p>Idempotency: an attempt to add a user who is already a member raises {@link
   * DuplicateEntityException} → 409 rather than silently no-op. The admin UI is expected to use a
   * dedicated "already member" detection instead of leaning on add as a re-attach.
   *
   * @param specialCommandId the SK to add the user to; never {@code null}.
   * @param userId the user to add; never {@code null}.
   * @return the persisted membership row.
   * @throws NotFoundException if no SK matches the given id, or no user matches the given id.
   * @throws DuplicateEntityException if the user is already a member of this SK.
   */
  @Transactional
  public OrgUnitMembership addMember(@NotNull UUID specialCommandId, @NotNull UUID userId) {
    SpecialCommand sc = specialCommandService.getSpecialCommandById(specialCommandId);
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    if (membershipRepository.existsByIdUserIdAndIdOrgUnitId(userId, sc.getId())) {
      throw new DuplicateEntityException("User is already a member of this Spezialkommando");
    }

    final boolean wasMembershipless = membershipRepository.countByIdUserId(userId) == 0;

    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(userId, sc.getId()));
    membership.setUser(user);
    membership.setKind(OrgUnitKind.SPECIAL_COMMAND);
    membership.setJoinedAt(Instant.now());
    OrgUnitMembership saved = membershipRepository.save(membership);

    if (wasMembershipless) {
      inventoryReconciler.onUserGainedFirstOrgUnit(userId, sc);
    }
    auditService.record(
        AuditEventType.MEMBERSHIP_GRANTED,
        sc.getId(),
        OrgUnitLabels.shorthandOrName(sc),
        userId,
        "kind=SPECIAL_COMMAND");
    return saved;
  }

  /**
   * Removes the given user from the given Spezialkommando. Existence checks both sides so a stale
   * URL surfaces as 404 rather than as a silent no-op.
   *
   * @param specialCommandId the SK to remove the user from; never {@code null}.
   * @param userId the user to remove; never {@code null}.
   * @throws NotFoundException if no SK matches the given id, or the user is not a member of this
   *     SK.
   */
  @Transactional
  public void removeMember(@NotNull UUID specialCommandId, @NotNull UUID userId) {
    SpecialCommand sc = specialCommandService.getSpecialCommandById(specialCommandId);
    OrgUnitMembershipId id = new OrgUnitMembershipId(userId, sc.getId());
    if (!membershipRepository.existsById(id)) {
      throw new NotFoundException("Membership not found");
    }
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankResponsibilityServiceProvider.getObject().snapshotResponsibleHolders(sc.getId());
    membershipRepository.deleteById(id);
    orgChartService.mirrorRemoveUnitSeat(sc.getId(), userId);

    if (membershipRepository.countByIdUserId(userId) == 0) {
      inventoryReconciler.onUserLostLastOrgUnit(userId);
    }
    auditService.record(
        AuditEventType.MEMBERSHIP_REVOKED,
        sc.getId(),
        OrgUnitLabels.shorthandOrName(sc),
        userId,
        "kind=SPECIAL_COMMAND");
    orgUnitBankResponsibilityServiceProvider
        .getObject()
        .recordResponsibleHolderChanges(responsibleBefore);
  }

  /**
   * Grants a user an explicit, reach-bearing Bereichsleitung role on the given Bereich (epic #692,
   * REQ-ORG-017) — distinct from the SK-Leiter's derived (computed, not stored) Bereichsleitung
   * seat, which has no membership row. If the user already has a membership row on this Bereich
   * (from a prior explicit grant), its role flag is updated in place; otherwise a fresh membership
   * is created. Exactly one of the three Bereich role flags ends up set. The user must hold no
   * Staffel membership — the service guard returns a clean 400 before the V165 trigger would 500.
   * Unlike a Staffel/SK join this does <em>not</em> adopt the user's ownerless inventory (a Bereich
   * is not a personal-inventory home).
   *
   * @param bereichId the Bereich to add the leader to; must be a {@code BEREICH} org unit.
   * @param userId the user to grant the role to; never {@code null}.
   * @param role the Bereichsleitung role to set; never {@code null}.
   * @return the persisted membership row.
   * @throws NotFoundException if the Bereich or the user does not exist.
   * @throws BadRequestException if {@code bereichId} is not a Bereich, or the user belongs to a
   *     Staffel.
   */
  @Transactional
  public OrgUnitMembership addBereichLeader(
      @NotNull UUID bereichId, @NotNull UUID userId, @NotNull BereichLeadershipRole role) {
    OrgUnit bereich = Entities.require(orgUnitRepository.findById(bereichId), "Bereich not found");
    if (bereich.getKind() != OrgUnitKind.BEREICH) {
      throw new BadRequestException("Org unit " + bereichId + " is not a Bereich");
    }
    final User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    if (userHoldsStaffelMembership(userId)) {
      throw new BadRequestException(
          "User belongs to a Staffel and cannot be a Bereichsleitung member — remove the Staffel"
              + " membership first (REQ-ORG-017)");
    }
    OrgUnitMembership m =
        membershipRepository.findById(new OrgUnitMembershipId(userId, bereichId)).orElse(null);
    final MembershipRole previousRole = m != null ? m.getRole() : null;
    if (m == null) {
      m = new OrgUnitMembership();
      m.setId(new OrgUnitMembershipId(userId, bereichId));
      m.setUser(user);
      m.setKind(OrgUnitKind.BEREICH);
      m.setJoinedAt(Instant.now());
    }
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankResponsibilityServiceProvider.getObject().snapshotResponsibleHolders(bereichId);
    m.setRole(
        switch (role) {
          case LEITER -> MembershipRole.BEREICHSLEITER;
          case KOORDINATOR -> MembershipRole.BEREICHSKOORDINATOR;
          case OPERATOR -> MembershipRole.BEREICHSOPERATOR;
        });
    OrgUnitMembership saved = membershipRepository.saveAndFlush(m);
    orgChartService.mirrorBereichRole(bereich.getId(), userId, role);
    final boolean firstGrant = previousRole == null || previousRole == MembershipRole.MEMBER;
    auditService.record(
        firstGrant ? AuditEventType.ROLE_GRANTED : AuditEventType.ROLE_CHANGED,
        bereich.getId(),
        OrgUnitLabels.shorthandOrName(bereich),
        userId,
        firstGrant ? "role=" + saved.getRole() : "from=" + previousRole + " to=" + saved.getRole());
    orgUnitBankResponsibilityServiceProvider
        .getObject()
        .recordResponsibleHolderChanges(responsibleBefore);
    return saved;
  }

  /**
   * Removes a Bereichsleitung member by deleting their membership row on the Bereich.
   *
   * @param bereichId the Bereich; never {@code null}.
   * @param userId the user to remove; never {@code null}.
   * @throws NotFoundException if the user is not a member of the Bereich.
   */
  @Transactional
  public void removeBereichLeader(@NotNull UUID bereichId, @NotNull UUID userId) {
    OrgUnitMembershipId id = new OrgUnitMembershipId(userId, bereichId);
    OrgUnitMembership m =
        Entities.require(membershipRepository.findById(id), "Bereichsleitung membership not found");
    final MembershipRole previousRole = m.getRole();
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankResponsibilityServiceProvider.getObject().snapshotResponsibleHolders(bereichId);
    membershipRepository.delete(m);
    orgChartService.mirrorRemoveUnitSeat(bereichId, userId);
    auditService.record(
        AuditEventType.ROLE_REVOKED,
        bereichId,
        orgUnitLabelById(bereichId),
        userId,
        AuditDetails.of("role", previousRole));
    orgUnitBankResponsibilityServiceProvider
        .getObject()
        .recordResponsibleHolderChanges(responsibleBefore);
  }

  /**
   * Adds a user to the Organisationsleitung (epic #692, REQ-ORG-017), setting the {@code
   * is_ol_member} flag. The user must hold no Staffel membership (service guard + V165 trigger). A
   * duplicate add is rejected with 409.
   *
   * @param organisationsleitungId the OL org unit; must be of kind {@code ORGANISATIONSLEITUNG}.
   * @param userId the user to add; never {@code null}.
   * @return the persisted membership row.
   * @throws NotFoundException if the OL or the user does not exist.
   * @throws BadRequestException if the id is not the OL, or the user belongs to a Staffel.
   * @throws DuplicateEntityException if the user is already an OL member.
   */
  @Transactional
  public OrgUnitMembership addOlMember(@NotNull UUID organisationsleitungId, @NotNull UUID userId) {
    OrgUnit ol =
        Entities.require(
            orgUnitRepository.findById(organisationsleitungId), "Organisationsleitung not found");
    if (ol.getKind() != OrgUnitKind.ORGANISATIONSLEITUNG) {
      throw new BadRequestException(
          "Org unit " + organisationsleitungId + " is not the Organisationsleitung");
    }
    final User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    if (userHoldsStaffelMembership(userId)) {
      throw new BadRequestException(
          "User belongs to a Staffel and cannot be an Organisationsleitung member — remove the"
              + " Staffel membership first (REQ-ORG-017)");
    }
    if (membershipRepository.existsByIdUserIdAndIdOrgUnitId(userId, organisationsleitungId)) {
      throw new DuplicateEntityException("User is already an Organisationsleitung member");
    }
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, organisationsleitungId));
    m.setUser(user);
    m.setKind(OrgUnitKind.ORGANISATIONSLEITUNG);
    m.setJoinedAt(Instant.now());
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankResponsibilityServiceProvider
            .getObject()
            .snapshotResponsibleHolders(organisationsleitungId);
    m.setRole(MembershipRole.OL_MEMBER);
    final OrgUnitMembership saved = membershipRepository.saveAndFlush(m);
    orgChartService.mirrorOlMember(ol.getId(), userId);
    auditService.record(
        AuditEventType.ROLE_GRANTED,
        ol.getId(),
        OrgUnitLabels.shorthandOrName(ol),
        userId,
        "role=OL_MEMBER");
    orgUnitBankResponsibilityServiceProvider
        .getObject()
        .recordResponsibleHolderChanges(responsibleBefore);
    return saved;
  }

  /**
   * Removes a user from the Organisationsleitung by deleting their OL membership row.
   *
   * @param organisationsleitungId the OL org unit; never {@code null}.
   * @param userId the user to remove; never {@code null}.
   * @throws NotFoundException if the user is not an OL member.
   */
  @Transactional
  public void removeOlMember(@NotNull UUID organisationsleitungId, @NotNull UUID userId) {
    OrgUnitMembershipId id = new OrgUnitMembershipId(userId, organisationsleitungId);
    OrgUnitMembership m =
        Entities.require(
            membershipRepository.findById(id), "Organisationsleitung membership not found");
    final MembershipRole previousRole = m.getRole();
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankResponsibilityServiceProvider
            .getObject()
            .snapshotResponsibleHolders(organisationsleitungId);
    membershipRepository.delete(m);
    orgChartService.mirrorRemoveUnitSeat(organisationsleitungId, userId);
    orgUnitRepository
        .findById(organisationsleitungId)
        .filter(
            u -> u instanceof Organisationsleitung o && userId.equals(o.getGrandAdmiralUserId()))
        .ifPresent(
            u -> {
              ((Organisationsleitung) u).setGrandAdmiralUserId(null);
              orgUnitRepository.saveAndFlush(u);
            });
    auditService.record(
        AuditEventType.ROLE_REVOKED,
        organisationsleitungId,
        orgUnitLabelById(organisationsleitungId),
        userId,
        AuditDetails.of("role", previousRole));
    orgUnitBankResponsibilityServiceProvider
        .getObject()
        .recordResponsibleHolderChanges(responsibleBefore);
  }

  /**
   * Designates a user as the Grand Admiral (REQ-ORG-021) — the single OL member the org chart
   * renders at the very top of the Organisationsleitung. The holder keeps the {@code OL_MEMBER}
   * rank, so their rights are entirely unchanged; this only records the title. When the user is not
   * yet an OL member they are added as one first (auto-promote via {@link #addOlMember}, which runs
   * the Staffel-exclusion guard, mirrors the OL seat and audits the grant). Setting a new Grand
   * Admiral replaces any previous holder, who stays a plain OL member — the single {@code
   * grand_admiral_user_id} column is itself the org-wide "at most one" guarantee. Idempotent when
   * the user already holds the post.
   *
   * @param organisationsleitungId the OL org unit; never {@code null}.
   * @param userId the user to designate; never {@code null}.
   * @throws NotFoundException if the OL or the user does not exist.
   * @throws BadRequestException if the id is not the Organisationsleitung, or the user belongs to a
   *     Staffel (surfaced from {@link #addOlMember}).
   */
  @Transactional
  public void setGrandAdmiral(@NotNull UUID organisationsleitungId, @NotNull UUID userId) {
    Organisationsleitung ol = requireOrganisationsleitung(organisationsleitungId);
    Entities.require(userRepository.findPlainById(userId), "User not found");
    if (!membershipRepository.existsByIdUserIdAndIdOrgUnitId(userId, organisationsleitungId)) {
      addOlMember(organisationsleitungId, userId);
    }
    final UUID previous = ol.getGrandAdmiralUserId();
    if (userId.equals(previous)) {
      return;
    }
    ol.setGrandAdmiralUserId(userId);
    ol.setGrandAdmiralDisplayName(null);
    orgUnitRepository.saveAndFlush(ol);
    if (previous != null) {
      auditService.record(
          AuditEventType.ROLE_CHANGED,
          ol.getId(),
          OrgUnitLabels.shorthandOrName(ol),
          previous,
          AuditDetails.of("grandAdmiral", false));
    }
    auditService.record(
        AuditEventType.ROLE_CHANGED,
        ol.getId(),
        OrgUnitLabels.shorthandOrName(ol),
        userId,
        AuditDetails.of("grandAdmiral", true));
  }

  /**
   * Vacates the Grand Admiral post (REQ-ORG-021) by clearing the designation. The former Grand
   * Admiral stays a plain OL member — their {@code OL_MEMBER} membership is untouched. A no-op when
   * the post is already vacant.
   *
   * @param organisationsleitungId the OL org unit; never {@code null}.
   * @throws NotFoundException if the OL does not exist.
   * @throws BadRequestException if the id is not the Organisationsleitung.
   */
  @Transactional
  public void removeGrandAdmiral(@NotNull UUID organisationsleitungId) {
    Organisationsleitung ol = requireOrganisationsleitung(organisationsleitungId);
    final UUID previousUser = ol.getGrandAdmiralUserId();
    if (previousUser == null && ol.getGrandAdmiralDisplayName() == null) {
      return;
    }
    ol.setGrandAdmiralUserId(null);
    ol.setGrandAdmiralDisplayName(null);
    orgUnitRepository.saveAndFlush(ol);
    if (previousUser != null) {
      auditService.record(
          AuditEventType.ROLE_CHANGED,
          ol.getId(),
          OrgUnitLabels.shorthandOrName(ol),
          previousUser,
          AuditDetails.of("grandAdmiral", false));
    }
  }

  /**
   * Sets a <b>free-text</b> Grand Admiral (REQ-ORG-021): a typed name for an OL member who has no
   * Basetool account yet, the same account-XOR-freetext holder model every other chart position
   * uses (REQ-ORG-020). Grants nothing — it is a descriptive chart entry — so it makes no
   * membership change and is not audited. Supersedes any existing Grand Admiral (account or
   * free-text); the single {@code grand_admiral_*} column pair is the org-wide "at most one"
   * guarantee.
   *
   * @param organisationsleitungId the OL org unit; never {@code null}.
   * @param displayName the typed holder name; must not be blank.
   * @throws NotFoundException if the OL does not exist.
   * @throws BadRequestException if the id is not the Organisationsleitung, or the name is blank.
   */
  @Transactional
  public void setGrandAdmiralFreeText(
      @NotNull UUID organisationsleitungId, @NotNull String displayName) {
    final String trimmed = displayName.trim();
    if (trimmed.isEmpty()) {
      throw new BadRequestException("Grand Admiral name must not be blank");
    }
    Organisationsleitung ol = requireOrganisationsleitung(organisationsleitungId);
    ol.setGrandAdmiralDisplayName(trimmed);
    ol.setGrandAdmiralUserId(null);
    orgUnitRepository.saveAndFlush(ol);
  }

  /**
   * Loads an org unit by id and asserts it is the Organisationsleitung, returning the typed entity
   * so the Grand-Admiral designation column is reachable.
   *
   * @param organisationsleitungId the expected OL id; never {@code null}.
   * @return the typed {@link Organisationsleitung}; never {@code null}.
   * @throws NotFoundException if no org unit has that id.
   * @throws BadRequestException if the row exists but is not the Organisationsleitung.
   */
  private Organisationsleitung requireOrganisationsleitung(@NotNull UUID organisationsleitungId) {
    OrgUnit ol =
        Entities.require(
            orgUnitRepository.findById(organisationsleitungId), "Organisationsleitung not found");
    if (!(ol instanceof Organisationsleitung organisationsleitung)) {
      throw new BadRequestException(
          "Org unit " + organisationsleitungId + " is not the Organisationsleitung");
    }
    return organisationsleitung;
  }

  /**
   * Flips the per-membership Logistician / Mission Manager flags on the membership row. Either flag
   * may be {@code null} in the request — that means "leave the current value alone". The inbound
   * {@code version} is checked against the row's {@code @Version} to surface concurrent admin edits
   * as 409 instead of silently losing one of them.
   *
   * @param specialCommandId the SK whose membership to patch; never {@code null}.
   * @param userId the user whose membership to patch; never {@code null}.
   * @param request patch payload; never {@code null}.
   * @return the persisted membership row with the bumped {@code @Version}.
   * @throws NotFoundException if no SK matches the given id, or the user is not a member.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembership patchFlags(
      @NotNull UUID specialCommandId,
      @NotNull UUID userId,
      @NotNull MembershipFlagsPatchRequest request) {
    OrgUnitMembership m = loadMembership(specialCommandId, userId);
    assertVersionMatches(m, request.version());
    if (request.isLogistician() != null) {
      m.setLogistician(request.isLogistician());
    }
    if (request.isMissionManager() != null) {
      m.setMissionManager(request.isMissionManager());
    }
    OrgUnitMembership saved = membershipRepository.saveAndFlush(m);
    recordCapabilityFlagsChanged(specialCommandId, userId, saved);
    return saved;
  }

  /**
   * R6.e — Squadron-side counterpart of {@link #patchFlags(UUID, UUID,
   * MembershipFlagsPatchRequest)}. Same payload contract (boxed {@code Boolean} flags, mandatory
   * {@code version}) and same optimistic-lock semantics; only the existence check up front is
   * different — Squadrons live in the {@link SquadronRepository}, not the {@link
   * SpecialCommandRepository}. ADMIN-gated at the controller layer per plan §5.6 ({@code PATCH
   * /api/v1/squadrons/{id}/members/{userId}}). Used to migrate the legacy {@code
   * UserController.updateLogisticianStatus} / {@code updateMissionManagerStatus} writes from the
   * {@code app_user.is_logistician} / {@code is_mission_manager} columns onto the per-membership
   * row (R6.e write-side completion of plan D3).
   *
   * @param squadronId the Squadron whose membership to patch; never {@code null}.
   * @param userId the user whose membership to patch; never {@code null}.
   * @param request patch payload; never {@code null}.
   * @return the persisted membership row with the bumped {@code @Version}.
   * @throws NotFoundException if no Squadron matches the given id, or the user is not a member.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembership patchSquadronMemberFlags(
      @NotNull UUID squadronId,
      @NotNull UUID userId,
      @NotNull MembershipFlagsPatchRequest request) {
    Squadron squadron =
        Entities.require(squadronRepository.findById(squadronId), "Squadron not found");
    OrgUnitMembership m =
        Entities.require(
            membershipRepository.findById(new OrgUnitMembershipId(userId, squadron.getId())),
            "Membership not found");
    assertVersionMatches(m, request.version());
    if (request.isLogistician() != null) {
      m.setLogistician(request.isLogistician());
    }
    if (request.isMissionManager() != null) {
      m.setMissionManager(request.isMissionManager());
    }
    OrgUnitMembership saved = membershipRepository.saveAndFlush(m);
    auditService.record(
        AuditEventType.CAPABILITY_FLAGS_CHANGED,
        squadron.getId(),
        OrgUnitLabels.shorthandOrName(squadron),
        userId,
        AuditDetails.of("logistician", saved.isLogistician())
            .with("missionManager", saved.isMissionManager()));
    return saved;
  }

  /**
   * Reconciles the user's Staffel memberships to the supplied desired set (epic multi-Staffel,
   * REQ-ORG-017 — a user may belong to up to two Staffeln, each carrying its own per-squadron
   * Logistician / Mission-Manager flags per REQ-SEC-005). Backs the member-edit membership-delta
   * endpoint: the admin form posts the complete desired Staffel set, and this method adds the
   * squadrons that are not yet a membership, removes the current Staffel memberships absent from
   * the set, and patches the flags of the ones that stay — all in the caller's transaction.
   *
   * <p>Reconcile order matters: removals are deleted and flushed <em>before</em> any insert so the
   * V164 {@code enforce_max_two_squadron_memberships} counting trigger never miscounts an
   * about-to-be-removed row as a third Staffel during a re-point (e.g. {@code [A,B] → [A,C]}).
   * Additions and flag patches then run; a flag patch only writes (and only audits) when a value
   * actually changes, so re-posting an unchanged set is a clean no-op.
   *
   * <p>Guards (defence in depth ahead of the DB triggers, surfacing clean 400s):
   *
   * <ul>
   *   <li>duplicate squadron in the desired set → 400;
   *   <li>more than two desired squadrons → 400;
   *   <li>the user holds a silo-leader role (SK-Lead / Bereichsleitung / OL) while at least one
   *       Staffel is desired → 400 (a leader belongs to no Staffel, REQ-ORG-017).
   * </ul>
   *
   * <p>Inventory lifecycle: if this reconcile grants the user their first-ever org-unit membership
   * the ownerless-personal inventory adopts the name-sorted <em>primary</em> of the newly added
   * Staffeln (the same deterministic primary {@code UserDto.squadron} / the create-time auto-stamp
   * use, rather than whichever Staffel the client happened to list first); if it removes the user's
   * last remaining membership the inventory demotes back to ownerless-personal.
   *
   * @param user the user whose Staffel memberships to reconcile; never {@code null}.
   * @param desired the complete desired Staffel membership set (0–2 entries); never {@code null},
   *     possibly empty (which removes every Staffel membership).
   * @throws NotFoundException if a desired squadron id does not resolve to a Squadron.
   * @throws BadRequestException on a duplicate squadron, more than two squadrons, or a leadership
   *     conflict.
   */
  @Transactional
  public void reconcileStaffelMemberships(
      @NotNull User user, @NotNull List<MembershipDeltaRequest.StaffelChange> desired) {
    List<UUID> desiredIds =
        desired.stream().map(MembershipDeltaRequest.StaffelChange::squadronId).toList();
    Set<UUID> distinctIds = new LinkedHashSet<>(desiredIds);
    if (distinctIds.size() != desiredIds.size()) {
      throw new BadRequestException("A user cannot be assigned the same Staffel twice");
    }
    if (distinctIds.size() > 2) {
      throw new BadRequestException("A user may belong to at most two Staffeln (REQ-ORG-017)");
    }
    if (!desired.isEmpty() && userHoldsLeadershipRole(user.getId())) {
      throw new BadRequestException(
          "User holds a leadership role (SK-Lead/Bereichsleitung/OL) and cannot be assigned to a"
              + " Staffel — remove the leadership role first (REQ-ORG-017)");
    }

    Map<UUID, Squadron> targetSquadrons = new LinkedHashMap<>();
    for (UUID id : distinctIds) {
      Squadron sq =
          Entities.require(
              squadronRepository.findById(id), () -> "Squadron not found with id: " + id);
      targetSquadrons.put(id, sq);
    }

    final long membershipsBefore = membershipRepository.countByIdUserId(user.getId());
    List<OrgUnitMembership> currentStaffel =
        membershipRepository.findAllByIdUserIdAndKind(user.getId(), OrgUnitKind.SQUADRON);
    Map<UUID, OrgUnitMembership> currentById = new LinkedHashMap<>();
    for (OrgUnitMembership m : currentStaffel) {
      currentById.put(m.getId().getOrgUnitId(), m);
    }

    List<OrgUnitMembership> toRemove =
        currentStaffel.stream()
            .filter(m -> !distinctIds.contains(m.getId().getOrgUnitId()))
            .toList();
    if (!toRemove.isEmpty()) {
      final Map<UUID, Set<UUID>> responsibleBefore = new LinkedHashMap<>();
      for (OrgUnitMembership removed : toRemove) {
        responsibleBefore.putAll(
            orgUnitBankResponsibilityServiceProvider
                .getObject()
                .snapshotResponsibleHolders(removed.getId().getOrgUnitId()));
      }
      membershipRepository.deleteAll(toRemove);
      membershipRepository.flush();
      for (OrgUnitMembership removed : toRemove) {
        recordStaffelMembershipRevoked(removed.getId().getOrgUnitId(), user.getId());
        orgChartService.mirrorRemoveSquadronRank(removed.getId().getOrgUnitId(), user.getId());
      }
      orgUnitBankResponsibilityServiceProvider
          .getObject()
          .recordResponsibleHolderChanges(responsibleBefore);
    }

    List<Squadron> addedSquadrons = new ArrayList<>();
    for (MembershipDeltaRequest.StaffelChange change : desired) {
      UUID squadronId = change.squadronId();
      boolean wantLogistician = Boolean.TRUE.equals(change.isLogistician());
      boolean wantMissionManager = Boolean.TRUE.equals(change.isMissionManager());
      OrgUnitMembership existing = currentById.get(squadronId);
      if (existing != null) {
        boolean changed =
            existing.isLogistician() != wantLogistician
                || existing.isMissionManager() != wantMissionManager;
        if (changed) {
          existing.setLogistician(wantLogistician);
          existing.setMissionManager(wantMissionManager);
          OrgUnitMembership saved = membershipRepository.saveAndFlush(existing);
          recordCapabilityFlagsChanged(squadronId, user.getId(), saved);
        }
      } else {
        OrgUnitMembership fresh = new OrgUnitMembership();
        fresh.setId(new OrgUnitMembershipId(user.getId(), squadronId));
        fresh.setUser(user);
        fresh.setJoinedAt(Instant.now());
        fresh.setLogistician(wantLogistician);
        fresh.setMissionManager(wantMissionManager);
        membershipRepository.save(fresh);
        Squadron sq = targetSquadrons.get(squadronId);
        auditService.record(
            AuditEventType.MEMBERSHIP_GRANTED,
            squadronId,
            OrgUnitLabels.shorthandOrName(sq),
            user.getId(),
            "kind=SQUADRON");
        addedSquadrons.add(sq);
      }
    }

    if (membershipsBefore == 0 && !addedSquadrons.isEmpty()) {
      Squadron primaryAdded =
          addedSquadrons.stream()
              .min(Comparator.comparing(Squadron::getName, String.CASE_INSENSITIVE_ORDER))
              .orElseThrow();
      inventoryReconciler.onUserGainedFirstOrgUnit(user.getId(), primaryAdded);
    } else if (membershipsBefore > 0 && membershipRepository.countByIdUserId(user.getId()) == 0) {
      inventoryReconciler.onUserLostLastOrgUnit(user.getId());
    }
  }

  /**
   * Flips the {@code is_lead} flag on the membership row. ADMIN-only at the controller layer — a
   * member-managing Lead cannot promote themselves or someone else to Lead. Carries an
   * optimistic-lock version like {@link #patchFlags}.
   *
   * @param specialCommandId the SK whose membership to update; never {@code null}.
   * @param userId the user whose membership to update; never {@code null}.
   * @param request toggle payload; never {@code null}.
   * @return the persisted membership row.
   * @throws NotFoundException if no SK matches the given id, or the user is not a member.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembership toggleLead(
      @NotNull UUID specialCommandId,
      @NotNull UUID userId,
      @NotNull MembershipLeadToggleRequest request) {
    OrgUnitMembership m = loadMembership(specialCommandId, userId);
    assertVersionMatches(m, request.version());
    if (request.isLead() && userHoldsStaffelMembership(userId)) {
      throw new BadRequestException(
          "User belongs to a Staffel and cannot be made an SK lead — remove the Staffel membership"
              + " first (REQ-ORG-017)");
    }
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankResponsibilityServiceProvider
            .getObject()
            .snapshotResponsibleHolders(specialCommandId);
    m.setRole(request.isLead() ? MembershipRole.SK_LEAD : MembershipRole.MEMBER);
    final OrgUnitMembership saved = membershipRepository.saveAndFlush(m);
    orgChartService.mirrorSkLead(specialCommandId, userId, request.isLead());
    auditService.record(
        request.isLead() ? AuditEventType.ROLE_GRANTED : AuditEventType.ROLE_REVOKED,
        specialCommandId,
        orgUnitLabelById(specialCommandId),
        userId,
        "role=SK_LEAD");
    orgUnitBankResponsibilityServiceProvider
        .getObject()
        .recordResponsibleHolderChanges(responsibleBefore);
    return saved;
  }

  /**
   * Assigns (or changes) a squadron leadership rank on an existing Staffel member (epic #800,
   * REQ-ROLE-003/004): Staffelleiter / Kommandoleiter / stellv. Kommandoleiter / Ensign, optionally
   * bound to a Kommandogruppe. The target user must already be a member of the Staffel; the rank
   * must be a squadron rank; the Kommandogruppe pairing and the cardinality caps (&le;1
   * Staffelleiter per squadron, &le;1 Kommandoleiter + &le;1 stellv. per group, &le;4 Ensigns per
   * squadron) are enforced here with clean 400s, complementing the V185 DB CHECK. Squadron ranks
   * are exempt from the V165 {@code enforce_leader_excludes_squadron} trigger (they <em>are</em>
   * Staffel members) — they set only the {@code role} and the {@code kommandoGroup}.
   *
   * @param squadronId the Staffel; must be a {@code SQUADRON} org unit.
   * @param userId the member to assign the rank to; must already be a member of this Staffel.
   * @param rank the squadron rank to set; must be a squadron rank.
   * @param kommandoGroupId the Kommandogruppe to bind, or {@code null}; constrained per the rank.
   * @param version the optimistic-lock version of the member's row, or {@code null} to skip it.
   * @return the persisted membership row with the bumped version.
   * @throws NotFoundException if the user is not a member of this Staffel, or the group is unknown.
   * @throws BadRequestException on a non-squadron rank, a bad group pairing, or a cardinality
   *     breach.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembership assignSquadronRank(
      @NotNull UUID squadronId,
      @NotNull UUID userId,
      @NotNull MembershipRole rank,
      @Nullable UUID kommandoGroupId,
      @Nullable Long version) {
    if (!rank.isSquadronRank()) {
      throw new BadRequestException("Rank " + rank + " is not a squadron rank");
    }
    OrgUnitMembership m =
        Entities.require(
            membershipRepository.findById(new OrgUnitMembershipId(userId, squadronId)),
            "User is not a member of this Staffel");
    if (m.getKind() != OrgUnitKind.SQUADRON) {
      throw new BadRequestException("Org unit " + squadronId + " is not a Staffel");
    }
    assertVersionMatches(m, version);

    KommandoGroup group = resolveKommandoGroupForRank(squadronId, rank, kommandoGroupId);
    assertSquadronRankCardinality(squadronId, userId, rank, group);

    final MembershipRole previousRole = m.getRole();
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankResponsibilityServiceProvider.getObject().snapshotResponsibleHolders(squadronId);
    m.setRole(rank);
    m.setKommandoGroup(group);
    final OrgUnitMembership saved = membershipRepository.saveAndFlush(m);
    orgChartService.mirrorSquadronRank(squadronId, userId, rank, group);

    final boolean firstGrant = previousRole == MembershipRole.MEMBER;
    auditService.record(
        firstGrant ? AuditEventType.ROLE_GRANTED : AuditEventType.ROLE_CHANGED,
        squadronId,
        orgUnitLabelById(squadronId),
        userId,
        firstGrant ? "role=" + rank : "from=" + previousRole + " to=" + rank);
    orgUnitBankResponsibilityServiceProvider
        .getObject()
        .recordResponsibleHolderChanges(responsibleBefore);
    return saved;
  }

  /**
   * Clears a member's squadron leadership rank back to plain {@link MembershipRole#MEMBER} (and
   * unbinds any Kommandogruppe) without removing the Staffel membership itself (epic #800,
   * REQ-ROLE-004).
   *
   * @param squadronId the Staffel; never {@code null}.
   * @param userId the member whose rank to clear; never {@code null}.
   * @param version the optimistic-lock version of the member's row, or {@code null} to skip it.
   * @return the persisted membership row with the bumped version.
   * @throws NotFoundException if the user is not a member of this Staffel.
   * @throws BadRequestException if the member holds no squadron rank.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembership removeSquadronRank(
      @NotNull UUID squadronId, @NotNull UUID userId, @Nullable Long version) {
    OrgUnitMembership m =
        Entities.require(
            membershipRepository.findById(new OrgUnitMembershipId(userId, squadronId)),
            "User is not a member of this Staffel");
    assertVersionMatches(m, version);
    final MembershipRole previousRole = m.getRole();
    if (!previousRole.isSquadronRank()) {
      throw new BadRequestException("Member holds no squadron rank to remove");
    }
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankResponsibilityServiceProvider.getObject().snapshotResponsibleHolders(squadronId);
    m.setRole(MembershipRole.MEMBER);
    m.setKommandoGroup(null);
    final OrgUnitMembership saved = membershipRepository.saveAndFlush(m);
    orgChartService.mirrorRemoveSquadronRank(squadronId, userId);
    auditService.record(
        AuditEventType.ROLE_REVOKED,
        squadronId,
        orgUnitLabelById(squadronId),
        userId,
        AuditDetails.of("role", previousRole));
    orgUnitBankResponsibilityServiceProvider
        .getObject()
        .recordResponsibleHolderChanges(responsibleBefore);
    return saved;
  }

  /**
   * DTO projection of {@link #addMember(UUID, UUID)}: adds the user and returns the persisted
   * membership as its response DTO, mapped inside the write transaction.
   *
   * @param specialCommandId the SK to add the user to; never {@code null}.
   * @param userId the user to add; never {@code null}.
   * @return the persisted membership DTO with role flags defaulted to {@code false}.
   * @throws NotFoundException if no SK matches the given id, or no user matches the given id.
   * @throws DuplicateEntityException if the user is already a member of this SK.
   */
  @Transactional
  public OrgUnitMembershipDto addMemberDto(@NotNull UUID specialCommandId, @NotNull UUID userId) {
    return orgUnitMembershipMapper.toDto(addMember(specialCommandId, userId));
  }

  /**
   * DTO projection of {@link #patchFlags(UUID, UUID, MembershipFlagsPatchRequest)}: patches the SK
   * membership's role flags and returns the persisted membership as its response DTO, mapped inside
   * the write transaction.
   *
   * @param specialCommandId the SK whose membership to patch; never {@code null}.
   * @param userId the user whose membership to patch; never {@code null}.
   * @param request patch payload; never {@code null}.
   * @return the persisted membership DTO with the bumped {@code @Version}.
   * @throws NotFoundException if no SK matches the given id, or the user is not a member.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembershipDto patchFlagsDto(
      @NotNull UUID specialCommandId,
      @NotNull UUID userId,
      @NotNull MembershipFlagsPatchRequest request) {
    return orgUnitMembershipMapper.toDto(patchFlags(specialCommandId, userId, request));
  }

  /**
   * DTO projection of {@link #patchSquadronMemberFlags(UUID, UUID, MembershipFlagsPatchRequest)}:
   * patches the Squadron membership's role flags and returns the persisted membership as its
   * response DTO, mapped inside the write transaction.
   *
   * @param squadronId the Squadron whose membership to patch; never {@code null}.
   * @param userId the user whose membership to patch; never {@code null}.
   * @param request patch payload; never {@code null}.
   * @return the persisted membership DTO with the bumped {@code @Version}.
   * @throws NotFoundException if no Squadron matches the given id, or the user is not a member.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembershipDto patchSquadronMemberFlagsDto(
      @NotNull UUID squadronId,
      @NotNull UUID userId,
      @NotNull MembershipFlagsPatchRequest request) {
    return orgUnitMembershipMapper.toDto(patchSquadronMemberFlags(squadronId, userId, request));
  }

  /**
   * DTO projection of {@link #toggleLead(UUID, UUID, MembershipLeadToggleRequest)}: promotes /
   * demotes the SK member and returns the persisted membership as its response DTO, mapped inside
   * the write transaction.
   *
   * @param specialCommandId the SK whose membership to update; never {@code null}.
   * @param userId the user whose membership to update; never {@code null}.
   * @param request toggle payload; never {@code null}.
   * @return the persisted membership DTO with the bumped {@code @Version}.
   * @throws NotFoundException if no SK matches the given id, or the user is not a member.
   * @throws BadRequestException if the user still belongs to a Staffel (REQ-ORG-017).
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembershipDto toggleLeadDto(
      @NotNull UUID specialCommandId,
      @NotNull UUID userId,
      @NotNull MembershipLeadToggleRequest request) {
    return orgUnitMembershipMapper.toDto(toggleLead(specialCommandId, userId, request));
  }

  /**
   * DTO projection of {@link #assignSquadronRank(UUID, UUID, MembershipRole, UUID, Long)}: assigns
   * the squadron rank and returns the persisted membership as its response DTO, mapped inside the
   * write transaction.
   *
   * @param squadronId the Staffel; must be a {@code SQUADRON} org unit.
   * @param userId the member to assign the rank to; must already be a member of this Staffel.
   * @param rank the squadron rank to set; must be a squadron rank.
   * @param kommandoGroupId the Kommandogruppe to bind, or {@code null}; constrained per the rank.
   * @param version the optimistic-lock version of the member's row, or {@code null} to skip it.
   * @return the persisted membership DTO with the bumped version.
   * @throws NotFoundException if the user is not a member of this Staffel, or the group is unknown.
   * @throws BadRequestException on a non-squadron rank, a bad group pairing, or a cardinality
   *     breach.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembershipDto assignSquadronRankDto(
      @NotNull UUID squadronId,
      @NotNull UUID userId,
      @NotNull MembershipRole rank,
      @Nullable UUID kommandoGroupId,
      @Nullable Long version) {
    return orgUnitMembershipMapper.toDto(
        assignSquadronRank(squadronId, userId, rank, kommandoGroupId, version));
  }

  /**
   * DTO projection of {@link #removeSquadronRank(UUID, UUID, Long)}: clears the member's squadron
   * rank and returns the persisted membership as its response DTO, mapped inside the write
   * transaction.
   *
   * @param squadronId the Staffel; never {@code null}.
   * @param userId the member whose rank to clear; never {@code null}.
   * @param version the optimistic-lock version of the member's row, or {@code null} to skip it.
   * @return the persisted membership DTO with the bumped version.
   * @throws NotFoundException if the user is not a member of this Staffel.
   * @throws BadRequestException if the member holds no squadron rank.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembershipDto removeSquadronRankDto(
      @NotNull UUID squadronId, @NotNull UUID userId, @Nullable Long version) {
    return orgUnitMembershipMapper.toDto(removeSquadronRank(squadronId, userId, version));
  }

  /**
   * Resolves and validates the Kommandogruppe binding for a squadron rank against the V185 pairing
   * CHECK: Kommandoleiter / stellv. Kommandoleiter MUST reference a group; Ensign MAY;
   * Staffelleiter MUST NOT. A referenced group must exist and belong to the same squadron.
   *
   * @param squadronId the Staffel the rank is on; never {@code null}.
   * @param rank the squadron rank being assigned; never {@code null}.
   * @param kommandoGroupId the requested group id, or {@code null}.
   * @return the resolved group, or {@code null} when the rank carries no group.
   * @throws NotFoundException if a referenced group does not exist.
   * @throws BadRequestException on a group pairing that violates the rank's contract.
   */
  @Nullable
  private KommandoGroup resolveKommandoGroupForRank(
      @NotNull UUID squadronId, @NotNull MembershipRole rank, @Nullable UUID kommandoGroupId) {
    boolean groupRequired =
        rank == MembershipRole.KOMMANDOLEITER || rank == MembershipRole.STELLV_KOMMANDOLEITER;
    boolean groupAllowed = groupRequired || rank == MembershipRole.ENSIGN;
    if (kommandoGroupId == null) {
      if (groupRequired) {
        throw new BadRequestException(rank + " must be assigned to a Kommandogruppe");
      }
      return null;
    }
    if (!groupAllowed) {
      throw new BadRequestException(rank + " must not be assigned to a Kommandogruppe");
    }
    KommandoGroup group =
        Entities.require(
            kommandoGroupRepository.findById(kommandoGroupId), "Kommandogruppe not found");
    if (!group.getSquadron().getId().equals(squadronId)) {
      throw new BadRequestException("Kommandogruppe does not belong to this Staffel");
    }
    return group;
  }

  /**
   * Enforces the squadron-rank cardinality caps against the current roster, excluding the target
   * user so re-assigning the same user is idempotent: &le;1 Staffelleiter per squadron, &le;1
   * Kommandoleiter + &le;1 stellv. Kommandoleiter per group, &le;4 Ensigns per squadron.
   *
   * <p>This in-memory check gives a clean 4xx for the common case; the three singleton caps are
   * additionally backstopped by the {@code V188} partial unique indexes ({@code
   * uq_org_unit_membership_one_staffelleiter} / {@code _one_kommandoleiter_per_group} / {@code
   * _one_stellv_per_group}), so a concurrent double-assign that slips past the roster scan fails on
   * the constraint rather than committing a duplicate. The &le;4 Ensign cap stays
   * service-layer-only (a count, not a uniqueness rule), like the org chart's own &le;4 ENSIGN cap.
   *
   * @param squadronId the Staffel; never {@code null}.
   * @param userId the user being (re)assigned, excluded from the roster scan; never {@code null}.
   * @param rank the squadron rank being assigned; never {@code null}.
   * @param group the resolved Kommandogruppe (non-null for Kommandoleiter / stellv.); may be {@code
   *     null}.
   * @throws BadRequestException when the assignment would breach a cardinality cap.
   */
  private void assertSquadronRankCardinality(
      @NotNull UUID squadronId,
      @NotNull UUID userId,
      @NotNull MembershipRole rank,
      @Nullable KommandoGroup group) {
    List<OrgUnitMembership> roster =
        membershipRepository.findAllByIdOrgUnitId(squadronId).stream()
            .filter(m -> !m.getId().getUserId().equals(userId))
            .toList();
    switch (rank) {
      case STAFFELLEITER -> {
        if (roster.stream().anyMatch(m -> m.getRole() == MembershipRole.STAFFELLEITER)) {
          throw new BadRequestException("This Staffel already has a Staffelleiter");
        }
      }
      case KOMMANDOLEITER, STELLV_KOMMANDOLEITER -> {
        UUID groupId = group.getId();
        boolean taken =
            roster.stream()
                .anyMatch(
                    m ->
                        m.getRole() == rank
                            && m.getKommandoGroup() != null
                            && groupId.equals(m.getKommandoGroup().getId()));
        if (taken) {
          throw new BadRequestException("This Kommandogruppe already has a " + rank);
        }
      }
      case ENSIGN -> {
        long ensigns = roster.stream().filter(m -> m.getRole() == MembershipRole.ENSIGN).count();
        if (ensigns >= 4) {
          throw new BadRequestException("This Staffel already has the maximum of 4 Ensigns");
        }
      }
      default -> throw new BadRequestException("Rank " + rank + " is not a squadron rank");
    }
  }

  /**
   * Loads the membership row for the given (SK, user) pair, validating SK existence first so a
   * stale SK id surfaces as 404 with a clear message before the membership lookup fires.
   *
   * @param specialCommandId the SK id.
   * @param userId the user id.
   * @return the membership row.
   * @throws NotFoundException if no SK matches the given id, or the user is not a member.
   */
  private OrgUnitMembership loadMembership(UUID specialCommandId, UUID userId) {
    SpecialCommand sc = specialCommandService.getSpecialCommandById(specialCommandId);
    return Entities.require(
        membershipRepository.findById(new OrgUnitMembershipId(userId, sc.getId())),
        "Membership not found");
  }

  /**
   * Throws {@link ObjectOptimisticLockingFailureException} if the inbound client-held version does
   * not match the persisted row's {@code @Version}. Mirrors the pattern used in {@code
   * SpecialCommandService.updateSpecialCommand} so the optimistic-lock surface is uniform across
   * the SK administration endpoints.
   */
  private void assertVersionMatches(@NotNull OrgUnitMembership m, Long version) {
    OptimisticLock.check(m.getVersion(), version, OrgUnitMembership.class, null);
  }

  /**
   * {@code true} iff the user holds at least one Staffel ({@code SQUADRON}) membership. Backs the
   * REQ-ORG-017 guard that an SK-Leiter must belong to no Staffel.
   *
   * @param userId the user to check; never {@code null}.
   * @return {@code true} when the user has a Staffel membership row.
   */
  private boolean userHoldsStaffelMembership(@NotNull UUID userId) {
    return !membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON).isEmpty();
  }

  /**
   * {@code true} iff the user holds a <em>silo-leader</em> rank on any membership — an SK-Leiter
   * ({@link MembershipRole#SK_LEAD}), a Bereichsleitung rank, or the OL ({@link
   * MembershipRole#isAreaOrOl()}). The four squadron ranks are deliberately <b>exempt</b>: they are
   * held by Staffel members, so they must not trip this guard. Backs the REQ-ORG-017 guard that a
   * silo leader is never (also) assigned to a Staffel.
   *
   * @param userId the user to check; never {@code null}.
   * @return {@code true} when any of the user's membership rows carries a silo-leader rank.
   */
  private boolean userHoldsLeadershipRole(@NotNull UUID userId) {
    return membershipRepository.findAllByIdUserId(userId).stream()
        .anyMatch(m -> m.getRole() == MembershipRole.SK_LEAD || m.getRole().isAreaOrOl());
  }

  /**
   * Records a {@link AuditEventType#MEMBERSHIP_REVOKED} event for a removed Staffel membership
   * (epic #800, REQ-AUDIT-001). Extracted so the {@link #reconcileStaffelMemberships} delete
   * branches stay readable; the details payload carries only the org-unit kind (no PII).
   *
   * @param squadronOrgUnitId the Staffel the removed membership pointed at; never {@code null}.
   * @param userId the user whose Staffel membership was removed; never {@code null}.
   */
  private void recordStaffelMembershipRevoked(
      @NotNull UUID squadronOrgUnitId, @NotNull UUID userId) {
    auditService.record(
        AuditEventType.MEMBERSHIP_REVOKED,
        squadronOrgUnitId,
        orgUnitLabelById(squadronOrgUnitId),
        userId,
        "kind=SQUADRON");
  }

  /**
   * Records a {@link AuditEventType#CAPABILITY_FLAGS_CHANGED} event capturing the resulting
   * Logistician / Mission-Manager flag values on a membership (epic #800, REQ-AUDIT-001). The
   * details payload holds only the two boolean values (no PII / no free text).
   *
   * @param orgUnitId the org unit the membership belongs to; never {@code null}.
   * @param userId the affected user; never {@code null}.
   * @param saved the persisted membership row whose flags were changed; never {@code null}.
   */
  private void recordCapabilityFlagsChanged(
      @NotNull UUID orgUnitId, @NotNull UUID userId, @NotNull OrgUnitMembership saved) {
    auditService.record(
        AuditEventType.CAPABILITY_FLAGS_CHANGED,
        orgUnitId,
        orgUnitLabelById(orgUnitId),
        userId,
        AuditDetails.of("logistician", saved.isLogistician())
            .with("missionManager", saved.isMissionManager()));
  }

  /**
   * Loads an org unit by id and resolves its audit {@code subjectLabel} via {@link
   * OrgUnitLabels#shorthandOrName(OrgUnit)}, or {@code null} when the org unit cannot be resolved
   * (e.g. already deleted). Used by the delete / patch audit paths that hold only the org-unit id.
   *
   * @param orgUnitId the org unit id; never {@code null}.
   * @return the org unit's shorthand/name label, or {@code null}.
   */
  private @Nullable String orgUnitLabelById(@NotNull UUID orgUnitId) {
    return orgUnitRepository.findById(orgUnitId).map(OrgUnitLabels::shorthandOrName).orElse(null);
  }
}
