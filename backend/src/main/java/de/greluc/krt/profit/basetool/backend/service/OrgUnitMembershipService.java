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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.OrgUnitMembershipMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.KommandoGroup;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BereichLeadershipRole;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipFlagsPatchRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipLeadToggleRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.repository.KommandoGroupRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.StaffelMembershipResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Membership-management service for Spezialkommandos — adds / removes / patches members of an SK
 * through the endpoints under {@code /api/v1/special-commands/{id}/members}. The service is
 * intentionally scoped to {@link OrgUnitKind#SPECIAL_COMMAND} memberships: every entry point loads
 * the parent SK through {@link SpecialCommandService#getSpecialCommandById(UUID)} first, which
 * already filters via the JPA discriminator. A Squadron UUID accidentally routed through the SK
 * endpoints therefore lands as a clean 404 before any membership row is touched, never as a
 * corrupted Staffel membership.
 *
 * <p>Staffel membership add / remove / flag changes flow through {@link
 * #reconcileStaffelMemberships(User, java.util.List)} (the member-edit membership-delta path, up to
 * two Staffeln per REQ-ORG-017) and the version-aware {@link #patchSquadronMemberFlags(UUID, UUID,
 * MembershipFlagsPatchRequest)}. The legacy {@code app_user.is_logistician} / {@code
 * app_user.is_mission_manager} columns were dropped in V101 (R9 Step 5) — the membership row is the
 * single source of truth.
 *
 * <p>Concurrency: every write method checks the inbound {@code version} against the membership
 * row's {@code @Version} field, throwing {@link ObjectOptimisticLockingFailureException} → 409 on
 * mismatch so two concurrent admin edits do not silently lose either flag flip.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgUnitMembershipService {

  private final OrgUnitMembershipRepository membershipRepository;
  private final SpecialCommandService specialCommandService;
  private final UserRepository userRepository;
  private final SquadronRepository squadronRepository;
  private final SpecialCommandRepository specialCommandRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final KommandoGroupRepository kommandoGroupRepository;
  private final OrgUnitCascadeService orgUnitCascadeService;
  private final InventoryOrgUnitReconciler inventoryReconciler;
  private final AuditService auditService;
  private final OrgChartService orgChartService;
  private final StaffelMembershipResolver staffelMembershipResolver;
  private final OrgUnitMembershipMapper orgUnitMembershipMapper;

  /**
   * The org-unit-aware bank seam, injected as an {@link ObjectProvider} to break the constructor
   * cycle (the seam depends on {@code BankBookingRequestService}, which depends on this service).
   * It is resolved lazily around each leadership mutation to audit a change of the affected
   * accounts' derived responsible holder(s) (Kontoverantwortliche/r, REQ-BANK-034/ADR-0070). All
   * bank access stays inside the seam — this service only brackets its mutation with a before/after
   * snapshot.
   */
  private final ObjectProvider<OrgUnitBankAccessService> orgUnitBankAccessServiceProvider;

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
   * denormalised {@code kind} discriminator, so a polymorphic load through a single {@code
   * OrgUnitRepository} would require introducing one (still deferred per R2.a's repository
   * decision). Branching on {@code kind} and dispatching to the existing {@link SquadronRepository}
   * / {@link SpecialCommandRepository} avoids that dependency and stays consistent with how the
   * rest of the service already treats the two kinds (the {@code addMember} / {@code removeMember}
   * paths already only touch the SK side).
   *
   * @param userId the user whose memberships to enumerate; never {@code null}.
   * @return picker-friendly DTOs for each membership; never {@code null}, possibly empty.
   */
  public List<OrgUnitMembershipOptionDto> listOptionsForUser(@NotNull UUID userId) {
    List<OrgUnitMembership> rows = membershipRepository.findAllByIdUserId(userId);
    if (rows.isEmpty()) {
      return List.of();
    }
    List<OrgUnitMembershipOptionDto> options = new ArrayList<>(rows.size());
    for (OrgUnitMembership row : rows) {
      UUID orgUnitId = row.getId().getOrgUnitId();
      if (row.getKind() == OrgUnitKind.SQUADRON) {
        Optional<Squadron> sq = squadronRepository.findById(orgUnitId);
        sq.ifPresent(
            s ->
                options.add(
                    new OrgUnitMembershipOptionDto(
                        s.getId(),
                        s.getName(),
                        s.getShorthand(),
                        OrgUnitKind.SQUADRON,
                        s.isProfitEligible())));
      } else if (row.getKind() == OrgUnitKind.SPECIAL_COMMAND) {
        Optional<SpecialCommand> sc = specialCommandRepository.findById(orgUnitId);
        sc.ifPresent(
            s ->
                options.add(
                    new OrgUnitMembershipOptionDto(
                        s.getId(),
                        s.getName(),
                        s.getShorthand(),
                        OrgUnitKind.SPECIAL_COMMAND,
                        s.isProfitEligible())));
      }
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
   * Adds the given user as a member of the given Spezialkommando. Returns the persisted membership
   * row with the V95 trigger-derived {@code kind} value pre-populated on the in-memory entity (the
   * actual DB column is written by the BEFORE-INSERT trigger; we mirror the value on the entity so
   * the immediate DTO mapping reads the right discriminator without an extra refresh).
   *
   * <p>Idempotency: an attempt to add a user who is already a member raises {@link
   * DuplicateEntityException} → 409 rather than silently no-op. The admin UI is expected to use a
   * dedicated "already member" detection instead of leaning on add as a re-attach.
   *
   * @param specialCommandId the SK to add the user to; never {@code null}.
   * @param userId the user to add; never {@code null}.
   * @return the persisted membership row.
   * @throws NotFoundException if no SK matches the given id, or no user matches the given id.
   * @throws DuplicateEntityException if the user is already a member of this SK.
   */
  @Transactional
  public OrgUnitMembership addMember(@NotNull UUID specialCommandId, @NotNull UUID userId) {
    SpecialCommand sc = specialCommandService.getSpecialCommandById(specialCommandId);
    User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    if (membershipRepository.existsByIdUserIdAndIdOrgUnitId(userId, sc.getId())) {
      throw new DuplicateEntityException("User is already a member of this Spezialkommando");
    }

    final boolean wasMembershipless = membershipRepository.countByIdUserId(userId) == 0;

    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(userId, sc.getId()));
    membership.setUser(user);
    // The kind column is managed by the V95 sync_org_unit_membership_kind trigger
    // (insertable=false on the @Column mapping). We mirror the value on the in-memory entity so
    // the immediate DTO mapping reads the right discriminator without re-fetching the row.
    membership.setKind(OrgUnitKind.SPECIAL_COMMAND);
    membership.setJoinedAt(Instant.now());
    OrgUnitMembership saved = membershipRepository.save(membership);

    // First org-unit membership for this user → their ownerless-personal inventory adopts this SK
    // (the auto-promote lifecycle policy). No-op when the user already had memberships or owns no
    // ownerless inventory.
    if (wasMembershipless) {
      inventoryReconciler.onUserGainedFirstOrgUnit(userId, sc);
    }
    auditService.record(
        AuditEventType.MEMBERSHIP_GRANTED,
        sc.getId(),
        orgUnitLabel(sc),
        userId,
        "kind=SPECIAL_COMMAND");
    return saved;
  }

  /**
   * Removes the given user from the given Spezialkommando. Existence checks both sides so a stale
   * URL surfaces as 404 rather than as a silent no-op.
   *
   * @param specialCommandId the SK to remove the user from; never {@code null}.
   * @param userId the user to remove; never {@code null}.
   * @throws NotFoundException if no SK matches the given id, or the user is not a member of this
   *     SK.
   */
  @Transactional
  public void removeMember(@NotNull UUID specialCommandId, @NotNull UUID userId) {
    SpecialCommand sc = specialCommandService.getSpecialCommandById(specialCommandId);
    OrgUnitMembershipId id = new OrgUnitMembershipId(userId, sc.getId());
    if (!membershipRepository.existsById(id)) {
      throw new NotFoundException("Membership not found");
    }
    // Removing an SK member who is the SK-Lead drops the SK account's derived responsible holder,
    // so
    // snapshot before the delete and re-diff after it (REQ-BANK-034, ADR-0070).
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankAccessServiceProvider.getObject().snapshotResponsibleHolders(sc.getId());
    membershipRepository.deleteById(id);
    // Drop any mirrored SK chart seat (an SK-Leiter losing the membership) in the same transaction
    // so no stale seat lingers (REQ-ROLE-006).
    orgChartService.mirrorRemoveUnitSeat(sc.getId(), userId);

    // Last org-unit membership removed → the user's org-stamped inventory falls back to
    // ownerless-personal (the auto-demote lifecycle policy). The count query auto-flushes the
    // delete first, so it reflects the just-removed row.
    if (membershipRepository.countByIdUserId(userId) == 0) {
      inventoryReconciler.onUserLostLastOrgUnit(userId);
    }
    auditService.record(
        AuditEventType.MEMBERSHIP_REVOKED,
        sc.getId(),
        orgUnitLabel(sc),
        userId,
        "kind=SPECIAL_COMMAND");
    orgUnitBankAccessServiceProvider.getObject().recordResponsibleHolderChanges(responsibleBefore);
  }

  /**
   * Grants a user an explicit, reach-bearing Bereichsleitung role on the given Bereich (epic #692,
   * REQ-ORG-017) — distinct from the SK-Leiter's derived (computed, not stored) Bereichsleitung
   * seat, which has no membership row. If the user already has a membership row on this Bereich
   * (from a prior explicit grant), its role flag is updated in place; otherwise a fresh membership
   * is created. Exactly one of the three Bereich role flags ends up set. The user must hold no
   * Staffel membership — the service guard returns a clean 400 before the V165 trigger would 500.
   * Unlike a Staffel/SK join this does <em>not</em> adopt the user's ownerless inventory (a Bereich
   * is not a personal-inventory home).
   *
   * @param bereichId the Bereich to add the leader to; must be a {@code BEREICH} org unit.
   * @param userId the user to grant the role to; never {@code null}.
   * @param role the Bereichsleitung role to set; never {@code null}.
   * @return the persisted membership row.
   * @throws NotFoundException if the Bereich or the user does not exist.
   * @throws BadRequestException if {@code bereichId} is not a Bereich, or the user belongs to a
   *     Staffel.
   */
  @Transactional
  public OrgUnitMembership addBereichLeader(
      @NotNull UUID bereichId, @NotNull UUID userId, @NotNull BereichLeadershipRole role) {
    OrgUnit bereich =
        orgUnitRepository
            .findById(bereichId)
            .orElseThrow(() -> new NotFoundException("Bereich not found"));
    if (bereich.getKind() != OrgUnitKind.BEREICH) {
      throw new BadRequestException("Org unit " + bereichId + " is not a Bereich");
    }
    final User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    if (userHoldsStaffelMembership(userId)) {
      throw new BadRequestException(
          "User belongs to a Staffel and cannot be a Bereichsleitung member — remove the Staffel"
              + " membership first (REQ-ORG-017)");
    }
    OrgUnitMembership m =
        membershipRepository.findById(new OrgUnitMembershipId(userId, bereichId)).orElse(null);
    final MembershipRole previousRole = m != null ? m.getRole() : null;
    if (m == null) {
      m = new OrgUnitMembership();
      m.setId(new OrgUnitMembershipId(userId, bereichId));
      m.setUser(user);
      // kind is trigger-managed (insertable=false); mirror it so the in-memory row is consistent.
      m.setKind(OrgUnitKind.BEREICH);
      m.setJoinedAt(Instant.now());
    }
    // Snapshot the Bereich account's (and, for a Profit Bereich, the CARTEL/CARTEL_BANK accounts')
    // derived responsible holder(s) before the role changes, to audit the change (REQ-BANK-034,
    // ADR-0070). Bank access is confined to the seam; the ObjectProvider breaks the DI cycle.
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankAccessServiceProvider.getObject().snapshotResponsibleHolders(bereichId);
    // The unified rank is the sole source of truth (epic #800, REQ-ROLE-001); the legacy boolean
    // leadership flags (is_bereichsleiter / -koordinator / -operator) were dropped in the Phase 5
    // cleanup (V187).
    m.setRole(
        switch (role) {
          case LEITER -> MembershipRole.BEREICHSLEITER;
          case KOORDINATOR -> MembershipRole.BEREICHSKOORDINATOR;
          case OPERATOR -> MembershipRole.BEREICHSOPERATOR;
        });
    // saveAndFlush (not save): on the upsert-onto-existing-row branch this is an UPDATE, so without
    // an explicit flush the @Version increment would land after the controller maps the response
    // and the caller would get a stale version. Flushing also surfaces the V165 trigger as a clean
    // in-transaction failure rather than at commit.
    OrgUnitMembership saved = membershipRepository.saveAndFlush(m);
    // Mirror the account-linked seat onto the descriptive chart in the same transaction
    // (REQ-ROLE-006); the chart still grants nothing.
    orgChartService.mirrorBereichRole(bereich.getId(), userId, role);
    final boolean firstGrant = previousRole == null || previousRole == MembershipRole.MEMBER;
    auditService.record(
        firstGrant ? AuditEventType.ROLE_GRANTED : AuditEventType.ROLE_CHANGED,
        bereich.getId(),
        orgUnitLabel(bereich),
        userId,
        firstGrant ? "role=" + saved.getRole() : "from=" + previousRole + " to=" + saved.getRole());
    orgUnitBankAccessServiceProvider.getObject().recordResponsibleHolderChanges(responsibleBefore);
    return saved;
  }

  /**
   * Removes a Bereichsleitung member by deleting their membership row on the Bereich.
   *
   * @param bereichId the Bereich; never {@code null}.
   * @param userId the user to remove; never {@code null}.
   * @throws NotFoundException if the user is not a member of the Bereich.
   */
  @Transactional
  public void removeBereichLeader(@NotNull UUID bereichId, @NotNull UUID userId) {
    OrgUnitMembershipId id = new OrgUnitMembershipId(userId, bereichId);
    OrgUnitMembership m =
        membershipRepository
            .findById(id)
            .orElseThrow(() -> new NotFoundException("Bereichsleitung membership not found"));
    final MembershipRole previousRole = m.getRole();
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankAccessServiceProvider.getObject().snapshotResponsibleHolders(bereichId);
    membershipRepository.delete(m);
    // Remove the mirrored chart seat in the same transaction (REQ-ROLE-006).
    orgChartService.mirrorRemoveUnitSeat(bereichId, userId);
    auditService.record(
        AuditEventType.ROLE_REVOKED,
        bereichId,
        orgUnitLabelById(bereichId),
        userId,
        AuditDetails.of("role", previousRole));
    orgUnitBankAccessServiceProvider.getObject().recordResponsibleHolderChanges(responsibleBefore);
  }

  /**
   * Adds a user to the Organisationsleitung (epic #692, REQ-ORG-017), setting the {@code
   * is_ol_member} flag. The user must hold no Staffel membership (service guard + V165 trigger). A
   * duplicate add is rejected with 409.
   *
   * @param organisationsleitungId the OL org unit; must be of kind {@code ORGANISATIONSLEITUNG}.
   * @param userId the user to add; never {@code null}.
   * @return the persisted membership row.
   * @throws NotFoundException if the OL or the user does not exist.
   * @throws BadRequestException if the id is not the OL, or the user belongs to a Staffel.
   * @throws DuplicateEntityException if the user is already an OL member.
   */
  @Transactional
  public OrgUnitMembership addOlMember(@NotNull UUID organisationsleitungId, @NotNull UUID userId) {
    OrgUnit ol =
        orgUnitRepository
            .findById(organisationsleitungId)
            .orElseThrow(() -> new NotFoundException("Organisationsleitung not found"));
    if (ol.getKind() != OrgUnitKind.ORGANISATIONSLEITUNG) {
      throw new BadRequestException(
          "Org unit " + organisationsleitungId + " is not the Organisationsleitung");
    }
    final User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    if (userHoldsStaffelMembership(userId)) {
      throw new BadRequestException(
          "User belongs to a Staffel and cannot be an Organisationsleitung member — remove the"
              + " Staffel membership first (REQ-ORG-017)");
    }
    if (membershipRepository.existsByIdUserIdAndIdOrgUnitId(userId, organisationsleitungId)) {
      throw new DuplicateEntityException("User is already an Organisationsleitung member");
    }
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, organisationsleitungId));
    m.setUser(user);
    m.setKind(OrgUnitKind.ORGANISATIONSLEITUNG);
    m.setJoinedAt(Instant.now());
    // Snapshot the CARTEL account's collegial responsible holders (all OL members) before the add,
    // to audit the change (REQ-BANK-034, ADR-0070).
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankAccessServiceProvider
            .getObject()
            .snapshotResponsibleHolders(organisationsleitungId);
    // The unified rank is the sole source of truth (epic #800, REQ-ROLE-001); is_ol_member was
    // dropped in the Phase 5 cleanup (V187).
    m.setRole(MembershipRole.OL_MEMBER);
    // saveAndFlush for parity with addBereichLeader: surfaces the V165 trigger as a clean
    // in-transaction failure and keeps the flushed @Version in the response under the
    // class-@Transactional controller.
    final OrgUnitMembership saved = membershipRepository.saveAndFlush(m);
    // Mirror the OL seat onto the descriptive chart in the same transaction (REQ-ROLE-006).
    orgChartService.mirrorOlMember(ol.getId(), userId);
    auditService.record(
        AuditEventType.ROLE_GRANTED, ol.getId(), orgUnitLabel(ol), userId, "role=OL_MEMBER");
    orgUnitBankAccessServiceProvider.getObject().recordResponsibleHolderChanges(responsibleBefore);
    return saved;
  }

  /**
   * Removes a user from the Organisationsleitung by deleting their OL membership row.
   *
   * @param organisationsleitungId the OL org unit; never {@code null}.
   * @param userId the user to remove; never {@code null}.
   * @throws NotFoundException if the user is not an OL member.
   */
  @Transactional
  public void removeOlMember(@NotNull UUID organisationsleitungId, @NotNull UUID userId) {
    OrgUnitMembershipId id = new OrgUnitMembershipId(userId, organisationsleitungId);
    OrgUnitMembership m =
        membershipRepository
            .findById(id)
            .orElseThrow(() -> new NotFoundException("Organisationsleitung membership not found"));
    final MembershipRole previousRole = m.getRole();
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankAccessServiceProvider
            .getObject()
            .snapshotResponsibleHolders(organisationsleitungId);
    membershipRepository.delete(m);
    // Remove the mirrored OL chart seat in the same transaction (REQ-ROLE-006).
    orgChartService.mirrorRemoveUnitSeat(organisationsleitungId, userId);
    auditService.record(
        AuditEventType.ROLE_REVOKED,
        organisationsleitungId,
        orgUnitLabelById(organisationsleitungId),
        userId,
        AuditDetails.of("role", previousRole));
    orgUnitBankAccessServiceProvider.getObject().recordResponsibleHolderChanges(responsibleBefore);
  }

  /**
   * Flips the per-membership Logistician / Mission Manager flags on the membership row. Either flag
   * may be {@code null} in the request — that means "leave the current value alone". The inbound
   * {@code version} is checked against the row's {@code @Version} to surface concurrent admin edits
   * as 409 instead of silently losing one of them.
   *
   * @param specialCommandId the SK whose membership to patch; never {@code null}.
   * @param userId the user whose membership to patch; never {@code null}.
   * @param request patch payload; never {@code null}.
   * @return the persisted membership row with the bumped {@code @Version}.
   * @throws NotFoundException if no SK matches the given id, or the user is not a member.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembership patchFlags(
      @NotNull UUID specialCommandId,
      @NotNull UUID userId,
      @NotNull MembershipFlagsPatchRequest request) {
    OrgUnitMembership m = loadMembership(specialCommandId, userId);
    assertVersionMatches(m, request.version());
    if (request.isLogistician() != null) {
      m.setLogistician(request.isLogistician());
    }
    if (request.isMissionManager() != null) {
      m.setMissionManager(request.isMissionManager());
    }
    // saveAndFlush (not save): the DTO wrapper maps the response inside this transaction, and
    // without an explicit flush the @Version bump would land at commit — after the mapping — so
    // the client would echo the stale pre-bump version and 409 on its next edit (REQ-FE-003).
    OrgUnitMembership saved = membershipRepository.saveAndFlush(m);
    recordCapabilityFlagsChanged(specialCommandId, userId, saved);
    return saved;
  }

  /**
   * R6.e — Squadron-side counterpart of {@link #patchFlags(UUID, UUID,
   * MembershipFlagsPatchRequest)}. Same payload contract (boxed {@code Boolean} flags, mandatory
   * {@code version}) and same optimistic-lock semantics; only the existence check up front is
   * different — Squadrons live in the {@link SquadronRepository}, not the {@link
   * SpecialCommandRepository}. ADMIN-gated at the controller layer per plan §5.6 ({@code PATCH
   * /api/v1/squadrons/{id}/members/{userId}}). Used to migrate the legacy {@code
   * UserController.updateLogisticianStatus} / {@code updateMissionManagerStatus} writes from the
   * {@code app_user.is_logistician} / {@code is_mission_manager} columns onto the per-membership
   * row (R6.e write-side completion of plan D3).
   *
   * @param squadronId the Squadron whose membership to patch; never {@code null}.
   * @param userId the user whose membership to patch; never {@code null}.
   * @param request patch payload; never {@code null}.
   * @return the persisted membership row with the bumped {@code @Version}.
   * @throws NotFoundException if no Squadron matches the given id, or the user is not a member.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembership patchSquadronMemberFlags(
      @NotNull UUID squadronId,
      @NotNull UUID userId,
      @NotNull MembershipFlagsPatchRequest request) {
    Squadron squadron =
        squadronRepository
            .findById(squadronId)
            .orElseThrow(() -> new NotFoundException("Squadron not found"));
    OrgUnitMembership m =
        membershipRepository
            .findById(new OrgUnitMembershipId(userId, squadron.getId()))
            .orElseThrow(() -> new NotFoundException("Membership not found"));
    assertVersionMatches(m, request.version());
    if (request.isLogistician() != null) {
      m.setLogistician(request.isLogistician());
    }
    if (request.isMissionManager() != null) {
      m.setMissionManager(request.isMissionManager());
    }
    // saveAndFlush (not save): flush before the DTO wrapper maps the response so the client gets
    // the bumped @Version, not the stale pre-flush one (REQ-FE-003).
    OrgUnitMembership saved = membershipRepository.saveAndFlush(m);
    auditService.record(
        AuditEventType.CAPABILITY_FLAGS_CHANGED,
        squadron.getId(),
        orgUnitLabel(squadron),
        userId,
        AuditDetails.of("logistician", saved.isLogistician())
            .with("missionManager", saved.isMissionManager()));
    return saved;
  }

  /**
   * Reconciles the user's Staffel memberships to the supplied desired set (epic multi-Staffel,
   * REQ-ORG-017 — a user may belong to up to two Staffeln, each carrying its own per-squadron
   * Logistician / Mission-Manager flags per REQ-SEC-005). Backs the member-edit membership-delta
   * endpoint: the admin form posts the complete desired Staffel set, and this method adds the
   * squadrons that are not yet a membership, removes the current Staffel memberships absent from
   * the set, and patches the flags of the ones that stay — all in the caller's transaction.
   *
   * <p>Reconcile order matters: removals are deleted and flushed <em>before</em> any insert so the
   * V164 {@code enforce_max_two_squadron_memberships} counting trigger never miscounts an
   * about-to-be-removed row as a third Staffel during a re-point (e.g. {@code [A,B] → [A,C]}).
   * Additions and flag patches then run; a flag patch only writes (and only audits) when a value
   * actually changes, so re-posting an unchanged set is a clean no-op.
   *
   * <p>Guards (defence in depth ahead of the DB triggers, surfacing clean 400s):
   *
   * <ul>
   *   <li>duplicate squadron in the desired set → 400;
   *   <li>more than two desired squadrons → 400;
   *   <li>the user holds a silo-leader role (SK-Lead / Bereichsleitung / OL) while at least one
   *       Staffel is desired → 400 (a leader belongs to no Staffel, REQ-ORG-017).
   * </ul>
   *
   * <p>Inventory lifecycle: if this reconcile grants the user their first-ever org-unit membership
   * the ownerless-personal inventory adopts the name-sorted <em>primary</em> of the newly added
   * Staffeln (the same deterministic primary {@code UserDto.squadron} / the create-time auto-stamp
   * use, rather than whichever Staffel the client happened to list first); if it removes the user's
   * last remaining membership the inventory demotes back to ownerless-personal.
   *
   * @param user the user whose Staffel memberships to reconcile; never {@code null}.
   * @param desired the complete desired Staffel membership set (0–2 entries); never {@code null},
   *     possibly empty (which removes every Staffel membership).
   * @throws NotFoundException if a desired squadron id does not resolve to a Squadron.
   * @throws BadRequestException on a duplicate squadron, more than two squadrons, or a leadership
   *     conflict.
   */
  @Transactional
  public void reconcileStaffelMemberships(
      @NotNull User user, @NotNull List<MembershipDeltaRequest.StaffelChange> desired) {
    List<UUID> desiredIds =
        desired.stream().map(MembershipDeltaRequest.StaffelChange::squadronId).toList();
    Set<UUID> distinctIds = new LinkedHashSet<>(desiredIds);
    if (distinctIds.size() != desiredIds.size()) {
      throw new BadRequestException("A user cannot be assigned the same Staffel twice");
    }
    if (distinctIds.size() > 2) {
      throw new BadRequestException("A user may belong to at most two Staffeln (REQ-ORG-017)");
    }
    // REQ-ORG-017: a silo leader (SK-Leiter / Bereichsleitung / OL) belongs to no Staffel. Reject a
    // Staffel assignment with a clean 400 before the V165 DB trigger turns it into a 500.
    if (!desired.isEmpty() && userHoldsLeadershipRole(user.getId())) {
      throw new BadRequestException(
          "User holds a leadership role (SK-Lead/Bereichsleitung/OL) and cannot be assigned to a"
              + " Staffel — remove the leadership role first (REQ-ORG-017)");
    }

    // Resolve every desired squadron up front so an unknown id fails before any row is mutated.
    Map<UUID, Squadron> targetSquadrons = new LinkedHashMap<>();
    for (UUID id : distinctIds) {
      Squadron sq =
          squadronRepository
              .findById(id)
              .orElseThrow(() -> new NotFoundException("Squadron not found with id: " + id));
      targetSquadrons.put(id, sq);
    }

    final long membershipsBefore = membershipRepository.countByIdUserId(user.getId());
    List<OrgUnitMembership> currentStaffel =
        membershipRepository.findAllByIdUserIdAndKind(user.getId(), OrgUnitKind.SQUADRON);
    Map<UUID, OrgUnitMembership> currentById = new LinkedHashMap<>();
    for (OrgUnitMembership m : currentStaffel) {
      currentById.put(m.getId().getOrgUnitId(), m);
    }

    // 1. Removals: current Staffel memberships not in the desired set. Delete + flush BEFORE any
    // insert so the V164 counting trigger sees the post-removal state.
    List<OrgUnitMembership> toRemove =
        currentStaffel.stream()
            .filter(m -> !distinctIds.contains(m.getId().getOrgUnitId()))
            .toList();
    if (!toRemove.isEmpty()) {
      // A removed Staffel membership that carried the Staffelleiter rank drops that Staffel
      // account's
      // derived responsible holder — snapshot the affected accounts before the delete and re-diff
      // after it (REQ-BANK-034, ADR-0070). A Staffel is never a Profit Bereich, so there is no
      // ripple.
      final Map<UUID, Set<UUID>> responsibleBefore = new LinkedHashMap<>();
      for (OrgUnitMembership removed : toRemove) {
        responsibleBefore.putAll(
            orgUnitBankAccessServiceProvider
                .getObject()
                .snapshotResponsibleHolders(removed.getId().getOrgUnitId()));
      }
      membershipRepository.deleteAll(toRemove);
      membershipRepository.flush();
      for (OrgUnitMembership removed : toRemove) {
        recordStaffelMembershipRevoked(removed.getId().getOrgUnitId(), user.getId());
        // A squadron rank held on the removed row leaves a stale chart seat — clear it
        // (REQ-ROLE-006).
        orgChartService.mirrorRemoveSquadronRank(removed.getId().getOrgUnitId(), user.getId());
      }
      orgUnitBankAccessServiceProvider
          .getObject()
          .recordResponsibleHolderChanges(responsibleBefore);
    }

    // 2. Additions + in-place flag patches.
    List<Squadron> addedSquadrons = new ArrayList<>();
    for (MembershipDeltaRequest.StaffelChange change : desired) {
      UUID squadronId = change.squadronId();
      boolean wantLogistician = Boolean.TRUE.equals(change.isLogistician());
      boolean wantMissionManager = Boolean.TRUE.equals(change.isMissionManager());
      OrgUnitMembership existing = currentById.get(squadronId);
      if (existing != null) {
        boolean changed =
            existing.isLogistician() != wantLogistician
                || existing.isMissionManager() != wantMissionManager;
        if (changed) {
          existing.setLogistician(wantLogistician);
          existing.setMissionManager(wantMissionManager);
          OrgUnitMembership saved = membershipRepository.saveAndFlush(existing);
          recordCapabilityFlagsChanged(squadronId, user.getId(), saved);
        }
      } else {
        OrgUnitMembership fresh = new OrgUnitMembership();
        fresh.setId(new OrgUnitMembershipId(user.getId(), squadronId));
        fresh.setUser(user);
        fresh.setJoinedAt(Instant.now());
        fresh.setLogistician(wantLogistician);
        fresh.setMissionManager(wantMissionManager);
        membershipRepository.save(fresh);
        Squadron sq = targetSquadrons.get(squadronId);
        auditService.record(
            AuditEventType.MEMBERSHIP_GRANTED,
            squadronId,
            orgUnitLabel(sq),
            user.getId(),
            "kind=SQUADRON");
        addedSquadrons.add(sq);
      }
    }

    // 3. Inventory lifecycle: a first-ever membership adopts the name-sorted PRIMARY of the newly
    // added Staffeln (REQ-ORG-017) — not the request-order-first — so the inventory's owning
    // Staffel
    // matches the deterministic primary every other surface (UserDto.squadron, the create-time
    // auto-stamp, officer oversight) derives from the same name sort. The last membership removed
    // demotes the org-stamped inventory back to ownerless-personal.
    if (membershipsBefore == 0 && !addedSquadrons.isEmpty()) {
      Squadron primaryAdded =
          addedSquadrons.stream()
              .min(Comparator.comparing(Squadron::getName, String.CASE_INSENSITIVE_ORDER))
              .orElseThrow();
      inventoryReconciler.onUserGainedFirstOrgUnit(user.getId(), primaryAdded);
    } else if (membershipsBefore > 0 && membershipRepository.countByIdUserId(user.getId()) == 0) {
      inventoryReconciler.onUserLostLastOrgUnit(user.getId());
    }
  }

  /**
   * Flips the {@code is_lead} flag on the membership row. ADMIN-only at the controller layer — a
   * member-managing Lead cannot promote themselves or someone else to Lead. Carries an
   * optimistic-lock version like {@link #patchFlags}.
   *
   * @param specialCommandId the SK whose membership to update; never {@code null}.
   * @param userId the user whose membership to update; never {@code null}.
   * @param request toggle payload; never {@code null}.
   * @return the persisted membership row.
   * @throws NotFoundException if no SK matches the given id, or the user is not a member.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembership toggleLead(
      @NotNull UUID specialCommandId,
      @NotNull UUID userId,
      @NotNull MembershipLeadToggleRequest request) {
    OrgUnitMembership m = loadMembership(specialCommandId, userId);
    assertVersionMatches(m, request.version());
    // REQ-ORG-017: an SK-Leiter holds no Staffel membership. Reject promoting a user who still
    // belongs to a Staffel with a clean 400 before the V165 DB trigger turns it into a 500.
    if (request.isLead() && userHoldsStaffelMembership(userId)) {
      throw new BadRequestException(
          "User belongs to a Staffel and cannot be made an SK lead — remove the Staffel membership"
              + " first (REQ-ORG-017)");
    }
    // Snapshot the SK account's derived responsible holder (its SK-Lead) before the toggle, to
    // audit
    // the change (REQ-BANK-034, ADR-0070).
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankAccessServiceProvider.getObject().snapshotResponsibleHolders(specialCommandId);
    // The unified rank is the sole source of truth (epic #800, REQ-ROLE-001); is_lead was dropped
    // in
    // the Phase 5 cleanup (V187). The request's isLead boolean is the API verb (promote/demote).
    m.setRole(request.isLead() ? MembershipRole.SK_LEAD : MembershipRole.MEMBER);
    // saveAndFlush (not save): flush before the DTO wrapper maps the response so the client gets
    // the bumped @Version, not the stale pre-flush one (REQ-FE-003).
    final OrgUnitMembership saved = membershipRepository.saveAndFlush(m);
    // Mirror the SK-Leiter seat onto the descriptive chart in the same transaction (REQ-ROLE-006).
    orgChartService.mirrorSkLead(specialCommandId, userId, request.isLead());
    auditService.record(
        request.isLead() ? AuditEventType.ROLE_GRANTED : AuditEventType.ROLE_REVOKED,
        specialCommandId,
        orgUnitLabelById(specialCommandId),
        userId,
        "role=SK_LEAD");
    orgUnitBankAccessServiceProvider.getObject().recordResponsibleHolderChanges(responsibleBefore);
    return saved;
  }

  /**
   * Assigns (or changes) a squadron leadership rank on an existing Staffel member (epic #800,
   * REQ-ROLE-003/004): Staffelleiter / Kommandoleiter / stellv. Kommandoleiter / Ensign, optionally
   * bound to a Kommandogruppe. The target user must already be a member of the Staffel; the rank
   * must be a squadron rank; the Kommandogruppe pairing and the cardinality caps (&le;1
   * Staffelleiter per squadron, &le;1 Kommandoleiter + &le;1 stellv. per group, &le;4 Ensigns per
   * squadron) are enforced here with clean 400s, complementing the V185 DB CHECK. Squadron ranks
   * are exempt from the V165 {@code enforce_leader_excludes_squadron} trigger (they <em>are</em>
   * Staffel members) — they set only the {@code role} and the {@code kommandoGroup}.
   *
   * @param squadronId the Staffel; must be a {@code SQUADRON} org unit.
   * @param userId the member to assign the rank to; must already be a member of this Staffel.
   * @param rank the squadron rank to set; must be a squadron rank.
   * @param kommandoGroupId the Kommandogruppe to bind, or {@code null}; constrained per the rank.
   * @param version the optimistic-lock version of the member's row, or {@code null} to skip it.
   * @return the persisted membership row with the bumped version.
   * @throws NotFoundException if the user is not a member of this Staffel, or the group is unknown.
   * @throws BadRequestException on a non-squadron rank, a bad group pairing, or a cardinality
   *     breach.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembership assignSquadronRank(
      @NotNull UUID squadronId,
      @NotNull UUID userId,
      @NotNull MembershipRole rank,
      @org.jetbrains.annotations.Nullable UUID kommandoGroupId,
      @org.jetbrains.annotations.Nullable Long version) {
    if (!rank.isSquadronRank()) {
      throw new BadRequestException("Rank " + rank + " is not a squadron rank");
    }
    OrgUnitMembership m =
        membershipRepository
            .findById(new OrgUnitMembershipId(userId, squadronId))
            .orElseThrow(() -> new NotFoundException("User is not a member of this Staffel"));
    if (m.getKind() != OrgUnitKind.SQUADRON) {
      throw new BadRequestException("Org unit " + squadronId + " is not a Staffel");
    }
    assertVersionMatches(m, version);

    KommandoGroup group = resolveKommandoGroupForRank(squadronId, rank, kommandoGroupId);
    assertSquadronRankCardinality(squadronId, userId, rank, group);

    final MembershipRole previousRole = m.getRole();
    // Snapshot the Staffel account's derived responsible holder (its Staffelleiter) before the rank
    // change, to audit a change of the account holder (REQ-BANK-034, ADR-0070). Only a
    // Staffelleiter assignment/clearing actually moves the set; the seam no-ops otherwise.
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankAccessServiceProvider.getObject().snapshotResponsibleHolders(squadronId);
    m.setRole(rank);
    m.setKommandoGroup(group);
    final OrgUnitMembership saved = membershipRepository.saveAndFlush(m);
    // Mirror the squadron seat onto the descriptive chart in the same transaction (REQ-ROLE-006):
    // Staffelleiter / Kommandoleiter (vacates+fills the group node) / stellv. / Ensign.
    orgChartService.mirrorSquadronRank(squadronId, userId, rank, group);

    final boolean firstGrant = previousRole == MembershipRole.MEMBER;
    auditService.record(
        firstGrant ? AuditEventType.ROLE_GRANTED : AuditEventType.ROLE_CHANGED,
        squadronId,
        orgUnitLabelById(squadronId),
        userId,
        firstGrant ? "role=" + rank : "from=" + previousRole + " to=" + rank);
    orgUnitBankAccessServiceProvider.getObject().recordResponsibleHolderChanges(responsibleBefore);
    return saved;
  }

  /**
   * Clears a member's squadron leadership rank back to plain {@link MembershipRole#MEMBER} (and
   * unbinds any Kommandogruppe) without removing the Staffel membership itself (epic #800,
   * REQ-ROLE-004).
   *
   * @param squadronId the Staffel; never {@code null}.
   * @param userId the member whose rank to clear; never {@code null}.
   * @param version the optimistic-lock version of the member's row, or {@code null} to skip it.
   * @return the persisted membership row with the bumped version.
   * @throws NotFoundException if the user is not a member of this Staffel.
   * @throws BadRequestException if the member holds no squadron rank.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembership removeSquadronRank(
      @NotNull UUID squadronId,
      @NotNull UUID userId,
      @org.jetbrains.annotations.Nullable Long version) {
    OrgUnitMembership m =
        membershipRepository
            .findById(new OrgUnitMembershipId(userId, squadronId))
            .orElseThrow(() -> new NotFoundException("User is not a member of this Staffel"));
    assertVersionMatches(m, version);
    final MembershipRole previousRole = m.getRole();
    if (!previousRole.isSquadronRank()) {
      throw new BadRequestException("Member holds no squadron rank to remove");
    }
    // Snapshot the Staffel account's derived responsible holder before clearing the rank, to audit
    // a
    // Staffelleiter (account holder) change (REQ-BANK-034, ADR-0070).
    final Map<UUID, Set<UUID>> responsibleBefore =
        orgUnitBankAccessServiceProvider.getObject().snapshotResponsibleHolders(squadronId);
    m.setRole(MembershipRole.MEMBER);
    m.setKommandoGroup(null);
    final OrgUnitMembership saved = membershipRepository.saveAndFlush(m);
    // Clear the mirrored squadron chart seat in the same transaction (REQ-ROLE-006).
    orgChartService.mirrorRemoveSquadronRank(squadronId, userId);
    auditService.record(
        AuditEventType.ROLE_REVOKED,
        squadronId,
        orgUnitLabelById(squadronId),
        userId,
        AuditDetails.of("role", previousRole));
    orgUnitBankAccessServiceProvider.getObject().recordResponsibleHolderChanges(responsibleBefore);
    return saved;
  }

  // -------------------------------------------------------------------------------------------
  // Controller-facing DTO projections (L4, #923, ADR-0067)
  //
  // These map the just-persisted membership to its OrgUnitMembershipDto response *inside* the
  // service transaction, so the membership controllers no longer need a class-level
  // @Transactional to keep the persistence session open for the lazy user.effectiveName read
  // the mapper performs. The entity-returning methods above stay authoritative for internal
  // callers (UserService reuses the managed addMember row to flip flags in-place) and for the
  // full-fidelity unit tests, because OrgUnitMembershipDto is a lossy projection (it carries
  // only isLead, not the full role) and could not verify an assigned squadron rank.
  //
  // Each write wrapper is @Transactional so the (self-invoked) write and the mapping share one
  // read-write transaction, and the underlying entity methods persist with saveAndFlush so the
  // mapped DTO carries the bumped @Version (REQ-FE-003). Two ArchitectureTest rules pin this
  // seam: mutatingServiceMethodsInReadOnlyClassesNeedExplicitTransactional (write wrappers must
  // escape the class readOnly default) and controllersMustNotInjectTheLazyMembershipMapper
  // (DTO projection stays in this service; no controller maps the entity itself).
  // -------------------------------------------------------------------------------------------

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
   * DTO projection of {@link #addMember(UUID, UUID)}: adds the user and returns the persisted
   * membership as its response DTO, mapped inside the write transaction.
   *
   * @param specialCommandId the SK to add the user to; never {@code null}.
   * @param userId the user to add; never {@code null}.
   * @return the persisted membership DTO with role flags defaulted to {@code false}.
   * @throws NotFoundException if no SK matches the given id, or no user matches the given id.
   * @throws DuplicateEntityException if the user is already a member of this SK.
   */
  @Transactional
  public OrgUnitMembershipDto addMemberDto(@NotNull UUID specialCommandId, @NotNull UUID userId) {
    return orgUnitMembershipMapper.toDto(addMember(specialCommandId, userId));
  }

  /**
   * DTO projection of {@link #patchFlags(UUID, UUID, MembershipFlagsPatchRequest)}: patches the SK
   * membership's role flags and returns the persisted membership as its response DTO, mapped inside
   * the write transaction.
   *
   * @param specialCommandId the SK whose membership to patch; never {@code null}.
   * @param userId the user whose membership to patch; never {@code null}.
   * @param request patch payload; never {@code null}.
   * @return the persisted membership DTO with the bumped {@code @Version}.
   * @throws NotFoundException if no SK matches the given id, or the user is not a member.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembershipDto patchFlagsDto(
      @NotNull UUID specialCommandId,
      @NotNull UUID userId,
      @NotNull MembershipFlagsPatchRequest request) {
    return orgUnitMembershipMapper.toDto(patchFlags(specialCommandId, userId, request));
  }

  /**
   * DTO projection of {@link #patchSquadronMemberFlags(UUID, UUID, MembershipFlagsPatchRequest)}:
   * patches the Squadron membership's role flags and returns the persisted membership as its
   * response DTO, mapped inside the write transaction.
   *
   * @param squadronId the Squadron whose membership to patch; never {@code null}.
   * @param userId the user whose membership to patch; never {@code null}.
   * @param request patch payload; never {@code null}.
   * @return the persisted membership DTO with the bumped {@code @Version}.
   * @throws NotFoundException if no Squadron matches the given id, or the user is not a member.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembershipDto patchSquadronMemberFlagsDto(
      @NotNull UUID squadronId,
      @NotNull UUID userId,
      @NotNull MembershipFlagsPatchRequest request) {
    return orgUnitMembershipMapper.toDto(patchSquadronMemberFlags(squadronId, userId, request));
  }

  /**
   * DTO projection of {@link #toggleLead(UUID, UUID, MembershipLeadToggleRequest)}: promotes /
   * demotes the SK member and returns the persisted membership as its response DTO, mapped inside
   * the write transaction.
   *
   * @param specialCommandId the SK whose membership to update; never {@code null}.
   * @param userId the user whose membership to update; never {@code null}.
   * @param request toggle payload; never {@code null}.
   * @return the persisted membership DTO with the bumped {@code @Version}.
   * @throws NotFoundException if no SK matches the given id, or the user is not a member.
   * @throws BadRequestException if the user still belongs to a Staffel (REQ-ORG-017).
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembershipDto toggleLeadDto(
      @NotNull UUID specialCommandId,
      @NotNull UUID userId,
      @NotNull MembershipLeadToggleRequest request) {
    return orgUnitMembershipMapper.toDto(toggleLead(specialCommandId, userId, request));
  }

  /**
   * DTO projection of {@link #assignSquadronRank(UUID, UUID, MembershipRole, UUID, Long)}: assigns
   * the squadron rank and returns the persisted membership as its response DTO, mapped inside the
   * write transaction.
   *
   * @param squadronId the Staffel; must be a {@code SQUADRON} org unit.
   * @param userId the member to assign the rank to; must already be a member of this Staffel.
   * @param rank the squadron rank to set; must be a squadron rank.
   * @param kommandoGroupId the Kommandogruppe to bind, or {@code null}; constrained per the rank.
   * @param version the optimistic-lock version of the member's row, or {@code null} to skip it.
   * @return the persisted membership DTO with the bumped version.
   * @throws NotFoundException if the user is not a member of this Staffel, or the group is unknown.
   * @throws BadRequestException on a non-squadron rank, a bad group pairing, or a cardinality
   *     breach.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembershipDto assignSquadronRankDto(
      @NotNull UUID squadronId,
      @NotNull UUID userId,
      @NotNull MembershipRole rank,
      @org.jetbrains.annotations.Nullable UUID kommandoGroupId,
      @org.jetbrains.annotations.Nullable Long version) {
    return orgUnitMembershipMapper.toDto(
        assignSquadronRank(squadronId, userId, rank, kommandoGroupId, version));
  }

  /**
   * DTO projection of {@link #removeSquadronRank(UUID, UUID, Long)}: clears the member's squadron
   * rank and returns the persisted membership as its response DTO, mapped inside the write
   * transaction.
   *
   * @param squadronId the Staffel; never {@code null}.
   * @param userId the member whose rank to clear; never {@code null}.
   * @param version the optimistic-lock version of the member's row, or {@code null} to skip it.
   * @return the persisted membership DTO with the bumped version.
   * @throws NotFoundException if the user is not a member of this Staffel.
   * @throws BadRequestException if the member holds no squadron rank.
   * @throws ObjectOptimisticLockingFailureException if the inbound version is stale.
   */
  @Transactional
  public OrgUnitMembershipDto removeSquadronRankDto(
      @NotNull UUID squadronId,
      @NotNull UUID userId,
      @org.jetbrains.annotations.Nullable Long version) {
    return orgUnitMembershipMapper.toDto(removeSquadronRank(squadronId, userId, version));
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
   * Resolves and validates the Kommandogruppe binding for a squadron rank against the V185 pairing
   * CHECK: Kommandoleiter / stellv. Kommandoleiter MUST reference a group; Ensign MAY;
   * Staffelleiter MUST NOT. A referenced group must exist and belong to the same squadron.
   *
   * @param squadronId the Staffel the rank is on; never {@code null}.
   * @param rank the squadron rank being assigned; never {@code null}.
   * @param kommandoGroupId the requested group id, or {@code null}.
   * @return the resolved group, or {@code null} when the rank carries no group.
   * @throws NotFoundException if a referenced group does not exist.
   * @throws BadRequestException on a group pairing that violates the rank's contract.
   */
  @org.jetbrains.annotations.Nullable
  private KommandoGroup resolveKommandoGroupForRank(
      @NotNull UUID squadronId,
      @NotNull MembershipRole rank,
      @org.jetbrains.annotations.Nullable UUID kommandoGroupId) {
    boolean groupRequired =
        rank == MembershipRole.KOMMANDOLEITER || rank == MembershipRole.STELLV_KOMMANDOLEITER;
    boolean groupAllowed = groupRequired || rank == MembershipRole.ENSIGN;
    if (kommandoGroupId == null) {
      if (groupRequired) {
        throw new BadRequestException(rank + " must be assigned to a Kommandogruppe");
      }
      return null;
    }
    if (!groupAllowed) {
      throw new BadRequestException(rank + " must not be assigned to a Kommandogruppe");
    }
    KommandoGroup group =
        kommandoGroupRepository
            .findById(kommandoGroupId)
            .orElseThrow(() -> new NotFoundException("Kommandogruppe not found"));
    if (!group.getSquadron().getId().equals(squadronId)) {
      throw new BadRequestException("Kommandogruppe does not belong to this Staffel");
    }
    return group;
  }

  /**
   * Enforces the squadron-rank cardinality caps against the current roster, excluding the target
   * user so re-assigning the same user is idempotent: &le;1 Staffelleiter per squadron, &le;1
   * Kommandoleiter + &le;1 stellv. Kommandoleiter per group, &le;4 Ensigns per squadron.
   *
   * <p>This in-memory check gives a clean 4xx for the common case; the three singleton caps are
   * additionally backstopped by the {@code V188} partial unique indexes ({@code
   * uq_org_unit_membership_one_staffelleiter} / {@code _one_kommandoleiter_per_group} / {@code
   * _one_stellv_per_group}), so a concurrent double-assign that slips past the roster scan fails on
   * the constraint rather than committing a duplicate. The &le;4 Ensign cap stays
   * service-layer-only (a count, not a uniqueness rule), like the org chart's own &le;4 ENSIGN cap.
   *
   * @param squadronId the Staffel; never {@code null}.
   * @param userId the user being (re)assigned, excluded from the roster scan; never {@code null}.
   * @param rank the squadron rank being assigned; never {@code null}.
   * @param group the resolved Kommandogruppe (non-null for Kommandoleiter / stellv.); may be {@code
   *     null}.
   * @throws BadRequestException when the assignment would breach a cardinality cap.
   */
  private void assertSquadronRankCardinality(
      @NotNull UUID squadronId,
      @NotNull UUID userId,
      @NotNull MembershipRole rank,
      @org.jetbrains.annotations.Nullable KommandoGroup group) {
    List<OrgUnitMembership> roster =
        membershipRepository.findAllByIdOrgUnitId(squadronId).stream()
            .filter(m -> !m.getId().getUserId().equals(userId))
            .toList();
    switch (rank) {
      case STAFFELLEITER -> {
        if (roster.stream().anyMatch(m -> m.getRole() == MembershipRole.STAFFELLEITER)) {
          throw new BadRequestException("This Staffel already has a Staffelleiter");
        }
      }
      case KOMMANDOLEITER, STELLV_KOMMANDOLEITER -> {
        UUID groupId = group.getId();
        boolean taken =
            roster.stream()
                .anyMatch(
                    m ->
                        m.getRole() == rank
                            && m.getKommandoGroup() != null
                            && groupId.equals(m.getKommandoGroup().getId()));
        if (taken) {
          throw new BadRequestException("This Kommandogruppe already has a " + rank);
        }
      }
      case ENSIGN -> {
        long ensigns = roster.stream().filter(m -> m.getRole() == MembershipRole.ENSIGN).count();
        if (ensigns >= 4) {
          throw new BadRequestException("This Staffel already has the maximum of 4 Ensigns");
        }
      }
      default -> throw new BadRequestException("Rank " + rank + " is not a squadron rank");
    }
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
    List<OrgUnitMembership> sorted = new ArrayList<>(rows);
    sorted.sort(
        Comparator.<OrgUnitMembership, Integer>comparing(
                m -> m.getKind() == OrgUnitKind.SQUADRON ? 0 : 1)
            .thenComparing(
                m -> {
                  UUID orgUnitId = m.getId().getOrgUnitId();
                  return m.getKind() == OrgUnitKind.SQUADRON
                      ? squadronRepository.findById(orgUnitId).map(Squadron::getName).orElse("")
                      : specialCommandRepository
                          .findById(orgUnitId)
                          .map(SpecialCommand::getName)
                          .orElse("");
                },
                String.CASE_INSENSITIVE_ORDER));
    return sorted;
  }

  /**
   * Loads the membership row for the given (SK, user) pair, validating SK existence first so a
   * stale SK id surfaces as 404 with a clear message before the membership lookup fires.
   *
   * @param specialCommandId the SK id.
   * @param userId the user id.
   * @return the membership row.
   * @throws NotFoundException if no SK matches the given id, or the user is not a member.
   */
  private OrgUnitMembership loadMembership(UUID specialCommandId, UUID userId) {
    SpecialCommand sc = specialCommandService.getSpecialCommandById(specialCommandId);
    return membershipRepository
        .findById(new OrgUnitMembershipId(userId, sc.getId()))
        .orElseThrow(() -> new NotFoundException("Membership not found"));
  }

  /**
   * Throws {@link ObjectOptimisticLockingFailureException} if the inbound client-held version does
   * not match the persisted row's {@code @Version}. Mirrors the pattern used in {@code
   * SpecialCommandService.updateSpecialCommand} so the optimistic-lock surface is uniform across
   * the SK administration endpoints.
   */
  private void assertVersionMatches(OrgUnitMembership m, Long version) {
    OptimisticLock.check(m.getVersion(), version, OrgUnitMembership.class, null);
  }

  /**
   * {@code true} iff the user holds at least one Staffel ({@code SQUADRON}) membership. Backs the
   * REQ-ORG-017 guard that an SK-Leiter must belong to no Staffel.
   *
   * @param userId the user to check; never {@code null}.
   * @return {@code true} when the user has a Staffel membership row.
   */
  private boolean userHoldsStaffelMembership(@NotNull UUID userId) {
    return !membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON).isEmpty();
  }

  /**
   * {@code true} iff the user holds a <em>silo-leader</em> rank on any membership — an SK-Leiter
   * ({@link MembershipRole#SK_LEAD}), a Bereichsleitung rank, or the OL ({@link
   * MembershipRole#isAreaOrOl()}). The four squadron ranks are deliberately <b>exempt</b>: they are
   * held by Staffel members, so they must not trip this guard. Backs the REQ-ORG-017 guard that a
   * silo leader is never (also) assigned to a Staffel.
   *
   * @param userId the user to check; never {@code null}.
   * @return {@code true} when any of the user's membership rows carries a silo-leader rank.
   */
  private boolean userHoldsLeadershipRole(@NotNull UUID userId) {
    return membershipRepository.findAllByIdUserId(userId).stream()
        .anyMatch(m -> m.getRole() == MembershipRole.SK_LEAD || m.getRole().isAreaOrOl());
  }

  /**
   * Records a {@link AuditEventType#MEMBERSHIP_REVOKED} event for a removed Staffel membership
   * (epic #800, REQ-AUDIT-001). Extracted so the {@link #reconcileStaffelMemberships} delete
   * branches stay readable; the details payload carries only the org-unit kind (no PII).
   *
   * @param squadronOrgUnitId the Staffel the removed membership pointed at; never {@code null}.
   * @param userId the user whose Staffel membership was removed; never {@code null}.
   */
  private void recordStaffelMembershipRevoked(
      @NotNull UUID squadronOrgUnitId, @NotNull UUID userId) {
    auditService.record(
        AuditEventType.MEMBERSHIP_REVOKED,
        squadronOrgUnitId,
        orgUnitLabelById(squadronOrgUnitId),
        userId,
        "kind=SQUADRON");
  }

  /**
   * Records a {@link AuditEventType#CAPABILITY_FLAGS_CHANGED} event capturing the resulting
   * Logistician / Mission-Manager flag values on a membership (epic #800, REQ-AUDIT-001). The
   * details payload holds only the two boolean values (no PII / no free text).
   *
   * @param orgUnitId the org unit the membership belongs to; never {@code null}.
   * @param userId the affected user; never {@code null}.
   * @param saved the persisted membership row whose flags were changed; never {@code null}.
   */
  private void recordCapabilityFlagsChanged(
      @NotNull UUID orgUnitId, @NotNull UUID userId, @NotNull OrgUnitMembership saved) {
    auditService.record(
        AuditEventType.CAPABILITY_FLAGS_CHANGED,
        orgUnitId,
        orgUnitLabelById(orgUnitId),
        userId,
        AuditDetails.of("logistician", saved.isLogistician())
            .with("missionManager", saved.isMissionManager()));
  }

  /**
   * Loads an org unit by id and resolves its audit {@code subjectLabel} via {@link
   * #orgUnitLabel(OrgUnit)}, or {@code null} when the org unit cannot be resolved (e.g. already
   * deleted). Used by the delete / patch audit paths that hold only the org-unit id.
   *
   * @param orgUnitId the org unit id; never {@code null}.
   * @return the org unit's shorthand/name label, or {@code null}.
   */
  private @org.jetbrains.annotations.Nullable String orgUnitLabelById(@NotNull UUID orgUnitId) {
    return orgUnitRepository
        .findById(orgUnitId)
        .map(OrgUnitMembershipService::orgUnitLabel)
        .orElse(null);
  }

  /**
   * Resolves a compact, non-personal audit label for an org unit: its shorthand, falling back to
   * its name. Used as the {@code subjectLabel} on role / membership audit events.
   *
   * @param unit the org unit, or {@code null}.
   * @return the shorthand if set, otherwise the name, otherwise {@code null}.
   */
  private static @org.jetbrains.annotations.Nullable String orgUnitLabel(
      @org.jetbrains.annotations.Nullable OrgUnit unit) {
    if (unit == null) {
      return null;
    }
    String shorthand = unit.getShorthand();
    return shorthand != null && !shorthand.isBlank() ? shorthand : unit.getName();
  }
}
