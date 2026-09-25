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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Resolves a user's {@code SQUADRON}-kind {@link OrgUnitMembership} rows to their {@link
 * Squadron}s, sorted case-insensitively by name so the first element is the primary Staffel
 * (REQ-ORG-017).
 *
 * <p>The single definition of the primary Staffel, shared by the membership service, {@code
 * UserMapper} and {@code OwnerScopeService}; lives in the dependency-leaf {@code support} package
 * (ADR-0047). Dangling memberships are skipped, and it joins the caller's transaction, if any.
 */
@Service
@RequiredArgsConstructor
public class StaffelMembershipResolver {

  /** The primary-Staffel order: case-insensitive by squadron name, so the first is the primary. */
  private static final Comparator<Squadron> BY_NAME =
      Comparator.comparing(Squadron::getName, String.CASE_INSENSITIVE_ORDER);

  private final SquadronRepository squadronRepository;
  private final OrgUnitRepository orgUnitRepository;

  /**
   * Resolves the given {@code SQUADRON}-kind membership rows to their squadrons, name-sorted with
   * the primary Staffel first; dangling rows are dropped.
   *
   * @param squadronRows the user's {@code SQUADRON}-kind membership rows; never {@code null},
   *     possibly empty.
   * @return the owning squadrons, name-sorted (primary first); never {@code null}, possibly empty.
   */
  @NotNull
  public List<Squadron> resolveNameSortedStaffeln(@NotNull List<OrgUnitMembership> squadronRows) {
    if (squadronRows.isEmpty()) {
      return List.of();
    }
    List<UUID> staffelIds = squadronRows.stream().map(r -> r.getId().getOrgUnitId()).toList();
    return loadStaffelnById(staffelIds).values().stream().sorted(BY_NAME).toList();
  }

  /**
   * Batch variant of {@link #resolveNameSortedStaffeln(List)} that loads all referenced Staffeln in
   * one query (REQ-DATA-003).
   *
   * @param squadronRowsByUser each user's {@code SQUADRON}-kind membership rows, keyed by user id;
   *     never {@code null}. A user mapped to an empty list resolves to an empty list.
   * @return every key of {@code squadronRowsByUser} mapped to that user's squadrons, name-sorted
   *     (primary first); never {@code null}.
   */
  @NotNull
  public Map<UUID, List<Squadron>> resolveNameSortedStaffelnByUser(
      @NotNull Map<UUID, List<OrgUnitMembership>> squadronRowsByUser) {
    Set<UUID> staffelIds =
        squadronRowsByUser.values().stream()
            .flatMap(List::stream)
            .map(r -> r.getId().getOrgUnitId())
            .collect(Collectors.toSet());
    Map<UUID, Squadron> staffelnById =
        staffelIds.isEmpty() ? Map.of() : loadStaffelnById(staffelIds);
    Map<UUID, List<Squadron>> result = new HashMap<>();
    squadronRowsByUser.forEach(
        (userId, rows) ->
            result.put(
                userId,
                rows.stream()
                    .map(r -> staffelnById.get(r.getId().getOrgUnitId()))
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted(BY_NAME)
                    .toList()));
    return result;
  }

  /**
   * Loads the given org-unit ids polymorphically and keeps the {@link Squadron}s, keyed by id. A
   * base-typed load avoids narrowing an {@link OrgUnit} proxy the session already holds.
   *
   * @param staffelIds the org-unit ids to load; never {@code null}.
   * @return the resolvable squadrons keyed by id; never {@code null}, possibly empty.
   */
  @NotNull
  private Map<UUID, Squadron> loadStaffelnById(@NotNull Collection<UUID> staffelIds) {
    Map<UUID, Squadron> byId = new LinkedHashMap<>();
    for (OrgUnit ou : orgUnitRepository.findAllById(staffelIds)) {
      if (Hibernate.unproxy(ou, OrgUnit.class) instanceof Squadron squadron) {
        byId.put(squadron.getId(), squadron);
      }
    }
    return byId;
  }

  /**
   * Resolves the given {@code SQUADRON}-kind membership rows to their Staffel ids in the same order
   * as {@link #resolveNameSortedStaffeln(List)}. A single row is only checked for existence, not
   * loaded.
   *
   * @param squadronRows the user's {@code SQUADRON}-kind membership rows; never {@code null},
   *     possibly empty.
   * @return the owning Staffel ids, name-sorted (primary first); never {@code null}, possibly
   *     empty.
   */
  @NotNull
  public List<UUID> resolveNameSortedStaffelIds(@NotNull List<OrgUnitMembership> squadronRows) {
    if (squadronRows.isEmpty()) {
      return List.of();
    }
    if (squadronRows.size() == 1) {
      UUID staffelId = squadronRows.getFirst().getId().getOrgUnitId();
      return squadronRepository.existsById(staffelId) ? List.of(staffelId) : List.of();
    }
    return resolveNameSortedStaffeln(squadronRows).stream().map(Squadron::getId).toList();
  }
}
