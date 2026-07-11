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
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.OrgChartPositionMapper;
import de.greluc.krt.profit.basetool.backend.model.KommandoGroup;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgChartPosition;
import de.greluc.krt.profit.basetool.backend.model.OrgChartPositionType;
import de.greluc.krt.profit.basetool.backend.model.OrgChartScope;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BereichLeadershipRole;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartPositionCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartPositionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartPositionUpdateRequest;
import de.greluc.krt.profit.basetool.backend.repository.OrgChartPositionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles and mutates the Profit-Bereich org chart ({@link OrgChartPosition} aggregate). The
 * chart is purely descriptive — placing a user in a position grants no permission — so this service
 * is NOT org-unit-scoped: it deliberately does not inject {@code OwnerScopeService} / {@code
 * AuthHelperService} and must stay off the {@code
 * staffelScopedServicesMustWireOwnerScopeOrAuthHelper} ArchUnit whitelist. Read access is open to
 * every authenticated user; write access is gated to ADMIN at the controller.
 *
 * <p>The unit tier is every active Staffel + SK (loaded via {@link
 * OrgUnitRepository#findActiveSquadronsAndSpecialCommands()}), <strong>regardless of {@code
 * is_profit_eligible}</strong> — that flag governs Job-Order processing only, not chart visibility
 * (ADR-0029, REQ-ORG-018), so a unit wired under any Bereich renders there, not just the
 * Profit-side ones. Every such unit is rendered even when empty so an admin can fill it in.
 *
 * <p>All structural invariants the database cannot express in plain SQL — the per-Staffel limits
 * (≤{@value #MAX_COMMAND_LEADS} Kommandos, ≤{@value #MAX_ENSIGNS} Ensign), the per-SK limit
 * (≤{@value #MAX_SK_COMMANDERS} SK-Leiter), the parent/scope consistency rules, the "name only on a
 * Kommando" rule, the "holder is an account OR a free-text name, never both" rule, and "a user
 * appears at most once per scope" — are enforced here and surface as 400 problem responses. The
 * singleton rules (one Bereichsleiter, one Staffelleiter per Staffel, one Stv. per Kommando, one
 * position per user and scope) are additionally backstopped by partial unique indexes in migrations
 * {@code V136}/{@code V138}, so a concurrent double-create there fails cleanly as a 409. The count
 * caps (≤4/≤2) are service-layer only: two interleaved creates could momentarily exceed a cap by
 * one — harmless for a descriptive, ADMIN-only chart where an admin simply removes the surplus.
 *
 * <p>A {@link OrgChartPositionType#COMMAND_LEAD} row models the Kommando(gruppe) itself, carrying
 * an optional {@code name} and an optional holder (the Kommandoleiter). This lets an admin create
 * and name a Kommando, hang a Stv. Kommandoleiter and Ensigns off it, and only later assign its
 * Kommandoleiter — so a subordinate seat is fillable while its superior is still vacant.
 *
 * <p>Any position's holder may be a Basetool account or — for a Kartell member who has no account
 * yet — a free-text {@code displayName} (the two are mutually exclusive). Reassigning a free-text
 * position to an account clears the typed name in the same transaction, so the swap is
 * regression-free. A free-text holder grants nothing, as the chart is descriptive only.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgChartService {

  /** Maximum number of Kommandos (COMMAND_LEAD rows) per Staffel. */
  static final int MAX_COMMAND_LEADS = 4;

  /**
   * Maximum number of Ensign positions per Staffel (directly-attached plus all under Kommandos).
   */
  static final int MAX_ENSIGNS = 4;

  /** Maximum number of SK-Leiter (SK_COMMANDER) positions per Spezialkommando. */
  static final int MAX_SK_COMMANDERS = 2;

  private static final String ERR_SCOPE_MISMATCH = "problem.org_chart.scope_mismatch";
  private static final String ERR_UNIT_INACTIVE = "problem.org_chart.unit_inactive";
  private static final String ERR_INVALID_PARENT = "problem.org_chart.invalid_parent";
  private static final String ERR_COMMAND_LIMIT = "problem.org_chart.command_limit";
  private static final String ERR_ENSIGN_LIMIT = "problem.org_chart.ensign_limit";
  private static final String ERR_COMMANDER_LIMIT = "problem.org_chart.commander_limit";
  private static final String ERR_DUPLICATE_LEAD = "problem.org_chart.duplicate_lead";
  private static final String ERR_DUPLICATE_DEPUTY = "problem.org_chart.duplicate_deputy";
  private static final String ERR_USER_ASSIGNED = "problem.org_chart.user_already_assigned";
  private static final String ERR_USER_REQUIRED = "problem.org_chart.user_required";
  private static final String ERR_HOLDER_AMBIGUOUS = "problem.org_chart.holder_ambiguous";
  private static final String ERR_NAME_NOT_ALLOWED = "problem.org_chart.name_not_allowed";
  private static final String ERR_VACATE_NOT_COMMAND = "problem.org_chart.vacate_not_command";
  private static final String ERR_ACCOUNT_MANAGED = "problem.org_chart.account_managed_in_leitung";

  private final OrgChartPositionRepository positionRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final UserRepository userRepository;
  private final OrgChartPositionMapper mapper;

  /**
   * Creates a new position, validating scope/type consistency, parent rules, cardinality limits,
   * the name/holder rules and the one-user-per-scope rule before persisting. ADMIN-only at the
   * controller. The holder is either an account ({@code userId}) or a free-text {@code displayName}
   * for a member without one — supplying both is rejected. A {@code COMMAND_LEAD} create may omit
   * both to make a still-leaderless Kommando and may carry a Kommandogruppen-{@code name}; every
   * other rank requires a holder (account or free-text) and rejects a {@code name}.
   *
   * @param request the assignment payload; never {@code null}.
   * @return the persisted position as a flat DTO with id + version populated.
   * @throws NotFoundException if the user, the OrgUnit, or the referenced parent does not exist.
   * @throws BadRequestException if any scope/parent/cardinality/name/uniqueness rule is violated,
   *     if both an account and a free-text name are supplied, or if neither is supplied for a
   *     non-{@code COMMAND_LEAD} rank.
   */
  @Transactional
  public OrgChartPositionDto createPosition(@NotNull OrgChartPositionCreateRequest request) {
    OrgChartPositionType type = request.positionType();
    OrgChartScope scope = type.scope();
    final String displayName = trimToNull(request.displayName());
    final User user = resolveHolderForCreate(type, request.userId(), displayName);

    OrgUnit orgUnit = resolveScopeOrgUnit(scope, request.orgUnitId());
    final OrgChartPosition parent = resolveAndValidateParent(type, orgUnit, request.parentId());
    // A kommando_group-linked Kommando mirrors a functional rank: its whole subtree (Stv. /
    // Ensigns)
    // is managed under Organisation -> Leitung (epic #800, REQ-ROLE-006), so the chart editor may
    // not
    // bolt children onto it — the seat is read-only here, mirror-writes go through OrgChartService.
    if (parent != null && parent.getKommandoGroup() != null) {
      throw new BadRequestException(ERR_ACCOUNT_MANAGED);
    }
    validateCardinality(type, orgUnit);
    final String name = validateAndNormalizeName(type, request.name());
    // Account-linked seats are a mirror of the functional ranks (epic #800, REQ-ROLE-006): the
    // chart
    // editor may only place a free-text holder or leave a Kommando leaderless — an account is
    // appointed under Organisation -> Leitung and projected here by OrgChartService.mirror*. The
    // create is still fully validated above (scope / parent / cardinality / name) so a free-text
    // create hits the same guards; an account-holder create is always refused. We run
    // validateUserUnique first even though the account is rejected so a duplicate-in-scope account
    // still surfaces its own specific error before the generic refusal — preserving the error
    // precedence the pre-demotion contract (and createPosition_userAlreadyInScope_isRejected)
    // expects.
    if (user != null) {
      validateUserUnique(scope, orgUnit, request.userId());
      throw new BadRequestException(ERR_ACCOUNT_MANAGED);
    }

    OrgChartPosition position = new OrgChartPosition();
    position.setPositionType(type);
    position.setOrgUnit(orgUnit);
    position.setUser(user);
    // Mutually exclusive with the account: resolveHolderForCreate rejects supplying both, so
    // displayName is null whenever an account was resolved.
    position.setDisplayName(displayName);
    position.setName(name);
    position.setParent(parent);
    position.setSortIndex(request.sortIndex() != null ? request.sortIndex() : 0);
    return mapper.toDto(positionRepository.save(position));
  }

  /**
   * Reassigns the holder, renames a Kommando and/or reorders an existing position. The functional
   * rank, scope and parent are immutable after creation (move = remove + re-add), so only the
   * holder ({@code userId} or {@code displayName}), {@code name} (Kommando only) and {@code
   * sortIndex} are honoured; a {@code null} field leaves the current value unchanged, while a blank
   * {@code name} clears it.
   *
   * <p>The holder swap is the heart of the free-text feature and runs on the already-managed entity
   * with a single {@code save()} (no second fetch, no bulk clear), so there is no optimistic-lock
   * second-bump: supplying a {@code userId} sets the account <em>and</em> clears any free-text
   * {@code displayName} on the same row in the same transaction — the regression-free "the member
   * now has an account" path. Supplying a non-blank {@code displayName} (without a {@code userId})
   * sets the typed name and clears the account holder; supplying <em>both</em> in one call is
   * rejected as ambiguous, mirroring create. Assigning a holder to a still-leaderless Kommando is
   * just a reassign through {@code userId} or {@code displayName}.
   *
   * @param id the position id; never {@code null}.
   * @param request the edit payload carrying the current optimistic-lock version; never {@code
   *     null}.
   * @return the updated position as a flat DTO with the bumped version.
   * @throws NotFoundException if the position or the new user does not exist.
   * @throws BadRequestException if the new holder already occupies a position in the same scope, a
   *     name is supplied for a non-Kommando rank, both an account and a free-text name are supplied
   *     at once, or clearing the typed name would leave a non-Kommando rank with no holder at all.
   * @throws ObjectOptimisticLockingFailureException if the supplied version is stale.
   */
  @Transactional
  public OrgChartPositionDto updatePosition(
      @NotNull UUID id, @NotNull OrgChartPositionUpdateRequest request) {
    OrgChartPosition position =
        positionRepository
            .findById(id)
            .orElseThrow(() -> new NotFoundException("OrgChartPosition not found: " + id));
    OptimisticLock.check(position.getVersion(), request.version(), OrgChartPosition.class, id);
    // The chart editor may not assign an account, nor touch a seat the rank mirror manages — an
    // account-held or kommando_group-linked position reflects the functional ranks and is edited
    // under Organisation -> Leitung (epic #800, REQ-ROLE-006). Free-text holders and leaderless /
    // legacy Kommandos stay editable here.
    if (request.userId() != null || isMirrorManaged(position)) {
      throw new BadRequestException(ERR_ACCOUNT_MANAGED);
    }
    // Symmetric with createPosition: a position is held by an account OR a free-text name, never
    // both, so a single update may not set both at once. A bare userId still clears any existing
    // free-text name below (the regression-free swap); only supplying both together is ambiguous.
    if (request.userId() != null && trimToNull(request.displayName()) != null) {
      throw new BadRequestException(ERR_HOLDER_AMBIGUOUS);
    }
    if (request.name() != null) {
      if (position.getPositionType() != OrgChartPositionType.COMMAND_LEAD) {
        throw new BadRequestException(ERR_NAME_NOT_ALLOWED);
      }
      position.setName(trimToNull(request.name()));
    }
    if (request.userId() != null) {
      // Account holder: assign (if changed) and clear any free-text name in the same transaction.
      User current = position.getUser();
      if (current == null || !request.userId().equals(current.getId())) {
        User newUser =
            userRepository
                .findById(request.userId())
                .orElseThrow(() -> new NotFoundException("User not found: " + request.userId()));
        validateUserUnique(
            position.getPositionType().scope(), position.getOrgUnit(), request.userId());
        position.setUser(newUser);
      }
      position.setDisplayName(null);
    } else if (request.displayName() != null) {
      // Free-text holder: a non-blank typed name replaces the account holder; a blank value clears
      // it, which is only allowed where a holder is optional (a COMMAND_LEAD Kommando).
      String typed = trimToNull(request.displayName());
      if (typed == null
          && position.getUser() == null
          && position.getPositionType() != OrgChartPositionType.COMMAND_LEAD) {
        throw new BadRequestException(ERR_USER_REQUIRED);
      }
      position.setDisplayName(typed);
      if (typed != null) {
        position.setUser(null);
      }
    }
    if (request.sortIndex() != null) {
      position.setSortIndex(request.sortIndex());
    }
    return mapper.toDto(positionRepository.save(position));
  }

  /**
   * Vacates the Kommandoleiter seat of a Kommando(gruppe): clears the holder (both an account and
   * any free-text leader name) on a {@code COMMAND_LEAD} row while leaving the row itself — its
   * name, its Stv. Kommandoleiter and its Ensigns — intact. This is the inverse of assigning a
   * leader through {@link #updatePosition} and the reason a Kommando outlives a departing
   * Kommandoleiter instead of having to be deleted and rebuilt. Only a {@code COMMAND_LEAD} may be
   * left with no holder at all (the {@code chk_org_chart_user} CHECK keeps every other rank filled
   * by an account or a free-text name), so every other rank is rejected as a 400: removing such a
   * person-centric position is {@link #deletePosition} instead. ADMIN-only at the controller.
   *
   * @param id the Kommando position id; never {@code null}.
   * @param version the optimistic-lock version the client last saw; a mismatch surfaces as 409.
   * @return the updated, now-leaderless Kommando as a flat DTO with the bumped version.
   * @throws NotFoundException if no position matches the id.
   * @throws BadRequestException if the position is not a {@code COMMAND_LEAD} Kommando.
   * @throws ObjectOptimisticLockingFailureException if the supplied version is stale.
   */
  @Transactional
  public OrgChartPositionDto vacateCommandLeader(@NotNull UUID id, long version) {
    OrgChartPosition position =
        positionRepository
            .findById(id)
            .orElseThrow(() -> new NotFoundException("OrgChartPosition not found: " + id));
    if (position.getPositionType() != OrgChartPositionType.COMMAND_LEAD) {
      throw new BadRequestException(ERR_VACATE_NOT_COMMAND);
    }
    OptimisticLock.check(position.getVersion(), version, OrgChartPosition.class, id);
    // A kommando_group-linked Kommando mirrors a functional rank — its Kommandoleiter is vacated by
    // removing the rank under Organisation -> Leitung (epic #800, REQ-ROLE-006), not here.
    if (position.getKommandoGroup() != null) {
      throw new BadRequestException(ERR_ACCOUNT_MANAGED);
    }
    // A vacated Kommando is fully empty: drop both an account and any free-text leader name.
    position.setUser(null);
    position.setDisplayName(null);
    return mapper.toDto(positionRepository.save(position));
  }

  /**
   * Removes a position. Removing a Kommando (COMMAND_LEAD) cascades to its Stv. Kommandoleiter and
   * the Ensigns reporting into it (the {@code parent_id} FK is {@code ON DELETE CASCADE}); the
   * inline editor warns the admin of the affected children before calling this. ADMIN-only at the
   * controller.
   *
   * @param id the position id; never {@code null}.
   * @throws NotFoundException if no position matches the id.
   */
  @Transactional
  public void deletePosition(@NotNull UUID id) {
    OrgChartPosition position =
        positionRepository
            .findById(id)
            .orElseThrow(() -> new NotFoundException("OrgChartPosition not found: " + id));
    // A mirror-managed seat (account-held, or a kommando_group-linked Kommando) reflects a
    // functional rank — it is removed by clearing the rank / deleting the Kommandogruppe under
    // Organisation -> Leitung (epic #800, REQ-ROLE-006), not from the chart. Free-text holders and
    // leaderless / legacy positions stay removable here.
    if (isMirrorManaged(position)) {
      throw new BadRequestException(ERR_ACCOUNT_MANAGED);
    }
    positionRepository.delete(position);
  }

  /**
   * Whether the position is managed by the rank mirror (epic #800, REQ-ROLE-006) and therefore
   * read-only in the chart editor: an account-held seat (its holder is a Basetool account,
   * projected from a functional rank) or a kommando_group-linked Kommando node. Free-text holders
   * and leaderless / legacy positions (no account, no group link) are not mirror-managed.
   *
   * @param position the position to classify; never {@code null}.
   * @return {@code true} iff the chart editor must not mutate the position.
   */
  private static boolean isMirrorManaged(@NotNull OrgChartPosition position) {
    return position.getUser() != null || position.getKommandoGroup() != null;
  }

  // ------------------------------------------------- role-model mirror (REQ-ROLE-006) --
  // The functional rank on org_unit_membership is the source of truth; these methods keep the
  // descriptive account-linked chart seats in lockstep with it (epic #800, REQ-ROLE-006). They run
  // with MANDATORY propagation: the appointment that triggered them (OrgUnitMembershipService /
  // KommandoGroupService) has already opened a read-write transaction, opened the delegated-authz
  // gate and persisted the membership row, so the mirror joins that very transaction (same-tx
  // requirement) and never starts one of its own — calling a mirror method outside a transaction is
  // a programming error and fails fast. The mirror only ever writes the chart; the authority
  // cascade
  // still never reads it (the chart grants nothing), so the ArchUnit chart invariants stay green.

  /**
   * Mirrors a Bereich leadership appointment onto the chart: ensures the appointee holds exactly
   * the matching descriptive seat ({@code BEREICHSLEITER} / {@code BEREICHSKOORDINATOR} / {@code
   * BEREICHSOPERATOR}) in the Bereich, replacing whatever Bereich seat they held before. The
   * single-Bereichsleiter chart slot is reassigned rather than duplicated so the {@code
   * uq_org_chart_one_bereichsleiter_per_bereich} index is never tripped.
   *
   * @param bereichId the Bereich the appointment is on; never {@code null}.
   * @param userId the appointed account; never {@code null}.
   * @param role the Bereich leadership role granted; never {@code null}.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void mirrorBereichRole(
      @NotNull UUID bereichId, @NotNull UUID userId, @NotNull BereichLeadershipRole role) {
    clearUnitSeatsForUser(bereichId, userId);
    OrgChartPositionType type =
        switch (role) {
          case LEITER -> OrgChartPositionType.BEREICHSLEITER;
          case KOORDINATOR -> OrgChartPositionType.BEREICHSKOORDINATOR;
          case OPERATOR -> OrgChartPositionType.BEREICHSOPERATOR;
        };
    // The single Bereichsleiter slot is reassigned (so the partial unique index holds); the
    // unbounded Koordinator / Operator ranks just append a fresh seat.
    if (type == OrgChartPositionType.BEREICHSLEITER) {
      upsertSingletonSeat(bereichId, type, userId);
    } else {
      createUserSeat(bereichId, type, userId, null);
    }
  }

  /**
   * Mirrors an Organisationsleitung membership onto the chart: ensures the member holds an {@code
   * OL_MEMBER} seat on the OL. Idempotent — a member who already has the seat keeps it.
   *
   * @param organisationsleitungId the OL the membership is on; never {@code null}.
   * @param userId the OL member's account; never {@code null}.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void mirrorOlMember(@NotNull UUID organisationsleitungId, @NotNull UUID userId) {
    if (positionRepository.findByOrgUnitIdAndUserId(organisationsleitungId, userId).isEmpty()) {
      createUserSeat(organisationsleitungId, OrgChartPositionType.OL_MEMBER, userId, null);
    }
  }

  /**
   * Mirrors an SK-Leiter toggle onto the chart: creates an {@code SK_COMMANDER} seat for the user
   * on the SK when the lead flag is set, or removes their SK seat when it is cleared.
   *
   * @param specialCommandId the Spezialkommando the toggle is on; never {@code null}.
   * @param userId the toggled account; never {@code null}.
   * @param isLead the new lead state.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void mirrorSkLead(@NotNull UUID specialCommandId, @NotNull UUID userId, boolean isLead) {
    if (isLead) {
      if (positionRepository.findByOrgUnitIdAndUserId(specialCommandId, userId).isEmpty()) {
        createUserSeat(specialCommandId, OrgChartPositionType.SK_COMMANDER, userId, null);
      }
    } else {
      clearUnitSeatsForUser(specialCommandId, userId);
    }
  }

  /**
   * Mirrors a squadron-rank assignment onto the chart, reconciling the appointee's single squadron
   * seat to match the freshly-assigned rank (epic #800, REQ-ROLE-006):
   *
   * <ul>
   *   <li>{@code STAFFELLEITER} → the squadron's single {@code SQUADRON_LEAD} seat (reassigned, not
   *       duplicated);
   *   <li>{@code KOMMANDOLEITER} → the holder of the group's {@code COMMAND_LEAD} Kommando node;
   *   <li>{@code STELLV_KOMMANDOLEITER} → the {@code DEPUTY_COMMAND_LEAD} hanging off that node;
   *   <li>{@code ENSIGN} → an {@code ENSIGN} under that node, or a Staffelleiter-direct Ensign when
   *       the rank carries no group.
   * </ul>
   *
   * <p>Any prior squadron seat of the appointee is first cleared: a led Kommando is
   * <em>vacated</em> (the node survives, REQ-ORG-011), every other prior seat is removed, so the
   * one-user-per-unit chart invariant holds.
   *
   * @param squadronId the Staffel the rank is on; never {@code null}.
   * @param userId the appointed member's account; never {@code null}.
   * @param rank the squadron rank assigned; must be a squadron rank.
   * @param group the bound Kommandogruppe, or {@code null} for a Staffelleiter / general Ensign.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void mirrorSquadronRank(
      @NotNull UUID squadronId,
      @NotNull UUID userId,
      @NotNull MembershipRole rank,
      KommandoGroup group) {
    clearSquadronSeatForUser(squadronId, userId);
    switch (rank) {
      case STAFFELLEITER ->
          upsertSingletonSeat(squadronId, OrgChartPositionType.SQUADRON_LEAD, userId);
      case KOMMANDOLEITER -> {
        OrgChartPosition command = commandLeadForGroup(squadronId, group);
        command.setUser(userReference(userId));
        command.setDisplayName(null);
      }
      case STELLV_KOMMANDOLEITER ->
          upsertDeputySeat(squadronId, commandLeadForGroup(squadronId, group), userId);
      case ENSIGN ->
          createEnsignSeat(
              squadronId, group == null ? null : commandLeadForGroup(squadronId, group), userId);
      default -> throw new IllegalArgumentException("Not a squadron rank: " + rank);
    }
  }

  /**
   * Mirrors the clearing of a member's squadron rank: removes the appointee's squadron chart seat
   * (a led Kommando is vacated, every other seat removed), back to a plain member with no chart
   * seat.
   *
   * @param squadronId the Staffel; never {@code null}.
   * @param userId the member whose squadron seat to clear; never {@code null}.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void mirrorRemoveSquadronRank(@NotNull UUID squadronId, @NotNull UUID userId) {
    clearSquadronSeatForUser(squadronId, userId);
  }

  /**
   * Mirrors the removal of a member's leadership seat in a flat-scoped unit (Bereich / OL / SK):
   * deletes any chart position the user holds in that unit. Used when a membership row is dropped
   * (revoke, member removal, Staffel switch off a leadership unit) so no stale seat lingers.
   *
   * @param orgUnitId the org unit the membership pointed at; never {@code null}.
   * @param userId the user whose seat to remove; never {@code null}.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void mirrorRemoveUnitSeat(@NotNull UUID orgUnitId, @NotNull UUID userId) {
    clearUnitSeatsForUser(orgUnitId, userId);
  }

  /**
   * Mirrors the creation of a Kommandogruppe: adds a still-leaderless {@code COMMAND_LEAD} Kommando
   * node tied to the group, ready for a Kommandoleiter / stellv. / Ensigns to be hung off it.
   *
   * @param group the freshly-created Kommandogruppe; never {@code null}.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void mirrorCreateKommandoGroup(@NotNull KommandoGroup group) {
    OrgChartPosition command = new OrgChartPosition();
    command.setPositionType(OrgChartPositionType.COMMAND_LEAD);
    command.setOrgUnit(group.getSquadron());
    command.setKommandoGroup(group);
    command.setName(group.getName());
    command.setSortIndex(group.getSortIndex());
    positionRepository.save(command);
  }

  /**
   * Mirrors a Kommandogruppe rename / reorder onto its {@code COMMAND_LEAD} node (name + sort
   * index). A no-op when no mirror node exists yet (a legacy group never assigned a leader).
   *
   * @param group the updated Kommandogruppe; never {@code null}.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void mirrorUpdateKommandoGroup(@NotNull KommandoGroup group) {
    positionRepository
        .findByKommandoGroupId(group.getId())
        .ifPresent(
            command -> {
              command.setName(group.getName());
              command.setSortIndex(group.getSortIndex());
            });
  }

  /**
   * Mirrors a Kommandogruppe deletion: removes its {@code COMMAND_LEAD} node (and, via the {@code
   * parent_id} cascade, any stellv. / Ensigns under it — though the group delete already requires
   * an empty group). A no-op when no mirror node exists.
   *
   * @param kommandoGroupId the deleted group's id; never {@code null}.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void mirrorDeleteKommandoGroup(@NotNull UUID kommandoGroupId) {
    positionRepository.findByKommandoGroupId(kommandoGroupId).ifPresent(positionRepository::delete);
  }

  /**
   * Removes every chart seat the user holds in a flat-scoped unit (Bereich / OL / SK). The {@code
   * uq_org_chart_user_per_unit} index caps this at one, but the loop is robust to a legacy
   * duplicate.
   *
   * @param orgUnitId the org unit; never {@code null}.
   * @param userId the user; never {@code null}.
   */
  private void clearUnitSeatsForUser(@NotNull UUID orgUnitId, @NotNull UUID userId) {
    positionRepository
        .findByOrgUnitIdAndUserId(orgUnitId, userId)
        .forEach(positionRepository::delete);
  }

  /**
   * Clears the appointee's single squadron chart seat: a led Kommando ({@code COMMAND_LEAD}) is
   * vacated so the Kommando survives (REQ-ORG-011), while a {@code SQUADRON_LEAD} / {@code
   * DEPUTY_COMMAND_LEAD} / {@code ENSIGN} seat is deleted outright.
   *
   * @param squadronId the Staffel; never {@code null}.
   * @param userId the member; never {@code null}.
   */
  private void clearSquadronSeatForUser(@NotNull UUID squadronId, @NotNull UUID userId) {
    for (OrgChartPosition seat : positionRepository.findByOrgUnitIdAndUserId(squadronId, userId)) {
      if (seat.getPositionType() == OrgChartPositionType.COMMAND_LEAD) {
        seat.setUser(null);
        seat.setDisplayName(null);
      } else {
        positionRepository.delete(seat);
      }
    }
  }

  /**
   * Reassigns the single existing seat of the given type in the org unit to the appointee, or
   * creates it when none exists yet. Used for the singleton ranks ({@code SQUADRON_LEAD} / {@code
   * BEREICHSLEITER}) so the partial unique index is never tripped by a second row.
   *
   * @param orgUnitId the org unit; never {@code null}.
   * @param type the singleton rank; never {@code null}.
   * @param userId the appointee; never {@code null}.
   */
  private void upsertSingletonSeat(
      @NotNull UUID orgUnitId, @NotNull OrgChartPositionType type, @NotNull UUID userId) {
    OrgChartPosition existing =
        positionRepository
            .findFirstByOrgUnitIdAndPositionTypeOrderBySortIndexAscCreatedAtAsc(orgUnitId, type)
            .orElse(null);
    if (existing == null) {
      createUserSeat(orgUnitId, type, userId, null);
    } else {
      existing.setUser(userReference(userId));
      existing.setDisplayName(null);
    }
  }

  /**
   * Reassigns the single {@code DEPUTY_COMMAND_LEAD} under the given Kommando node to the
   * appointee, or creates it when the Kommando has no deputy yet.
   *
   * @param squadronId the Staffel the deputy belongs to; never {@code null}.
   * @param command the parent {@code COMMAND_LEAD} node; never {@code null}.
   * @param userId the appointee; never {@code null}.
   */
  private void upsertDeputySeat(
      @NotNull UUID squadronId, @NotNull OrgChartPosition command, @NotNull UUID userId) {
    OrgChartPosition existing =
        positionRepository
            .findByParentIdAndPositionType(
                command.getId(), OrgChartPositionType.DEPUTY_COMMAND_LEAD)
            .orElse(null);
    if (existing == null) {
      createSeat(squadronId, OrgChartPositionType.DEPUTY_COMMAND_LEAD, userId, command, 0);
    } else {
      existing.setUser(userReference(userId));
      existing.setDisplayName(null);
    }
  }

  /**
   * Creates an {@code ENSIGN} seat for the appointee, appended after the squadron's existing
   * Ensigns, optionally reporting into a Kommando node (a {@code null} parent is a
   * Staffelleiter-direct Ensign).
   *
   * @param squadronId the Staffel; never {@code null}.
   * @param parent the Kommando node to report into, or {@code null} for a direct Ensign.
   * @param userId the appointee; never {@code null}.
   */
  private void createEnsignSeat(
      @NotNull UUID squadronId, OrgChartPosition parent, @NotNull UUID userId) {
    int sortIndex =
        (int)
            positionRepository.countByOrgUnitIdAndPositionType(
                squadronId, OrgChartPositionType.ENSIGN);
    createSeat(squadronId, OrgChartPositionType.ENSIGN, userId, parent, sortIndex);
  }

  /**
   * Creates an account-held seat of the given type, appended after the existing siblings of that
   * type in the org unit. For the multi-holder flat ranks (Koordinator / Operator / OL member /
   * SK-Leiter) and the singleton ranks when none exists yet.
   *
   * @param orgUnitId the org unit; never {@code null}.
   * @param type the rank; never {@code null}.
   * @param userId the appointee; never {@code null}.
   * @param parent the parent position, or {@code null}.
   */
  private void createUserSeat(
      @NotNull UUID orgUnitId,
      @NotNull OrgChartPositionType type,
      @NotNull UUID userId,
      OrgChartPosition parent) {
    int sortIndex = (int) positionRepository.countByOrgUnitIdAndPositionType(orgUnitId, type);
    createSeat(orgUnitId, type, userId, parent, sortIndex);
  }

  /**
   * Persists a fresh account-held chart position. Common tail of the create helpers.
   *
   * @param orgUnitId the org unit; never {@code null}.
   * @param type the rank; never {@code null}.
   * @param userId the appointee; never {@code null}.
   * @param parent the parent position, or {@code null}.
   * @param sortIndex the display order to stamp.
   */
  private void createSeat(
      @NotNull UUID orgUnitId,
      @NotNull OrgChartPositionType type,
      @NotNull UUID userId,
      OrgChartPosition parent,
      int sortIndex) {
    OrgChartPosition position = new OrgChartPosition();
    position.setPositionType(type);
    position.setOrgUnit(orgUnitReference(orgUnitId));
    position.setUser(userReference(userId));
    position.setParent(parent);
    position.setSortIndex(sortIndex);
    positionRepository.save(position);
  }

  /**
   * Loads the Kommando node ({@code COMMAND_LEAD}) mirroring the given group, creating a leaderless
   * one on the fly for a legacy group that never had its node mirrored yet.
   *
   * @param squadronId the Staffel the group belongs to; never {@code null}.
   * @param group the Kommandogruppe; never {@code null}.
   * @return the (managed) mirroring {@code COMMAND_LEAD} node.
   */
  private OrgChartPosition commandLeadForGroup(
      @NotNull UUID squadronId, @NotNull KommandoGroup group) {
    return positionRepository
        .findByKommandoGroupId(group.getId())
        .orElseGet(
            () -> {
              OrgChartPosition command = new OrgChartPosition();
              command.setPositionType(OrgChartPositionType.COMMAND_LEAD);
              command.setOrgUnit(orgUnitReference(squadronId));
              command.setKommandoGroup(group);
              command.setName(group.getName());
              command.setSortIndex(group.getSortIndex());
              return positionRepository.save(command);
            });
  }

  /**
   * Returns a lazy {@link User} reference (no SELECT) for stamping a position's holder FK; the
   * appointing flow has already proven the user exists (the membership row references it).
   *
   * @param userId the user id; never {@code null}.
   * @return a managed {@link User} reference.
   */
  private User userReference(@NotNull UUID userId) {
    return userRepository.getReferenceById(userId);
  }

  /**
   * Returns a lazy {@link OrgUnit} reference (no SELECT) for stamping a position's org-unit FK.
   *
   * @param orgUnitId the org unit id; never {@code null}.
   * @return a managed {@link OrgUnit} reference.
   */
  private OrgUnit orgUnitReference(@NotNull UUID orgUnitId) {
    return orgUnitRepository.getReferenceById(orgUnitId);
  }

  // ----------------------------------------------------------------- write guards --

  /**
   * Resolves the account holder for a create, enforcing the "account OR free-text name, never both"
   * rule. Returns the {@link User} when {@code userId} is given (and {@code displayName} is not),
   * or {@code null} when the position is held by a free-text name or is a still-leaderless
   * Kommando.
   *
   * @param type the rank being created.
   * @param userId the account id, or {@code null} for a free-text / leaderless holder.
   * @param displayName the already-trimmed free-text holder name, or {@code null}.
   * @return the resolved account, or {@code null} for a free-text / leaderless holder.
   * @throws BadRequestException if both a {@code userId} and a {@code displayName} are supplied, or
   *     if neither is supplied for a rank other than {@code COMMAND_LEAD}.
   * @throws NotFoundException if {@code userId} does not match an existing user.
   */
  private User resolveHolderForCreate(OrgChartPositionType type, UUID userId, String displayName) {
    if (userId != null && displayName != null) {
      throw new BadRequestException(ERR_HOLDER_AMBIGUOUS);
    }
    if (userId == null) {
      // A free-text name fills the seat; a Kommando may also be created before any holder exists.
      if (displayName != null || type == OrgChartPositionType.COMMAND_LEAD) {
        return null;
      }
      throw new BadRequestException(ERR_USER_REQUIRED);
    }
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new NotFoundException("User not found: " + userId));
  }

  private String validateAndNormalizeName(OrgChartPositionType type, String rawName) {
    String normalized = trimToNull(rawName);
    if (normalized != null && type != OrgChartPositionType.COMMAND_LEAD) {
      throw new BadRequestException(ERR_NAME_NOT_ALLOWED);
    }
    return normalized;
  }

  private OrgUnit resolveScopeOrgUnit(OrgChartScope scope, UUID orgUnitId) {
    if (scope == OrgChartScope.AREA) {
      if (orgUnitId != null) {
        throw new BadRequestException(ERR_SCOPE_MISMATCH);
      }
      return null;
    }
    if (orgUnitId == null) {
      throw new BadRequestException(ERR_SCOPE_MISMATCH);
    }
    OrgUnit unit =
        orgUnitRepository
            .findById(orgUnitId)
            .orElseThrow(() -> new NotFoundException("OrgUnit not found: " + orgUnitId));
    OrgUnitKind expectedKind =
        switch (scope) {
          case SQUADRON -> OrgUnitKind.SQUADRON;
          case SPECIAL_COMMAND -> OrgUnitKind.SPECIAL_COMMAND;
          case BEREICH -> OrgUnitKind.BEREICH;
          case OL -> OrgUnitKind.ORGANISATIONSLEITUNG;
          case AREA -> throw new IllegalStateException("AREA scope handled above");
        };
    if (unit.getKind() != expectedKind) {
      throw new BadRequestException(ERR_SCOPE_MISMATCH);
    }
    // The org chart is descriptive across the WHOLE organisation (ADR-0029, REQ-ORG-018): every
    // active unit of any tier may be staffed, regardless of is_profit_eligible. That flag governs
    // Job-Order processing only (a non-Profit Staffel/SK still appears on the chart and can hold
    // functional ranks), so the sole create-time gate here is the active flag — uniform across
    // Staffel/SK and Bereich/OL.
    if (!unit.isActive()) {
      throw new BadRequestException(ERR_UNIT_INACTIVE);
    }
    return unit;
  }

  private OrgChartPosition resolveAndValidateParent(
      OrgChartPositionType type, OrgUnit orgUnit, UUID parentId) {
    if (type != OrgChartPositionType.DEPUTY_COMMAND_LEAD && type != OrgChartPositionType.ENSIGN) {
      if (parentId != null) {
        throw new BadRequestException(ERR_INVALID_PARENT);
      }
      return null;
    }
    if (type == OrgChartPositionType.DEPUTY_COMMAND_LEAD) {
      if (parentId == null) {
        throw new BadRequestException(ERR_INVALID_PARENT);
      }
      OrgChartPosition parent = loadCommandLeadParent(parentId, orgUnit);
      if (positionRepository.existsByParentIdAndPositionType(
          parentId, OrgChartPositionType.DEPUTY_COMMAND_LEAD)) {
        throw new BadRequestException(ERR_DUPLICATE_DEPUTY);
      }
      return parent;
    }
    // ENSIGN: a null parent means "reports directly to the Staffelleiter"; a non-null parent must
    // be a Kommando in the same Staffel.
    return parentId == null ? null : loadCommandLeadParent(parentId, orgUnit);
  }

  private OrgChartPosition loadCommandLeadParent(UUID parentId, OrgUnit orgUnit) {
    OrgChartPosition parent =
        positionRepository
            .findById(parentId)
            .orElseThrow(() -> new NotFoundException("Parent position not found: " + parentId));
    if (parent.getPositionType() != OrgChartPositionType.COMMAND_LEAD
        || parent.getOrgUnit() == null
        || !parent.getOrgUnit().getId().equals(orgUnit.getId())) {
      throw new BadRequestException(ERR_INVALID_PARENT);
    }
    return parent;
  }

  private void validateCardinality(OrgChartPositionType type, OrgUnit orgUnit) {
    switch (type) {
      case AREA_LEAD -> {
        if (positionRepository.existsByOrgUnitIsNullAndPositionType(
            OrgChartPositionType.AREA_LEAD)) {
          throw new BadRequestException(ERR_DUPLICATE_LEAD);
        }
      }
      case SQUADRON_LEAD -> {
        if (positionRepository.countByOrgUnitIdAndPositionType(
                orgUnit.getId(), OrgChartPositionType.SQUADRON_LEAD)
            > 0) {
          throw new BadRequestException(ERR_DUPLICATE_LEAD);
        }
      }
      case COMMAND_LEAD -> {
        if (positionRepository.countByOrgUnitIdAndPositionType(
                orgUnit.getId(), OrgChartPositionType.COMMAND_LEAD)
            >= MAX_COMMAND_LEADS) {
          throw new BadRequestException(ERR_COMMAND_LIMIT);
        }
      }
      case ENSIGN -> {
        if (positionRepository.countByOrgUnitIdAndPositionType(
                orgUnit.getId(), OrgChartPositionType.ENSIGN)
            >= MAX_ENSIGNS) {
          throw new BadRequestException(ERR_ENSIGN_LIMIT);
        }
      }
      case SK_COMMANDER -> {
        if (positionRepository.countByOrgUnitIdAndPositionType(
                orgUnit.getId(), OrgChartPositionType.SK_COMMANDER)
            >= MAX_SK_COMMANDERS) {
          throw new BadRequestException(ERR_COMMANDER_LIMIT);
        }
      }
      case BEREICHSLEITER -> {
        // Epic #692, REQ-ORG-018: at most one Bereichsleiter PER Bereich (scoped to org_unit_id),
        // unlike the legacy AREA_LEAD which is a global singleton.
        if (positionRepository.countByOrgUnitIdAndPositionType(
                orgUnit.getId(), OrgChartPositionType.BEREICHSLEITER)
            > 0) {
          throw new BadRequestException(ERR_DUPLICATE_LEAD);
        }
      }
      // DEPUTY_COMMAND_LEAD's "at most one per Kommando" is enforced during parent resolution;
      // AREA_COORDINATOR / AREA_OPERATOR / AREA_COMMANDER, BEREICHSKOORDINATOR / BEREICHSOPERATOR
      // and OL_MEMBER are unbounded.
      default -> {
        // no cardinality limit
      }
    }
  }

  private void validateUserUnique(OrgChartScope scope, OrgUnit orgUnit, UUID userId) {
    boolean alreadyAssigned =
        scope == OrgChartScope.AREA
            ? positionRepository.existsByOrgUnitIsNullAndUserId(userId)
            : positionRepository.existsByOrgUnitIdAndUserId(orgUnit.getId(), userId);
    if (alreadyAssigned) {
      throw new BadRequestException(ERR_USER_ASSIGNED);
    }
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
