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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReadSet;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRelation;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitSeatEligibility;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides the member's exchange read set from the database: the units come from the member's
 * direct memberships on active units while they hold {@code KRT Member} (REQ-SEC-015,
 * REQ-ORG-032), the relations from the shared predicates of {@code JobOrderScope}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class JobOrderExchangeReadSetService implements JobOrderExchangeReadSet {

  private final OrgUnitSeatEligibility seatEligibility;
  private final OrgUnitMembershipQueryService memberships;
  private final JobOrderReadSetRepository readSet;

  @Override
  public @NotNull RelatingMember relatingMember(@NotNull UUID memberId) {
    return new RelatingMember(
        memberId,
        seatEligibility.holdsKrtMember(memberId)
            ? memberships.findDirectMembershipOrgUnitIds(memberId)
            : Set.of());
  }

  @Override
  public @NotNull @Unmodifiable Map<UUID, Set<JobOrderRelation>> relations(
      @NotNull RelatingMember member, @NotNull Collection<UUID> orderIds) {
    if (orderIds.isEmpty()) {
      return Map.of();
    }
    return readSet.findRelations(member.unitIds(), member.memberId(), Set.copyOf(orderIds)).stream()
        .collect(
            Collectors.toUnmodifiableMap(
                JobOrderReadSetRepository.RelationRow::orderId,
                JobOrderExchangeReadSetService::relationsOf));
  }

  @Override
  public @NotNull @Unmodifiable Map<UUID, String> ownNotes(
      @NotNull UUID memberId, @NotNull Collection<UUID> orderIds) {
    if (orderIds.isEmpty()) {
      return Map.of();
    }
    return readSet.findOwnNotes(memberId, Set.copyOf(orderIds)).stream()
        .collect(
            Collectors.toUnmodifiableMap(
                JobOrderReadSetRepository.OwnNote::orderId,
                JobOrderReadSetRepository.OwnNote::note));
  }

  private static Set<JobOrderRelation> relationsOf(JobOrderReadSetRepository.RelationRow row) {
    Set<JobOrderRelation> relations = EnumSet.noneOf(JobOrderRelation.class);
    if (row.requesting()) {
      relations.add(JobOrderRelation.REQUESTING);
    }
    if (row.responsible()) {
      relations.add(JobOrderRelation.RESPONSIBLE);
    }
    if (row.supporting()) {
      relations.add(JobOrderRelation.SUPPORTING);
    }
    if (row.assignee()) {
      relations.add(JobOrderRelation.ASSIGNEE);
    }
    return Set.copyOf(relations);
  }
}
