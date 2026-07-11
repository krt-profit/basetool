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
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountApprovalLimit;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGrant;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGranteeKind;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.Department;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAccountDetailDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAccountRefDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankApprovalLimitsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankCapabilitiesDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankAccountDetailDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankAccountSettingsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankBalanceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankViewUserDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.projection.BankAccountBalance;
import de.greluc.krt.profit.basetool.backend.model.projection.BankPostingSlice;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountApprovalLimitRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountViewGrantRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BereichRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.util.BankAmounts;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The org-unit-aware bridge between the bank and the org-unit oversight scope (ADR-0020/0028/0043).
 *
 * <p>The bank's own authorization ({@code BankSecurityService}) is, by construction, blind to
 * org-unit membership — it decides visibility solely from the bank roles and {@code
 * bank_account_grant} rows (REQ-BANK-008, ADR-0011), and the {@code
 * bankClassesMustNotConsultOrgUnitScope} ArchUnit rule pins that by forbidding every {@code
 * Bank*}-named class from depending on {@link OwnerScopeService}. The org-unit features need
 * exactly the opposite input: who oversees / is responsible for which org unit. This class is the
 * single, deliberately non-{@code Bank*}-named seam that carries that org-unit logic; it authorizes
 * here and then reuses the bank's own org-unit-blind read/PDF code ({@link BankAccountService},
 * {@link BankStatementReportService}), so the bank stays 100% org-unit-blind.
 *
 * <p>The org-unit authorization brain (who may view / configure / is responsible for an account)
 * stays in this bridge, because it couples {@link OwnerScopeService} to the bank-account repository
 * and only this class may (ADR-0020, {@code orgUnitAwareBankSeamIsContainedToOneClass}). The L3
 * split (#922) extracted the two write-mechanics slabs it orchestrates — the view-grant
 * grant/revoke ({@link OrgUnitBankVisibilityService}, incl. the shared {@code createViewGrant}
 * factory) and the approval-limit upsert/clear ({@link OrgUnitBankApprovalLimitService}) — into
 * focused collaborators that hold no org-unit scope: each facade settings method still loads +
 * authorizes + validates here, then delegates the persistence + audit to the collaborator and
 * re-reads the settings snapshot.
 *
 * <p>The OwnerScope-free responsible-holder reverse-resolution (who oversees an account, resolved
 * from the outside with no current principal) and its leadership-change audit were split out under
 * audit Thema 7 (#14) into {@link OrgUnitBankResponsibilityService}: those methods wire no {@link
 * OwnerScopeService}, so they legally sit outside this seam without eroding {@code
 * orgUnitAwareBankSeamIsContainedToOneClass} (ADR-0020).
 *
 * <p>It serves four things on the org-unit bank page:
 *
 * <ul>
 *   <li><b>The card list</b> (F1, REQ-BANK-021/-027/-028): the balance — and, since REQ-BANK-036,
 *       the balance target — of every account the caller may view ({@link #canView}).
 *   <li><b>The read-only drill-in</b> (REQ-BANK-038): the account detail + history + a
 *       Halter-redacted Kontoauszug for any account the caller may view; no booking actions.
 *   <li><b>Booking requests</b> (F2): a <em>deposit</em> against any active account by any caller
 *       (REQ-BANK-042); a <em>withdrawal / transfer</em> only against an account the caller may
 *       view (REQ-BANK-039), subject to the per-tier approval limits (REQ-BANK-041).
 *   <li><b>The responsibility settings</b> (REQ-BANK-035/-036): the derived responsible holder
 *       (and, for Sonderkonten, the OL) configures who else may view the account and sets the
 *       balance target.
 * </ul>
 *
 * <p><b>Who may view an account</b> ({@link #canView}) — admins see all; otherwise by type:
 *
 * <ul>
 *   <li>{@code ORG_UNIT}/{@code AREA}: the cascading oversight scope ({@link
 *       OwnerScopeService#currentOversightScope()}) <em>or</em> a holder-configured view grant
 *       (membership-role bucket / all-members / individual user).
 *   <li>{@code CARTEL} (the KRT account): every KRT member (fixed, REQ-BANK-037), plus the OL via
 *       oversight.
 *   <li>{@code CARTEL_BANK}: only its responsible holder — the {@code BEREICHSLEITER} of a {@code
 *       Department.PROFIT} Bereich (bank staff use the bank surface).
 *   <li>{@code SPECIAL} (Sonderkonten, REQ-BANK-037): every OL member and every {@code
 *       BEREICHSLEITER} automatically, plus OL-configured grants (global-role / all-members /
 *       individual user). Bereichskoordinatoren/-operatoren and officers do <em>not</em> see them.
 * </ul>
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

  /**
   * Visibility role buckets a Staffel account's Staffelleiter may toggle (MembershipRole names).
   */
  private static final List<String> SQUADRON_ROLE_BUCKETS =
      List.of(
          MembershipRole.KOMMANDOLEITER.name(),
          MembershipRole.STELLV_KOMMANDOLEITER.name(),
          MembershipRole.ENSIGN.name());

  /**
   * Visibility role buckets a Bereich account's Bereichsleiter may toggle (MembershipRole names).
   */
  private static final List<String> BEREICH_ROLE_BUCKETS =
      List.of(MembershipRole.BEREICHSKOORDINATOR.name(), MembershipRole.BEREICHSOPERATOR.name());

  /**
   * Global-role buckets the OL may toggle for a Sonderkonto (no owning unit ⇒ no membership ranks).
   * Stored without the {@code ROLE_} prefix; evaluated via {@code
   * hasReachableRole(Roles.authority(code))}.
   */
  private static final List<String> SPECIAL_GLOBAL_ROLE_BUCKETS =
      List.of(Roles.OFFICER, Roles.LOGISTICIAN, Roles.MISSION_MANAGER);

  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelperService;
  private final BankAccountRepository bankAccountRepository;
  private final BankPostingRepository bankPostingRepository;
  private final BankAccountViewGrantRepository viewGrantRepository;
  private final BankAccountApprovalLimitRepository approvalLimitRepository;
  private final BankBookingRequestRepository bankBookingRequestRepository;
  private final BereichRepository bereichRepository;
  private final UserRepository userRepository;
  private final BankAccountService bankAccountService;
  private final BankApprovalLimitService bankApprovalLimitService;
  private final BankStatementReportService bankStatementReportService;
  private final BankBookingRequestService bankBookingRequestService;
  private final BankAuditService bankAuditService;
  private final OrgUnitBankVisibilityService orgUnitBankVisibilityService;
  private final OrgUnitBankApprovalLimitService orgUnitBankApprovalLimitService;

  // ---------------------------------------------------------------------------------------------
  // F1 — card list
  // ---------------------------------------------------------------------------------------------

  /**
   * Lists the balance of every active bank account the caller may view on the org-unit bank page
   * (REQ-BANK-021/-027/-028/-035/-037, F1). The visible set is decided by {@link #canView} per
   * account; the view grants of the candidate accounts are loaded in one batched query so the
   * filter adds no per-account N+1 (REQ-DATA-003). Each card also carries the 30-day trend
   * (REQ-BANK-016), the balance target (REQ-BANK-036) and whether the caller may open the account's
   * settings.
   *
   * @return the visible account balances, ordered by account number; never {@code null}, empty when
   *     the caller may view no active account
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<OrgUnitBankBalanceDto> listOverseenOrgUnitBalances() {
    boolean admin = authHelperService.isAdmin();
    ScopePredicate viewScope = ownerScopeService.currentOversightScope();
    List<BankAccount> active =
        bankAccountRepository.findAllByOrderByAccountNoAsc().stream()
            .filter(account -> account.getStatus() == BankAccountStatus.ACTIVE)
            .toList();
    if (active.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<BankAccountViewGrant>> grantsByAccount =
        viewGrantRepository
            .findByAccountIdIn(active.stream().map(BankAccount::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(grant -> grant.getAccount().getId()));
    List<BankAccount> visible =
        active.stream()
            .filter(
                account ->
                    admin
                        || canViewInternal(
                            account,
                            viewScope,
                            grantsByAccount.getOrDefault(account.getId(), List.of())))
            .toList();
    if (visible.isEmpty()) {
      return List.of();
    }
    Map<UUID, BigDecimal> balances = balancesByAccountId(visible);
    Map<UUID, List<BankPostingSlice>> slicesByAccount = slicesByAccountId(visible);
    Map<UUID, List<BankAccountApprovalLimit>> limitsByAccount =
        approvalLimitRepository
            .findByAccountIdIn(visible.stream().map(BankAccount::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(limit -> limit.getAccount().getId()));
    return visible.stream()
        .map(
            account -> {
              BigDecimal balance = balances.getOrDefault(account.getId(), BigDecimal.ZERO);
              List<BankPostingSlice> slices =
                  slicesByAccount.getOrDefault(account.getId(), List.of());
              BigDecimal delta = BankTrendCalculator.windowDelta(slices);
              // REQ-BANK-039: eligibility = view eligibility — every viewable, request-capable
              // account the caller can already see is requestable.
              boolean canRequest = isRequestCapable(account);
              BigDecimal approvalLimit =
                  canRequest
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
                  approvalLimit);
            })
        .toList();
  }

  // ---------------------------------------------------------------------------------------------
  // Read-only drill-in (REQ-BANK-038)
  // ---------------------------------------------------------------------------------------------

  /**
   * Returns the read-only account detail for an account the caller may view (REQ-BANK-038). Reuses
   * the bank-staff detail aggregate ({@link BankAccountService#getAccountDetail}) but with
   * all-false capabilities, and adds the org-unit-side affordances (export statement, manage
   * settings, raise a request).
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
    // REQ-BANK-039: any viewer of a request-capable account may request against it.
    boolean canRequest = isRequestCapable(account);
    BigDecimal applicableLimit = canRequest ? resolveApplicableLimit(account) : null;
    return new OrgUnitBankAccountDetailDto(
        detail,
        true,
        canSetTarget(account),
        canConfigureVisibility(account),
        canRequest,
        canConfigureApprovalLimits(account),
        applicableLimit);
  }

  /**
   * Returns one page of an account's booking history for an org-unit viewer (REQ-BANK-038), with
   * the player-custody ("Halter") columns <b>redacted</b> — {@code holderHandle} and {@code
   * counterHolderHandle} are nulled so custody never crosses the wire to org-unit viewers.
   *
   * @param accountId the account to read
   * @param pageable page, size and whitelisted sort
   * @param from inclusive lower bound on the booking instant, or {@code null} for no lower bound
   * @param to inclusive upper bound on the booking instant, or {@code null} for no upper bound
   * @return one page of redacted booking rows inside the (optionally bounded) period
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
   * Returns the balance-over-time series of an account an org-unit viewer may see (REQ-BANK-049),
   * for the detail page's balance chart. The org-unit view authorization is enforced here; the
   * series itself is pure balance math with no holder/counterparty data, so nothing is redacted —
   * every viewer who may see the account (and thus its current balance in the facts strip) may see
   * how that balance moved over time.
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
   * Generates the Halter-redacted account statement PDF for an org-unit viewer (REQ-BANK-038). The
   * org-unit authorization is enforced here; the generation reuses {@link
   * BankStatementReportService#generateStatement(UUID, Instant, Instant, ZoneId, boolean)} with
   * redaction on, which also records the {@code STATEMENT_EXPORTED} audit event for the org-unit
   * actor.
   *
   * @param accountId the account
   * @param from period start (inclusive)
   * @param to period end (inclusive)
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

  // ---------------------------------------------------------------------------------------------
  // Settings — balance target + configurable visibility (REQ-BANK-035/-036)
  // ---------------------------------------------------------------------------------------------

  /**
   * Returns the responsibility settings of one account for its holder/OL settings panel
   * (REQ-BANK-035/-036): the current balance target, the configurable role/all-members/user grants,
   * and which controls the caller may use.
   *
   * @param accountId the account
   * @return the settings snapshot
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may neither set the target nor configure
   *     visibility
   */
  @NotNull
  @Transactional(readOnly = true)
  public OrgUnitBankAccountSettingsDto getAccountSettings(@NotNull UUID accountId) {
    BankAccount account = requireAccount(accountId);
    if (!canSetTarget(account) && !canConfigureVisibility(account)) {
      throw new AccessDeniedException("The caller may not manage this account's settings");
    }
    return toSettingsDto(account);
  }

  /**
   * Sets, changes or clears an account's balance target (REQ-BANK-036). Only the responsible holder
   * may do this from the org-unit side; bank staff use the bank surface. A {@code null} target
   * clears the goal; a present target is a positive whole amount — enforced at the API boundary by
   * the {@code @Valid} {@code OrgUnitBalanceTargetRequest}'s
   * {@code @DecimalMin("1")}/{@code @WholeNumber} constraints (same gate the bank-surface path
   * relies on), so the service trusts the validated input rather than re-checking it. {@code
   * saveAndFlush} writes back the bumped {@code @Version} within the transaction so the form can
   * echo it next time (REQ-FE-003).
   *
   * @param accountId the account
   * @param target the new target, or {@code null} to clear it
   * @param version the echoed optimistic-locking version
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not set the target
   * @throws ObjectOptimisticLockingFailureException on a version mismatch (409)
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto setBalanceTarget(
      @NotNull UUID accountId, @Nullable BigDecimal target, long version) {
    BankAccount account = requireAccount(accountId);
    requireCanSetTarget(account);
    requireVersionMatch(account, version);
    account.setBalanceTarget(target);
    bankAccountRepository.saveAndFlush(account);
    bankAuditService.record(
        target == null
            ? BankAuditEventType.BALANCE_TARGET_CLEARED
            : BankAuditEventType.BALANCE_TARGET_SET,
        accountId,
        null,
        null,
        target == null ? null : "target=" + BankAmounts.plain(target));
    return toSettingsDto(account);
  }

  /**
   * Adds a role-bucket view grant to an account (REQ-BANK-035). The bucket kind is derived from the
   * account type — a {@code MembershipRole} on the owning unit for org-unit accounts, a global role
   * code for a Sonderkonto — and the role code is validated against the buckets that apply to this
   * account. Idempotent: granting an already-granted bucket is a no-op.
   *
   * @param accountId the account
   * @param roleCode the role bucket to grant (a {@code MembershipRole} name or a global role code)
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure visibility
   * @throws BadRequestException when the role code is not a valid bucket for this account
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto addRoleVisibility(
      @NotNull UUID accountId, @NotNull String roleCode) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureVisibility(account);
    BankAccountViewGranteeKind kind = requireValidRoleBucket(account, roleCode);
    orgUnitBankVisibilityService.grantRole(account, kind, roleCode);
    return toSettingsDto(account);
  }

  /**
   * Removes a role-bucket view grant from an account (REQ-BANK-035).
   *
   * @param accountId the account
   * @param roleCode the role bucket to revoke
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure visibility
   * @throws BadRequestException when the role code is not a valid bucket for this account
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto removeRoleVisibility(
      @NotNull UUID accountId, @NotNull String roleCode) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureVisibility(account);
    BankAccountViewGranteeKind kind = requireValidRoleBucket(account, roleCode);
    orgUnitBankVisibilityService.revokeRole(account, kind, roleCode);
    return toSettingsDto(account);
  }

  /**
   * Enables or disables the all-members view grant of an account (REQ-BANK-035): every member of
   * the owning unit (org-unit accounts) or every KRT member (Sonderkonten) may view it. Idempotent.
   *
   * @param accountId the account
   * @param enabled whether all members may view the account
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure visibility
   * @throws BadRequestException when the account type has no all-members bucket
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto setAllMembersVisibility(
      @NotNull UUID accountId, boolean enabled) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureVisibility(account);
    if (!allMembersSupported(account.getType())) {
      throw new BadRequestException("This account type has no all-members visibility bucket");
    }
    orgUnitBankVisibilityService.setAllMembers(account, enabled);
    return toSettingsDto(account);
  }

  /**
   * Grants an individual user view access to an account (REQ-BANK-035). Idempotent.
   *
   * @param accountId the account
   * @param userId the user to grant
   * @return the refreshed settings
   * @throws NotFoundException when the account or the user does not exist
   * @throws AccessDeniedException when the caller may not configure visibility
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto addUserVisibility(
      @NotNull UUID accountId, @NotNull UUID userId) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureVisibility(account);
    orgUnitBankVisibilityService.grantUser(account, userId);
    return toSettingsDto(account);
  }

  /**
   * Revokes an individual user's view access to an account (REQ-BANK-035).
   *
   * @param accountId the account
   * @param userId the user to revoke
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure visibility
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto removeUserVisibility(
      @NotNull UUID accountId, @NotNull UUID userId) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureVisibility(account);
    orgUnitBankVisibilityService.revokeUser(account, userId);
    return toSettingsDto(account);
  }

  /**
   * Enables or disables the "Mitglieder des Bereichs" cascade view grant of a Bereichskonto
   * (REQ-BANK-048): every member of the whole area cascade — the Bereichsleitung plus every member
   * of the Bereich's child Staffeln/SKs — may view it. Idempotent; only valid for {@code AREA}
   * accounts.
   *
   * @param accountId the account
   * @param enabled whether the whole area cascade may view the account
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure visibility
   * @throws BadRequestException when the account type has no area-members bucket
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto setAreaMembersVisibility(
      @NotNull UUID accountId, boolean enabled) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureVisibility(account);
    if (!areaMembersSupported(account.getType())) {
      throw new BadRequestException("This account type has no area-members visibility bucket");
    }
    orgUnitBankVisibilityService.setAreaMembers(account, enabled);
    return toSettingsDto(account);
  }

  // ---------------------------------------------------------------------------------------------
  // Settings — per-tier approval limits (REQ-BANK-041)
  // ---------------------------------------------------------------------------------------------

  /**
   * Sets or changes a role-bucket approval limit on an account (REQ-BANK-041). The role code is
   * validated against the account's limit buckets (squadron / Bereich sub-ranks); the limit is an
   * idempotent upsert.
   *
   * @param accountId the account
   * @param roleCode the role bucket ({@code MembershipRole} name)
   * @param limit the whole-aUEC ceiling (>= 0)
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   * @throws BadRequestException when the role code is not a valid bucket for this account
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto setRoleApprovalLimit(
      @NotNull UUID accountId, @NotNull String roleCode, @NotNull BigDecimal limit) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    requireValidLimitRoleBucket(account, roleCode);
    orgUnitBankApprovalLimitService.setRole(account, roleCode, limit);
    return toSettingsDto(account);
  }

  /**
   * Removes a role-bucket approval limit from an account (REQ-BANK-041).
   *
   * @param accountId the account
   * @param roleCode the role bucket to clear
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   * @throws BadRequestException when the role code is not a valid bucket for this account
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto clearRoleApprovalLimit(
      @NotNull UUID accountId, @NotNull String roleCode) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    requireValidLimitRoleBucket(account, roleCode);
    orgUnitBankApprovalLimitService.clearRole(account, roleCode);
    return toSettingsDto(account);
  }

  /**
   * Sets or changes the all-members approval limit on an account (REQ-BANK-041): the ceiling for
   * any member who may view the account but matches no more specific tier.
   *
   * @param accountId the account
   * @param limit the whole-aUEC ceiling (>= 0)
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   * @throws BadRequestException when the account type has no all-members tier
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto setAllMembersApprovalLimit(
      @NotNull UUID accountId, @NotNull BigDecimal limit) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    if (!BankApprovalLimitService.allMembersSupported(account.getType())) {
      throw new BadRequestException("This account type has no all-members approval-limit tier");
    }
    orgUnitBankApprovalLimitService.setAllMembers(account, limit);
    return toSettingsDto(account);
  }

  /**
   * Removes the all-members approval limit from an account (REQ-BANK-041).
   *
   * @param accountId the account
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto clearAllMembersApprovalLimit(@NotNull UUID accountId) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    orgUnitBankApprovalLimitService.clearAllMembers(account);
    return toSettingsDto(account);
  }

  /**
   * Sets or changes the "Mitglieder des Bereichs" cascade approval limit on a Bereichskonto
   * (REQ-BANK-048): the ceiling for any member of the whole area cascade (Bereichsleitung + child
   * Staffel/SK members) who matches no more specific tier.
   *
   * @param accountId the account
   * @param limit the whole-aUEC ceiling (>= 0)
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   * @throws BadRequestException when the account type has no area-members tier
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto setAreaMembersApprovalLimit(
      @NotNull UUID accountId, @NotNull BigDecimal limit) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    if (!BankApprovalLimitService.areaMembersSupported(account.getType())) {
      throw new BadRequestException("This account type has no area-members approval-limit tier");
    }
    orgUnitBankApprovalLimitService.setAreaMembers(account, limit);
    return toSettingsDto(account);
  }

  /**
   * Removes the "Mitglieder des Bereichs" cascade approval limit from a Bereichskonto
   * (REQ-BANK-048).
   *
   * @param accountId the account
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto clearAreaMembersApprovalLimit(@NotNull UUID accountId) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    orgUnitBankApprovalLimitService.clearAreaMembers(account);
    return toSettingsDto(account);
  }

  /**
   * Sets or changes an individual user's approval limit on an account (REQ-BANK-041); the most
   * specific tier, overriding any role/all-members limit for that user.
   *
   * @param accountId the account
   * @param userId the user the limit addresses
   * @param limit the whole-aUEC ceiling (>= 0)
   * @return the refreshed settings
   * @throws NotFoundException when the account or the user does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto setUserApprovalLimit(
      @NotNull UUID accountId, @NotNull UUID userId, @NotNull BigDecimal limit) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    orgUnitBankApprovalLimitService.setUser(account, userId, limit);
    return toSettingsDto(account);
  }

  /**
   * Removes an individual user's approval limit from an account (REQ-BANK-041).
   *
   * @param accountId the account
   * @param userId the user whose limit to clear
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto clearUserApprovalLimit(
      @NotNull UUID accountId, @NotNull UUID userId) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    orgUnitBankApprovalLimitService.clearUser(account, userId);
    return toSettingsDto(account);
  }

  // ---------------------------------------------------------------------------------------------
  // F2 — booking requests, "Fremde Anträge" and the responsible-holder in-app approval
  // ---------------------------------------------------------------------------------------------

  /**
   * Raises a confirm-before-post booking request and delegates persistence to the org-unit-blind
   * {@link BankBookingRequestService}. Eligibility is <b>type-dependent</b>:
   *
   * <ul>
   *   <li><b>Deposit (REQ-BANK-042):</b> any authenticated caller may request a deposit against
   *       <em>any active account</em> — every type, regardless of whether the caller may view it —
   *       and a deposit is <em>never</em> subject to an approval limit ({@code
   *       requiresOwnerApproval = false}, {@code applicableLimit = null}). Whoever pays money in
   *       only needs a bank employee to confirm receipt; no {@code canView} / {@code
   *       isRequestCapable} gate and no limit resolution run. The account-active guard lives in
   *       {@link BankBookingRequestService#create}.
   *   <li><b>Withdrawal / transfer (REQ-BANK-039/-040/-041):</b> these debit a balance, so they
   *       stay gated by view eligibility ({@link #canView}), restricted to the request-capable
   *       account types ({@link #isRequestCapable}) and subject to the requester's approval limit
   *       ({@link #resolveApplicableLimit}); the snapshot drives the org-unit-blind confirm path.
   * </ul>
   *
   * @param request the create payload (source account, type, amount, optional destination, note)
   * @return the created pending request
   * @throws AccessDeniedException when a withdrawal/transfer caller may not view the source account
   * @throws BadRequestException when the destination account is missing for a transfer or present
   *     for a deposit/withdrawal, or the account does not accept withdrawals/transfers
   * @throws NotFoundException when the (source) account does not exist
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto createBookingRequest(@NotNull CreateBankBookingRequest request) {
    BankAccount account =
        bankAccountRepository
            .findById(request.sourceAccountId())
            .orElseThrow(() -> new NotFoundException("Bank account not found"));

    if (request.type() == BankBookingRequestType.DEPOSIT) {
      // REQ-BANK-042: a deposit is requestable by ANY authenticated caller against ANY active
      // account (every type) and is NEVER approval-limited — no view gate, no request-capability
      // gate, no limit resolution. The account-active guard is enforced downstream in
      // BankBookingRequestService.create.
      if (request.targetAccountId() != null) {
        throw new BadRequestException("A deposit request must not carry a destination account");
      }
      // REQ-BANK-043: a deposit request may carry a split snapshot (whole-percent distributed
      // across
      // the squadron accounts on confirmation); the DTO already pins split = DEPOSIT-only.
      return bankBookingRequestService.create(
          account.getId(),
          BankBookingRequestType.DEPOSIT,
          request.amount(),
          request.note(),
          // REQ-BANK-045: a deposit never captures a justification.
          null,
          null,
          false,
          null,
          // A deposit is never approval-limited, so it needs no approver (REQ-BANK-042).
          null,
          request.splitEnabled(),
          request.splitPercent());
    }

    // REQ-BANK-039: a withdrawal/transfer stays gated by view eligibility — only a caller who may
    // view the account may debit it.
    if (!canView(account)) {
      throw new AccessDeniedException("The caller may not raise a booking request on this account");
    }
    if (!isRequestCapable(account)) {
      throw new BadRequestException("This account does not accept booking requests");
    }
    // REQ-BANK-040: a transfer names a destination (any active account); a withdrawal must not.
    UUID targetAccountId = null;
    if (request.type() == BankBookingRequestType.TRANSFER) {
      if (request.targetAccountId() == null) {
        throw new BadRequestException("A transfer request requires a destination account");
      }
      targetAccountId = request.targetAccountId();
    } else if (request.targetAccountId() != null) {
      throw new BadRequestException("A non-transfer request must not carry a destination account");
    }
    // REQ-BANK-041/-046: resolve which approver (if any) the request needs and snapshot it. The
    // org-unit-blind confirm path only reads the boolean; the seam routes the "Fremde Anträge"
    // approval surface by the recorded approver class.
    BigDecimal applicableLimit;
    boolean requiresOwnerApproval;
    BankRequestApprover requiredApprover;
    if (account.getType() == BankAccountType.CARTEL) {
      // The KRT account uses the amount-tiered ladder (REQ-BANK-047): amount <= T1 the bank
      // employee
      // self-approves; T1 < amount <= T2 the Bereichsleiter Profit; amount > T2 the
      // Organisationsleitung. An unset T1 is treated as 0 (no employee self-approval band); an
      // unset
      // T2 as +infinity (the Bereichsleiter Profit covers everything above T1, the OL band empty).
      BigDecimal t1 =
          account.getEmployeeApprovalCeiling() == null
              ? BigDecimal.ZERO
              : account.getEmployeeApprovalCeiling();
      BigDecimal t2 = account.getAreaLeadApprovalCeiling();
      applicableLimit = t1;
      if (request.amount().compareTo(t1) <= 0) {
        requiresOwnerApproval = false;
        requiredApprover = null;
      } else if (t2 == null || request.amount().compareTo(t2) <= 0) {
        requiresOwnerApproval = true;
        requiredApprover = BankRequestApprover.AREA_LEAD_PROFIT;
      } else {
        requiresOwnerApproval = true;
        requiredApprover = BankRequestApprover.ORGANISATIONSLEITUNG;
      }
    } else {
      // Every other request-capable account: a configured per-audience limit lets the requester
      // through up to its ceiling; no matching limit ⇒ the responsible holder's approval is the
      // safe default (REQ-BANK-041).
      applicableLimit = resolveApplicableLimit(account);
      requiresOwnerApproval =
          applicableLimit == null || request.amount().compareTo(applicableLimit) > 0;
      requiredApprover = requiresOwnerApproval ? BankRequestApprover.RESPONSIBLE_HOLDER : null;
    }
    // A withdrawal/transfer never carries a split (REQ-BANK-043, DEPOSIT-only).
    return bankBookingRequestService.create(
        account.getId(),
        request.type(),
        request.amount(),
        request.note(),
        request.justification(),
        targetAccountId,
        requiresOwnerApproval,
        applicableLimit,
        requiredApprover,
        false,
        null);
  }

  /**
   * Lists the caller's own booking requests (REQ-BANK-022).
   *
   * @return the caller's requests, newest first
   */
  @NotNull
  public List<BankBookingRequestDto> listOwnBookingRequests() {
    return bankBookingRequestService.listForCurrentRequester();
  }

  /**
   * Cancels the caller's own pending booking request (REQ-BANK-022).
   *
   * @param requestId the request to cancel
   * @param version the echoed optimistic-locking version
   * @return the cancelled request
   */
  @NotNull
  public BankBookingRequestDto cancelOwnBookingRequest(@NotNull UUID requestId, long version) {
    return bankBookingRequestService.cancelOwn(requestId, version);
  }

  /**
   * Lists every active account as a transfer-request destination (REQ-BANK-040): a requester may
   * transfer from a viewable source account to <em>any</em> active account. Balance-free references
   * only; ordered by account number.
   *
   * @return the active accounts as transfer targets; never {@code null}
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<BankAccountRefDto> listTransferTargetAccounts() {
    return bankAccountRepository.findAllByOrderByAccountNoAsc().stream()
        .filter(account -> account.getStatus() == BankAccountStatus.ACTIVE)
        .map(
            account ->
                new BankAccountRefDto(
                    account.getId(), account.getAccountNo(), account.getName(), account.getType()))
        .toList();
  }

  /**
   * Lists the booking requests the caller may act on in the "Fremde Anträge" tab
   * (REQ-BANK-041/-046). For every account the caller is the responsible holder of, all its
   * requests are listed; the KRT account's amount-band-routed requests are additionally surfaced to
   * their band approver — the {@code AREA_LEAD_PROFIT} band to the Bereichsleiter Profit, the
   * {@code ORGANISATIONSLEITUNG} band to the OL. An admin sees every account's requests.
   *
   * @return the requests the caller may act on; never {@code null}, empty when there are none
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<BankBookingRequestDto> listRequestsForResponsibleAccounts() {
    boolean admin = authHelperService.isAdmin();
    boolean profitBl = isProfitBereichsleiter();
    // One pass over the accounts (findAllByOrderByAccountNoAsc carries @EntityGraph(orgUnit) so
    // isResponsibleHolder's org-unit dereference is N+1-free, REQ-DATA-003): the accounts the
    // caller
    // is the responsible holder of, plus the KRT account for the Bereichsleiter Profit (who
    // approves
    // its middle band without being its responsible holder, REQ-BANK-047).
    Set<UUID> responsibleIds = new LinkedHashSet<>();
    Set<UUID> cartelIds = new LinkedHashSet<>();
    for (BankAccount account : bankAccountRepository.findAllByOrderByAccountNoAsc()) {
      if (admin || isResponsibleHolder(account)) {
        responsibleIds.add(account.getId());
      }
      if (account.getType() == BankAccountType.CARTEL) {
        cartelIds.add(account.getId());
      }
    }
    Set<UUID> candidateIds = new LinkedHashSet<>(responsibleIds);
    if (profitBl) {
      candidateIds.addAll(cartelIds);
    }
    if (candidateIds.isEmpty()) {
      return List.of();
    }
    boolean olMember = ownerScopeService.currentUserIsOlMember();
    return bankBookingRequestService.listForAccounts(candidateIds).stream()
        .filter(request -> canSeeForeignRequest(request, admin, profitBl, olMember, responsibleIds))
        .toList();
  }

  /**
   * Whether a "Fremde Anträge" row is visible to the caller (REQ-BANK-047 band routing): a
   * KRT-account band-flagged request is shown only to its band approver (the Bereichsleiter Profit
   * for {@code AREA_LEAD_PROFIT}, the OL for {@code ORGANISATIONSLEITUNG}); every other request
   * stays visible to the account's responsible holder. Admins see all.
   *
   * @param request the request row
   * @param admin whether the caller is an admin
   * @param profitBl whether the caller is a Bereichsleiter Profit
   * @param olMember whether the caller is an OL member
   * @param responsibleIds the ids of accounts the caller is the responsible holder of
   * @return whether the caller may see (and, if flagged and pending, approve) the request
   */
  private static boolean canSeeForeignRequest(
      @NotNull BankBookingRequestDto request,
      boolean admin,
      boolean profitBl,
      boolean olMember,
      @NotNull Set<UUID> responsibleIds) {
    if (admin) {
      return true;
    }
    String approver = request.requiredApprover();
    if (BankRequestApprover.AREA_LEAD_PROFIT.name().equals(approver)) {
      return profitBl;
    }
    if (BankRequestApprover.ORGANISATIONSLEITUNG.name().equals(approver)) {
      return olMember;
    }
    return responsibleIds.contains(request.accountId());
  }

  /**
   * Grants the required approver's in-app approval for an over-limit booking request
   * (REQ-BANK-041/-046), which pre-fills the bank employee's confirmation checkbox. Only the
   * request's required approver — the account's responsible holder, or for a KRT amount band the
   * Bereichsleiter Profit / Organisationsleitung — or an admin may do this ({@link #canApprove});
   * the request must currently require approval and still be pending.
   *
   * @param requestId the request to approve
   * @return the updated request
   * @throws NotFoundException when the request does not exist
   * @throws AccessDeniedException when the caller may not approve the request
   * @throws BadRequestException when the request needs no approval or is no longer pending
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto grantOwnerApproval(@NotNull UUID requestId) {
    return applyOwnerApproval(requestId, true);
  }

  /**
   * Revokes a previously granted in-app approval for a booking request (REQ-BANK-041/-046).
   *
   * @param requestId the request whose approval to revoke
   * @return the updated request
   * @throws NotFoundException when the request does not exist
   * @throws AccessDeniedException when the caller may not approve the request
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto revokeOwnerApproval(@NotNull UUID requestId) {
    return applyOwnerApproval(requestId, false);
  }

  /**
   * Loads and locks a request, authorizes the caller as the request's required approver — the
   * account's responsible holder, or for a KRT amount band the Bereichsleiter Profit ({@code
   * AREA_LEAD_PROFIT}) / Organisationsleitung ({@code ORGANISATIONSLEITUNG}); admins always pass
   * ({@link #canApprove}) — the org-unit-aware half — then delegates the blind mutation + audit to
   * {@link BankBookingRequestService#applyOwnerApprovalWithinTransaction(BankBookingRequest,
   * boolean)}.
   *
   * @param requestId the request
   * @param granted whether to grant ({@code true}) or revoke ({@code false}) the approval
   * @return the updated request
   */
  @NotNull
  private BankBookingRequestDto applyOwnerApproval(@NotNull UUID requestId, boolean granted) {
    BankBookingRequest request =
        bankBookingRequestRepository
            .findByIdForUpdate(requestId)
            .orElseThrow(() -> new NotFoundException("Booking request not found"));
    if (!canApprove(request)) {
      throw new AccessDeniedException("The caller may not approve this request");
    }
    return bankBookingRequestService.applyOwnerApprovalWithinTransaction(request, granted);
  }

  /**
   * {@code true} iff the caller may grant/revoke the in-app approval of a booking request
   * (REQ-BANK-041/-046): an admin always; for a KRT amount band the band approver (Bereichsleiter
   * Profit for {@code AREA_LEAD_PROFIT}, OL for {@code ORGANISATIONSLEITUNG}); otherwise the
   * account's responsible holder. The single-request authorization counterpart of {@link
   * #canSeeForeignRequest}.
   *
   * @param request the request entity
   * @return whether the caller may act on its approval
   */
  private boolean canApprove(@NotNull BankBookingRequest request) {
    if (authHelperService.isAdmin()) {
      return true;
    }
    BankRequestApprover approver = request.getRequiredApprover();
    if (approver == null) {
      return isResponsibleHolder(request.getAccount());
    }
    return switch (approver) {
      case RESPONSIBLE_HOLDER -> isResponsibleHolder(request.getAccount());
      case AREA_LEAD_PROFIT -> isProfitBereichsleiter();
      case ORGANISATIONSLEITUNG -> ownerScopeService.currentUserIsOlMember();
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Capability predicates
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code true} iff the current caller may view the given account (balance + read-only drill-in).
   * Loads the account's view grants on demand — for the batched card-list path use {@link
   * #canViewInternal}.
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
   * Non-admin view decision over a single account given the caller's oversight scope and that
   * account's view grants — the shared core of {@link #canView} and the batched card list. Admins
   * are short-circuited by the callers.
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
   * {@code true} iff a view grant on an <em>org-unit</em> account (Staffel/SK/Bereich) matches the
   * caller: a membership-role bucket they hold on the owning unit, an all-members grant when they
   * are a member of the owning unit, or an individual-user grant naming them.
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
            // "Mitglieder des Bereichs" (REQ-BANK-048): the whole area cascade of a Bereichskonto.
            // Only ever granted on AREA accounts, whose owning unit is the Bereich.
            case AREA_MEMBERS ->
                ownerScopeService.currentUserIsMemberOfAreaCascade(owningOrgUnitId);
            case USER -> userId.isPresent() && userId.get().equals(grant.getGranteeUserId());
            case GLOBAL_ROLE -> false;
          };
      if (match) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code true} iff a view grant on a {@code SPECIAL} account matches the caller: a global-role
   * bucket they reach, an all-members grant (any KRT member), or an individual-user grant naming
   * them.
   *
   * @param grants the account's view grants
   * @return {@code true} iff a grant admits the caller
   */
  private boolean matchesSpecialGrant(@NotNull List<BankAccountViewGrant> grants) {
    Optional<UUID> userId = authHelperService.currentUserId();
    for (BankAccountViewGrant grant : grants) {
      boolean match =
          switch (grant.getGranteeKind()) {
            case GLOBAL_ROLE ->
                authHelperService.hasReachableRole(Roles.authority(grant.getRoleCode()));
            case ALL_MEMBERS -> authHelperService.isMemberOrAbove();
            case USER -> userId.isPresent() && userId.get().equals(grant.getGranteeUserId());
            // SPECIAL accounts have no owning org unit, so neither membership-role nor the
            // Bereich-cascade audience applies to them.
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
   * {@code true} iff the caller is the {@code BEREICHSLEITER} of a {@code Department.PROFIT}
   * Bereich. This is both the responsible holder of the {@code CARTEL_BANK} account (REQ-BANK-037)
   * and the middle-band approver of the KRT amount ladder (REQ-BANK-047, {@code AREA_LEAD_PROFIT}).
   *
   * @return {@code true} iff the caller leads a PROFIT Bereich
   */
  private boolean isProfitBereichsleiter() {
    Set<UUID> profitBereichIds =
        bereichRepository.findByDepartment(Department.PROFIT).stream()
            .map(Bereich::getId)
            .collect(Collectors.toSet());
    return profitBereichIds.stream()
        .anyMatch(
            id ->
                ownerScopeService.currentUserHoldsRoleOnOrgUnit(id, MembershipRole.BEREICHSLEITER));
  }

  /**
   * {@code true} iff the caller is the derived responsible holder of the account (REQ-BANK-034):
   * Staffelleiter / SK-Leiter (ORG_UNIT), Bereichsleiter (AREA), any OL member (CARTEL), the
   * Profit-Bereichsleiter (CARTEL_BANK). Sonderkonten have no responsible holder.
   *
   * @param account the account
   * @return {@code true} iff the caller is its responsible holder
   */
  private boolean isResponsibleHolder(@NotNull BankAccount account) {
    UUID owner = owningOrgUnitId(account);
    return switch (account.getType()) {
      case ORG_UNIT -> {
        if (owner == null) {
          yield false;
        }
        MembershipRole holderRole =
            account.getOrgUnit().getKind() == OrgUnitKind.SPECIAL_COMMAND
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
   * {@code true} iff the caller may configure who else may view the account (REQ-BANK-035): the
   * responsible holder for an org-unit account; for a Sonderkonto an OL member <em>or</em> bank
   * management (REQ-BANK-037 — "neben den Bankmitarbeitern/-verwaltung"). {@code CARTEL} (fixed
   * all-members) and {@code CARTEL_BANK} (internal) are not configurable.
   *
   * @param account the account
   * @return {@code true} iff the caller may add/remove view grants
   */
  private boolean canConfigureVisibility(@NotNull BankAccount account) {
    // Admin override: an admin manages the permissions of every account whose visibility is
    // configurable at all. CARTEL (all-members) and CARTEL_BANK (internal) audiences are fixed
    // (REQ-BANK-037) — nothing to configure there, not even for an admin.
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
  private boolean canSetTarget(@NotNull BankAccount account) {
    // An admin may set the target on any account (admin override); otherwise the responsible
    // holder.
    // Sonderkonto targets are bank-staff-only for non-admins (set via the bank surface).
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
   * {@code true} iff the account accepts booking requests (REQ-BANK-039/-040): an {@code ACTIVE}
   * account of a request-capable type ({@code ORG_UNIT} / {@code AREA} / {@code CARTEL}).
   * Sonderkonten and the bank-operating account are never request targets.
   *
   * @param account the account
   * @return whether a booking request may be raised against it
   */
  private boolean isRequestCapable(@NotNull BankAccount account) {
    return account.getStatus() == BankAccountStatus.ACTIVE
        && BankApprovalLimitService.configurable(account.getType());
  }

  /**
   * {@code true} iff the caller may set/clear this account's approval limits (REQ-BANK-041): the
   * responsible holder, bank management or an admin — never a plain bank employee. Only the
   * request-capable types carry limits.
   *
   * @param account the account
   * @return whether the caller may configure approval limits
   */
  private boolean canConfigureApprovalLimits(@NotNull BankAccount account) {
    // Per-audience limits are editable only on ORG_UNIT / AREA accounts (REQ-BANK-041). The KRT
    // account (CARTEL) is request-capable but uses the Verwaltung-managed amount ladder instead
    // (REQ-BANK-047), so it is not per-audience-configurable here.
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
  private void requireCanConfigureApprovalLimits(@NotNull BankAccount account) {
    if (!canConfigureApprovalLimits(account)) {
      throw new AccessDeniedException(
          "The caller may not configure this account's approval limits");
    }
  }

  /**
   * Validates an approval-limit role-bucket code against the account's limit buckets.
   *
   * @param account the account
   * @param roleCode the role code to validate
   * @throws BadRequestException when the role code is not a valid limit bucket for this account
   */
  private void requireValidLimitRoleBucket(@NotNull BankAccount account, @NotNull String roleCode) {
    if (!BankApprovalLimitService.roleBuckets(account).contains(roleCode)) {
      throw new BadRequestException(
          "Unknown approval-limit role bucket for this account: " + roleCode);
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
  private BigDecimal resolveApplicableLimit(@NotNull BankAccount account) {
    return resolveApplicableLimit(
        account, approvalLimitRepository.findByAccountId(account.getId()));
  }

  /**
   * Resolves the current caller's applicable approval limit from the given limit rows
   * (REQ-BANK-041, amended by REQ-BANK-047/-047).
   *
   * <ul>
   *   <li>The KRT account ({@code CARTEL}) does not use per-audience limits at all — its ladder
   *       (REQ-BANK-047) governs approval; the value returned here is only the display/self-approve
   *       ceiling, the bank-employee band {@code T1} (its {@code employeeApprovalCeiling}).
   *   <li>Otherwise an individual-user limit wins outright; else the <em>most permissive</em>
   *       (maximum) of every membership tier the caller actually matches — a role bucket held on
   *       the owning unit, the whole-area cascade ({@code AREA_MEMBERS}) when the caller is
   *       anywhere in it, and the all-members ceiling <em>only when the caller is a member of the
   *       owning org unit</em> ("Alle Mitglieder" = the account's own org unit, REQ-BANK-047); else
   *       {@code null} = unlimited.
   * </ul>
   *
   * <p>The members-only gating of the {@code ALL_MEMBERS} tier means an outsider holding only an
   * individual view grant matches no membership tier and falls through to {@code null} (⇒ the
   * caller needs an explicit USER limit, else the request requires approval) — retiring the former
   * catch-all-for-everyone behaviour.
   *
   * @param account the account
   * @param limits the account's approval-limit rows (possibly empty)
   * @return the caller's limit, or {@code null} = unlimited
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
          && userId.isPresent()
          && userId.get().equals(limit.getGranteeUserId())) {
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
            case GLOBAL_ROLE ->
                authHelperService.hasReachableRole(Roles.authority(limit.getRoleCode()));
            case AREA_MEMBERS ->
                owner != null && ownerScopeService.currentUserIsMemberOfAreaCascade(owner);
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

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * Builds the settings snapshot for one account.
   *
   * @param account the account
   * @return the settings DTO
   */
  @NotNull
  private OrgUnitBankAccountSettingsDto toSettingsDto(@NotNull BankAccount account) {
    List<BankAccountViewGrant> grants = viewGrantRepository.findByAccountId(account.getId());
    List<String> grantedRoleCodes =
        grants.stream()
            .filter(
                grant ->
                    grant.getGranteeKind() == BankAccountViewGranteeKind.MEMBERSHIP_ROLE
                        || grant.getGranteeKind() == BankAccountViewGranteeKind.GLOBAL_ROLE)
            .map(BankAccountViewGrant::getRoleCode)
            .toList();
    boolean allMembersGranted =
        grants.stream()
            .anyMatch(grant -> grant.getGranteeKind() == BankAccountViewGranteeKind.ALL_MEMBERS);
    boolean areaMembersGranted =
        grants.stream()
            .anyMatch(grant -> grant.getGranteeKind() == BankAccountViewGranteeKind.AREA_MEMBERS);
    List<UUID> grantedUserIds =
        grants.stream()
            .filter(grant -> grant.getGranteeKind() == BankAccountViewGranteeKind.USER)
            .map(BankAccountViewGrant::getGranteeUserId)
            .toList();
    Map<UUID, String> names =
        grantedUserIds.isEmpty()
            ? Map.of()
            : userRepository.findAllById(grantedUserIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEffectiveName));
    List<OrgUnitBankViewUserDto> grantedUsers =
        grantedUserIds.stream()
            .map(id -> new OrgUnitBankViewUserDto(id, names.getOrDefault(id, "")))
            .toList();
    OrgUnit orgUnit = account.getOrgUnit();
    boolean canConfigureApprovalLimits = canConfigureApprovalLimits(account);
    BankApprovalLimitsDto approvalLimits =
        bankApprovalLimitService.assemble(account, canConfigureApprovalLimits);
    return new OrgUnitBankAccountSettingsDto(
        account.getId(),
        account.getAccountNo(),
        account.getName(),
        account.getType(),
        orgUnit == null ? null : orgUnit.getKind(),
        account.getBalanceTarget(),
        account.getVersion(),
        canSetTarget(account),
        canConfigureVisibility(account),
        visibilityConfigurable(account.getType()),
        allMembersSupported(account.getType()),
        areaMembersSupported(account.getType()),
        roleBucketsGlobal(account.getType()),
        availableRoleCodes(account),
        grantedRoleCodes,
        allMembersGranted,
        areaMembersGranted,
        grantedUsers,
        canConfigureApprovalLimits,
        approvalLimits);
  }

  /**
   * The role-bucket codes the caller may toggle for an account, in display order: the squadron /
   * Bereich sub-ranks for org-unit accounts, the global roles for a Sonderkonto, none otherwise.
   *
   * @param account the account
   * @return the available role codes (possibly empty)
   */
  @NotNull
  private static List<String> availableRoleCodes(@NotNull BankAccount account) {
    if (account.getType() == BankAccountType.SPECIAL) {
      return SPECIAL_GLOBAL_ROLE_BUCKETS;
    }
    OrgUnit orgUnit = account.getOrgUnit();
    if (orgUnit == null) {
      return List.of();
    }
    return switch (orgUnit.getKind()) {
      case SQUADRON -> SQUADRON_ROLE_BUCKETS;
      case BEREICH -> BEREICH_ROLE_BUCKETS;
      case SPECIAL_COMMAND, ORGANISATIONSLEITUNG -> List.of();
    };
  }

  /**
   * Validates a role-bucket code against the account and returns the grant kind it maps to ({@code
   * GLOBAL_ROLE} for a Sonderkonto, else {@code MEMBERSHIP_ROLE}).
   *
   * @param account the account
   * @param roleCode the role code to validate
   * @return the grant kind to use
   * @throws BadRequestException when the role code is not a valid bucket for this account
   */
  @NotNull
  private BankAccountViewGranteeKind requireValidRoleBucket(
      @NotNull BankAccount account, @NotNull String roleCode) {
    if (!availableRoleCodes(account).contains(roleCode)) {
      throw new BadRequestException("Unknown visibility role bucket for this account: " + roleCode);
    }
    return roleBucketsGlobal(account.getType())
        ? BankAccountViewGranteeKind.GLOBAL_ROLE
        : BankAccountViewGranteeKind.MEMBERSHIP_ROLE;
  }

  /**
   * {@code true} iff the account type supports configurable visibility at all (ORG_UNIT / AREA /
   * SPECIAL); {@code CARTEL} (fixed all-members) and {@code CARTEL_BANK} (internal) do not.
   *
   * @param type the account type
   * @return whether visibility is configurable
   */
  private static boolean visibilityConfigurable(@NotNull BankAccountType type) {
    return type == BankAccountType.ORG_UNIT
        || type == BankAccountType.AREA
        || type == BankAccountType.SPECIAL;
  }

  /**
   * {@code true} iff the account type has an all-members visibility bucket (same set as {@link
   * #visibilityConfigurable}).
   *
   * @param type the account type
   * @return whether the all-members bucket applies
   */
  private static boolean allMembersSupported(@NotNull BankAccountType type) {
    return visibilityConfigurable(type);
  }

  /**
   * {@code true} iff the account type has a "Mitglieder des Bereichs" cascade visibility bucket —
   * only {@code AREA} (Bereichskonto) accounts (REQ-BANK-048).
   *
   * @param type the account type
   * @return whether the area-members visibility bucket applies
   */
  private static boolean areaMembersSupported(@NotNull BankAccountType type) {
    return type == BankAccountType.AREA;
  }

  /**
   * {@code true} iff the account type's role buckets are global role codes (only {@code SPECIAL});
   * org-unit accounts use {@code MembershipRole} buckets.
   *
   * @param type the account type
   * @return whether the role buckets are global roles
   */
  private static boolean roleBucketsGlobal(@NotNull BankAccountType type) {
    return type == BankAccountType.SPECIAL;
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
  private BankAccount requireAccount(@NotNull UUID accountId) {
    return bankAccountRepository
        .findById(accountId)
        .orElseThrow(() -> new NotFoundException("Bank account not found"));
  }

  /**
   * Asserts the caller may set the account's balance target.
   *
   * @param account the account
   * @throws AccessDeniedException when the caller may not set the target
   */
  private void requireCanSetTarget(@NotNull BankAccount account) {
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
  private void requireCanConfigureVisibility(@NotNull BankAccount account) {
    if (!canConfigureVisibility(account)) {
      throw new AccessDeniedException("The caller may not configure this account's visibility");
    }
  }

  /**
   * Explicit optimistic-lock check before mutating the account (mirrors {@code
   * BankAccountService}).
   *
   * @param account the loaded account
   * @param version the client-echoed version
   * @throws ObjectOptimisticLockingFailureException on a version mismatch
   */
  private static void requireVersionMatch(@NotNull BankAccount account, long version) {
    OptimisticLock.check(account.getVersion(), version, BankAccount.class, account.getId());
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
  @Nullable
  private static MembershipRole parseMembershipRole(@Nullable String code) {
    if (code == null) {
      return null;
    }
    try {
      return MembershipRole.valueOf(code);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /**
   * Redacts the player-custody ("Halter") columns of a booking row for an org-unit viewer
   * (REQ-BANK-038): the holder and counter-holder handles — the aUEC-custody chain — are nulled.
   * The deposit/withdrawal counterparty (Einzahler / Empf&auml;nger and their org unit,
   * REQ-BANK-044) is <em>kept</em>: the "von wem / an wen" of a booking on the member's own
   * org-unit account is shown to viewers (owner decision, amends REQ-BANK-044/-038 — only the
   * custody Halter stays hidden). The counter-<em>account</em> (transfer target/source) and the
   * note/justification were never redacted.
   *
   * @param booking the bank-staff booking row
   * @return a copy with only the holder/counter-holder handles removed
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
   * Batch-computes the balances of the given accounts in one grouped query (REQ-DATA-003).
   *
   * @param accounts the accounts to sum
   * @return a map of account id to balance for accounts that have at least one posting
   */
  @NotNull
  private Map<UUID, BigDecimal> balancesByAccountId(@NotNull List<BankAccount> accounts) {
    List<UUID> ids = accounts.stream().map(BankAccount::getId).toList();
    Map<UUID, BigDecimal> byAccount = new HashMap<>();
    for (BankAccountBalance row : bankPostingRepository.accountBalances(ids)) {
      byAccount.put(row.accountId(), row.balance());
    }
    return byAccount;
  }

  /**
   * Fetches the last 30 days of posting slices for the given accounts in one windowed query
   * (REQ-DATA-003) and groups them by account id, for the per-card 30-day trend (REQ-BANK-016).
   *
   * @param accounts the visible accounts
   * @return a map of account id to its in-window posting slices (only accounts with slices appear)
   */
  @NotNull
  private Map<UUID, List<BankPostingSlice>> slicesByAccountId(@NotNull List<BankAccount> accounts) {
    List<UUID> ids = accounts.stream().map(BankAccount::getId).toList();
    return bankPostingRepository
        .postingSlicesSince(ids, BankTrendCalculator.windowCutoff())
        .stream()
        .collect(Collectors.groupingBy(BankPostingSlice::accountId));
  }

  /**
   * Projects one visible account and its resolved figures into the balance-card wire shape, now
   * carrying the balance target (REQ-BANK-036) and the settings affordance.
   *
   * @param account the visible account
   * @param balance the resolved balance (zero when it has no postings)
   * @param canRequest {@code true} iff this is the caller's own-level account (F2 affordance)
   * @param delta30d the 30-day net change (signed)
   * @param sparkline the 30 end-of-day balances of the window, oldest first
   * @param canManageSettings whether the caller may open the account's settings
   * @param approvalLimit the caller's resolved approval limit for this account, or {@code null} =
   *     unlimited (REQ-BANK-041)
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
      @Nullable BigDecimal approvalLimit) {
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
        approvalLimit);
  }
}
