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
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.KommandoGroupMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.KommandoGroup;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateKommandoGroupRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.KommandoGroupDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateKommandoGroupRequest;
import de.greluc.krt.profit.basetool.backend.repository.KommandoGroupRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.OrgUnitLabels;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD for Kommandogruppen — the named sub-structures of a Staffel (epic #800, REQ-ROLE-003). A
 * Kommandogruppe is descriptive structure only; it grants no rights. The rank-bearing authority
 * sits on a member's {@code org_unit_membership.role} row, assigned via {@code
 * OrgUnitMembershipService}.
 *
 * <p>Cardinality and shape are guarded both here (clean 400s) and at the DB layer (V185): at most
 * four groups per squadron, the parent must be a {@code SQUADRON}, and a group still bound to a
 * Kommandoleiter / stellv. Kommandoleiter / Ensign cannot be deleted. Every mutation is recorded in
 * the {@code ROLE} activity audit log (REQ-AUDIT-001) in the same transaction.
 *
 * <p>Each group mutation also mirrors onto the descriptive org chart (epic #800, REQ-ROLE-006): a
 * create adds a leaderless {@code COMMAND_LEAD} Kommando node tied to the group, a rename / reorder
 * updates it, and a delete removes it — all in the same transaction via {@link OrgChartService}.
 * The chart stays descriptive (grants nothing); the rank-bearing authority remains on the
 * membership row.
 *
 * <p>Class-level {@code @Transactional(readOnly = true)}; the mutating methods override it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class KommandoGroupService {

  /** The hard cap on Kommandogruppen per squadron, mirrored by the V185 counting trigger. */
  private static final long MAX_GROUPS_PER_SQUADRON = 4;

  private final KommandoGroupRepository kommandoGroupRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final OrgUnitMembershipRepository membershipRepository;
  private final AuditService auditService;
  private final OrgChartService orgChartService;
  private final KommandoGroupMapper kommandoGroupMapper;

  /**
   * Lists the Kommandogruppen of a Staffel in display order.
   *
   * @param squadronId the Staffel whose groups to list; must be a {@code SQUADRON} org unit.
   * @return the squadron's groups, ascending by sort index; never {@code null}, possibly empty.
   * @throws NotFoundException if no org unit matches the id.
   * @throws BadRequestException if the org unit is not a Staffel.
   */
  @NotNull
  public List<KommandoGroupDto> listGroups(@NotNull UUID squadronId) {
    requireSquadron(squadronId);
    return kommandoGroupRepository.findBySquadronIdOrderBySortIndexAsc(squadronId).stream()
        .map(kommandoGroupMapper::toDto)
        .toList();
  }

  /**
   * Creates a Kommandogruppe at the end of a Staffel's order. Rejects a non-Staffel parent and a
   * fifth group with a clean 400 before the V185 triggers would turn either into a 500.
   *
   * @param squadronId the Staffel to create the group in; must be a {@code SQUADRON} org unit.
   * @param request the create payload (group name); never {@code null}.
   * @return the persisted group.
   * @throws NotFoundException if no org unit matches the id.
   * @throws BadRequestException if the org unit is not a Staffel, or the squadron already holds
   *     four groups.
   */
  @Transactional
  @NotNull
  public KommandoGroupDto createGroup(
      @NotNull UUID squadronId, @NotNull CreateKommandoGroupRequest request) {
    OrgUnit squadron = requireSquadron(squadronId);
    long existing = kommandoGroupRepository.countBySquadronId(squadronId);
    if (existing >= MAX_GROUPS_PER_SQUADRON) {
      throw new BadRequestException(
          "A squadron may have at most " + MAX_GROUPS_PER_SQUADRON + " Kommandogruppen");
    }
    KommandoGroup group =
        KommandoGroup.builder()
            .squadron(squadron)
            .name(request.name().strip())
            .sortIndex((int) existing)
            .build();
    KommandoGroup saved = kommandoGroupRepository.saveAndFlush(group);
    // Mirror the group onto the descriptive chart as a leaderless Kommando node (REQ-ROLE-006).
    orgChartService.mirrorCreateKommandoGroup(saved);
    auditService.record(
        AuditEventType.KOMMANDO_GROUP_CREATED,
        saved.getId(),
        saved.getName(),
        null,
        AuditDetails.of("squadron", OrgUnitLabels.shorthandOrName(squadron)));
    return kommandoGroupMapper.toDto(saved);
  }

  /**
   * Renames and/or reorders a Kommandogruppe. The inbound {@code version} is checked against the
   * row's {@code @Version} to surface a concurrent edit as a 409.
   *
   * @param groupId the group to update; never {@code null}.
   * @param request the update payload (name + sort index + version); never {@code null}.
   * @return the persisted group with the bumped version.
   * @throws NotFoundException if no group matches the id.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  @NotNull
  public KommandoGroupDto updateGroup(
      @NotNull UUID groupId, @NotNull UpdateKommandoGroupRequest request) {
    KommandoGroup group =
        Entities.require(kommandoGroupRepository.findById(groupId), "Kommandogruppe not found");
    assertVersionMatches(group, request.version());
    group.setName(request.name().strip());
    group.setSortIndex(request.sortIndex());
    KommandoGroup saved = kommandoGroupRepository.saveAndFlush(group);
    // Mirror the rename / reorder onto the Kommando node (REQ-ROLE-006).
    orgChartService.mirrorUpdateKommandoGroup(saved);
    auditService.record(
        AuditEventType.KOMMANDO_GROUP_UPDATED, saved.getId(), saved.getName(), null, null);
    return kommandoGroupMapper.toDto(saved);
  }

  /**
   * Deletes a Kommandogruppe. Rejects the delete with a clean 400 while any membership is still
   * assigned to the group (the members must be reassigned first), so the V185 group-link CHECK can
   * never be violated and no member is silently orphaned.
   *
   * @param groupId the group to delete; never {@code null}.
   * @throws NotFoundException if no group matches the id.
   * @throws BadRequestException if a member is still assigned to the group.
   */
  @Transactional
  public void deleteGroup(@NotNull UUID groupId) {
    KommandoGroup group =
        Entities.require(kommandoGroupRepository.findById(groupId), "Kommandogruppe not found");
    if (membershipRepository.existsByKommandoGroupId(groupId)) {
      throw new BadRequestException(
          "Kommandogruppe still has assigned members — reassign them before deleting it");
    }
    String name = group.getName();
    // Remove the mirrored Kommando node first, then the group (REQ-ROLE-006).
    orgChartService.mirrorDeleteKommandoGroup(groupId);
    kommandoGroupRepository.delete(group);
    auditService.record(AuditEventType.KOMMANDO_GROUP_DELETED, groupId, name, null, null);
  }

  /**
   * Loads an org unit and asserts it is a Staffel ({@code SQUADRON}).
   *
   * @param squadronId the candidate squadron id; never {@code null}.
   * @return the loaded {@code SQUADRON} org unit.
   * @throws NotFoundException if no org unit matches the id.
   * @throws BadRequestException if the org unit is not a Staffel.
   */
  @NotNull
  private OrgUnit requireSquadron(@NotNull UUID squadronId) {
    OrgUnit unit = Entities.require(orgUnitRepository.findById(squadronId), "Squadron not found");
    if (unit.getKind() != OrgUnitKind.SQUADRON) {
      throw new BadRequestException("Org unit " + squadronId + " is not a Staffel");
    }
    return unit;
  }

  /**
   * Throws {@link ObjectOptimisticLockingFailureException} when the inbound client-held version
   * does not match the persisted group's {@code @Version} (mirrors the membership-service pattern).
   *
   * @param group the persisted group; never {@code null}.
   * @param version the client-held version, or {@code null} to skip the check.
   */
  private static void assertVersionMatches(@NotNull KommandoGroup group, java.lang.Long version) {
    OptimisticLock.check(group.getVersion(), version, KommandoGroup.class, group.getId());
  }
}
