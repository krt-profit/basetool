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

import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the bank-holder registry in sync with the bank roster (REQ-BANK-029, ADR-0040): every user
 * with {@code ROLE_BANK_EMPLOYEE} or {@code ROLE_BANK_MANAGEMENT} is an active holder, and a
 * role-managed holder whose user lost all bank roles is deactivated.
 *
 * <p>Runs on the {@code UserSyncTask} schedule, is idempotent, and never touches manually
 * registered holders.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BankHolderReconciliationService {

  /** Role codes whose holders must exist as active bank holders (REQ-BANK-029). */
  private static final List<String> BANK_ROLE_CODES =
      List.of(Roles.BANK_EMPLOYEE, Roles.BANK_MANAGEMENT);

  private final UserRepository userRepository;
  private final BankHolderRepository holderRepository;
  private final BankAuditService bankAuditService;

  /**
   * Reconciles the whole roster in one sweep: creates a holder for every bank-role user that lacks
   * one, reactivates a role-managed holder whose user is bank staff again, and deactivates every
   * role-managed holder whose user no longer holds any bank role. Runs in its own transaction so
   * the {@code @Transactional(MANDATORY)} audit writes have a context and a partial failure rolls
   * the sweep back cleanly (the next run retries).
   */
  @Transactional
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
            .collect(Collectors.toMap(h -> h.getUser().getId(), h -> h, (a, b) -> a));

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
        BankHolder holder = new BankHolder();
        holder.setUser(user);
        holder.setHandle(user.getEffectiveName());
        holder.setActive(true);
        holder.setRoleManaged(true);
        BankHolder saved = holderRepository.save(holder);
        bankAuditService.record(
            BankAuditEventType.HOLDER_REGISTERED, null, null, user.getId(), saved.getHandle());
        created++;
      }
    }

    int reactivated = 0;
    for (BankHolder holder : staffHolders) {
      if (holder.isRoleManaged() && !holder.isActive()) {
        holder.setActive(true);
        holderRepository.save(holder);
        bankAuditService.record(
            BankAuditEventType.HOLDER_REACTIVATED,
            null,
            null,
            holder.getUser() == null ? null : holder.getUser().getId(),
            holder.getHandle());
        reactivated++;
      }
    }

    int deactivated = 0;
    for (BankHolder holder : holderRepository.findByRoleManagedTrueAndActiveTrue()) {
      UUID userId = holder.getUser() == null ? null : holder.getUser().getId();
      if (userId == null || !staff.contains(userId)) {
        holder.setActive(false);
        holderRepository.save(holder);
        bankAuditService.record(
            BankAuditEventType.HOLDER_DEACTIVATED, null, null, userId, holder.getHandle());
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
}
