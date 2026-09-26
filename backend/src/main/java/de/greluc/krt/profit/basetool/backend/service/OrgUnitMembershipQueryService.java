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

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.OrgUnitMembershipMapper;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.StaffelMembershipResolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only queries of the org-unit membership domain: picker options, membership projections and
 * the Staffel accessors behind the authorization gates (REQ-ORG-017). Writes live in {@link
 * OrgUnitMembershipService}.
 *
 * <p>Read-only transactional so the DTO projections can read the lazy {@code user.effectiveName}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgUnitMembershipQueryService {

  private final OrgUnitMembershipRepository membershipRepository;
  private final SquadronRepository squadronRepository;
  private final SpecialCommandRepository specialCommandRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final OrgUnitCascadeService orgUnitCascadeService;
  private final SpecialCommandService specialCommandService;
  private final StaffelMembershipResolver staffelMembershipResolver;
  private final OrgUnitMembershipMapper orgUnitMembershipMapper;

  /**
   * Lists every active Staffel and Spezialkommando as picker options, independent of memberships;
   * backs {@code GET /api/v1/org-units/active}. Sorted like {@link #listOptionsForUser}.
   *
   * @return active Squadron + SpecialCommand options; never {@code null}, possibly empty.
   */
  @NotNull
  public List<OrgUnitMembershipOptionDto> listAllActiveOptions() {
    List<OrgUnitMembershipOptionDto> options = new ArrayList<>();
    for (Squadron s : squadronRepository.findAllByActiveTrue()) {
      options.add(
          new OrgUnitMembershipOptionDto(
              s.getId(),
              s.getName(),
              s.getShorthand(),
              OrgUnitKind.SQUADRON,
              s.isProfitEligible()));
    }
    for (SpecialCommand sc : specialCommandRepository.findAllByActiveTrue()) {
      options.add(
          new OrgUnitMembershipOptionDto(
              sc.getId(),
              sc.getName(),
              sc.getShorthand(),
              OrgUnitKind.SPECIAL_COMMAND,
              sc.isProfitEligible()));
    }
    options.sort(
        Comparator.<OrgUnitMembershipOptionDto, Integer>comparing(
                o -> o.kind() == OrgUnitKind.SQUADRON ? 0 : 1)
            .thenComparing(
                o -> o.orgUnitName() == null ? "" : o.orgUnitName(),
                String.CASE_INSENSITIVE_ORDER));
    return options;
  }

  /**
   * Lists every active org unit of all four kinds as picker options, ordered Staffel, SK, Bereich,
   * OL and alphabetically within each (REQ-ORG-019).
   *
   * <p>Bereich and OL options carry {@code isProfitEligible = false}; consumers filter by {@link
   * OrgUnitMembershipOptionDto#kind()}.
   *
   * @return active org-unit options across all four kinds; never {@code null}, possibly empty.
   */
  @NotNull
  public List<OrgUnitMembershipOptionDto> listAllActiveOrgUnitOptionsAllKinds() {
    List<OrgUnitMembershipOptionDto> options = new ArrayList<>(listAllActiveOptions());
    orgUnitRepository.findActiveBereiche().stream()
        .sorted(Comparator.comparing(OrgUnit::getName, String.CASE_INSENSITIVE_ORDER))
        .forEach(
            b ->
                options.add(
                    new OrgUnitMembershipOptionDto(
                        b.getId(), b.getName(), b.getShorthand(), OrgUnitKind.BEREICH, false)));
    orgUnitRepository.findActiveOrganisationsleitung().stream()
        .sorted(Comparator.comparing(OrgUnit::getName, String.CASE_INSENSITIVE_ORDER))
        .forEach(
            ol ->
                options.add(
                    new OrgUnitMembershipOptionDto(
                        ol.getId(),
                        ol.getName(),
                        ol.getShorthand(),
                        OrgUnitKind.ORGANISATIONSLEITUNG,
                        false)));
    return options;
  }

  /**
   * Lists every active org unit an admin may pin in the active-context switcher, in the top-down
   * order of {@link #listPickerOptionsWithDescendants(UUID)} (OL &rarr; Bereich &rarr; Staffel
   * &rarr; SK). Backs the admin branch of {@code GET /api/v1/me/org-units}.
   *
   * @return every active org unit as a pinnable option; never {@code null}, possibly empty.
   */
  @NotNull
  public List<OrgUnitMembershipOptionDto> listAllPinnableOptions() {
    List<OrgUnitMembershipOptionDto> options =
        new ArrayList<>(listAllActiveOrgUnitOptionsAllKinds());
    options.sort(pickerOrder());
    return options;
  }

  /**
   * Lists the user's Staffel and Spezialkommando memberships as picker options, Staffel first then
   * SKs by name; backs {@code GET /api/v1/users/{userId}/memberships}.
   *
   * <p>Bereich and OL memberships, and rows whose stored kind disagrees with the org unit, are
   * skipped. An unknown user yields an empty list.
   *
   * @param userId the user whose memberships to enumerate; never {@code null}.
   * @return picker-friendly DTOs for each membership; never {@code null}, possibly empty.
   */
  @NotNull
  public List<OrgUnitMembershipOptionDto> listOptionsForUser(@NotNull UUID userId) {
    List<OrgUnitMembership> rows = membershipRepository.findAllByIdUserId(userId);
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<UUID, OrgUnit> units = loadOrgUnitsById(rows);
    List<OrgUnitMembershipOptionDto> options = new ArrayList<>(rows.size());
    for (OrgUnitMembership row : rows) {
      OrgUnit unit = units.get(row.getId().getOrgUnitId());
      if (unit == null
          || unit.getKind() != row.getKind()
          || (row.getKind() != OrgUnitKind.SQUADRON
              && row.getKind() != OrgUnitKind.SPECIAL_COMMAND)) {
        continue;
      }
      options.add(
          new OrgUnitMembershipOptionDto(
              unit.getId(),
              unit.getName(),
              unit.getShorthand(),
              row.getKind(),
              unit.isProfitEligible()));
    }
    options.sort(
        Comparator.<OrgUnitMembershipOptionDto, Integer>comparing(
                o -> o.kind() == OrgUnitKind.SQUADRON ? 0 : 1)
            .thenComparing(
                o -> o.orgUnitName() == null ? "" : o.orgUnitName(),
                String.CASE_INSENSITIVE_ORDER));
    return options;
  }

  /**
   * Returns the org-unit ids the user is a direct member of, across all kinds and without the
   * leadership cascade.
   *
   * @param userId the user whose direct memberships to enumerate; never {@code null}.
   * @return the org-unit ids of the user's direct memberships across all kinds; never {@code null},
   *     possibly empty.
   */
  @NotNull
  public Set<UUID> findDirectMembershipOrgUnitIds(@NotNull UUID userId) {
    Set<UUID> ids = new LinkedHashSet<>();
    for (OrgUnitMembership row : membershipRepository.findAllByIdUserId(userId)) {
      ids.add(row.getId().getOrgUnitId());
    }
    return ids;
  }

  /**
   * Lists the user's direct memberships across all four kinds as named picker options, ordered OL,
   * Bereich, Staffel, SK and then by name, so the first element is the primary unit. Backs the bank
   * counterparty org-unit picker (REQ-BANK-044).
   *
   * @param userId the user whose direct memberships to enumerate; never {@code null}.
   * @return picker-friendly DTOs across all four kinds; never {@code null}, possibly empty.
   */
  @NotNull
  public List<OrgUnitMembershipOptionDto> listDirectMembershipOptions(@NotNull UUID userId) {
    Set<UUID> ids = findDirectMembershipOrgUnitIds(userId);
    if (ids.isEmpty()) {
      return List.of();
    }
    List<OrgUnitMembershipOptionDto> options = new ArrayList<>(ids.size());
    for (OrgUnit orgUnit : orgUnitRepository.findAllById(ids)) {
      options.add(
          new OrgUnitMembershipOptionDto(
              orgUnit.getId(),
              orgUnit.getName(),
              orgUnit.getShorthand(),
              orgUnit.getKind(),
              orgUnit.isProfitEligible()));
    }
    options.sort(pickerOrder());
    return options;
  }

  /**
   * Resolves the user's primary direct org-unit membership: the first of {@link
   * #listDirectMembershipOptions(UUID)} (REQ-BANK-044).
   *
   * @param userId the user whose primary membership to resolve; never {@code null}.
   * @return the primary org-unit id, or empty when the user has no direct membership at all.
   */
  public Optional<UUID> findPrimaryDirectMembershipOrgUnitId(@NotNull UUID userId) {
    return listDirectMembershipOptions(userId).stream()
        .findFirst()
        .map(OrgUnitMembershipOptionDto::orgUnitId);
  }

  /**
   * Lists the owning-org-unit picker options: the caller's direct memberships plus the cascaded
   * leadership reach from {@link OrgUnitCascadeService#expandWithDescendants(java.util.Collection)}
   * (REQ-ORG-016).
   *
   * <p>Without a leadership membership this equals {@link #listOptionsForUser(UUID)}. Options may
   * include Bereich and OL entries and are sorted OL, Bereich, Staffel, SK, then by name.
   *
   * @param userId the caller whose reachable org units to enumerate; never {@code null}.
   * @return picker-friendly DTOs across all reachable kinds; never {@code null}, possibly empty
   *     when the caller has no membership at all.
   */
  @NotNull
  public List<OrgUnitMembershipOptionDto> listPickerOptionsWithDescendants(@NotNull UUID userId) {
    List<OrgUnitMembership> rows = membershipRepository.findAllByIdUserId(userId);
    if (rows.isEmpty()) {
      return List.of();
    }
    Set<UUID> reach = orgUnitCascadeService.expandWithDescendants(rows);
    List<OrgUnitMembershipOptionDto> options = new ArrayList<>(reach.size());
    for (OrgUnit orgUnit : orgUnitRepository.findAllById(reach)) {
      options.add(
          new OrgUnitMembershipOptionDto(
              orgUnit.getId(),
              orgUnit.getName(),
              orgUnit.getShorthand(),
              orgUnit.getKind(),
              orgUnit.isProfitEligible()));
    }
    options.sort(
        Comparator.<OrgUnitMembershipOptionDto, Integer>comparing(o -> pickerKindOrder(o.kind()))
            .thenComparing(
                o -> o.orgUnitName() == null ? "" : o.orgUnitName(),
                String.CASE_INSENSITIVE_ORDER));
    return options;
  }

  /**
   * Stable top-down ordering of org-unit kinds for the owning-org-unit picker (OL → Bereich →
   * Staffel → SK), so the picker fragment renders its {@code optgroup}s in hierarchy order.
   *
   * @param kind the option's org-unit kind; never {@code null}.
   * @return the sort rank (0 = top of the hierarchy).
   */
  private static Comparator<OrgUnitMembershipOptionDto> pickerOrder() {
    return Comparator.<OrgUnitMembershipOptionDto, Integer>comparing(o -> pickerKindOrder(o.kind()))
        .thenComparing(
            o -> o.orgUnitName() == null ? "" : o.orgUnitName(), String.CASE_INSENSITIVE_ORDER);
  }

  /**
   * Stable top-down ordering of org-unit kinds for the owning-org-unit picker (OL &rarr; Bereich
   * &rarr; Staffel &rarr; SK), so the picker fragment renders its {@code optgroup}s in hierarchy
   * order.
   *
   * @param kind the option's org-unit kind; never {@code null}.
   * @return the sort rank (0 = top of the hierarchy).
   */
  private static int pickerKindOrder(@NotNull OrgUnitKind kind) {
    return switch (kind) {
      case ORGANISATIONSLEITUNG -> 0;
      case BEREICH -> 1;
      case SQUADRON -> 2;
      case SPECIAL_COMMAND -> 3;
    };
  }

  /**
   * Lists every membership of the given Spezialkommando.
   *
   * @param specialCommandId the Spezialkommando id; never {@code null}.
   * @return the (possibly empty) list of memberships in repository insertion order.
   * @throws NotFoundException if no SK matches the given id.
   */
  public List<OrgUnitMembership> listMembers(@NotNull UUID specialCommandId) {
    SpecialCommand sc = specialCommandService.getSpecialCommandById(specialCommandId);
    return membershipRepository.findAllByIdOrgUnitId(sc.getId());
  }

  /**
   * DTO projection of {@link #listMembers(UUID)}: lists every Spezialkommando member as its
   * response DTO, mapped inside the read transaction so the lazy {@code user.effectiveName} read
   * succeeds.
   *
   * @param specialCommandId the Spezialkommando id; never {@code null}.
   * @return the membership DTOs in repository insertion order; never {@code null}, possibly empty.
   * @throws NotFoundException if no SK matches the given id.
   */
  public List<OrgUnitMembershipDto> listMemberDtos(@NotNull UUID specialCommandId) {
    return toDtos(listMembers(specialCommandId));
  }

  /**
   * Lists the user's complete membership set as response DTOs, mapped inside the read transaction;
   * backs {@code GET /api/v1/users/{id}/memberships/detail}.
   *
   * @param userId the user whose memberships to project; never {@code null}.
   * @return the user's memberships as DTOs; never {@code null}, possibly empty.
   */
  public List<OrgUnitMembershipDto> findAllMembershipDtosForUser(@NotNull UUID userId) {
    return toDtos(findAllMembershipsForUser(userId));
  }

  /**
   * Maps membership rows to their response DTOs; the rows must still be attached to the session,
   * because the mapper reads the lazy {@code user.effectiveName}.
   *
   * @param memberships the membership rows to project; never {@code null}.
   * @return the membership DTOs in the same order; never {@code null}, possibly empty.
   */
  private List<OrgUnitMembershipDto> toDtos(@NotNull List<OrgUnitMembership> memberships) {
    return memberships.stream().map(orgUnitMembershipMapper::toDto).toList();
  }

  /**
   * Returns the user's Staffel ids (up to two, REQ-ORG-017), sorted case-insensitively by name so
   * the first is the primary Staffel, as resolved by {@link
   * StaffelMembershipResolver#resolveNameSortedStaffelIds(List)}. Dangling memberships are skipped.
   *
   * @param userId the user whose Staffel memberships to resolve; never {@code null}.
   * @return the user's Staffel ids, name-sorted (primary first); never {@code null}, possibly
   *     empty.
   */
  @NotNull
  public List<UUID> findStaffelMembershipOrgUnitIds(@NotNull UUID userId) {
    return staffelMembershipResolver.resolveNameSortedStaffelIds(
        membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON));
  }

  /**
   * Returns the user's primary Staffel id, the first of {@link
   * #findStaffelMembershipOrgUnitIds(UUID)}; consistent with {@code UserDto.squadron}.
   *
   * @param userId the user whose primary Staffel to resolve; never {@code null}.
   * @return the primary Staffel's id when the user belongs to one, empty otherwise.
   */
  @NotNull
  public Optional<UUID> findStaffelMembershipOrgUnitId(@NotNull UUID userId) {
    return findStaffelMembershipOrgUnitIds(userId).stream().findFirst();
  }

  /**
   * Resolves the Staffel to record for the executing user on a job-order handover: the order's own
   * org unit when the user belongs to it, otherwise the user's primary Staffel (REQ-ORG-017).
   *
   * @param userId the executing user; never {@code null}.
   * @param orderOrgUnitId the order's responsible org-unit id, or {@code null} when the order names
   *     none.
   * @return the order-aligned executing Staffel id, or empty when the user holds no Staffel.
   */
  @NotNull
  public Optional<UUID> findExecutingStaffelForOrder(
      @NotNull UUID userId, @Nullable UUID orderOrgUnitId) {
    List<UUID> staffelIds = findStaffelMembershipOrgUnitIds(userId);
    if (orderOrgUnitId != null && staffelIds.contains(orderOrgUnitId)) {
      return Optional.of(orderOrgUnitId);
    }
    return staffelIds.stream().findFirst();
  }

  /**
   * Returns every membership row of the user, ordered like {@link #listOptionsForUser}: Staffel
   * first, then SKs by name.
   *
   * @param userId the user whose memberships to enumerate; never {@code null}.
   * @return the membership rows; never {@code null}, possibly empty.
   */
  @NotNull
  public List<OrgUnitMembership> findAllMembershipsForUser(@NotNull UUID userId) {
    List<OrgUnitMembership> rows = membershipRepository.findAllByIdUserId(userId);
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<UUID, OrgUnit> units = loadOrgUnitsById(rows);
    List<OrgUnitMembership> sorted = new ArrayList<>(rows);
    sorted.sort(
        Comparator.<OrgUnitMembership, Integer>comparing(
                m -> m.getKind() == OrgUnitKind.SQUADRON ? 0 : 1)
            .thenComparing(
                m -> {
                  OrgUnit unit = units.get(m.getId().getOrgUnitId());
                  OrgUnitKind expected =
                      m.getKind() == OrgUnitKind.SQUADRON
                          ? OrgUnitKind.SQUADRON
                          : OrgUnitKind.SPECIAL_COMMAND;
                  return unit != null && unit.getKind() == expected ? unit.getName() : "";
                },
                String.CASE_INSENSITIVE_ORDER));
    return sorted;
  }

  /**
   * Loads the org units behind the membership rows in one polymorphic {@link OrgUnitRepository}
   * batch, keyed by id; dangling ids are absent from the result.
   *
   * @param rows the membership rows whose org units to load; never {@code null}, non-empty.
   * @return the resolved org units keyed by id; never {@code null}, possibly smaller than {@code
   *     rows} when ids dangle.
   */
  @NotNull
  private Map<UUID, OrgUnit> loadOrgUnitsById(@NotNull List<OrgUnitMembership> rows) {
    List<UUID> ids = rows.stream().map(r -> r.getId().getOrgUnitId()).distinct().toList();
    return orgUnitRepository.findAllById(ids).stream()
        .collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
  }
}
