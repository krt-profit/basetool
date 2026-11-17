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
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeReassignmentParticipant;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeReferenceParticipant;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionUnit;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The mission module's ship type references: the ship types a mission unit or a mission template's
 * unit plans (REQ-P4K-052), moved onto the ship type that stays when two are merged (REQ-DATA-042).
 */
@Component
@RequiredArgsConstructor
public class MissionShipTypeReferences
    implements ShipTypeReferenceParticipant, ShipTypeReassignmentParticipant {

  /** The audit subject token of a mission unit. */
  private static final String MISSION_UNIT = "mission_unit";

  /** The audit subject token of a mission template's unit. */
  private static final String MISSION_TEMPLATE_UNIT = "mission_template_unit";

  private final EntityManager entityManager;
  private final MissionUnitRepository missionUnits;
  private final MissionTemplateRepository templates;
  private final AuditRecorder auditRecorder;
  private final LiveSyncPublisher liveSync;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Set<UUID> referencedShipTypes(@NotNull Collection<UUID> shipTypeIds) {
    if (shipTypeIds.isEmpty()) {
      return Set.of();
    }
    Set<UUID> referenced =
        new HashSet<>(
            entityManager
                .createQuery(
                    "SELECT DISTINCT u.shipType.id FROM MissionUnit u WHERE u.shipType.id IN :ids",
                    UUID.class)
                .setParameter("ids", shipTypeIds)
                .getResultList());
    referenced.addAll(
        entityManager
            .createQuery(
                "SELECT DISTINCT u.shipTypeId FROM MissionTemplate t JOIN t.units u"
                    + " WHERE u.shipTypeId IN :ids",
                UUID.class)
            .setParameter("ids", shipTypeIds)
            .getResultList());
    return referenced;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int reassignShipType(@NotNull UUID retiredId, @NotNull UUID survivorId) {
    return reassignUnits(retiredId, survivorId) + reassignTemplates(retiredId, survivorId);
  }

  /**
   * Points the mission units of a merged ship type at the one that stays.
   *
   * @param retiredId the ship type merged away
   * @param survivorId the ship type that stays
   * @return the number of units changed
   */
  private int reassignUnits(@NotNull UUID retiredId, @NotNull UUID survivorId) {
    List<MissionUnit> units = missionUnits.lockAllByShipTypeId(retiredId);
    ShipType survivor = entityManager.getReference(ShipType.class, survivorId);
    for (MissionUnit unit : units) {
      unit.setShipType(survivor);
      Mission mission = unit.getMission();
      auditRecorder.record(
          AuditEventType.MISSION_CATALOGUE_REASSIGNED,
          mission.getId(),
          mission.getName(),
          null,
          details(retiredId, survivorId, MISSION_UNIT).with("unit", unit.getId()));
    }
    if (!units.isEmpty()) {
      entityManager.flush();
    }
    return units.size();
  }

  /**
   * Points the units of the mission templates planned with a merged ship type at the one that
   * stays.
   *
   * @param retiredId the ship type merged away
   * @param survivorId the ship type that stays
   * @return the number of templates changed
   */
  private int reassignTemplates(@NotNull UUID retiredId, @NotNull UUID survivorId) {
    List<UUID> ids =
        entityManager
            .createQuery(
                "SELECT DISTINCT t.id FROM MissionTemplate t JOIN t.units u"
                    + " WHERE u.shipTypeId = :id",
                UUID.class)
            .setParameter("id", retiredId)
            .getResultList();
    int changed = 0;
    for (UUID id : ids.stream().sorted().toList()) {
      MissionTemplate template = templates.lock(id).orElse(null);
      if (template != null && template.reassignShipType(retiredId, survivorId)) {
        UUID owner = template.getOwningOrgUnit().getId();
        auditRecorder.record(
            AuditEventType.MISSION_CATALOGUE_REASSIGNED,
            id,
            null,
            null,
            details(retiredId, survivorId, MISSION_TEMPLATE_UNIT).with("orgUnit", owner));
        liveSync.publishAfterCommit(
            LiveSyncTopic.of(LiveSyncTopicClass.MISSION_TEMPLATES, owner), List.of("list"));
        changed++;
      }
    }
    if (changed > 0) {
      templates.flush();
    }
    return changed;
  }

  /**
   * Starts the audit details of a reassignment.
   *
   * @param retiredId the ship type merged away
   * @param survivorId the ship type that stays
   * @param subject the token of the changed row's kind
   * @return the details
   */
  @NotNull
  private static AuditDetails details(
      @NotNull UUID retiredId, @NotNull UUID survivorId, @NotNull String subject) {
    return AuditDetails.of("retiredShipType", retiredId)
        .with("survivorShipType", survivorId)
        .with("subject", subject);
  }
}
