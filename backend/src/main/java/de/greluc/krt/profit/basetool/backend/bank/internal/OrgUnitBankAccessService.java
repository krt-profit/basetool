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

import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.BereichRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Department;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single org-unit-aware seam between the bank and the org-unit oversight scope (ADR-0020): it
 * authorizes against {@link OwnerScopeService} and then reuses the org-unit-blind bank code ({@link
 * BankAccountService}, {@link BankStatementReportService}).
 *
 * <p>Owns every org-unit bank access decision — who views, holds, configures or is exempt — and
 * serves the read side of the org-unit bank page: the account cards and the read-only drill-in.
 * {@link OrgUnitBankSettingsService} and {@link OrgUnitBankRequestService} serve the settings and
 * the booking requests and ask this class for every decision. Visibility is decided by {@link
 * #canView}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrgUnitBankAccessService {

  /**
   * Read-only capabilities handed to org-unit viewers — no deposit/withdraw/transfer (management).
   */
  private static final BankCapabilitiesDto READ_ONLY_CAPABILITIES =
      new BankCapabilitiesDto(false, false, false, false);

  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelperService;
  private final BankAccountRepository bankAccountRepository;
  private final BankPostingRepository bankPostingRepository;
  private final BankAccountViewGrantRepository viewGrantRepository;
  private final BankAccountApprovalLimitRepository approvalLimitRepository;
  private final BereichRepository bereichRepository;
  private final BankAccountService bankAccountService;
  private final BankStatementReportService bankStatementReportService;
  private final OrgUnitRepository orgUnitRepository;

  /**
   * Lists every active account the caller may view, with balance, 30-day trend, balance target and
   * settings affordance (REQ-BANK-021).
   *
   * @return the visible account balances ordered by account number; empty when none
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<OrgUnitBankBalanceDto> listOverseenOrgUnitBalances() {
    List<BankAccount> visible = visibleActiveAccounts(ownerScopeService.currentOversightScope());
    if (visible.isEmpty()) {
      return List.of();
    }
    BankAccountFigures figures = BankAccountFigures.load(bankPostingRepository, visible);
    Map<UUID, List<BankAccountApprovalLimit>> limitsByAccount =
        approvalLimitRepository
            .findByAccountIdIn(visible.stream().map(BankAccount::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(limit -> limit.getAccount().getId()));
    return visible.stream()
        .map(account -> {
          BigDecimal balance = figures.balanceOf(account.getId());
          List<BankPostingSlice> slices = figures.slicesOf(account.getId());
          BigDecimal delta = BankTrendCalculator.windowDelta(slices);
          boolean canRequest = isRequestCapable(account);
          boolean approvalExempt = canRequest && isApprovalExempt(account);
          BigDecimal approvalLimit =
              canRequest && !approvalExempt
                  ? resolveApplicableLimit(
                      account, limitsByAccount.getOrDefault(account.getId(), List.of()))
                  : null;
          return toDto(
              account,
              balance,
              canRequest,
              delta,
              BankTrendCalculator.sparkline(balance, delta, slices),
              canManageSettings(account),
              approvalLimit,
              approvalExempt);
        })
        .toList();
  }

  /**
   * Lists every active account the caller may view on the org-unit bank page over their whole
   * reach, ignoring the active-org-unit pin (REQ-BANK-064).
   *
   * @return the visible active accounts ordered by account number; empty when none
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<BankAccount> listViewableActiveAccounts() {
    return visibleActiveAccounts(ownerScopeService.currentOversightScopeIgnoringPin());
  }

  /**
   * The org units of the caller's active context (REQ-BANK-064): the pinned unit, or every unit the
   * caller is a direct member of when none is pinned.
   *
   * @return the active-context org-unit ids; empty for a caller without memberships and pin
   */
  @NotNull
  public Set<UUID> activeContextOrgUnitIds() {
    UUID pinned = ownerScopeService.currentScopePredicate().activeOrgUnitId();
    return pinned != null ? Set.of(pinned) : ownerScopeService.currentDirectMembershipOrgUnitIds();
  }

  /**
   * The active accounts the caller may view under the given oversight scope; an admin views all.
   *
   * @param viewScope the caller's cascading oversight scope
   * @return the visible active accounts ordered by account number; empty when none
   */
  @NotNull
  private List<BankAccount> visibleActiveAccounts(@NotNull ScopePredicate viewScope) {
    boolean admin = authHelperService.isAdmin();
    List<BankAccount> active =
        bankAccountRepository.findAllByOrderByAccountNoAsc().stream()
            .filter(account -> account.getStatus() == BankAccountStatus.ACTIVE)
            .toList();
    if (active.isEmpty() || admin) {
      return active;
    }
    Map<UUID, List<BankAccountViewGrant>> grantsByAccount =
        viewGrantRepository
            .findByAccountIdIn(active.stream().map(BankAccount::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(grant -> grant.getAccount().getId()));
    return active.stream()
        .filter(account ->
            canViewInternal(
                account, viewScope, grantsByAccount.getOrDefault(account.getId(), List.of())))
        .toList();
  }

  /**
   * Returns the read-only detail of an account the caller may view (REQ-BANK-038), with all
   * bank-staff capabilities off.
   *
   * @param accountId the account to open
   * @return the read-only detail
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not view the account
   */
  @NotNull
  @Transactional(readOnly = true)
  public OrgUnitBankAccountDetailDto getViewableAccountDetail(@NotNull UUID accountId) {
    BankAccount account = requireViewableAccount(accountId);
    BankAccountDetailDto detail =
        bankAccountService.getAccountDetail(accountId, READ_ONLY_CAPABILITIES);
    boolean canRequest = isRequestCapable(account);
    boolean approvalExempt = canRequest && isApprovalExempt(account);
    BigDecimal applicableLimit =
        canRequest && !approvalExempt ? resolveApplicableLimit(account) : null;
    return new OrgUnitBankAccountDetailDto(
        detail,
        true,
        canSetTarget(account),
        canConfigureVisibility(account),
        canRequest,
        canConfigureApprovalLimits(account),
        applicableLimit,
        approvalExempt);
  }

  /**
   * Returns one page of an account's booking history with the holder handles redacted
   * (REQ-BANK-038).
   *
   * @param accountId the account to read
   * @param pageable page, size and whitelisted sort
   * @param from inclusive lower bound on the booking instant, or {@code null}
   * @param to inclusive upper bound on the booking instant, or {@code null}
   * @return one page of redacted booking rows
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not view the account
   */
  @NotNull
  @Transactional(readOnly = true)
  public Page<BankBookingDto> getViewableAccountBookings(
      @NotNull UUID accountId,
      @NotNull Pageable pageable,
      @Nullable Instant from,
      @Nullable Instant to) {
    requireViewableAccount(accountId);
    return bankAccountService
        .getBookings(accountId, pageable, from, to)
        .map(OrgUnitBankAccessService::redact);
  }

  /**
   * Returns the balance-over-time series of an account the caller may view (REQ-BANK-049).
   *
   * @param accountId the account
   * @param from inclusive period start
   * @param to inclusive period end
   * @return the balance series and the account's balance target
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not view the account
   */
  @NotNull
  @Transactional(readOnly = true)
  public BankBalanceSeriesDto getViewableBalanceSeries(
      @NotNull UUID accountId, @NotNull Instant from, @NotNull Instant to) {
    requireViewableAccount(accountId);
    return bankAccountService.getBalanceSeries(accountId, from, to);
  }

  /**
   * Generates the holder-redacted statement PDF for an account the caller may view (REQ-BANK-038)
   * and records the {@code STATEMENT_EXPORTED} audit event.
   *
   * @param accountId the account
   * @param from inclusive period start
   * @param to inclusive period end
   * @param userZone the zone to render timestamps in, or {@code null} for UTC
   * @return the redacted PDF bytes
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not view the account
   */
  @NotNull
  @Transactional
  public byte[] exportViewableStatement(
      @NotNull UUID accountId,
      @NotNull Instant from,
      @NotNull Instant to,
      @Nullable ZoneId userZone) {
    requireViewableAccount(accountId);
    return bankStatementReportService.generateStatement(accountId, from, to, userZone, true);
  }

  /**
   * Whether the caller reaches the {@code BANK_MANAGEMENT} role, the middle-band KRT approver
   * (REQ-BANK-047).
   *
   * @return whether the caller is or outranks the Bankleitung
   */
  public boolean hasBankManagement() {
    return authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT));
  }

  /**
   * Whether the caller is a member of the Organisationsleitung, the top-band KRT approver
   * (REQ-BANK-047).
   *
   * @return whether the caller is an OL member
   */
  public boolean callerIsOlMember() {
    return ownerScopeService.currentUserIsOlMember();
  }

  /**
   * Whether the caller may view the account; loads its view grants on demand.
   *
   * @param account the account to test
   * @return {@code true} iff the caller may view it
   */
  @Transactional(readOnly = true)
  public boolean canView(@NotNull BankAccount account) {
    if (authHelperService.isAdmin()) {
      return true;
    }
    return canViewInternal(
        account,
        ownerScopeService.currentOversightScope(),
        viewGrantRepository.findByAccountId(account.getId()));
  }

  /**
   * Non-admin view decision for one account from the caller's oversight scope and the account's
   * view grants.
   *
   * @param account the account
   * @param viewScope the caller's cascading oversight scope
   * @param grants the account's view grants
   * @return {@code true} iff the caller may view the account
   */
  private boolean canViewInternal(
      @NotNull BankAccount account,
      @NotNull ScopePredicate viewScope,
      @NotNull List<BankAccountViewGrant> grants) {
    UUID owner = owningOrgUnitId(account);
    return switch (account.getType()) {
      case ORG_UNIT, AREA ->
          owner != null && (viewScope.permits(owner) || matchesOrgUnitGrant(owner, grants));
      case CARTEL ->
          (owner != null && viewScope.permits(owner)) || authHelperService.isMemberOrAbove();
      case CARTEL_BANK -> isProfitBereichsleiter();
      case SPECIAL -> currentUserCanAutoViewSpecial() || matchesSpecialGrant(grants);
    };
  }

  /**
   * Whether a view grant of an org-unit account admits the caller: a role bucket held on the owning
   * unit, all-members as a member of it, or an individual grant.
   *
   * @param owningOrgUnitId the account's owning org unit id
   * @param grants the account's view grants
   * @return {@code true} iff a grant admits the caller
   */
  private boolean matchesOrgUnitGrant(
      @NotNull UUID owningOrgUnitId, @NotNull List<BankAccountViewGrant> grants) {
    Optional<UUID> userId = authHelperService.currentUserId();
    for (BankAccountViewGrant grant : grants) {
      boolean match =
          switch (grant.getGranteeKind()) {
            case MEMBERSHIP_ROLE -> {
              MembershipRole role = parseMembershipRole(grant.getRoleCode());
              yield role != null
                  && ownerScopeService.currentUserHoldsRoleOnOrgUnit(owningOrgUnitId, role);
            }
            case ALL_MEMBERS -> ownerScopeService.currentUserIsMemberOfOrgUnit(owningOrgUnitId);
            case AREA_MEMBERS -> callerIsMemberOfAreaCascade(owningOrgUnitId);
            case USER -> userId.filter(id -> id.equals(grant.getGranteeUserId())).isPresent();
          };
      if (match) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a view grant of a {@code SPECIAL} account admits the caller: all-members or an
   * individual grant.
   *
   * @param grants the account's view grants
   * @return {@code true} iff a grant admits the caller
   */
  private boolean matchesSpecialGrant(@NotNull List<BankAccountViewGrant> grants) {
    Optional<UUID> userId = authHelperService.currentUserId();
    for (BankAccountViewGrant grant : grants) {
      boolean match =
          switch (grant.getGranteeKind()) {
            case ALL_MEMBERS -> authHelperService.isMemberOrAbove();
            case USER -> userId.filter(id -> id.equals(grant.getGranteeUserId())).isPresent();
            case MEMBERSHIP_ROLE, AREA_MEMBERS -> false;
          };
      if (match) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code true} iff the caller auto-views Sonderkonten (REQ-BANK-037): an OL member or any
   * Bereichsleiter. Admins are handled separately by the callers.
   *
   * @return {@code true} iff the caller is an OL member or a Bereichsleiter
   */
  private boolean currentUserCanAutoViewSpecial() {
    return ownerScopeService.currentUserIsOlMember()
        || ownerScopeService.currentUserIsBereichsleiter();
  }

  /**
   * Whether the caller is the {@code BEREICHSLEITER} of a {@code Department.PROFIT} Bereich, the
   * responsible holder of the {@code CARTEL_BANK} account (REQ-BANK-037).
   *
   * @return {@code true} iff the caller leads a PROFIT Bereich
   */
  private boolean isProfitBereichsleiter() {
    Set<UUID> profitBereichIds =
        bereichRepository.findByDepartment(Department.PROFIT).stream()
            .map(Bereich::getId)
            .collect(Collectors.toSet());
    return profitBereichIds.stream()
        .anyMatch(id ->
            ownerScopeService.currentUserHoldsRoleOnOrgUnit(id, MembershipRole.BEREICHSLEITER));
  }

  /**
   * Whether the caller is the account's derived responsible holder (REQ-BANK-034); Sonderkonten
   * have none.
   *
   * @param account the account
   * @return {@code true} iff the caller is its responsible holder
   */
  public boolean isResponsibleHolder(@NotNull BankAccount account) {
    UUID owner = owningOrgUnitId(account);
    return switch (account.getType()) {
      case ORG_UNIT -> {
        if (owner == null) {
          yield false;
        }
        MembershipRole holderRole =
            Objects.requireNonNull(account.getOrgUnit()).getKind() == OrgUnitKind.SPECIAL_COMMAND
                ? MembershipRole.SK_LEAD
                : MembershipRole.STAFFELLEITER;
        yield ownerScopeService.currentUserHoldsRoleOnOrgUnit(owner, holderRole);
      }
      case AREA ->
          owner != null
              && ownerScopeService.currentUserHoldsRoleOnOrgUnit(
                  owner, MembershipRole.BEREICHSLEITER);
      case CARTEL -> ownerScopeService.currentUserIsOlMember();
      case CARTEL_BANK -> isProfitBereichsleiter();
      case SPECIAL -> false;
    };
  }

  /**
   * Whether the caller, as the account's responsible holder, is exempt from every approval gate
   * including the KRT ladder (REQ-BANK-041).
   *
   * @param account the account a request would debit
   * @return whether the caller may debit it without any approver's sign-off
   */
  public boolean isApprovalExempt(@NotNull BankAccount account) {
    return isResponsibleHolder(account);
  }

  /**
   * Whether the caller may configure the account's view grants (REQ-BANK-035): the responsible
   * holder of an org-unit account, or an OL member or bank management for a Sonderkonto.
   *
   * @param account the account
   * @return {@code true} iff the caller may add or remove view grants
   */
  public boolean canConfigureVisibility(@NotNull BankAccount account) {
    if (authHelperService.isAdmin()) {
      return visibilityConfigurable(account.getType());
    }
    return switch (account.getType()) {
      case ORG_UNIT, AREA -> isResponsibleHolder(account);
      case SPECIAL ->
          ownerScopeService.currentUserIsOlMember()
              || authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT));
      case CARTEL, CARTEL_BANK -> false;
    };
  }

  /**
   * {@code true} iff the caller may set/clear the account's balance target from the org-unit side
   * (REQ-BANK-036): the responsible holder. Sonderkonto targets are bank-staff-only.
   *
   * @param account the account
   * @return {@code true} iff the caller may set the target
   */
  public boolean canSetTarget(@NotNull BankAccount account) {
    if (authHelperService.isAdmin()) {
      return true;
    }
    return switch (account.getType()) {
      case ORG_UNIT, AREA, CARTEL, CARTEL_BANK -> isResponsibleHolder(account);
      case SPECIAL -> false;
    };
  }

  /**
   * {@code true} iff the caller may open the account's settings panel at all (set the target and/or
   * configure visibility). Drives the per-card settings affordance.
   *
   * @param account the account
   * @return {@code true} iff the caller may manage the account's settings
   */
  private boolean canManageSettings(@NotNull BankAccount account) {
    return canSetTarget(account) || canConfigureVisibility(account);
  }

  /**
   * Whether the account accepts booking requests: {@code ACTIVE} and of type {@code ORG_UNIT},
   * {@code AREA} or {@code CARTEL}.
   *
   * @param account the account
   * @return whether a booking request may be raised against it
   */
  public boolean isRequestCapable(@NotNull BankAccount account) {
    return account.getStatus() == BankAccountStatus.ACTIVE
        && BankApprovalLimitService.configurable(account.getType());
  }

  /**
   * Whether the caller may set or clear the account's approval limits (REQ-BANK-041): its
   * responsible holder, bank management or an admin.
   *
   * @param account the account
   * @return whether the caller may configure approval limits
   */
  public boolean canConfigureApprovalLimits(@NotNull BankAccount account) {
    if (!BankApprovalLimitService.audienceLimitsSupported(account.getType())) {
      return false;
    }
    return authHelperService.isAdmin()
        || authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT))
        || isResponsibleHolder(account);
  }

  /**
   * Asserts the caller may configure the account's approval limits.
   *
   * @param account the account
   * @throws AccessDeniedException when the caller may not configure approval limits
   */
  public void requireCanConfigureApprovalLimits(@NotNull BankAccount account) {
    if (!canConfigureApprovalLimits(account)) {
      throw new AccessDeniedException(
          "The caller may not configure this account's approval limits");
    }
  }

  /**
   * Resolves the current caller's applicable approval limit for an account (REQ-BANK-041), loading
   * the account's limit rows on demand. See {@link #resolveApplicableLimit(BankAccount, List)}.
   *
   * @param account the account
   * @return the caller's limit, or {@code null} = unlimited (no approval needed)
   */
  @Nullable
  public BigDecimal resolveApplicableLimit(@NotNull BankAccount account) {
    return resolveApplicableLimit(
        account, approvalLimitRepository.findByAccountId(account.getId()));
  }

  /**
   * Resolves the caller's applicable approval limit (REQ-BANK-041, REQ-BANK-047).
   *
   * <p>For the KRT account it is the employee ceiling {@code T1}. Otherwise an individual limit
   * wins; else the maximum of every matching membership tier, where the all-members tier matches
   * only members of the owning unit. {@code null} means no tier matched, so approval is required.
   *
   * @param account the account
   * @param limits the account's approval-limit rows; may be empty
   * @return the caller's limit, or {@code null} when no tier matched
   */
  @Nullable
  private BigDecimal resolveApplicableLimit(
      @NotNull BankAccount account, @NotNull List<BankAccountApprovalLimit> limits) {
    if (account.getType() == BankAccountType.CARTEL) {
      return account.getEmployeeApprovalCeiling();
    }
    if (limits.isEmpty()) {
      return null;
    }
    Optional<UUID> userId = authHelperService.currentUserId();
    for (BankAccountApprovalLimit limit : limits) {
      if (limit.getGranteeKind() == BankAccountViewGranteeKind.USER
          && userId.filter(id -> id.equals(limit.getGranteeUserId())).isPresent()) {
        return limit.getLimitAmount();
      }
    }
    UUID owner = owningOrgUnitId(account);
    BigDecimal best = null;
    for (BankAccountApprovalLimit limit : limits) {
      boolean matches =
          switch (limit.getGranteeKind()) {
            case MEMBERSHIP_ROLE -> {
              MembershipRole role = parseMembershipRole(limit.getRoleCode());
              yield owner != null
                  && role != null
                  && ownerScopeService.currentUserHoldsRoleOnOrgUnit(owner, role);
            }
            case AREA_MEMBERS -> owner != null && callerIsMemberOfAreaCascade(owner);
            case ALL_MEMBERS ->
                owner != null && ownerScopeService.currentUserIsMemberOfOrgUnit(owner);
            case USER -> false;
          };
      if (matches) {
        best = best == null ? limit.getLimitAmount() : best.max(limit.getLimitAmount());
      }
    }
    return best;
  }

  /**
   * {@code true} iff the account type supports configurable visibility at all (ORG_UNIT / AREA /
   * SPECIAL); {@code CARTEL} (fixed all-members) and {@code CARTEL_BANK} (internal) do not.
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
   * Loads an account and asserts the caller may view it.
   *
   * @param accountId the account id
   * @return the account entity
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not view it
   */
  @NotNull
  private BankAccount requireViewableAccount(@NotNull UUID accountId) {
    BankAccount account = requireAccount(accountId);
    if (!canView(account)) {
      throw new AccessDeniedException("The caller may not view this account");
    }
    return account;
  }

  /**
   * Loads an account or fails with 404.
   *
   * @param accountId the account id
   * @return the account entity
   * @throws NotFoundException when the account does not exist
   */
  @NotNull
  public BankAccount requireAccount(@NotNull UUID accountId) {
    return Entities.require(bankAccountRepository.findById(accountId), "Bank account not found");
  }

  /**
   * Asserts the caller may set the account's balance target.
   *
   * @param account the account
   * @throws AccessDeniedException when the caller may not set the target
   */
  public void requireCanSetTarget(@NotNull BankAccount account) {
    if (!canSetTarget(account)) {
      throw new AccessDeniedException("The caller may not set this account's balance target");
    }
  }

  /**
   * Asserts the caller may configure the account's visibility.
   *
   * @param account the account
   * @throws AccessDeniedException when the caller may not configure visibility
   */
  public void requireCanConfigureVisibility(@NotNull BankAccount account) {
    if (!canConfigureVisibility(account)) {
      throw new AccessDeniedException("The caller may not configure this account's visibility");
    }
  }

  /**
   * The owning org-unit id of an account, or {@code null} when it has none.
   *
   * @param account the account
   * @return the owning org-unit id, or {@code null}
   */
  @Nullable
  private static UUID owningOrgUnitId(@NotNull BankAccount account) {
    return account.getOrgUnit() == null ? null : account.getOrgUnit().getId();
  }

  /**
   * Parses a {@code MembershipRole} name, returning {@code null} for an unknown code.
   *
   * @param code the role code
   * @return the role, or {@code null}
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
   * Redacts a booking row for an org-unit viewer (REQ-BANK-038): nulls the holder and
   * counter-holder handles and the internal staff note; the counterparty stays visible.
   *
   * @param booking the bank-staff booking row
   * @return a redacted copy
   */
  @NotNull
  private static BankBookingDto redact(@NotNull BankBookingDto booking) {
    return new BankBookingDto(
        booking.postingId(),
        booking.transactionId(),
        booking.type(),
        booking.amount(),
        null,
        booking.note(),
        booking.justification(),
        null,
        booking.createdAt(),
        booking.reversedTransactionId(),
        booking.counterAccountNo(),
        booking.counterAccountName(),
        null,
        booking.intraAccount(),
        booking.transferFee(),
        booking.counterpartyHandle(),
        booking.counterpartyOrgUnitName());
  }

  /**
   * Projects a visible account and its resolved figures into the balance-card DTO.
   *
   * @param account the visible account
   * @param balance the resolved balance; zero without postings
   * @param canRequest {@code true} iff this is the caller's own-level account
   * @param delta30d the signed 30-day net change
   * @param sparkline the 30 end-of-day balances, oldest first
   * @param canManageSettings whether the caller may open the account's settings
   * @param approvalLimit the caller's approval limit (REQ-BANK-041), or {@code null} when none
   *     applies
   * @param approvalExempt whether the caller is the responsible holder and bound by no ceiling
   * @return the balance-card DTO
   */
  @NotNull
  private OrgUnitBankBalanceDto toDto(
      @NotNull BankAccount account,
      @NotNull BigDecimal balance,
      boolean canRequest,
      @NotNull BigDecimal delta30d,
      @NotNull List<BigDecimal> sparkline,
      boolean canManageSettings,
      @Nullable BigDecimal approvalLimit,
      boolean approvalExempt) {
    OrgUnit orgUnit = account.getOrgUnit();
    return new OrgUnitBankBalanceDto(
        account.getId(),
        account.getAccountNo(),
        account.getName(),
        account.getStatus(),
        account.getType(),
        orgUnit == null ? null : orgUnit.getId(),
        orgUnit == null ? null : orgUnit.getName(),
        orgUnit == null ? null : orgUnit.getShorthand(),
        orgUnit == null ? null : orgUnit.getKind(),
        balance,
        canRequest,
        delta30d,
        sparkline,
        account.getBalanceTarget(),
        canManageSettings,
        approvalLimit,
        approvalExempt);
  }

  /**
   * Whether the caller is a member of the given Bereich or of any of its child units, the {@code
   * AREA_MEMBERS} grant and approval-limit tier (REQ-BANK-048).
   *
   * @param bereichId the owning Bereich org unit
   * @return {@code true} iff the caller has any direct membership on the Bereich or a child unit
   */
  boolean callerIsMemberOfAreaCascade(@NotNull UUID bereichId) {
    List<UUID> childIds = orgUnitRepository.findChildOrgUnitIds(bereichId);
    return isMemberOfAreaCascade(
        bereichId, ownerScopeService.currentDirectMembershipOrgUnitIds(), childIds);
  }

  /**
   * Decides the {@code AREA_MEMBERS} cascade from the caller's direct memberships and the Bereich's
   * child units.
   *
   * @param bereichId the owning Bereich org unit
   * @param directMemberships the org units the caller is a direct member of
   * @param childIds the Bereich's direct child org units
   * @return {@code true} iff a direct membership is the Bereich or one of its children
   */
  static boolean isMemberOfAreaCascade(
      @NotNull UUID bereichId, @NotNull Set<UUID> directMemberships, @NotNull List<UUID> childIds) {
    return directMemberships.stream().anyMatch(ou -> ou.equals(bereichId) || childIds.contains(ou));
  }
}
