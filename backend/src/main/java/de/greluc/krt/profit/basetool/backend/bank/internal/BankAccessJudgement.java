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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountAccess;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountRight;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffRights;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.BereichRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Department;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The per-account bank access rules for one actor under one active-org-unit pin, created by {@link
 * BankAccessRules} for each decision. Reads the Bereich tree only when a rule needs it, at most
 * once per Bereich.
 */
final class BankAccessJudgement {

  private final BankActor actor;
  private final BankStaffRights staffRights;
  private final BereichRepository bereichRepository;
  private final OrgUnitRepository orgUnitRepository;

  /**
   * Whether the actor reaches {@code ADMIN}, who views and configures every account but the
   * org-unit and Bereich accounts outside the pin (REQ-ORG-032).
   */
  private final boolean admin;

  /**
   * Whether the actor's memberships confer anything: only while the actor holds {@code KRT Member}
   * (REQ-SEC-015); a retained seat or plain membership then grants no oversight, holder, approver,
   * member-grant or tier right.
   */
  private final boolean membershipsConfer;

  /**
   * The org units whose accounts the actor views by oversight: the officer reach of a {@code KRT
   * Member}, narrowed to the pin alone when the pin lies in it and to nothing when it does not
   * (REQ-BANK-021, REQ-ORG-032).
   */
  private final Set<UUID> viewScope;

  /** The honoured active org unit, or {@code null}; an org-unit account outside it stays hidden. */
  @Nullable private final UUID pin;

  /** The child org units per Bereich, read on first use. */
  private final Map<UUID, List<UUID>> childrenByBereich = new HashMap<>();

  /** Whether the actor leads a {@code PROFIT} Bereich; {@code null} until first asked. */
  @Nullable private Boolean profitBereichsleiter;

  /**
   * Prepares the judgement.
   *
   * @param actor the actor
   * @param pin the active org unit, or {@code null}
   * @param staffRights the actor's staff rights
   * @param bereichRepository reads the {@code PROFIT} Bereiche
   * @param orgUnitRepository reads a Bereich's child units
   */
  BankAccessJudgement(
      @NotNull BankActor actor,
      @Nullable UUID pin,
      @NotNull BankStaffRights staffRights,
      @NotNull BereichRepository bereichRepository,
      @NotNull OrgUnitRepository orgUnitRepository) {
    this.actor = actor;
    this.staffRights = staffRights;
    this.bereichRepository = bereichRepository;
    this.orgUnitRepository = orgUnitRepository;
    this.admin = staffRights.admin();
    this.membershipsConfer = membershipsConfer(actor);
    this.pin = pin;
    Set<UUID> oversight = membershipsConfer ? actor.officerReach() : Set.of();
    this.viewScope = pin == null ? oversight : oversight.contains(pin) ? Set.of(pin) : Set.of();
  }

  /**
   * Whether the actor is an administrator.
   *
   * @return {@code true} iff the actor reaches {@code ADMIN}
   */
  boolean admin() {
    return admin;
  }

  /**
   * Every right the actor holds on the account.
   *
   * @param account the account
   * @param viewGrants the account's view grants, read only when the view rule needs them
   * @param limits the account's approval limits, read only when a display ceiling applies
   * @return {@link BankAccountAccess.Visible} with the rights, or {@link BankAccountAccess.Hidden}
   *     when there are none
   */
  @NotNull
  BankAccountAccess access(
      @NotNull BankAccount account,
      @NotNull Supplier<List<BankAccountViewGrant>> viewGrants,
      @NotNull Supplier<List<BankAccountApprovalLimit>> limits) {
    Set<BankAccountRight> rights = EnumSet.noneOf(BankAccountRight.class);
    boolean view = canView(account, viewGrants);
    boolean holder = isResponsibleHolder(account);
    boolean request = view && isRequestCapable(account);
    addIf(rights, view, BankAccountRight.VIEW);
    addIf(rights, request, BankAccountRight.REQUEST);
    addIf(rights, holder, BankAccountRight.RESPONSIBLE_HOLDER);
    boolean inContext = inContext(account);
    addIf(rights, canSetTarget(account, holder, inContext), BankAccountRight.SET_TARGET);
    addIf(
        rights,
        canConfigureVisibility(account, holder, inContext),
        BankAccountRight.CONFIGURE_VISIBILITY);
    addIf(
        rights,
        canConfigureApprovalLimits(account, holder, inContext),
        BankAccountRight.CONFIGURE_APPROVAL_LIMITS);
    UUID accountId = account.getId();
    addIf(rights, staffRights.canSee(accountId), BankAccountRight.STAFF_VIEW);
    addIf(
        rights,
        staffRights.canBook(BankBookingRequestType.DEPOSIT, accountId),
        BankAccountRight.STAFF_DEPOSIT);
    addIf(
        rights,
        staffRights.canBook(BankBookingRequestType.WITHDRAWAL, accountId),
        BankAccountRight.STAFF_WITHDRAW);
    addIf(
        rights,
        staffRights.canBook(BankBookingRequestType.TRANSFER, accountId),
        BankAccountRight.STAFF_TRANSFER);
    if (rights.isEmpty()) {
      return BankAccountAccess.Hidden.INSTANCE;
    }
    BigDecimal approvalLimit = request && !holder ? applicableLimit(account, limits) : null;
    return new BankAccountAccess.Visible(accountId, rights, approvalLimit);
  }

  /**
   * Whether the actor views the account on the org-unit side (REQ-BANK-021, REQ-BANK-037): an
   * org-unit or Bereich account its overseers, its grantees and an administrator, and under a pin
   * only when the pinned unit owns it (REQ-ORG-032); the KRT account its overseers and every
   * member; the cartel bank account a {@code PROFIT} Bereichsleiter; a Sonderkonto the OL, every
   * Bereichsleiter and its grantees. An administrator views every account but the org-unit and
   * Bereich accounts outside the active context ({@link #inContext(BankAccount)}).
   *
   * @param account the account
   * @param viewGrants the account's view grants, read only when needed
   * @return {@code true} iff the actor views the account
   */
  boolean canView(
      @NotNull BankAccount account, @NotNull Supplier<List<BankAccountViewGrant>> viewGrants) {
    UUID owner = owningOrgUnitId(account);
    return switch (account.getType()) {
      case ORG_UNIT, AREA ->
          admin
              ? inContext(account)
              : owner != null
                  && inPinnedContext(owner)
                  && (viewScope.contains(owner) || matchesOrgUnitGrant(owner, viewGrants.get()));
      case CARTEL -> admin || (owner != null && viewScope.contains(owner)) || isMemberOrAbove();
      case CARTEL_BANK -> admin || isProfitBereichsleiter();
      case SPECIAL ->
          admin
              || holdsSeat(MembershipRole.OL_MEMBER)
              || holdsSeat(MembershipRole.BEREICHSLEITER)
              || matchesSpecialGrant(viewGrants.get());
    };
  }

  /**
   * Whether an org-unit or Bereich account of {@code owner} lies in the active context: always
   * without a pin, otherwise only the pinned unit's own account (REQ-ORG-032).
   *
   * @param owner the account's owning org unit
   * @return {@code true} iff the account is in the active context
   */
  private boolean inPinnedContext(@NotNull UUID owner) {
    return pin == null || pin.equals(owner);
  }

  /**
   * Whether the account lies in the active context (REQ-ORG-032): an org-unit or Bereich account
   * when no unit is pinned or the pinned unit owns it, so one without an owning unit (a legacy
   * Bereich account named only by its area) only without a pin; the KRT, cartel bank and
   * Sonderkonto accounts always, since they belong to the whole organisation.
   *
   * @param account the account
   * @return {@code true} iff the actor's org-unit-side rights may act on the account
   */
  boolean inContext(@NotNull BankAccount account) {
    UUID owner = owningOrgUnitId(account);
    return switch (account.getType()) {
      case ORG_UNIT, AREA -> owner == null ? pin == null : inPinnedContext(owner);
      case CARTEL, CARTEL_BANK, SPECIAL -> true;
    };
  }

  /**
   * Whether the actor is the account's derived responsible holder (REQ-BANK-034) in the active
   * context: the Staffelleiter or SK lead of an org-unit account and the Bereichsleiter of a
   * Bereich account, each only while that unit is not pinned away (REQ-ORG-032); an OL member for
   * the KRT account, a {@code PROFIT} Bereichsleiter for the cartel bank account; a Sonderkonto has
   * none.
   *
   * @param account the account
   * @return {@code true} iff the actor is its responsible holder
   */
  boolean isResponsibleHolder(@NotNull BankAccount account) {
    UUID owner = owningOrgUnitId(account);
    return switch (account.getType()) {
      case ORG_UNIT -> {
        if (owner == null || !inPinnedContext(owner)) {
          yield false;
        }
        MembershipRole holderRole =
            Objects.requireNonNull(account.getOrgUnit()).getKind() == OrgUnitKind.SPECIAL_COMMAND
                ? MembershipRole.SK_LEAD
                : MembershipRole.STAFFELLEITER;
        yield holdsSeatOn(owner, holderRole);
      }
      case AREA ->
          owner != null
              && inPinnedContext(owner)
              && holdsSeatOn(owner, MembershipRole.BEREICHSLEITER);
      case CARTEL -> holdsSeat(MembershipRole.OL_MEMBER);
      case CARTEL_BANK -> isProfitBereichsleiter();
      case SPECIAL -> false;
    };
  }

  /**
   * Whether the account accepts booking requests: {@code ACTIVE} and of type {@code ORG_UNIT},
   * {@code AREA} or {@code CARTEL}.
   *
   * @param account the account
   * @return whether a withdrawal or transfer request may be raised against it
   */
  static boolean isRequestCapable(@NotNull BankAccount account) {
    return account.getStatus() == BankAccountStatus.ACTIVE
        && BankApprovalLimitService.configurable(account.getType());
  }

  /**
   * The actor's applicable approval limit (REQ-BANK-041, REQ-BANK-047): {@code T1} on the KRT
   * account; otherwise an individual limit wins, else the maximum of every matching membership
   * tier, where the all-members tier matches only members of the owning unit.
   *
   * @param account the account
   * @param limitRows the account's approval-limit rows, read only off the KRT account
   * @return the limit, or {@code null} when no tier matches
   */
  @Nullable
  BigDecimal applicableLimit(
      @NotNull BankAccount account, @NotNull Supplier<List<BankAccountApprovalLimit>> limitRows) {
    if (account.getType() == BankAccountType.CARTEL) {
      return account.getEmployeeApprovalCeiling();
    }
    List<BankAccountApprovalLimit> limits = limitRows.get();
    for (BankAccountApprovalLimit limit : limits) {
      if (limit.getGranteeKind() == BankAccountViewGranteeKind.USER
          && actor.is(limit.getGranteeUserId())) {
        return limit.getLimitAmount();
      }
    }
    UUID owner = owningOrgUnitId(account);
    if (owner == null) {
      return null;
    }
    BigDecimal best = null;
    for (BankAccountApprovalLimit limit : limits) {
      boolean matches =
          switch (limit.getGranteeKind()) {
            case MEMBERSHIP_ROLE -> holdsSeatOn(owner, limit.getRoleCode());
            case AREA_MEMBERS -> isMemberOfAreaCascade(owner);
            case ALL_MEMBERS -> isMemberOf(owner);
            case USER -> false;
          };
      if (matches) {
        best = best == null ? limit.getLimitAmount() : best.max(limit.getLimitAmount());
      }
    }
    return best;
  }

  /**
   * Whether the actor may set the balance target from the org-unit side (REQ-BANK-036): an
   * administrator on an account in the active context (REQ-ORG-032), or the responsible holder of
   * any account but a Sonderkonto.
   *
   * @param account the account
   * @param holder whether the actor is its responsible holder
   * @param inContext whether the account lies in the active context
   * @return {@code true} iff the actor may set the target
   */
  private boolean canSetTarget(@NotNull BankAccount account, boolean holder, boolean inContext) {
    if (admin) {
      return inContext;
    }
    return switch (account.getType()) {
      case ORG_UNIT, AREA, CARTEL, CARTEL_BANK -> holder;
      case SPECIAL -> false;
    };
  }

  /**
   * Whether the actor may configure the view grants (REQ-BANK-035): the responsible holder of an
   * org-unit or Bereich account, an OL member or the Bankleitung for a Sonderkonto, an
   * administrator wherever visibility is configurable on an account in the active context
   * (REQ-ORG-032).
   *
   * @param account the account
   * @param holder whether the actor is its responsible holder
   * @param inContext whether the account lies in the active context
   * @return {@code true} iff the actor may add or remove view grants
   */
  private boolean canConfigureVisibility(
      @NotNull BankAccount account, boolean holder, boolean inContext) {
    if (admin) {
      return inContext && visibilityConfigurable(account.getType());
    }
    return switch (account.getType()) {
      case ORG_UNIT, AREA -> holder;
      case SPECIAL -> holdsSeat(MembershipRole.OL_MEMBER) || staffRights.management();
      case CARTEL, CARTEL_BANK -> false;
    };
  }

  /**
   * Whether the actor may set or clear approval limits (REQ-BANK-041): on an org-unit or Bereich
   * account, its responsible holder, the Bankleitung under any pin, or an administrator on an
   * account in the active context (REQ-ORG-032).
   *
   * @param account the account
   * @param holder whether the actor is its responsible holder
   * @param inContext whether the account lies in the active context
   * @return {@code true} iff the actor may configure approval limits
   */
  private boolean canConfigureApprovalLimits(
      @NotNull BankAccount account, boolean holder, boolean inContext) {
    // An administrator also reaches BANK_MANAGEMENT through the role hierarchy, so the admin rule
    // decides alone and the pin-free Bankleitung right never applies to one.
    return BankApprovalLimitService.audienceLimitsSupported(account.getType())
        && (admin ? inContext : staffRights.management() || holder);
  }

  /**
   * Whether the account type supports configurable visibility: {@code ORG_UNIT}, {@code AREA} and
   * {@code SPECIAL}; the KRT account is fixed to all members and the cartel bank account internal.
   *
   * @param type the account type
   * @return whether visibility is configurable
   */
  static boolean visibilityConfigurable(@NotNull BankAccountType type) {
    return type == BankAccountType.ORG_UNIT
        || type == BankAccountType.AREA
        || type == BankAccountType.SPECIAL;
  }

  /**
   * Whether a view grant of an org-unit account admits the actor: a seat bucket on the owning unit,
   * all members as a member of it, the Bereich cascade, or an individual grant.
   *
   * @param owner the account's owning org unit
   * @param grants the account's view grants
   * @return {@code true} iff a grant admits the actor
   */
  private boolean matchesOrgUnitGrant(
      @NotNull UUID owner, @NotNull List<BankAccountViewGrant> grants) {
    for (BankAccountViewGrant grant : grants) {
      boolean match =
          switch (grant.getGranteeKind()) {
            case MEMBERSHIP_ROLE -> holdsSeatOn(owner, grant.getRoleCode());
            case ALL_MEMBERS -> isMemberOf(owner);
            case AREA_MEMBERS -> isMemberOfAreaCascade(owner);
            case USER -> actor.is(grant.getGranteeUserId());
          };
      if (match) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a view grant of a Sonderkonto admits the actor: all members as a member or above, or an
   * individual grant.
   *
   * @param grants the account's view grants
   * @return {@code true} iff a grant admits the actor
   */
  private boolean matchesSpecialGrant(@NotNull List<BankAccountViewGrant> grants) {
    for (BankAccountViewGrant grant : grants) {
      boolean match =
          switch (grant.getGranteeKind()) {
            case ALL_MEMBERS -> isMemberOrAbove();
            case USER -> actor.is(grant.getGranteeUserId());
            case MEMBERSHIP_ROLE, AREA_MEMBERS -> false;
          };
      if (match) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the actor is an organisation member or holds an elevated role.
   *
   * @return {@code true} iff the actor reaches a role of {@link Roles#MEMBER_OR_ABOVE}
   */
  private boolean isMemberOrAbove() {
    return Roles.MEMBER_OR_ABOVE.stream().anyMatch(actor::holds);
  }

  /**
   * Whether the actor holds the seat named by a stored role code on the org unit; an unknown code
   * matches nothing.
   *
   * @param orgUnitId the org unit
   * @param roleCode the stored {@link MembershipRole} name, or {@code null}
   * @return {@code true} iff the actor holds that seat there
   */
  private boolean holdsSeatOn(@NotNull UUID orgUnitId, @Nullable String roleCode) {
    MembershipRole role = parseMembershipRole(roleCode);
    return role != null && holdsSeatOn(orgUnitId, role);
  }

  /**
   * Whether the actor holds the seat on the org unit and the seat confers anything, which it does
   * only while its holder holds {@code KRT Member} (REQ-SEC-015).
   *
   * @param orgUnitId the org unit
   * @param role the seat
   * @return {@code true} iff the actor holds the seat there as a {@code KRT Member}
   */
  private boolean holdsSeatOn(@NotNull UUID orgUnitId, @NotNull MembershipRole role) {
    return membershipsConfer && actor.holdsSeatOn(orgUnitId, role);
  }

  /**
   * Whether the actor holds the seat on any org unit and the seat confers anything (REQ-SEC-015).
   *
   * @param role the seat
   * @return {@code true} iff the actor holds the seat as a {@code KRT Member}
   */
  private boolean holdsSeat(@NotNull MembershipRole role) {
    return membershipsConfer && actor.holdsSeat(role);
  }

  /**
   * Whether the actor is a member of the org unit and the membership confers anything, the {@code
   * ALL_MEMBERS} grant and tier (REQ-BANK-035, REQ-SEC-015).
   *
   * @param orgUnitId the org unit
   * @return {@code true} iff the actor is a {@code KRT Member} with a membership on the unit
   */
  private boolean isMemberOf(@NotNull UUID orgUnitId) {
    return membershipsConfer && actor.isMemberOf(orgUnitId);
  }

  /**
   * Whether the actor's memberships confer any bank right, seats and plain memberships alike: only
   * while the actor holds {@code KRT Member}; a membership retained after losing it confers nothing
   * (REQ-SEC-015).
   *
   * @param actor the actor
   * @return {@code true} iff the actor's memberships count
   */
  static boolean membershipsConfer(@NotNull BankActor actor) {
    return actor.holds(Roles.KRT_MEMBER);
  }

  /**
   * Whether the actor leads a {@code PROFIT} Bereich, the holder of the cartel bank account
   * (REQ-BANK-037); reads the Bereiche only for a Bereichsleiter, once.
   *
   * @return {@code true} iff the actor holds {@code BEREICHSLEITER} on a {@code PROFIT} Bereich
   */
  private boolean isProfitBereichsleiter() {
    if (profitBereichsleiter == null) {
      profitBereichsleiter =
          holdsSeat(MembershipRole.BEREICHSLEITER)
              && bereichRepository.findByDepartment(Department.PROFIT).stream()
                  .map(Bereich::getId)
                  .anyMatch(id -> holdsSeatOn(id, MembershipRole.BEREICHSLEITER));
    }
    return profitBereichsleiter;
  }

  /**
   * Whether the actor is a member of the Bereich or of one of its child units and the membership
   * confers anything, the {@code AREA_MEMBERS} grant and tier (REQ-BANK-048, REQ-SEC-015).
   *
   * @param bereichId the owning Bereich
   * @return {@code true} iff the actor is a {@code KRT Member} and a direct membership is the
   *     Bereich or a child of it
   */
  private boolean isMemberOfAreaCascade(@NotNull UUID bereichId) {
    if (!membershipsConfer) {
      return false;
    }
    List<UUID> children =
        childrenByBereich.computeIfAbsent(bereichId, orgUnitRepository::findChildOrgUnitIds);
    return isMemberOfAreaCascade(bereichId, actor.memberOrgUnitIds(), children);
  }

  /**
   * Decides the {@code AREA_MEMBERS} cascade from the direct memberships and the Bereich's child
   * units.
   *
   * @param bereichId the owning Bereich
   * @param directMemberships the org units the actor is a direct member of
   * @param childIds the Bereich's direct child units
   * @return {@code true} iff a direct membership is the Bereich or one of its children
   */
  static boolean isMemberOfAreaCascade(
      @NotNull UUID bereichId, @NotNull Set<UUID> directMemberships, @NotNull List<UUID> childIds) {
    return directMemberships.stream().anyMatch(ou -> ou.equals(bereichId) || childIds.contains(ou));
  }

  /**
   * The owning org-unit id of an account.
   *
   * @param account the account
   * @return the owning org-unit id, or {@code null} when it has none
   */
  @Nullable
  private static UUID owningOrgUnitId(@NotNull BankAccount account) {
    return account.getOrgUnit() == null ? null : account.getOrgUnit().getId();
  }

  /**
   * Parses a {@link MembershipRole} name.
   *
   * @param code the stored role code
   * @return the role, or {@code null} for {@code null} or an unknown code
   */
  @Contract("null -> null")
  @Nullable
  private static MembershipRole parseMembershipRole(@Nullable String code) {
    if (code == null) {
      return null;
    }
    try {
      return MembershipRole.valueOf(code);
    } catch (IllegalArgumentException _) {
      return null;
    }
  }

  /**
   * Adds the right when the condition holds.
   *
   * @param rights the rights being collected
   * @param condition whether the actor holds the right
   * @param right the right
   */
  private static void addIf(
      @NotNull Set<BankAccountRight> rights, boolean condition, @NotNull BankAccountRight right) {
    if (condition) {
      rights.add(right);
    }
  }
}
