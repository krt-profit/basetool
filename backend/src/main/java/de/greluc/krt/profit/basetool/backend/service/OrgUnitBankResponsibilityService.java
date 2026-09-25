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
import java.util.List;
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
 * Resolves the responsible holders (Kontoverantwortliche) of bank accounts from persisted
 * membership ranks, and audits changes to them around leadership mutations (REQ-BANK-034,
 * ADR-0070).
 *
 * <p>It carries no caller context and depends on no {@code OwnerScopeService}, so the interactive
 * org-unit/bank authorization stays in {@link OrgUnitBankAccessService} (ADR-0020) and the bank
 * classes stay org-unit-blind (REQ-BANK-008).
 */
@Service
@RequiredArgsConstructor
public class OrgUnitBankResponsibilityService {

  private final BankAccountRepository bankAccountRepository;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;
  private final BereichRepository bereichRepository;
  private final BankAuditService bankAuditService;

  /**
   * Resolves the user ids of an account's responsible holders (REQ-BANK-034), used to notify them
   * of booking requests (REQ-BANK-026).
   *
   * <p>Staffelkonto: its {@code STAFFELLEITER}; SK-Konto: its {@code SK_LEAD}; Bereichskonto: its
   * {@code BEREICHSLEITER}; {@code CARTEL}: every {@code OL_MEMBER}; {@code CARTEL_BANK}: the
   * {@code BEREICHSLEITER} of every {@code Department.PROFIT} Bereich; Sonderkonto: none.
   *
   * @param accountId the account whose responsible holders to resolve
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
      case CARTEL ->
          owner == null
              ? Set.of()
              : orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(
                  owner, MembershipRole.OL_MEMBER);
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
    List<UUID> profitBereichIds =
        bereichRepository.findByDepartment(Department.PROFIT).stream().map(Bereich::getId).toList();
    if (profitBereichIds.isEmpty()) {
      return Set.of();
    }
    return new LinkedHashSet<>(
        orgUnitMembershipRepository.findUserIdsByOrgUnitIdsAndRole(
            profitBereichIds, MembershipRole.BEREICHSLEITER));
  }

  /**
   * Snapshots the current responsible-holder sets of every account whose holders depend on the org
   * unit's leadership, as the "before" side for {@link #recordResponsibleHolderChanges(Map)}.
   *
   * <p>Covers the account the org unit owns and, for a {@code Department.PROFIT} Bereich, the
   * accounts whose responsible sets include the Profit-Bereichsleiter.
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
   * Snapshots the responsible holders of every account tied to any org unit the user belongs to, as
   * the "before" side of removing the user from all of them (REQ-BANK-034).
   *
   * <p>Resolves the org units through {@link
   * OrgUnitMembershipRepository#findOrgUnitIdsByUserId(UUID)} without loading membership entities,
   * because it runs inside the user-deletion transaction; delegates to {@link
   * #snapshotResponsibleHolders(UUID)} per org unit.
   *
   * @param userId the user about to be removed from all their org units
   * @return the affected accounts mapped to their current responsible-holder user ids
   */
  @NotNull
  @Transactional
  public Map<UUID, Set<UUID>> snapshotResponsibleHoldersForUser(@NotNull UUID userId) {
    Set<UUID> orgUnitIds =
        new LinkedHashSet<>(orgUnitMembershipRepository.findOrgUnitIdsByUserId(userId));
    Map<UUID, Set<UUID>> snapshot = new HashMap<>();
    for (UUID orgUnitId : orgUnitIds) {
      snapshot.putAll(snapshotResponsibleHolders(orgUnitId));
    }
    return snapshot;
  }

  /**
   * Records one {@code ACCOUNT_RESPONSIBLE_CHANGED} bank audit event (REQ-BANK-034) for every
   * account whose responsible-holder set differs from the {@code before} snapshot.
   *
   * <p>Runs in the mutation's transaction; {@link BankAuditService} captures the actor. {@code
   * targetUserId} is the sole new holder for a singleton set, else {@code null}.
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
   * Returns the bank accounts whose responsible holders can change with the org unit's leadership:
   * the account it owns plus, for a {@code Department.PROFIT} Bereich, the {@code CARTEL_BANK}
   * account.
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
          .findFirstByType(BankAccountType.CARTEL_BANK)
          .ifPresent(account -> ids.add(account.getId()));
    }
    return ids;
  }

  /**
   * {@code true} iff the org unit is a {@code Department.PROFIT} Bereich — whose Bereichsleiter is
   * the responsible holder of the {@code CARTEL_BANK} account (REQ-BANK-034), so its leadership
   * change ripples onto that account.
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
