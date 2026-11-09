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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderNotices;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitWriterDirectory;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Groups automatic promise endings into one current-writer coordination cause per order. */
@Service
@RequiredArgsConstructor
public class JobOrderEligibilityNotices {

  private final OrgUnitWriterDirectory writers;
  private final ApplicationEventPublisher events;

  /**
   * Publishes one cause to the affected unit writers and current responsible coordination.
   *
   * @param order the concrete affected order
   * @param materialsByUnit the ended material names grouped by affected Staffel
   * @param actor the actor of the hierarchy change
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void promisesEnded(
      @NotNull JobOrder order,
      @NotNull Map<UUID, Set<String>> materialsByUnit,
      @NotNull ActorRef actor) {
    Set<UUID> unitIds = new LinkedHashSet<>(materialsByUnit.keySet());
    if (order.getResponsibleOrgUnit() != null) {
      unitIds.add(order.getResponsibleOrgUnit().getId());
    }
    Set<String> materials = new java.util.TreeSet<>();
    materialsByUnit.values().forEach(materials::addAll);
    Set<UUID> recipients = writers.writerUserIds(unitIds);
    if (!recipients.isEmpty()) {
      events.publishEvent(
          JobOrderNotices.promisesEnded(
              order.getId(),
              order.getDisplayId(),
              order.getHandle(),
              recipients,
              String.join(", ", materials),
              actor));
    }
  }
}
