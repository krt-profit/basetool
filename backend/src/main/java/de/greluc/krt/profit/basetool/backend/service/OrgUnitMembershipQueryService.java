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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only query/projection half of the org-unit membership domain, split out of {@link
 * OrgUnitMembershipService} (audit Thema 7, #14). It owns every picker/option enumeration and every
 * membership accessor the controllers and downstream services read — active-org-unit option lists,
 * per-user direct/descendant membership options, the SK roster projection, and the name-sorted
 * Staffel accessors that back the authorization gates (REQ-ORG-017) — while the audit-,
 * {@code @Version}- and bank-responsibility-bearing add / remove / patch writes stay in {@link
 * OrgUnitMembershipService}. Carrying no mutation, it wires none of the write half's audit,
 * inventory-reconcile, org-chart-mirror or bank-seam collaborators.
 *
 * <p>The class-level {@code @Transactional(readOnly = true)} keeps the persistence session open for
 * the LAZY {@code user.effectiveName} read the {@code …Dto} projection wrappers perform (L4, #923,
 * ADR-0067); a projection reached from within an existing write transaction simply joins it, so the
 * membership-management flows that map the just-persisted row are unaffected.
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
   * Lists every active org unit (Staffel + Spezialkommando) as picker options, irrespective of
   * caller / target-user memberships. Backs the {@code GET /api/v1/org-units/active} endpoint that
   * the R5.d.c Job Order create form consumes — Job Orders are cross-staffel workspaces, so the
   * picker for {@code requestingOrgUnitId} is sourced from the full active-org-unit list rather
   * than the order owner's memberships.
   *
   * <p>The result is sorted Staffel-first then Spezialkommandos alphabetical, mirroring {@link
   * #listOptionsForUser}'s order so the two endpoints render identically in the picker.
   *
   * @return active Squadron + SpecialCommand options; never {@code null}, possibly empty when the
   *     system has zero active org units.
   */
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
   * Lists every active org unit of <em>all four</em> kinds (Staffel + Spezialkommando + Bereich +
   * Organisationsleitung) as picker options (epic #692 Phase 6, REQ-ORG-019). Unlike {@link
   * #listAllActiveOptions()} — which stays Staffel/SK-only because the public Job-Order form must
   * not offer a Bereich/OL as a requesting/responsible unit — this also surfaces the Bereiche and
   * the OL so the bank-management create form can link an {@code AREA} account to its Bereich and
   * the {@code CARTEL} account to the Organisationsleitung. Bereich/OL options carry {@code
   * isProfitEligible = false} (only Staffeln/SKs process orders). Ordered Staffel → SK → Bereich →
   * OL, each alphabetical, so the picker groups by tier; the consumer filters by {@link
   * OrgUnitMembershipOptionDto#kind()} per account type.
   *
   * @return active org-unit options across all four kinds; never {@code null}, possibly empty.
   */
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
   * Lists every org unit the given user is a member of, materialised as the picker-optimised {@link
   * OrgUnitMembershipOptionDto} wire shape. Backs the {@code GET
   * /api/v1/users/{userId}/memberships} endpoint that the R5.d owner-picker fragment consumes.
   *
   * <p>The result is sorted Staffel-first (because a user has at most one Staffel membership, and
   * keeping it at the top of the dropdown is the highest-frequency choice), then Spezialkommandos
   * alphabetical by name. Returns an empty list when the user has no memberships at all (typical
   * for admin / guest users that exist in {@code app_user} without a Staffel join), and also when
   * the user id itself is unknown — the picker treats both cases the same.
   *
   * <p>Inheritance look-up: the membership row carries an opaque {@code org_unit_id} plus the
   * denormalised {@code kind} discriminator. All rows are resolved in <em>one</em> batch through
   * the polymorphic {@link OrgUnitRepository} and matched back against the row's {@code kind} —
   * deliberately NOT via subclass-typed {@code SquadronRepository} / {@code
   * SpecialCommandRepository} loads, which would force Hibernate to narrow an org-unit id the
   * surrounding transaction already holds as a base-typed proxy (HHH000179, breaks ==). The
   * kind-match keeps the old discriminator-filter semantics: a row whose denormalised kind drifted
   * from the org-unit row is skipped, as is any non-Staffel/non-SK membership.
   *
   * @param userId the user whose memberships to enumerate; never {@code null}.
   * @return picker-friendly DTOs for each membership; never {@code null}, possibly empty.
   */
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
   * Returns the org-unit ids the user is a <em>direct</em> member of, across every kind (Staffel /
   * SK / Bereich / Organisationsleitung), with no leadership cascade. Unlike {@link
   * #listOptionsForUser(UUID)} — which materialises only the {@code SQUADRON} / {@code
   * SPECIAL_COMMAND} options the owner picker renders and deliberately skips {@code BEREICH} /
   * {@code ORGANISATIONSLEITUNG} rows — this is kind-agnostic: it reads the raw org-unit id off
   * every membership row, so a direct Bereich or OL assignment is included. It also avoids the
   * per-row org-unit lookups of the option list, because the home-page "Meine Einheit" highlight
   * (REQ-MISSION-012) only needs the id set, not the labelled options.
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
   * Lists every org unit the given user is a <em>direct</em> member of across <strong>all four
   * kinds</strong> (Staffel + SK + Bereich + Organisationsleitung), materialised as the
   * picker-optimised {@link OrgUnitMembershipOptionDto} wire shape with names. Unlike {@link
   * #listOptionsForUser(UUID)} — which deliberately materialises only the {@code SQUADRON} / {@code
   * SPECIAL_COMMAND} options the owner picker renders — this surfaces a direct Bereich or OL
   * membership too, resolving names through the kind-safe {@link OrgUnitRepository#findAllById}.
   * Backs the bank deposit/withdrawal counterparty org-unit picker (REQ-BANK-044), where a
   * depositor/recipient who is a Bereich/OL member must be able to record that unit. Ordered
   * top-down by kind (OL → Bereich → Staffel → SK) then by name, so the first element is the user's
   * deterministic primary unit.
   *
   * @param userId the user whose direct memberships to enumerate; never {@code null}.
   * @return picker-friendly DTOs across all four kinds; never {@code null}, possibly empty.
   */
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
    options.sort(
        Comparator.<OrgUnitMembershipOptionDto, Integer>comparing(o -> pickerKindOrder(o.kind()))
            .thenComparing(
                o -> o.orgUnitName() == null ? "" : o.orgUnitName(),
                String.CASE_INSENSITIVE_ORDER));
    return options;
  }

  /**
   * Resolves the user's <em>primary</em> direct org-unit membership id (REQ-BANK-044) — the first
   * of {@link #listDirectMembershipOptions(UUID)} in the deterministic top-down order, i.e. a
   * regular member's name-sorted primary Staffel, or a leader's Bereich / OL. Used to record the
   * requester's org unit when a booking <em>request</em> is confirmed (the requester is not present
   * to pick one).
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
   * Picker options for the <em>owning-org-unit</em> drill-down (epic #692 Phase 5, REQ-ORG-016 /
   * REQ-ORG-018): the caller's direct memberships <em>plus</em> the cascading leadership reach
   * (delegated to {@link OrgUnitCascadeService#expandWithDescendants(java.util.Collection)}).
   * Unlike {@link #listOptionsForUser(UUID)} — which stays strictly the user's DIRECT memberships
   * and is shared by the admin member views, the refinery-store/transfer receiver picker and the
   * active-context union — this widens the set so a Bereichsleitung/OL leader can pick their own
   * Bereich/OL <em>or</em> a subordinate Staffel/SK they oversee when stamping a new aggregate.
   *
   * <p>For a caller with no leadership flag the expansion collapses to their direct memberships, so
   * the picker is byte-identical to {@link #listOptionsForUser(UUID)} for an ordinary member. The
   * returned set may legitimately contain {@code BEREICH} / {@code ORGANISATIONSLEITUNG} options (a
   * leader owning their own level's data) — the picker fragment groups them by kind. Options are
   * sorted top-down by hierarchy kind (OL → Bereich → Staffel → SK) then by name.
   *
   * @param userId the caller whose reachable org units to enumerate; never {@code null}.
   * @return picker-friendly DTOs across all reachable kinds; never {@code null}, possibly empty
   *     when the caller has no membership at all.
   */
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
  private static int pickerKindOrder(@NotNull OrgUnitKind kind) {
    return switch (kind) {
      case ORGANISATIONSLEITUNG -> 0;
      case BEREICH -> 1;
      case SQUADRON -> 2;
      case SPECIAL_COMMAND -> 3;
    };
  }

  /**
   * Lists every membership of the given Spezialkommando. Used by the admin roster page to render
   * the member chip list. The SK existence is validated via {@link SpecialCommandService} so a
   * stale id surfaces as 404 instead of an empty list (which would mask a wrong URL).
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
   * DTO projection of {@link #findAllMembershipsForUser(UUID)}: the user's complete membership set
   * (Staffel + every SK) as full response DTOs, mapped inside the read transaction so the lazy
   * {@code user.effectiveName} read succeeds. Backs {@code GET
   * /api/v1/users/{id}/memberships/detail}.
   *
   * @param userId the user whose memberships to project; never {@code null}.
   * @return the user's memberships as DTOs; never {@code null}, possibly empty.
   */
  public List<OrgUnitMembershipDto> findAllMembershipDtosForUser(@NotNull UUID userId) {
    return toDtos(findAllMembershipsForUser(userId));
  }

  /**
   * Projects the given membership rows to their response DTOs. Deliberately {@code private}: the
   * mapper reads {@code user.effectiveName} through the LAZY user association, so the rows must
   * still be attached to the session that loaded them — a contract only same-transaction callers
   * inside this service can guarantee. External callers use the public {@code …Dto} projections,
   * which load and map inside one service transaction (L4, #923, ADR-0067).
   *
   * @param memberships the membership rows to project; never {@code null}.
   * @return the membership DTOs in the same order; never {@code null}, possibly empty.
   */
  private List<OrgUnitMembershipDto> toDtos(@NotNull List<OrgUnitMembership> memberships) {
    return memberships.stream().map(orgUnitMembershipMapper::toDto).toList();
  }

  /**
   * Returns every Staffel OrgUnit id the user belongs to (REQ-ORG-017 — up to two), sorted
   * case-insensitively by squadron name so the first element is the deterministic <em>primary</em>
   * Staffel — the same primary definition {@code UserMapper.resolveSquadron} / {@code
   * UserDto.squadron} use. Backs the authorization gates that must consider ALL of a target's
   * Staffeln (grant on any overlap), and the single-valued callers that want a stable primary. The
   * name-sort (and the single-Staffel fast path that skips the squadron load) lives in {@link
   * StaffelMembershipResolver#resolveNameSortedStaffelIds(List)}, the single owner of the primary
   * definition; a dangling membership (a row whose squadron no longer resolves) is skipped there,
   * so the accessor never throws.
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
   * Returns the user's deterministic <em>primary</em> Staffel OrgUnit id — the name-sorted first of
   * {@link #findStaffelMembershipOrgUnitIds(UUID)} (REQ-ORG-017: a user may now hold up to two
   * Staffeln). Stable across requests and consistent with {@code UserDto.squadron}. Callers that
   * must consider BOTH Staffeln (e.g. authorization gates) use {@link
   * #findStaffelMembershipOrgUnitIds(UUID)} instead of this single-valued accessor.
   *
   * @param userId the user whose primary Staffel to resolve; never {@code null}.
   * @return the primary Staffel's id when the user belongs to one, empty otherwise.
   */
  @NotNull
  public Optional<UUID> findStaffelMembershipOrgUnitId(@NotNull UUID userId) {
    return findStaffelMembershipOrgUnitIds(userId).stream().findFirst();
  }

  /**
   * Resolves the executing user's Staffel to snapshot on a cross-staffel audit trail (Job-Order
   * handover), <em>order-aligned</em> per REQ-ORG-017: when the user holds two Staffeln and one of
   * them is the order's own (responsible) org unit, that Staffel is recorded — it is the unit the
   * user was actually acting under; otherwise the user's deterministic name-sorted primary Staffel.
   * Returns empty only when the user holds no Staffel at all.
   *
   * @param userId the executing user; never {@code null}.
   * @param orderOrgUnitId the order's responsible org-unit id, or {@code null} when the order names
   *     none.
   * @return the order-aligned executing Staffel id, or empty when the user holds no Staffel.
   */
  @NotNull
  public Optional<UUID> findExecutingStaffelForOrder(
      @NotNull UUID userId, @org.jetbrains.annotations.Nullable UUID orderOrgUnitId) {
    List<UUID> staffelIds = findStaffelMembershipOrgUnitIds(userId);
    if (orderOrgUnitId != null && staffelIds.contains(orderOrgUnitId)) {
      return Optional.of(orderOrgUnitId);
    }
    return staffelIds.stream().findFirst();
  }

  /**
   * SPEZIALKOMMANDO_PLAN.md §7.4 helper — returns every membership row of the given user (Staffel +
   * every SK) in the same order {@link #listOptionsForUser} sorts the picker options (Staffel
   * first, then SKs alphabetical). The delta-endpoint return path uses this to render the
   * post-write state without a follow-up GET on the frontend side.
   *
   * @param userId the user whose memberships to enumerate; never {@code null}.
   * @return the membership rows; never {@code null}, possibly empty.
   */
  public List<OrgUnitMembership> findAllMembershipsForUser(@NotNull UUID userId) {
    List<OrgUnitMembership> rows = membershipRepository.findAllByIdUserId(userId);
    if (rows.isEmpty()) {
      return List.of();
    }
    // One polymorphic batch load feeds the name sort — the previous per-row subclass-typed
    // findById calls inside the comparator were both an N+1 and a proxy-narrowing source
    // (HHH000179) whenever the transaction already held one of the ids as a base-typed proxy.
    // The kind-match preserves the old discriminator-filter semantics (a drifted or
    // non-Staffel/non-SK row sorts under the empty name, exactly as its typed lookup missed
    // before).
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
   * Resolves the org units behind the given membership rows in one polymorphic {@link
   * OrgUnitRepository} batch, keyed by id. The base-typed query reuses any org-unit instance the
   * surrounding transaction already tracks (instead of narrowing it through a subclass-typed load,
   * HHH000179) and drops dangling ids — a membership whose org unit no longer resolves is simply
   * absent from the map.
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
