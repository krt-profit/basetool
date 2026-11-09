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

import de.greluc.krt.profit.basetool.backend.bank.api.BankAccessPolicy;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountAccess;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountRight;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAdmission;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffRights;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.BereichRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The implementation of {@link BankAccessPolicy} and the home of every bank access rule (ADR-0352):
 * it decides from the explicit {@link BankActor} and the bank's own rows, never from the security
 * context.
 *
 * <p>Beside the four queries of the interface it answers, for the web adapters of this package, the
 * decisions that take an entity they already hold: the routing of a corrected request, the staff
 * direct booking against the KRT ceiling, the approval of a request and the foreign-request scope.
 * The per-account judgement itself lives in {@link BankAccessJudgement}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankAccessRules implements BankAccessPolicy {

  private final BankAccountRepository accountRepository;
  private final BankAccountViewGrantRepository viewGrantRepository;
  private final BankAccountApprovalLimitRepository approvalLimitRepository;
  private final BereichRepository bereichRepository;
  private final OrgUnitRepository orgUnitRepository;

  @Override
  @NotNull
  public BankAccountAccess accessTo(
      @NotNull BankActor actor, @NotNull UUID accountId, @Nullable UUID pin) {
    return accountRepository
        .findById(accountId)
        .map(account -> accessTo(actor, account, pin))
        .orElse(BankAccountAccess.Hidden.INSTANCE);
  }

  /**
   * {@link #accessTo(BankActor, UUID, UUID)} for an account the caller already loaded.
   *
   * @param actor the actor
   * @param account the account
   * @param pin the active org unit, or {@code null}
   * @return the actor's access to the account
   */
  @NotNull
  BankAccountAccess accessTo(
      @NotNull BankActor actor, @NotNull BankAccount account, @Nullable UUID pin) {
    return judgement(actor, pin)
        .access(
            account,
            () -> viewGrantRepository.findByAccountId(account.getId()),
            () -> approvalLimitRepository.findByAccountId(account.getId()));
  }

  /**
   * Whether the actor views the account on the org-unit side, the {@link
   * BankAccountRight#VIEW} of {@link #accessTo(BankActor, BankAccount, UUID)} without deciding the
   * other rights.
   *
   * @param actor the actor
   * @param account the account
   * @param pin the active org unit, or {@code null}
   * @return {@code true} iff the actor views the account
   */
  boolean canView(@NotNull BankActor actor, @NotNull BankAccount account, @Nullable UUID pin) {
    return judgement(actor, pin)
        .canView(account, () -> viewGrantRepository.findByAccountId(account.getId()));
  }

  @Override
  @NotNull
  @Unmodifiable
  public List<BankAccountAccess.Visible> viewableAccounts(
      @NotNull BankActor actor, @Nullable UUID pin) {
    return viewable(actor, pin).stream().map(ViewableAccount::access).toList();
  }

  /**
   * The active accounts the actor views on the org-unit side, with the entity, reading the view
   * grants and approval limits of all of them in one query each (REQ-DATA-003).
   *
   * @param actor the actor
   * @param pin the active org unit, or {@code null} for the whole reach
   * @return the viewable accounts ordered by account number
   */
  @NotNull
  @Unmodifiable
  List<ViewableAccount> viewable(@NotNull BankActor actor, @Nullable UUID pin) {
    List<BankAccount> active =
        accountRepository.findAllByOrderByAccountNoAsc().stream()
            .filter(account -> account.getStatus() == BankAccountStatus.ACTIVE)
            .toList();
    if (active.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = active.stream().map(BankAccount::getId).toList();
    BankAccessJudgement judgement = judgement(actor, pin);
    Map<UUID, List<BankAccountViewGrant>> grantsByAccount =
        judgement.admin()
            ? Map.of()
            : viewGrantRepository.findByAccountIdIn(ids).stream()
                .collect(Collectors.groupingBy(grant -> grant.getAccount().getId()));
    List<BankAccount> visible =
        active.stream()
            .filter(account ->
                judgement.canView(
                    account, () -> grantsByAccount.getOrDefault(account.getId(), List.of())))
            .toList();
    if (visible.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<BankAccountApprovalLimit>> limitsByAccount =
        approvalLimitRepository
            .findByAccountIdIn(visible.stream().map(BankAccount::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(limit -> limit.getAccount().getId()));
    return visible.stream()
        .map(account ->
            new ViewableAccount(
                account,
                (BankAccountAccess.Visible)
                    judgement.access(
                        account,
                        () -> grantsByAccount.getOrDefault(account.getId(), List.of()),
                        () -> limitsByAccount.getOrDefault(account.getId(), List.of()))))
        .toList();
  }

  @Override
  @NotNull
  public BankAdmission admit(
      @NotNull BankActor actor,
      @NotNull UUID accountId,
      @Nullable UUID pin,
      @NotNull BankBookingRequestType type,
      @NotNull BigDecimal amount) {
    return accountRepository
        .findById(accountId)
        .map(account -> admit(actor, account, pin, type, amount))
        .orElse(BankAdmission.Rejection.NOT_FOUND);
  }

  /**
   * {@link #admit(BankActor, UUID, UUID, BankBookingRequestType, BigDecimal)} for an account the
   * caller already loaded.
   *
   * @param actor the requester
   * @param account the (source) account
   * @param pin the active org unit, or {@code null}
   * @param type the movement kind
   * @param amount the whole-aUEC amount
   * @return the routing, or why the request is refused
   */
  @NotNull
  BankAdmission admit(
      @NotNull BankActor actor,
      @NotNull BankAccount account,
      @Nullable UUID pin,
      @NotNull BankBookingRequestType type,
      @NotNull BigDecimal amount) {
    if (type == BankBookingRequestType.DEPOSIT) {
      return BankAdmission.Admitted.UNROUTED;
    }
    BankAccessJudgement judgement = judgement(actor, pin);
    if (!judgement.canView(account, () -> viewGrantRepository.findByAccountId(account.getId()))) {
      return BankAdmission.Rejection.NOT_FOUND;
    }
    if (!BankAccessJudgement.isRequestCapable(account)) {
      return BankAdmission.Rejection.NOT_REQUEST_CAPABLE;
    }
    return route(judgement, account, amount);
  }

  /**
   * The approval routing of the actor's correction of their own request, derived exactly as its
   * raise was (REQ-BANK-042, REQ-BANK-056, REQ-BANK-061): none for a deposit, the purpose-bound
   * routing for a purpose-bound request, else the routing of a withdrawal or transfer the actor
   * raises on the account.
   *
   * @param actor the requester
   * @param request the request being corrected
   * @param amount the corrected whole-aUEC amount
   * @return the routing to stamp on the request
   */
  @NotNull
  BankAdmission.Admitted routeCorrection(
      @NotNull BankActor actor, @NotNull BankBookingRequest request, @NotNull BigDecimal amount) {
    BankAccount account = request.getAccount();
    if (request.getType() == BankBookingRequestType.DEPOSIT) {
      return BankAdmission.Admitted.UNROUTED;
    }
    if (request.getPurposeKind() != null) {
      return routePurposeBound(account, request.getType(), amount);
    }
    return route(judgement(actor, null), account, amount);
  }

  /**
   * The routing of a purpose-bound request on a funding account (REQ-BANK-061): a withdrawal from
   * the KRT account climbs the amount ladder whoever raises it, every other one needs no approval.
   *
   * @param account the {@code CARTEL} or {@code SPECIAL} funding account
   * @param type {@code DEPOSIT} or {@code WITHDRAWAL}
   * @param amount the whole-aUEC amount
   * @return the routing to stamp on the request
   */
  @NotNull
  static BankAdmission.Admitted routePurposeBound(
      @NotNull BankAccount account,
      @NotNull BankBookingRequestType type,
      @NotNull BigDecimal amount) {
    return type == BankBookingRequestType.WITHDRAWAL && account.getType() == BankAccountType.CARTEL
        ? cartelLadder(account, amount)
        : BankAdmission.Admitted.UNROUTED;
  }

  /**
   * The approval routing of a withdrawal or transfer the actor raises on the account (REQ-BANK-041,
   * REQ-BANK-047): none for its responsible holder, the amount ladder on the KRT account, else the
   * actor's applicable limit.
   *
   * @param judgement the actor's judgement
   * @param account the (source) account
   * @param amount the whole-aUEC amount
   * @return the routing to stamp on the request
   */
  @NotNull
  private BankAdmission.Admitted route(
      @NotNull BankAccessJudgement judgement,
      @NotNull BankAccount account,
      @NotNull BigDecimal amount) {
    if (judgement.isResponsibleHolder(account)) {
      return BankAdmission.Admitted.UNROUTED;
    }
    if (account.getType() == BankAccountType.CARTEL) {
      return cartelLadder(account, amount);
    }
    BigDecimal limit =
        judgement.applicableLimit(
            account, () -> approvalLimitRepository.findByAccountId(account.getId()));
    return limit == null || amount.compareTo(limit) > 0
        ? BankAdmission.Admitted.approvedBy(limit, BankRequestApprover.RESPONSIBLE_HOLDER)
        : BankAdmission.Admitted.withoutApproval(limit);
  }

  /**
   * The amount ladder of the KRT account (REQ-BANK-047, ADR-0109): up to {@code T1} no approver, up
   * to {@code T2} the Bankleitung, above that the Organisationsleitung; an unset {@code T1} counts
   * as 0, an unset {@code T2} as unbounded, and the applicable limit is always {@code T1}.
   *
   * @param account the KRT account
   * @param amount the whole-aUEC amount leaving the account
   * @return the routing
   */
  @NotNull
  static BankAdmission.Admitted cartelLadder(
      @NotNull BankAccount account, @NotNull BigDecimal amount) {
    BigDecimal t1 = employeeCeiling(account);
    BigDecimal t2 = account.getAreaLeadApprovalCeiling();
    if (amount.compareTo(t1) <= 0) {
      return BankAdmission.Admitted.withoutApproval(t1);
    }
    if (t2 == null || amount.compareTo(t2) <= 0) {
      return BankAdmission.Admitted.approvedBy(t1, BankRequestApprover.BANK_MANAGEMENT);
    }
    return BankAdmission.Admitted.approvedBy(t1, BankRequestApprover.ORGANISATIONSLEITUNG);
  }

  /**
   * The routing of a staff direct booking that exceeds the KRT ceiling and is filed as a request
   * instead (REQ-BANK-047): none for the account's responsible holder, else the amount ladder.
   *
   * @param actor the bank staff member
   * @param account the KRT account
   * @param amount the whole-aUEC amount leaving the account
   * @return the routing to stamp on the request
   */
  @NotNull
  BankAdmission.Admitted routeCartelDirectBooking(
      @NotNull BankActor actor, @NotNull BankAccount account, @NotNull BigDecimal amount) {
    return judgement(actor, null).isResponsibleHolder(account)
        ? BankAdmission.Admitted.UNROUTED
        : cartelLadder(account, amount);
  }

  /**
   * Whether a staff direct withdrawal or transfer must become an approval request instead
   * (REQ-BANK-047, ADR-0109): it debits the KRT account by more than {@code T1} and the actor is no
   * Bankleitung. Request confirmation never asks this.
   *
   * @param actor the bank staff member
   * @param accountId the (source) account
   * @param amount the entered whole-aUEC amount
   * @return {@code true} iff the booking must be filed as a request
   */
  boolean exceedsDirectBookingCeiling(
      @NotNull BankActor actor, @NotNull UUID accountId, @NotNull BigDecimal amount) {
    if (staffRights(actor).management()) {
      return false;
    }
    return accountRepository
        .findById(accountId)
        .filter(account -> account.getType() == BankAccountType.CARTEL)
        .map(account -> amount.compareTo(employeeCeiling(account)) > 0)
        .orElse(false);
  }

  @Override
  @NotNull
  public BankStaffRights staffRights(@NotNull BankActor actor) {
    return new BankStaffRights(
        actor.userId(),
        actor.holds(Roles.BANK_EMPLOYEE),
        actor.holds(Roles.BANK_MANAGEMENT),
        actor.holds(Roles.ADMIN),
        BankAccessJudgement.seatsConfer(actor) && actor.holdsSeat(MembershipRole.OL_MEMBER),
        actor.grants());
  }

  /**
   * Whether the actor may grant or revoke the request's approval (REQ-BANK-041, REQ-BANK-047).
   *
   * @param actor the actor
   * @param request the request
   * @return {@code true} iff the actor acts on its approval
   */
  boolean mayApprove(@NotNull BankActor actor, @NotNull BankBookingRequest request) {
    return staffRights(actor)
        .mayApprove(
            request.getRequiredApprover(),
            judgement(actor, null).isResponsibleHolder(request.getAccount()));
  }

  /**
   * The org units of the actor's active context, which orders the viewable accounts and grants
   * nothing (REQ-BANK-064): the pin when an administrator sets it or it lies in the actor's reach,
   * otherwise every unit the actor is a direct member of.
   *
   * @param actor the actor
   * @param pin the active org unit as sent, or {@code null}
   * @return the active-context org-unit ids; empty without memberships and pin
   */
  @NotNull
  @Unmodifiable
  Set<UUID> activeContext(@NotNull BankActor actor, @Nullable UUID pin) {
    Set<UUID> direct = actor.memberOrgUnitIds();
    if (pin != null
        && (actor.holds(Roles.ADMIN)
            || direct.contains(pin)
            || actor.officerReach().contains(pin))) {
      return Set.of(pin);
    }
    return direct;
  }

  /**
   * Whether the request is the actor's own, which alone they may correct and cancel; a foreign
   * request answers like an unknown one (REQ-BANK-022, REQ-BANK-056).
   *
   * @param actor the actor
   * @param request the request
   * @return {@code true} iff the actor raised it
   */
  boolean ownsRequest(@NotNull BankActor actor, @NotNull BankBookingRequest request) {
    return ownsRequest(actor.userId(), request);
  }

  /**
   * {@link #ownsRequest(BankActor, BankBookingRequest)} for a caller known by id alone, which needs
   * no actor read.
   *
   * @param requester the member, or {@code null} for none
   * @param request the request
   * @return {@code true} iff the member raised it
   */
  boolean ownsRequest(@Nullable UUID requester, @NotNull BankBookingRequest request) {
    return requester != null && requester.equals(request.getRequestedBy());
  }

  /**
   * The accounts whose requests the actor reads as foreign requests (REQ-BANK-046): every account
   * for an administrator, the accounts the actor holds, and the KRT account for the Bankleitung.
   *
   * @param actor the actor
   * @return the candidate accounts and those the actor is responsible holder of
   */
  @NotNull
  ForeignRequestScope foreignRequestScope(@NotNull BankActor actor) {
    BankAccessJudgement judgement = judgement(actor, null);
    BankStaffRights rights = staffRights(actor);
    Set<UUID> responsible = new LinkedHashSet<>();
    Set<UUID> candidates = new LinkedHashSet<>();
    for (BankAccount account : accountRepository.findAllByOrderByAccountNoAsc()) {
      if (rights.admin() || judgement.isResponsibleHolder(account)) {
        responsible.add(account.getId());
      }
      if (rights.management() && account.getType() == BankAccountType.CARTEL) {
        candidates.add(account.getId());
      }
    }
    candidates.addAll(responsible);
    return new ForeignRequestScope(rights, Set.copyOf(candidates), Set.copyOf(responsible));
  }

  /**
   * The judgement of one actor under one pin.
   *
   * @param actor the actor
   * @param pin the active org unit, or {@code null}
   * @return the judgement
   */
  @NotNull
  private BankAccessJudgement judgement(@NotNull BankActor actor, @Nullable UUID pin) {
    return new BankAccessJudgement(
        actor, pin, staffRights(actor), bereichRepository, orgUnitRepository);
  }

  /**
   * The KRT employee ceiling {@code T1}; unset counts as 0.
   *
   * @param account the KRT account
   * @return the ceiling
   */
  @NotNull
  private static BigDecimal employeeCeiling(@NotNull BankAccount account) {
    BigDecimal t1 = account.getEmployeeApprovalCeiling();
    return t1 == null ? BigDecimal.ZERO : t1;
  }

  /**
   * One viewable account with its entity.
   *
   * @param account the account
   * @param access the actor's access to it
   */
  record ViewableAccount(@NotNull BankAccount account, @NotNull BankAccountAccess.Visible access) {}

  /**
   * The foreign requests an actor reads (REQ-BANK-046).
   *
   * @param rights the actor's staff rights
   * @param candidateAccountIds the accounts whose requests are read
   * @param responsibleAccountIds the accounts the actor is responsible holder of
   */
  record ForeignRequestScope(
      @NotNull BankStaffRights rights,
      @NotNull @Unmodifiable Set<UUID> candidateAccountIds,
      @NotNull @Unmodifiable Set<UUID> responsibleAccountIds) {

    /**
     * Whether a request on a candidate account shows: its band approver or, for any other request,
     * the account's responsible holder; an administrator sees all.
     *
     * @param approver the request's approver class, or {@code null}
     * @param accountId the request's account
     * @return {@code true} iff the actor reads the request
     */
    boolean shows(@Nullable BankRequestApprover approver, @NotNull UUID accountId) {
      return rights.mayApprove(approver, responsibleAccountIds.contains(accountId));
    }
  }
}
