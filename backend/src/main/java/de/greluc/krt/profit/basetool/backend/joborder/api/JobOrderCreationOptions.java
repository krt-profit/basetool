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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Projects current contextual creation choices without enumerating pairs (REQ-ORDERS-053). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobOrderCreationOptions {

  private final OrgUnitRepository orgUnitRepository;
  private final ProfitHierarchyService profitHierarchy;
  private final AccessGateService accessGateService;

  /**
   * Checks for an active requesting writer or eligible responsible writer in the current context.
   *
   * @return whether at least one currently valid pair can be commissioned
   */
  public boolean canCreate() {
    if (profitHierarchy.eligibleUnitIds().isEmpty()) {
      return false;
    }
    ScopePredicate writerScope = accessGateService.currentLogisticianScope();
    if (writerScope.adminAllScope()) {
      return true;
    }
    Set<UUID> writerIds =
        writerScope.activeOrgUnitId() == null
            ? writerScope.memberOrgUnitIds()
            : Set.of(writerScope.activeOrgUnitId());
    return !writerIds.isEmpty()
        && orgUnitRepository.findAllByIdInWithParent(writerIds).stream()
            .anyMatch(OrgUnit::isActive);
  }

  /**
   * Lists the currently usable choices and one valid pair; submission rechecks current authority.
   *
   * @return a fail-closed answer when no eligible responsible unit or active unit writer exists
   */
  public @NotNull JobOrderCreateOptionsDto current() {
    Set<UUID> eligibleIds = profitHierarchy.eligibleUnitIds();
    if (eligibleIds.isEmpty()) {
      return unavailable();
    }
    ScopePredicate writerScope = accessGateService.currentLogisticianScope();
    List<JobOrderCreateUnitOptionDto> active =
        orgUnitRepository.findAllActiveWithParent().stream()
            .sorted(Comparator.comparing(OrgUnit::getName, String.CASE_INSENSITIVE_ORDER))
            .map(unit ->
                new JobOrderCreateUnitOptionDto(
                    unit.getId(),
                    unit.getName(),
                    unit.getShorthand(),
                    unit.getKind(),
                    writerScope.permits(unit.getId())))
            .toList();
    List<JobOrderCreateUnitOptionDto> eligible =
        active.stream().filter(option -> eligibleIds.contains(option.orgUnitId())).toList();
    boolean requesterWriter = active.stream().anyMatch(JobOrderCreateUnitOptionDto::canWrite);
    boolean responsibleWriter = eligible.stream().anyMatch(JobOrderCreateUnitOptionDto::canWrite);
    if (eligible.isEmpty() || !requesterWriter) {
      return unavailable();
    }
    List<JobOrderCreateUnitOptionDto> responsible = eligible;
    List<JobOrderCreateUnitOptionDto> requesting =
        active.stream().filter(option -> responsibleWriter || option.canWrite()).toList();
    JobOrderCreateUnitOptionDto proposedRequester =
        requesting.stream()
            .filter(JobOrderCreateUnitOptionDto::canWrite)
            .findFirst()
            .orElse(requesting.getFirst());
    JobOrderCreateUnitOptionDto proposedResponsible =
        responsible.stream()
            .filter(option -> proposedRequester.canWrite() || option.canWrite())
            .findFirst()
            .orElseThrow();
    return new JobOrderCreateOptionsDto(
        true,
        responsible,
        requesting,
        proposedResponsible.orgUnitId(),
        proposedRequester.orgUnitId());
  }

  private static JobOrderCreateOptionsDto unavailable() {
    return new JobOrderCreateOptionsDto(false, List.of(), List.of(), null, null);
  }
}
