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

import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The assignee picker of a job order (REQ-ORDERS-047): a handle search over all active accounts,
 * the direct members of the order's processing unit first, every other hit marked with its primary
 * unit and nothing else.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobOrderAssigneeCandidateService {

  /** The most hits one search returns, the size of the picker's dropdown. */
  static final int CANDIDATE_LIMIT = 20;

  /** Placeholder id for an empty preferred set, since JPQL rejects an empty IN list. */
  private static final UUID NIL_ID = new UUID(0L, 0L);

  private final JobOrderRepository jobOrderRepository;
  private final UserRepository userRepository;
  private final OrgUnitMembershipQueryService membershipQueryService;

  /**
   * Searches the accounts that may be assigned to the order.
   *
   * @param jobOrderId the order the picker belongs to
   * @param query the handle fragment, or {@code null}/blank for the first accounts
   * @return at most {@value #CANDIDATE_LIMIT} candidates, members of the processing unit first
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the order is
   *     unknown
   */
  @NotNull
  public List<JobOrderAssigneeCandidateDto> searchCandidates(
      @NotNull UUID jobOrderId, @Nullable String query) {
    JobOrder order =
        Entities.require(
            jobOrderRepository.findById(jobOrderId), () -> "JobOrder not found: " + jobOrderId);
    OrgUnit processing = order.getResponsibleOrgUnit();
    Set<UUID> members =
        processing == null
            ? Set.of()
            : membershipQueryService.findDirectMemberUserIds(processing.getId());
    List<UserReferenceDto> hits =
        userRepository.searchActiveReferences(
            LikePatterns.escape(query == null ? "" : query.strip()),
            members.isEmpty() ? Set.of(NIL_ID) : members,
            PageRequest.of(0, CANDIDATE_LIMIT));
    Map<UUID, OrgUnitMembershipOptionDto> units =
        membershipQueryService.findPrimaryDirectMembershipOptions(
            hits.stream().map(UserReferenceDto::id).filter(id -> !members.contains(id)).toList());
    return hits.stream()
        .map(hit -> {
          boolean member = members.contains(hit.id());
          OrgUnitMembershipOptionDto unit = member ? null : units.get(hit.id());
          return new JobOrderAssigneeCandidateDto(
              hit.id(),
              hit.effectiveName(),
              member,
              unit == null ? null : unit.orgUnitShorthand(),
              unit == null ? null : unit.orgUnitName());
        })
        .toList();
  }
}
