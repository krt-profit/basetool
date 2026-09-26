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
 * Writes org-unit memberships: Spezialkommando members, Staffel membership reconciliation and
 * flags, squadron ranks, Bereichsleitung, OL and Grand Admiral.
 *
 * <p>SK entry points load the SK via {@link SpecialCommandService#getSpecialCommandById(UUID)}
 * first, so a non-SK id yields 404. Versioned writes throw {@link
 * ObjectOptimisticLockingFailureException} (409) on a stale version. Staffel memberships change
 * through {@link #reconcileStaffelMemberships(User, java.util.List)} and {@link
 * #patchSquadronMemberFlags(UUID, UUID, MembershipFlagsPatchRequest)}; read queries live in {@link
 * OrgUnitMembershipQueryService}.
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
   * Lazily resolved seam that audits changes to the accounts' responsible holders around each
   * leadership mutation (REQ-BANK-034); all bank access stays in {@link
   * OrgUnitBankResponsibilityService}.
   */
  private final ObjectProvider<OrgUnitBankResponsibilityService>
      orgUnitBankResponsibilityServiceProvider;

  /**
   * Adds the user as a member of the Spezialkommando.
   *
   * @param specialCommandId the SK to add the user to; never {@code null}.
   * @param userId the user to add; never {@code null}.
   * @return the persisted membership row, with its {@code kind} set on the entity.
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
   * Grants a user a Bereichsleitung role on the Bereich (REQ-ORG-017), updating an existing
   * membership row in place or creating one; exactly one Bereich role flag ends up set.
   *
   * <p>The user must hold no Staffel membership. The user's ownerless inventory is not adopted.
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
   * Adds a user to the Organisationsleitung (REQ-ORG-017); the user must hold no Staffel
   * membership.
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
   * Designates a user as the Grand Admiral (REQ-ORG-021), a title that changes no rights; a user
   * not yet in the OL is added first via {@link #addOlMember}. Replaces any previous holder, who
   * stays an OL member; idempotent.
   *
   * @param organisationsleitungId the OL org unit; never {@code null}.
   * @param userId the user to designate; never {@code null}.
   * @throws NotFoundException if the OL or the user does not exist.
   * @throws BadRequestException if the id is not the Organisationsleitung, or the user belongs to a
   *     Staffel.
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
   * Sets a free-text Grand Admiral (REQ-ORG-021) for an OL member without a Basetool account,
   * replacing any existing holder. Grants nothing, changes no membership and is not audited.
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
   * Updates the Logistician / Mission Manager flags of an SK membership; a {@code null} flag leaves
   * the current value.
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
   * Updates the Logistician / Mission Manager flags of a Staffel membership, with the same contract
   * as {@link #patchFlags(UUID, UUID, MembershipFlagsPatchRequest)}.
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
   * Reconciles the user's Staffel memberships to the desired set (at most two, REQ-ORG-017): adds
   * missing ones, removes absent ones and patches the flags of the rest, auditing only real
   * changes.
   *
   * <p>Removals are flushed before inserts. A first-ever membership moves the user's ownerless
   * inventory to the primary new Staffel; removing the last membership makes it ownerless again.
   *
   * @param user the user whose Staffel memberships to reconcile; never {@code null}.
   * @param desired the complete desired Staffel membership set (0–2 entries); never {@code null},
   *     possibly empty (which removes every Staffel membership).
   * @throws NotFoundException if a desired squadron id does not resolve to a Squadron.
   * @throws BadRequestException on a duplicate squadron, more than two squadrons, or a user holding
   *     a silo-leader role.
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
   * Flips the {@code is_lead} flag on an SK membership.
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
   * Assigns a squadron rank (Staffelleiter, Kommandoleiter, stellv. Kommandoleiter or Ensign) to an
   * existing Staffel member, optionally bound to a Kommandogruppe (REQ-ROLE-003).
   *
   * <p>Enforces the group pairing and the caps: &le;1 Staffelleiter per squadron, &le;1
   * Kommandoleiter and &le;1 stellv. per group, &le;4 Ensigns per squadron.
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
   * Resets a member's squadron rank to {@link MembershipRole#MEMBER} and unbinds any
   * Kommandogruppe, keeping the Staffel membership (REQ-ROLE-004).
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
   * Resolves and validates the Kommandogruppe for a squadron rank: Kommandoleiter and stellv.
   * Kommandoleiter must reference a group, Ensign may, Staffelleiter must not. The group must exist
   * and belong to the same squadron.
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
   * Enforces the squadron-rank caps against the current roster, excluding the target user: &le;1
   * Staffelleiter per squadron, &le;1 Kommandoleiter and &le;1 stellv. Kommandoleiter per group,
   * &le;4 Ensigns per squadron.
   *
   * <p>The three singleton caps are also backed by unique indexes; the Ensign cap is enforced only
   * here.
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
   * Answers whether the user holds a silo-leader rank: {@link MembershipRole#SK_LEAD}, a
   * Bereichsleitung rank or the OL ({@link MembershipRole#isAreaOrOl()}). Squadron ranks do not
   * count.
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
   * (REQ-AUDIT-001); the details carry only the org-unit kind.
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
   * Records a {@link AuditEventType#CAPABILITY_FLAGS_CHANGED} event with the resulting Logistician
   * / Mission-Manager flag values of a membership (REQ-AUDIT-001).
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
   * Resolves an org unit's audit {@code subjectLabel} via {@link
   * OrgUnitLabels#shorthandOrName(OrgUnit)}.
   *
   * @param orgUnitId the org unit id; never {@code null}.
   * @return the org unit's shorthand/name label, or {@code null} when it cannot be resolved.
   */
  private @Nullable String orgUnitLabelById(@NotNull UUID orgUnitId) {
    return orgUnitRepository.findById(orgUnitId).map(OrgUnitLabels::shorthandOrName).orElse(null);
  }
}
