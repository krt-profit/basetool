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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.kernel.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionTemplateStaleException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.jetbrains.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Manages reusable mission planning content within the caller's org-unit scope. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionTemplateService {

  private static final String TEMPLATE_NOT_FOUND = "Mission template not found";
  private static final String UNIQUE_OWNER_NAME = "uk_mission_template_owner_name";

  private final MissionTemplateRepository templates;
  private final MissionTemplateMapper mapper;
  private final AuditRecorder auditRecorder;
  private final LiveSyncPublisher liveSync;
  private final OrgUnitRepository orgUnits;
  private final ShipTypeRepository shipTypes;
  private final AccessGateService accessGate;
  private final RequestScopeResolver requestScope;

  /**
   * Lists templates owned by the authorized target unit.
   *
   * @param owner the target org unit
   * @param pageable the requested page
   * @return template summaries ordered by name and id
   */
  public Page<MissionTemplateSummaryDto> list(UUID owner, Pageable pageable) {
    requireOwner(owner);
    return templates.list(owner, pageable);
  }

  /**
   * Saves a new template in its explicitly selected owner.
   *
   * @param request the destination and reusable content
   * @return the stored template and its revision
   */
  @Transactional
  public MissionTemplateDto create(CreateMissionTemplateRequest request) {
    OrgUnit owner = requireOwner(request.owningOrgUnitId());
    MissionTemplatePlan plan = normalize(request.objectives(), request.steps(), request.units());
    MissionTemplate template = new MissionTemplate(owner, request.name().strip(), plan);
    templates.save(template);
    flush();
    recordChange(template, AuditEventType.MISSION_TEMPLATE_CREATED);
    return mapper.toDto(template);
  }

  /**
   * Loads the editor data of a manageable template.
   *
   * @param id the template id
   * @return the reusable plan and its revision
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public MissionTemplateDto get(UUID id) {
    return mapper.toDto(requireTemplate(templates.findById(id)));
  }

  /**
   * Replaces the reusable plan at the revision echoed by the editor.
   *
   * @param id the template id
   * @param request the current revision and replacement content
   * @return the stored plan and its current revision
   */
  @Transactional
  public MissionTemplateDto update(UUID id, UpdateMissionTemplateRequest request) {
    MissionTemplate template = requireTemplate(templates.lock(id));
    OptimisticLock.checkRequired(
        template.getVersion(), request.version(), MissionTemplate.class, id);
    MissionTemplatePlan plan = normalize(request.objectives(), request.steps(), request.units());
    if (template.replace(request.name().strip(), plan)) {
      flush();
      recordChange(template, AuditEventType.MISSION_TEMPLATE_UPDATED);
    }
    return mapper.toDto(template);
  }

  /**
   * Removes a manageable template at the revision echoed by the editor.
   *
   * @param id the template id
   * @param version the expected revision
   */
  @Transactional
  public void delete(UUID id, long version) {
    MissionTemplate template = requireTemplate(templates.lock(id));
    OptimisticLock.checkRequired(template.getVersion(), version, MissionTemplate.class, id);
    templates.delete(template);
    flush();
    recordChange(template, AuditEventType.MISSION_TEMPLATE_DELETED);
  }

  /**
   * Lists active org units where the caller can manage templates.
   *
   * @return owner options ordered by name
   */
  public List<MissionTemplateOwnerDto> owners() {
    Set<UUID> managerUnits = currentManagerUnits();
    return orgUnits.findAllActiveWithParent().stream()
        .filter(unit -> canManageIn(unit.getId(), managerUnits))
        .sorted(Comparator.comparing(OrgUnit::getName).thenComparing(OrgUnit::getId))
        .map(unit -> new MissionTemplateOwnerDto(unit.getId(), unit.getName()))
        .toList();
  }

  Page<MissionTemplateSummaryDto> choices(@Nullable OrgUnit owner, Pageable pageable) {
    return owner == null || !owner.isActive()
        ? Page.empty(pageable)
        : templates.list(owner.getId(), pageable);
  }

  MissionTemplateDto preview(@Nullable OrgUnit owner, UUID id) {
    if (owner == null || !owner.isActive()) {
      throw new NotFoundException(TEMPLATE_NOT_FOUND);
    }
    return mapper.toDto(
        Entities.require(
            templates.findByIdAndOwningOrgUnitId(id, owner.getId()), TEMPLATE_NOT_FOUND));
  }

  void checkSelection(@Nullable OrgUnit owner, CreateMissionRequest.TemplateSelection selection) {
    if (owner == null || !owner.isActive()) {
      throw new MissionTemplateStaleException();
    }
    MissionTemplate template =
        templates
            .lockForApplication(selection.id(), owner.getId())
            .orElseThrow(MissionTemplateStaleException::new);
    try {
      OptimisticLock.checkRequired(
          template.getVersion(), selection.version(), MissionTemplate.class, selection.id());
    } catch (ObjectOptimisticLockingFailureException failure) {
      throw new MissionTemplateStaleException(failure);
    }
  }

  private void flush() {
    try {
      templates.flush();
    } catch (DataIntegrityViolationException failure) {
      if (failure.getCause() instanceof ConstraintViolationException violation
          && UNIQUE_OWNER_NAME.equals(violation.getConstraintName())) {
        throw new DuplicateEntityException("error.missionTemplate.name.duplicate", "name", failure);
      }
      throw failure;
    }
  }

  private void recordChange(MissionTemplate template, AuditEventType type) {
    UUID owner = template.getOwningOrgUnit().getId();
    auditRecorder.record(type, template.getId(), null, null, AuditDetails.of("orgUnit", owner));
    liveSync.publishAfterCommit(
        LiveSyncTopic.of(LiveSyncTopicClass.MISSION_TEMPLATES, owner), List.of("list"));
  }

  private MissionTemplate requireTemplate(Optional<MissionTemplate> found) {
    MissionTemplate template = Entities.require(found, TEMPLATE_NOT_FOUND);
    OrgUnit owner = template.getOwningOrgUnit();
    if (!owner.isActive() || !canManageIn(owner.getId(), currentManagerUnits())) {
      throw new NotFoundException(TEMPLATE_NOT_FOUND);
    }
    return template;
  }

  private Set<UUID> currentManagerUnits() {
    return requestScope.currentGrantedOrgUnitIdsInActiveContext(Roles.MISSION_MANAGER);
  }

  private boolean canManageIn(UUID owner, Set<UUID> managerUnits) {
    return managerUnits.contains(owner) || accessGate.canActAsOfficerIn(owner);
  }

  private MissionTemplatePlan normalize(
      List<MissionTemplatePlan.Objective> objectives,
      List<MissionTemplatePlan.Step> steps,
      List<MissionTemplatePlan.Unit> units) {
    Set<UUID> shipTypeIds =
        units.stream()
            .map(MissionTemplatePlan.Unit::shipTypeId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (!shipTypeIds.isEmpty() && shipTypes.findAllById(shipTypeIds).size() != shipTypeIds.size()) {
      throw new NotFoundException("ShipType not found");
    }
    return new MissionTemplatePlan(
        objectives.stream()
            .map(row -> new MissionTemplatePlan.Objective(row.title().strip(), row.kind()))
            .toList(),
        steps.stream()
            .map(row ->
                new MissionTemplatePlan.Step(
                    row.title().strip(), StringNormalization.trimToNull(row.meta())))
            .toList(),
        units.stream()
            .map(row ->
                new MissionTemplatePlan.Unit(
                    row.name().strip(),
                    row.shipTypeId(),
                    StringNormalization.trimToNull(row.note()),
                    row.highValueUnit(),
                    MissionUnitFrequency.normalize(row.frequency())))
            .toList());
  }

  private OrgUnit requireOwner(UUID id) {
    if (!canManageIn(id, currentManagerUnits())) {
      throw new AccessDeniedException("Mission template ownership scope required");
    }
    return Entities.require(
        orgUnits.findById(id).filter(OrgUnit::isActive), "Active org unit not found");
  }
}
