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

import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.Department;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BereichRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The OwnerScope-free responsible-holder ("Kontoverantwortliche") reverse-resolution and
 * change-audit slice of {@link OrgUnitBankAccessService}, split out under audit Thema 7 (#14,
 * REQ-BANK-034/-026/-047, ADR-0070). It answers two questions no current principal is available
 * for: who are the responsible holder(s) of a given account (the reverse of the interactive "am I
 * the responsible holder?" gate the {@link OrgUnitBankAccessService} core keeps), and — around a
 * leadership mutation — which of those derived sets changed, recording one {@code
 * ACCOUNT_RESPONSIBLE_CHANGED} bank audit event per account that did.
 *
 * <p>Unlike the {@link OrgUnitBankAccessService} core it wires <b>no</b> {@code OwnerScopeService}:
 * it reverse-resolves the holders purely from the persisted membership ranks ({@link
 * OrgUnitMembershipRepository}) and account owner labels ({@link BankAccountRepository}), carrying
 * no caller context (the resolution runs on the after-commit notification thread and around
 * membership writes). That absence of an {@code OwnerScopeService} dependency is exactly what lets
 * this seam live outside {@link OrgUnitBankAccessService} without tripping the {@code
 * orgUnitAwareBankSeamIsContainedToOneClass} ArchUnit rule (ADR-0020): only a class that couples
 * {@code OwnerScopeService} <em>and</em> the bank-account repository must be the one sanctioned
 * bridge. Being deliberately <em>not</em> {@code Bank*}-named it also stays clear of the {@code
 * bankClassesMustNotConsultOrgUnitScope} pin, so the bank remains org-unit-blind (REQ-BANK-008).
 *
 * <p>The interactive F1/F2/authorization core — the card list, the read-only drill-in, booking
 * requests and the responsibility settings, all of which read the current principal through {@code
 * OwnerScopeService} — stays in {@link OrgUnitBankAccessService}, the single sanctioned org-unit /
 * bank bridge.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitBankResponsibilityService {

  private final BankAccountRepository bankAccountRepository;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;
  private final BereichRepository bereichRepository;
  private final BankAuditService bankAuditService;

  /**
   * Resolves the user ids of an account's <em>responsible holder(s)</em> (Kontoverantwortliche,
   * REQ-BANK-034) — the org-unit-aware reverse of {@code
   * OrgUnitBankAccessService#isResponsibleHolder}, used by the notification engine to notify the
   * responsible holder when a booking request on their account is created or decided
   * (REQ-BANK-026). Unlike {@code isResponsibleHolder} it carries no current principal (it runs on
   * the after-commit notification thread), so it reverse-resolves the holders via {@link
   * OrgUnitMembershipRepository}. Keeping it in this OwnerScope-free seam lets the notification
   * resolver reach the responsible holders while the {@code Bank*} classes stay org-unit-blind
   * (REQ-BANK-008, ArchUnit pins).
   *
   * <p>Per account type: a Staffelkonto → its {@code STAFFELLEITER}; an SK-Konto → its {@code
   * SK_LEAD}; a Bereichskonto → its {@code BEREICHSLEITER}; the {@code CARTEL}/KRT account → all
   * {@code OL_MEMBER}s <em>plus</em> the Bereichsleiter Profit (the middle-band approver of the KRT
   * amount ladder, REQ-BANK-047, so both approver classes are notified); the {@code CARTEL_BANK} →
   * the {@code BEREICHSLEITER} of every {@code Department.PROFIT} Bereich; a Sonderkonto → none.
   *
   * @param accountId the account whose responsible holder(s) to resolve
   * @return the responsible holders' user ids; never {@code null}, empty for a Sonderkonto, an
   *     unlinked account or a missing account
   */
  @NotNull
  @Transactional(readOnly = true)
  public Set<UUID> resolveResponsibleHolderUserIds(@NotNull UUID accountId) {
    BankAccount account = bankAccountRepository.findById(accountId).orElse(null);
    if (account == null) {
      return Set.of();
    }
    UUID owner = owningOrgUnitId(account);
    return switch (account.getType()) {
      case ORG_UNIT -> {
        if (owner == null) {
          yield Set.of();
        }
        MembershipRole holderRole =
            account.getOrgUnit().getKind() == OrgUnitKind.SPECIAL_COMMAND
                ? MembershipRole.SK_LEAD
                : MembershipRole.STAFFELLEITER;
        yield orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(owner, holderRole);
      }
      case AREA ->
          owner == null
              ? Set.of()
              : orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(
                  owner, MembershipRole.BEREICHSLEITER);
      case CARTEL -> {
        Set<UUID> holders = new LinkedHashSet<>();
        if (owner != null) {
          holders.addAll(
              orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(
                  owner, MembershipRole.OL_MEMBER));
        }
        // REQ-BANK-047: the KRT account's amount ladder additionally routes approval to the
        // Bereichsleiter Profit (middle band), so they are notified about its requests too — each
        // party still sees only its own band in the "Fremde Anträge" tab.
        holders.addAll(resolveCartelBankResponsibleHolders());
        yield holders;
      }
      case CARTEL_BANK -> resolveCartelBankResponsibleHolders();
      case SPECIAL -> Set.of();
    };
  }

  /**
   * Resolves the responsible holders of the {@code CARTEL_BANK} account (REQ-BANK-034): the {@code
   * BEREICHSLEITER} of every {@code Department.PROFIT} Bereich, unioned. The reverse of {@code
   * OrgUnitBankAccessService#isProfitBereichsleiter}.
   *
   * @return the Profit-Bereichsleiter user ids; never {@code null}, possibly empty
   */
  @NotNull
  private Set<UUID> resolveCartelBankResponsibleHolders() {
    Set<UUID> holders = new LinkedHashSet<>();
    for (Bereich bereich : bereichRepository.findByDepartment(Department.PROFIT)) {
      holders.addAll(
          orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(
              bereich.getId(), MembershipRole.BEREICHSLEITER));
    }
    return holders;
  }

  /**
   * Snapshots, per bank account whose derived responsible holder(s) depend on the given org unit's
   * leadership, the CURRENT responsible-holder user-id set — the "before" side of a leadership
   * change, to be diffed by {@link #recordResponsibleHolderChanges(Map)} right after the mutation
   * (REQ-BANK-034 change audit, ADR-0070). Affected accounts: the account the org unit owns
   * (Staffel/SK &rarr; {@code ORG_UNIT}, Bereich &rarr; {@code AREA}, OL &rarr; {@code CARTEL})
   * and, when the org unit is a {@code Department.PROFIT} Bereich, the collegial {@code CARTEL} and
   * the {@code CARTEL_BANK} accounts too, whose responsible sets include the Profit-Bereichsleiter
   * (REQ-BANK-034/-047). Called by {@code OrgUnitMembershipService} around each leadership
   * mutation: the sanctioned seam owns the bank access, so the membership service never touches
   * bank repos.
   *
   * @param orgUnitId the org unit whose leadership is about to change
   * @return the affected accounts mapped to their current responsible-holder user ids; empty when
   *     the org unit owns no account and is not a Profit Bereich
   */
  @NotNull
  @Transactional
  public Map<UUID, Set<UUID>> snapshotResponsibleHolders(@NotNull UUID orgUnitId) {
    Map<UUID, Set<UUID>> snapshot = new HashMap<>();
    for (UUID accountId : responsibleAuditAccountIds(orgUnitId)) {
      snapshot.put(accountId, resolveResponsibleHolderUserIds(accountId));
    }
    return snapshot;
  }

  /**
   * Snapshots the responsible holders of every bank account tied to any org unit the given user is
   * a member of — the "before" side for a mutation that removes the user from several org units at
   * once (a full user deletion, REQ-BANK-034/ADR-0070). Reuses {@link
   * #snapshotResponsibleHolders(UUID)} per membership org unit (so the Profit ripple onto {@code
   * CARTEL}/{@code CARTEL_BANK} is covered) and merges by account id. Over-covering a plain
   * (non-leadership) membership is harmless — {@link #recordResponsibleHolderChanges(Map)} records
   * nothing for an account whose set does not change.
   *
   * @param userId the user about to be removed from all their org units
   * @return the affected accounts mapped to their current responsible-holder user ids
   */
  @NotNull
  @Transactional
  public Map<UUID, Set<UUID>> snapshotResponsibleHoldersForUser(@NotNull UUID userId) {
    Set<UUID> orgUnitIds = new LinkedHashSet<>();
    for (var membership : orgUnitMembershipRepository.findAllByIdUserId(userId)) {
      orgUnitIds.add(membership.getId().getOrgUnitId());
    }
    Map<UUID, Set<UUID>> snapshot = new HashMap<>();
    for (UUID orgUnitId : orgUnitIds) {
      snapshot.putAll(snapshotResponsibleHolders(orgUnitId));
    }
    return snapshot;
  }

  /**
   * Records one {@code ACCOUNT_RESPONSIBLE_CHANGED} bank audit event (REQ-BANK-034, ADR-0070) for
   * every account whose derived responsible-holder set differs from the {@code before} snapshot —
   * called right after a leadership mutation, on the same transaction, so the recompute sees the
   * new state and {@link BankAuditService} captures the acting user automatically. The old/new
   * user-id sets go into the details payload (ids are system identifiers, not PII — REQ-BANK-012);
   * {@code targetUserId} is the sole new holder when the set is a singleton, else null (collegial
   * account).
   *
   * @param before the pre-mutation snapshot from {@link #snapshotResponsibleHolders(UUID)}
   */
  @Transactional
  public void recordResponsibleHolderChanges(@NotNull Map<UUID, Set<UUID>> before) {
    before.forEach(
        (accountId, oldHolders) -> {
          Set<UUID> newHolders = resolveResponsibleHolderUserIds(accountId);
          if (!oldHolders.equals(newHolders)) {
            bankAuditService.record(
                BankAuditEventType.ACCOUNT_RESPONSIBLE_CHANGED,
                accountId,
                null,
                newHolders.size() == 1 ? newHolders.iterator().next() : null,
                AuditDetails.of("old", joinResponsibleIds(oldHolders))
                    .with("new", joinResponsibleIds(newHolders)));
          }
        });
  }

  /**
   * The bank accounts whose derived responsible holder(s) can change when the given org unit's
   * leadership changes: the account the org unit owns, plus — for a {@code Department.PROFIT}
   * Bereich — the collegial {@code CARTEL} and the {@code CARTEL_BANK} singletons (their
   * responsible sets include the Profit-Bereichsleiter, REQ-BANK-034/-047).
   *
   * @param orgUnitId the org unit whose leadership changes
   * @return the affected account ids; never {@code null}, possibly empty
   */
  @NotNull
  private Set<UUID> responsibleAuditAccountIds(@NotNull UUID orgUnitId) {
    Set<UUID> ids = new LinkedHashSet<>();
    bankAccountRepository.findByOrgUnitId(orgUnitId).ifPresent(account -> ids.add(account.getId()));
    if (isProfitBereich(orgUnitId)) {
      bankAccountRepository
          .findFirstByType(BankAccountType.CARTEL)
          .ifPresent(account -> ids.add(account.getId()));
      bankAccountRepository
          .findFirstByType(BankAccountType.CARTEL_BANK)
          .ifPresent(account -> ids.add(account.getId()));
    }
    return ids;
  }

  /**
   * {@code true} iff the org unit is a {@code Department.PROFIT} Bereich — whose Bereichsleiter is
   * a responsible holder of the {@code CARTEL_BANK} account and part of the collegial {@code
   * CARTEL} set (REQ-BANK-034/-047), so its leadership change ripples onto those two accounts.
   *
   * @param orgUnitId the org unit to test
   * @return {@code true} for a Profit Bereich
   */
  private boolean isProfitBereich(@NotNull UUID orgUnitId) {
    return bereichRepository.findByDepartment(Department.PROFIT).stream()
        .anyMatch(bereich -> bereich.getId().equals(orgUnitId));
  }

  /**
   * Joins a responsible-holder user-id set into a stable, comma-separated audit-detail value:
   * sorted for a deterministic payload and free of whitespace, so it is a legal {@link
   * AuditDetails} value.
   *
   * @param ids the user ids
   * @return the sorted comma-joined ids, or an empty string for an empty set
   */
  @NotNull
  private static String joinResponsibleIds(@NotNull Set<UUID> ids) {
    return ids.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
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
}
