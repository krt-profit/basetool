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

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAdmission;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedAccountOptions;
import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffRights;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Projects only the Bank references an already admitted order action needs. */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true, propagation = Propagation.MANDATORY)
class BankLinkedAccountDirectory implements BankLinkedAccountOptions {

  private static final int LIMIT = 50;

  private final BankAccountRepository accounts;
  private final BankHolderRepository holders;
  private final BankAccessRules access;
  private final BankCaller caller;

  @Override
  @NotNull
  public Options candidates(@NotNull Scope scope) {
    var actor = caller.actor();
    var staff = access.staffRights(actor);
    List<BankAccount> visible =
        access.viewable(actor, null).stream()
            .map(BankAccessRules.ViewableAccount::account)
            .toList();
    var collection =
        scope.deposit()
            ? refs(
                Stream.concat(activeUnitAccount(scope.responsibleUnit()).stream(), visible.stream())
                    .distinct(),
                scope.responsibleUnit())
            : List.<Account>of();
    var designation =
        scope.designate() ? refs(visible.stream(), scope.requestingUnit()) : List.<Account>of();
    var debitAccounts =
        scope.debit()
            ? Stream.concat(visible.stream(), staffAccounts(staff)).distinct().toList()
            : List.<BankAccount>of();
    return new Options(
        collection,
        designation,
        debitRefs(
            debitAccounts,
            scope.responsibleUnit(),
            actor,
            staff,
            BankBookingRequestType.WITHDRAWAL,
            scope.pin()),
        debitRefs(
            debitAccounts,
            scope.responsibleUnit(),
            actor,
            staff,
            BankBookingRequestType.TRANSFER,
            scope.pin()),
        custodians(staff.staff(), staff.actorId()));
  }

  private List<Account> debitRefs(
      List<BankAccount> available,
      UUID responsibleUnit,
      BankActor actor,
      BankStaffRights staff,
      BankBookingRequestType type,
      @Nullable UUID pin) {
    return refs(
        available.stream()
            .filter(account ->
                staff.canBook(type, account.getId())
                    || access.admit(actor, account, pin, type, BigDecimal.ONE)
                        instanceof BankAdmission.Admitted),
        responsibleUnit);
  }

  private Stream<BankAccount> staffAccounts(BankStaffRights staff) {
    UUID actor = staff.actorId();
    if (!staff.staff() || actor == null) {
      return Stream.empty();
    }
    var accountsForStaff =
        staff.management()
            ? accounts.findAllByOrderByAccountNoAsc()
            : accounts.findAllGrantedTo(actor);
    return accountsForStaff.stream()
        .filter(account -> account.getStatus() == BankAccountStatus.ACTIVE);
  }

  private Optional<BankAccount> activeUnitAccount(UUID unitId) {
    return accounts
        .findByOrgUnitId(unitId)
        .filter(account -> account.getStatus() == BankAccountStatus.ACTIVE);
  }

  private static List<Account> refs(Stream<BankAccount> accounts, UUID preferredUnit) {
    return accounts
        .sorted(
            Comparator.<BankAccount, Boolean>comparing(account ->
                    account.getOrgUnit() == null
                        || !preferredUnit.equals(account.getOrgUnit().getId()))
                .thenComparing(BankAccount::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(BankAccount::getId))
        .limit(LIMIT)
        .map(account -> new Account(account.getId(), account.getName()))
        .toList();
  }

  private List<Custodian> custodians(boolean staff, @Nullable UUID actor) {
    if (actor == null) {
      return List.of();
    }
    Stream<BankHolder> permitted =
        staff ? holders.findAllWithUser().stream() : holders.findByUserId(actor).stream();
    return permitted
        .filter(holder -> holder.isActive() && holder.getUser() != null)
        .sorted(Comparator.comparing(BankHolder::getHandle, String.CASE_INSENSITIVE_ORDER))
        .limit(LIMIT)
        .map(holder ->
            new Custodian(
                holder.getId(),
                Objects.requireNonNull(holder.getUser()).getId(),
                holder.getHandle()))
        .toList();
  }
}
