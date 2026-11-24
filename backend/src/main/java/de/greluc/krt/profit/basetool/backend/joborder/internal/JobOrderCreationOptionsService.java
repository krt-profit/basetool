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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreateOptionsDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreateUnitOptionDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreationOptions;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderWriterRule;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchySnapshot;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Projects the creation choices for the writer rule a caller passes (REQ-ORDERS-053). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class JobOrderCreationOptionsService implements JobOrderCreationOptions {

  private final OrgUnitRepository orgUnitRepository;
  private final ProfitHierarchyService profitHierarchy;
  private final JobOrderWriters writers;
  private final AuthHelperService authHelper;

  @Override
  public boolean canCreate() {
    if (profitHierarchy.eligibleUnitIds().isEmpty()) {
      return false;
    }
    JobOrderWriters.Scope scope =
        writers.resolve(JobOrderWriterRule.CONTEXTUAL, JobOrderActor.Web.current(authHelper));
    if (scope.everyUnit()) {
      return true;
    }
    Set<UUID> writerIds = scope.listedUnits();
    return !writerIds.isEmpty()
        && orgUnitRepository.findAllByIdInWithParent(writerIds).stream()
            .anyMatch(OrgUnit::isActive);
  }

  @Override
  public @NotNull JobOrderCreateOptionsDto current(
      @NotNull JobOrderWriterRule rule, @NotNull JobOrderActor actor) {
    ProfitHierarchySnapshot hierarchy = profitHierarchy.snapshot();
    Set<UUID> eligibleIds = hierarchy.eligibleUnitIds();
    if (eligibleIds.isEmpty()) {
      return unavailable();
    }
    JobOrderWriters.Scope scope = writers.resolve(rule, actor);
    List<OrgUnit> active =
        orgUnitRepository.findAllActiveWithParent().stream()
            .sorted(Comparator.comparing(OrgUnit::getName, String.CASE_INSENSITIVE_ORDER))
            .toList();
    List<JobOrderCreateUnitOptionDto> eligible =
        options(
            active.stream().filter(unit -> eligibleIds.contains(unit.getId())).toList(),
            scope::writesResponsible);
    List<JobOrderCreateUnitOptionDto> requestingCandidates =
        options(active, scope::writesRequesting);
    boolean requesterWriter =
        requestingCandidates.stream().anyMatch(JobOrderCreateUnitOptionDto::canWrite);
    boolean responsibleWriter = eligible.stream().anyMatch(JobOrderCreateUnitOptionDto::canWrite);
    if (eligible.isEmpty() || !requesterWriter) {
      return unavailable();
    }
    List<JobOrderCreateUnitOptionDto> requesting =
        requestingCandidates.stream()
            .filter(option -> responsibleWriter || option.canWrite())
            .toList();
    UUID proposedRequester =
        requesting.stream()
            .filter(JobOrderCreateUnitOptionDto::canWrite)
            .findFirst()
            .orElseThrow()
            .orgUnitId();
    UUID proposedResponsible = eligible.getFirst().orgUnitId();
    UUID auecResponsible =
        eligible.stream()
            .map(JobOrderCreateUnitOptionDto::orgUnitId)
            .filter(id -> id.equals(hierarchy.profitRootId()))
            .findFirst()
            .orElse(proposedResponsible);
    return new JobOrderCreateOptionsDto(
        true, eligible, requesting, proposedResponsible, proposedRequester, auecResponsible);
  }

  private static @NotNull List<JobOrderCreateUnitOptionDto> options(
      @NotNull List<OrgUnit> units, @NotNull Predicate<UUID> writes) {
    return units.stream()
        .map(unit ->
            new JobOrderCreateUnitOptionDto(
                unit.getId(),
                unit.getName(),
                unit.getShorthand(),
                unit.getKind(),
                writes.test(unit.getId())))
        .toList();
  }

  private static @NotNull JobOrderCreateOptionsDto unavailable() {
    return new JobOrderCreateOptionsDto(false, List.of(), List.of(), null, null, null);
  }
}
