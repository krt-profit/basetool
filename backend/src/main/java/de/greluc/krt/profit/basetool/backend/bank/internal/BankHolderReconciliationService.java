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

import de.greluc.krt.profit.basetool.backend.bank.api.events.BankNotices;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserSyncFollowUp;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the bank-holder registry in sync with the bank roster (REQ-BANK-029, ADR-0040): every user
 * with {@code ROLE_BANK_EMPLOYEE} or {@code ROLE_BANK_MANAGEMENT} is an active holder, and a
 * role-managed holder whose user lost all bank roles is deactivated.
 *
 * <p>Sweeps the whole roster on the {@code UserSyncTask} schedule and reconciles one member after
 * each committed role change ({@link BankHolderRoleChangeListener}); both are idempotent and never
 * touch manually registered holders.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BankHolderReconciliationService implements UserSyncFollowUp {

  /** Role codes whose holders must exist as active bank holders (REQ-BANK-029). */
  private static final List<String> BANK_ROLE_CODES =
      List.of(Roles.BANK_EMPLOYEE, Roles.BANK_MANAGEMENT);

  private final UserRepository userRepository;
  private final BankHolderRepository holderRepository;
  private final BankAuditService bankAuditService;
  private final BankHolderPostingRepository holderPostingRepository;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Reconciles the whole roster in one sweep: creates a holder for every bank-role user that lacks
   * one, reactivates a role-managed holder whose user is bank staff again, and deactivates every
   * role-managed holder whose user no longer holds any bank role. Runs in its own transaction so
   * the {@code @Transactional(MANDATORY)} audit writes have a context and a partial failure rolls
   * the sweep back cleanly (the next run retries).
   */
  @Transactional
  @Override
  public void reconcileAll() {
    Set<UUID> staff = new HashSet<>();
    for (String code : BANK_ROLE_CODES) {
      staff.addAll(userRepository.findUserIdsByRoleCode(code));
    }

    List<BankHolder> staffHolders =
        staff.isEmpty() ? List.of() : holderRepository.findByUserIdIn(staff);
    Map<UUID, BankHolder> holderByUser =
        staffHolders.stream()
            .filter(h -> h.getUser() != null)
            .collect(
                Collectors.toMap(
                    h -> Objects.requireNonNull(h.getUser()).getId(), h -> h, (a, _) -> a));

    int created = 0;
    Set<UUID> missing =
        staff.stream().filter(id -> !holderByUser.containsKey(id)).collect(Collectors.toSet());
    if (!missing.isEmpty()) {
      Map<UUID, User> users =
          userRepository.findAllById(missing).stream()
              .collect(Collectors.toMap(User::getId, u -> u));
      for (UUID userId : missing) {
        User user = users.get(userId);
        if (user == null) {
          continue;
        }
        register(user);
        created++;
      }
    }

    int reactivated = 0;
    for (BankHolder holder : staffHolders) {
      if (holder.isRoleManaged() && !holder.isActive()) {
        reactivate(holder);
        reactivated++;
      }
    }

    int deactivated = 0;
    for (BankHolder holder : holderRepository.findByRoleManagedTrueAndActiveTrue()) {
      UUID userId = userIdOf(holder);
      if (userId == null || !staff.contains(userId)) {
        deactivate(holder);
        deactivated++;
      }
    }

    if (created > 0 || reactivated > 0 || deactivated > 0) {
      log.info(
          "Bank holder reconcile: created={}, reactivated={}, deactivated={} (roster size {}).",
          created,
          reactivated,
          deactivated,
          staff.size());
    }
  }

  /**
   * Reconciles one member's holder against the member's committed role set, in a transaction of its
   * own: registers or reactivates a role-managed holder for bank staff, deactivates one whose
   * member holds no bank role any more, and leaves a manually registered holder alone.
   *
   * @param userId the member whose roles changed
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void reconcileUser(@NotNull UUID userId) {
    boolean staff = userRepository.hasAnyRoleCode(userId, BANK_ROLE_CODES);
    Optional<BankHolder> existing = holderRepository.findByUserId(userId);
    if (existing.isEmpty()) {
      if (staff) {
        userRepository.findPlainById(userId).ifPresent(this::register);
      }
      return;
    }
    BankHolder holder = existing.get();
    if (!holder.isRoleManaged() || holder.isActive() == staff) {
      return;
    }
    if (staff) {
      reactivate(holder);
    } else {
      deactivate(holder);
    }
  }

  private void register(User user) {
    BankHolder holder = new BankHolder();
    holder.setUser(user);
    holder.setHandle(user.getEffectiveName());
    holder.setActive(true);
    holder.setRoleManaged(true);
    BankHolder saved = holderRepository.save(holder);
    bankAuditService.record(
        BankAuditEventType.HOLDER_REGISTERED, null, null, user.getId(), saved.getHandle());
  }

  private void reactivate(BankHolder holder) {
    holder.setActive(true);
    holderRepository.save(holder);
    bankAuditService.record(
        BankAuditEventType.HOLDER_REACTIVATED, null, null, userIdOf(holder), holder.getHandle());
    eventPublisher.publishEvent(BankNotices.holderNoticeCleared(holder.getId()));
  }

  private void deactivate(BankHolder holder) {
    holder.setActive(false);
    holderRepository.save(holder);
    bankAuditService.record(
        BankAuditEventType.HOLDER_DEACTIVATED, null, null, userIdOf(holder), holder.getHandle());
    BigDecimal balance = holderPostingRepository.holderTotal(holder.getId());
    if (balance.signum() != 0) {
      eventPublisher.publishEvent(
          BankNotices.holderDeactivatedWithBalance(holder.getId(), holder.getHandle(), balance));
    }
  }

  private static @Nullable UUID userIdOf(BankHolder holder) {
    User user = holder.getUser();
    return user == null ? null : user.getId();
  }
}
