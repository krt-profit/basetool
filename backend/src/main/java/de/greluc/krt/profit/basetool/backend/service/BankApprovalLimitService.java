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
import de.greluc.krt.profit.basetool.backend.model.BankAccountApprovalLimit;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGranteeKind;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BankApprovalLimitUserDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankApprovalLimitsDto;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountApprovalLimitRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side helper for per-account approval limits (REQ-BANK-041): defines which role buckets a
 * limit may address per account type and assembles the {@link BankApprovalLimitsDto}.
 *
 * <p>Org-unit-blind, so the bank-staff services may reuse it; limits apply only to {@code
 * ORG_UNIT}, {@code AREA} and {@code CARTEL} accounts.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankApprovalLimitService {

  /** Squadron sub-rank limit buckets (mirrors the squadron visibility buckets). */
  private static final List<String> SQUADRON_ROLE_BUCKETS =
      List.of(
          MembershipRole.KOMMANDOLEITER.name(),
          MembershipRole.STELLV_KOMMANDOLEITER.name(),
          MembershipRole.ENSIGN.name());

  /** Bereich sub-rank limit buckets (mirrors the Bereich visibility buckets). */
  private static final List<String> BEREICH_ROLE_BUCKETS =
      List.of(MembershipRole.BEREICHSKOORDINATOR.name(), MembershipRole.BEREICHSOPERATOR.name());

  private final BankAccountApprovalLimitRepository limitRepository;
  private final UserRepository userRepository;

  /**
   * Returns whether the account type carries approval limits: the request-capable types {@code
   * ORG_UNIT}, {@code AREA} and {@code CARTEL} (REQ-BANK-039/-040).
   *
   * @param type the account type
   * @return whether approval limits may be configured
   */
  public static boolean configurable(@NotNull BankAccountType type) {
    return type == BankAccountType.ORG_UNIT
        || type == BankAccountType.AREA
        || type == BankAccountType.CARTEL;
  }

  /**
   * Returns whether the account type carries per-audience approval limits: {@code ORG_UNIT} and
   * {@code AREA} only; the KRT account uses its approval ladder instead (REQ-BANK-047).
   *
   * @param type the account type
   * @return whether the per-audience limit editor applies
   */
  public static boolean audienceLimitsSupported(@NotNull BankAccountType type) {
    return type == BankAccountType.ORG_UNIT || type == BankAccountType.AREA;
  }

  /**
   * Returns whether the account has an all-members limit tier, which is the case exactly for the
   * types of {@link #audienceLimitsSupported}.
   *
   * @param type the account type
   * @return whether the all-members limit tier applies
   */
  public static boolean allMembersSupported(@NotNull BankAccountType type) {
    return audienceLimitsSupported(type);
  }

  /**
   * Returns whether the account has a "Mitglieder des Bereichs" limit tier, which only {@code AREA}
   * accounts have (REQ-BANK-048).
   *
   * @param type the account type
   * @return whether the area-members limit tier applies
   */
  public static boolean areaMembersSupported(@NotNull BankAccountType type) {
    return type == BankAccountType.AREA;
  }

  /**
   * The role-bucket codes that may carry a limit on the account, in display order: the squadron
   * sub-ranks for a Staffel, the Bereich ranks for a Bereich, none for SK / CARTEL / the
   * non-configurable types.
   *
   * @param account the account
   * @return the addressable role buckets (possibly empty)
   */
  @NotNull
  public static List<String> roleBuckets(@NotNull BankAccount account) {
    if (!configurable(account.getType())) {
      return List.of();
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
   * Assembles the approval-limit view of one account (REQ-BANK-041): configured ceilings per tier,
   * addressable role buckets and the edit flag.
   *
   * @param account the account
   * @param canEdit whether the calling surface may set or clear limits
   * @return the approval-limit DTO
   */
  @NotNull
  public BankApprovalLimitsDto assemble(@NotNull BankAccount account, boolean canEdit) {
    List<BankAccountApprovalLimit> limits = limitRepository.findByAccountId(account.getId());
    Map<String, BigDecimal> roleLimits = new LinkedHashMap<>();
    BigDecimal allMembersLimit = null;
    List<UUID> userIds =
        limits.stream()
            .filter(l -> l.getGranteeKind() == BankAccountViewGranteeKind.USER)
            .map(BankAccountApprovalLimit::getGranteeUserId)
            .toList();
    Map<UUID, String> names =
        userIds.isEmpty()
            ? Map.of()
            : userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEffectiveName));
    BigDecimal areaMembersLimit = null;
    List<BankApprovalLimitUserDto> userLimits = new ArrayList<>();
    for (BankAccountApprovalLimit limit : limits) {
      switch (limit.getGranteeKind()) {
        case MEMBERSHIP_ROLE, GLOBAL_ROLE ->
            roleLimits.put(limit.getRoleCode(), limit.getLimitAmount());
        case ALL_MEMBERS -> allMembersLimit = limit.getLimitAmount();
        case AREA_MEMBERS -> areaMembersLimit = limit.getLimitAmount();
        case USER ->
            userLimits.add(
                new BankApprovalLimitUserDto(
                    limit.getGranteeUserId(),
                    names.getOrDefault(limit.getGranteeUserId(), ""),
                    limit.getLimitAmount()));
        default -> {}
      }
    }
    return new BankApprovalLimitsDto(
        canEdit,
        audienceLimitsSupported(account.getType()),
        allMembersSupported(account.getType()),
        areaMembersSupported(account.getType()),
        roleBuckets(account),
        roleLimits,
        allMembersLimit,
        areaMembersLimit,
        List.copyOf(userLimits));
  }
}
