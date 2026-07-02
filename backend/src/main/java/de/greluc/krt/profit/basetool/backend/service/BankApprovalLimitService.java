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
 * Read-side helper for the per-account approval limits (REQ-BANK-041): it owns the tier dimensions
 * (which role buckets a limit may address per account type) and assembles the {@link
 * BankApprovalLimitsDto} shown on both account-detail surfaces. Deliberately org-unit-blind (it
 * consults only the limit rows and user names, never {@code OwnerScopeService}) so the bank-staff
 * {@code BankAccountService} may reuse it; the org-unit-aware decisions (who may edit, a
 * requester's applicable limit, the actual set/clear writes) live in the {@code
 * OrgUnitBankAccessService} seam.
 *
 * <p>Limits apply only to the request-capable account types {@code ORG_UNIT} / {@code AREA} /
 * {@code CARTEL} (REQ-BANK-039/-040); their role buckets mirror the account's configurable
 * visibility buckets (squadron sub-ranks / Bereich ranks), plus the all-members tier and individual
 * users.
 *
 * <p>The grantee-kind dimension (table {@code bank_account_approval_limit}) is the same four-kind
 * enum as the visibility-grant model on purpose, so the two tables stay structurally identical. One
 * kind, {@code GLOBAL_ROLE}, is therefore present in the schema and the switches but is never
 * produced for limits by design: it is the SPECIAL-account role bucket, and SPECIAL accounts (being
 * non-request-capable) carry no limits. The tier is kept rather than dropped to preserve the 1:1
 * mirror with the visibility model and to make a future "wire SPECIAL accounts into limits" a
 * purely additive change (REQ-BANK-041).
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
   * {@code true} iff the account type carries approval limits at all — the request-capable types
   * {@code ORG_UNIT} / {@code AREA} / {@code CARTEL} (REQ-BANK-039/-040). Sonderkonten and the
   * bank-operating account never receive booking requests, so no limit applies.
   *
   * @param type the account type
   * @return whether approval limits may be configured for this type
   */
  public static boolean configurable(@NotNull BankAccountType type) {
    return type == BankAccountType.ORG_UNIT
        || type == BankAccountType.AREA
        || type == BankAccountType.CARTEL;
  }

  /**
   * {@code true} iff the account type carries <em>per-audience</em> approval limits (the tiered
   * editor with role/all-members/area-members/user ceilings): the {@code ORG_UNIT} and {@code AREA}
   * accounts only. The KRT account ({@code CARTEL}) is request-capable ({@link #configurable}) but
   * is <em>not</em> per-audience-configurable — it uses the amount-tiered approval ladder managed
   * in the Verwaltung tab instead (REQ-BANK-047), which replaces the per-audience limits on it.
   *
   * @param type the account type
   * @return whether the per-audience limit editor applies to this type
   */
  public static boolean audienceLimitsSupported(@NotNull BankAccountType type) {
    return type == BankAccountType.ORG_UNIT || type == BankAccountType.AREA;
  }

  /**
   * {@code true} iff the account has an all-members limit tier — the per-audience-configurable
   * types ({@link #audienceLimitsSupported}): every member of the owning org unit who may view the
   * account may request, so capping that audience is meaningful.
   *
   * @param type the account type
   * @return whether the all-members limit tier applies
   */
  public static boolean allMembersSupported(@NotNull BankAccountType type) {
    return audienceLimitsSupported(type);
  }

  /**
   * {@code true} iff the account has a "Mitglieder des Bereichs" cascade limit tier — only the
   * {@code AREA} (Bereichskonto) accounts (REQ-BANK-048). The whole-area audience is meaningless
   * for a Staffel/SK account (no cascade) and the KRT account (no per-audience limits).
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
   * Assembles the approval-limit view of one account for an account-detail surface (REQ-BANK-041):
   * the configured per-tier ceilings (role buckets, all-members, individual users with resolved
   * names), the addressable role buckets and whether the calling surface may edit.
   *
   * @param account the account
   * @param canEdit whether the calling surface may set/clear limits (computed by the caller)
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
    List<BankApprovalLimitUserDto> userLimits = new java.util.ArrayList<>();
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
        default -> {
          // All BankAccountViewGranteeKind values are handled above; the default is unreachable and
          // present only to satisfy the MissingSwitchDefault check on the switch statement.
        }
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
