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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.supply.api.DonationStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedRef;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Maps needs and donations to their DTOs and applies the redaction rules of the supply module
 * (REQ-SUP-006, REQ-SUP-012). The caller's roles and the names of every unit and donor on a page
 * are read once per page, never per row (REQ-DATA-003).
 */
@Component
@RequiredArgsConstructor
public class SupplyMapper {

  private final SupplyAccessPolicy accessPolicy;
  private final OrgUnitRepository orgUnitRepository;
  private final UserRepository userRepository;
  private final AuthHelperService authHelperService;

  /**
   * What a page of needs is mapped against.
   *
   * @param units the raising units by id
   * @param admin whether the caller is an admin
   * @param leadership whether the caller belongs to the governing leadership
   * @param managed the units the caller manages explicitly
   * @param delegate whether the caller reads the purchase fields of every need
   */
  record NeedContext(
      Map<UUID, OrgUnit> units,
      boolean admin,
      boolean leadership,
      Set<UUID> managed,
      boolean delegate) {

    /**
     * Whether the caller manages the needs of a unit.
     *
     * @param unitId the unit
     * @return {@code true} for an admin, a manager of the unit or the governing leadership
     */
    boolean manages(UUID unitId) {
      return admin || managed.contains(unitId);
    }

    /**
     * Whether the caller reads the purchase fields of the needs of a unit.
     *
     * @param unitId the unit
     * @return {@code true} for a manager, the governing leadership or a delegate
     */
    boolean seesPurchase(UUID unitId) {
      return manages(unitId) || leadership || delegate;
    }
  }

  /**
   * What a page of donations is mapped against.
   *
   * @param units the receiving units by id
   * @param donors the donors by id
   * @param caller the caller, or {@code null}
   * @param admin whether the caller is an admin
   * @param leadership whether the caller belongs to the governing leadership
   * @param managed the units the caller manages explicitly
   */
  record DonationContext(
      Map<UUID, OrgUnit> units,
      Map<UUID, User> donors,
      @Nullable UUID caller,
      boolean admin,
      boolean leadership,
      Set<UUID> managed) {

    /**
     * Whether the caller manages the donations of a unit.
     *
     * @param unitId the unit
     * @return {@code true} for an admin or a manager of the unit
     */
    boolean manages(UUID unitId) {
      return admin || managed.contains(unitId);
    }
  }

  /**
   * Builds the context for a page of needs.
   *
   * @param needs the needs of the page
   * @return the context
   */
  @NotNull
  NeedContext needContext(@NotNull Collection<SupplyNeed> needs) {
    Set<UUID> unitIds = needs.stream().map(SupplyNeed::getOrgUnitId).collect(Collectors.toSet());
    boolean leadership = accessPolicy.isGoverningLeadership();
    boolean delegate = !leadership && !needs.isEmpty() && accessPolicy.isTradeDelegate();

    return new NeedContext(
        unitsById(unitIds),
        accessPolicy.isAdmin(),
        leadership,
        accessPolicy.managedUnitIds(),
        delegate);
  }

  /**
   * Builds the context for a page of donations.
   *
   * @param donations the donations of the page
   * @return the context
   */
  @NotNull
  DonationContext donationContext(@NotNull Collection<SupplyDonation> donations) {
    Set<UUID> unitIds =
        donations.stream().map(SupplyDonation::getReceivingOrgUnitId).collect(Collectors.toSet());
    Set<UUID> donorIds = new HashSet<>();
    for (SupplyDonation donation : donations) {
      if (donation.getDonorUserId() != null) {
        donorIds.add(donation.getDonorUserId());
      }
    }
    Map<UUID, User> donors = new HashMap<>();
    if (!donorIds.isEmpty()) {
      for (User user : userRepository.findAllById(donorIds)) {
        donors.put(user.getId(), user);
      }
    }
    return new DonationContext(
        unitsById(unitIds),
        donors,
        authHelperService.currentUserId().orElse(null),
        accessPolicy.isAdmin(),
        accessPolicy.isGoverningLeadership(),
        accessPolicy.managedUnitIds());
  }

  private Map<UUID, OrgUnit> unitsById(Set<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return orgUnitRepository.findAllById(ids).stream()
        .collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
  }

  /**
   * Maps a need.
   *
   * @param need the need, with its catalogue entry and tier loaded
   * @param context the page context
   * @return the DTO, with the purchase fields only for a caller who may read them
   */
  @NotNull
  NeedDto toDto(@NotNull SupplyNeed need, @NotNull NeedContext context) {
    OrgUnit unit = context.units().get(need.getOrgUnitId());
    boolean purchaseFields = context.seesPurchase(need.getOrgUnitId());
    Material material = need.getMaterial();
    return new NeedDto(
        need.getId(),
        need.getOrgUnitId(),
        unit == null ? null : unit.getName(),
        unit == null ? null : unit.getShorthand(),
        materialRef(material),
        gameItemRef(need.getGameItem()),
        quantityType(material),
        tierRef(need.getQualityTier()),
        need.getAmount(),
        need.getFulfilledAmount(),
        need.openAmount(),
        need.getPriority(),
        need.getStatus(),
        need.isPurchasable(),
        need.getPurchaseReason(),
        purchaseFields ? need.getMaxUnitPrice() : null,
        purchaseFields && need.isPurchasable() ? need.getPurchasedAmount() : null,
        purchaseFields && need.isPurchasable() && material != null ? material.getPriceBuy() : null,
        need.getJobOrderId(),
        context.manages(need.getOrgUnitId()),
        need.getVersion(),
        need.getCreatedAt());
  }

  /**
   * Maps a donation.
   *
   * @param donation the donation, with its catalogue entry loaded
   * @param context the page context
   * @return the DTO, with the donor's name only for a caller who may read it
   */
  @NotNull
  DonationDto toDto(@NotNull SupplyDonation donation, @NotNull DonationContext context) {
    OrgUnit unit = context.units().get(donation.getReceivingOrgUnitId());
    boolean donorIsMe =
        context.caller() != null && Objects.equals(context.caller(), donation.getDonorUserId());
    boolean manages = context.manages(donation.getReceivingOrgUnitId());
    boolean seesDonor = donorIsMe || context.admin() || context.leadership() || manages;
    User donor =
        donation.getDonorUserId() == null ? null : context.donors().get(donation.getDonorUserId());
    boolean pledged = donation.getStatus() == DonationStatus.PLEDGED;
    return new DonationDto(
        donation.getId(),
        donation.getReceivingOrgUnitId(),
        unit == null ? null : unit.getName(),
        materialRef(donation.getMaterial()),
        gameItemRef(donation.getGameItem()),
        donation.getQuality(),
        quantityType(donation.getMaterial()),
        donation.getPledgedAmount(),
        donation.getReceivedAmount(),
        donation.getNeedId(),
        donation.getStatus(),
        donation.getDeclineReason(),
        seesDonor && donor != null ? donor.getEffectiveName() : null,
        donorIsMe,
        donation.getSourceInventoryItemId() != null,
        donation.getReceivedAt(),
        pledged && manages,
        pledged && (donorIsMe || context.admin()),
        donation.getVersion(),
        donation.getCreatedAt());
  }

  private static @Nullable SupplyRefDto materialRef(@Nullable Material material) {
    return material == null ? null : new SupplyRefDto(material.getId(), material.getName());
  }

  private static @Nullable SupplyRefDto gameItemRef(@Nullable GameItem gameItem) {
    return gameItem == null ? null : new SupplyRefDto(gameItem.getId(), gameItem.getName());
  }

  private static @Nullable SupplyTierRefDto tierRef(@Nullable QualityTier tier) {
    return tier == null
        ? null
        : new SupplyTierRefDto(
            tier.getId(),
            tier.getCode(),
            tier.getLabelDe(),
            tier.getLabelEn(),
            tier.getMinQuality());
  }

  /**
   * Names the unit a catalogue entry is counted in.
   *
   * @param material the material, or {@code null} for a game item
   * @return {@code ITEM} for a game item, else the material's quantity type
   */
  static @NotNull String quantityType(@Nullable Material material) {
    if (material == null) {
      return "ITEM";
    }
    return material.getQuantityType() == QuantityType.PIECE ? "PIECE" : "SCU";
  }

  /**
   * Projects a need onto what other modules may read of it.
   *
   * @param need the need, with its catalogue entry and tier loaded
   * @return the reference
   */
  static @NotNull NeedRef toRef(@NotNull SupplyNeed need) {
    Material material = need.getMaterial();
    GameItem gameItem = need.getGameItem();
    QualityTier tier = need.getQualityTier();
    return new NeedRef(
        need.getId(),
        need.getOrgUnitId(),
        material == null ? null : material.getId(),
        gameItem == null ? null : gameItem.getId(),
        tier == null ? null : tier.getId(),
        tier == null ? 0 : tier.getMinQuality(),
        material != null ? material.getName() : gameItem == null ? "—" : gameItem.getName(),
        gameItem != null || (material != null && material.getQuantityType() == QuantityType.PIECE),
        need.getAmount(),
        need.getFulfilledAmount(),
        need.getPurchasedAmount(),
        need.isPurchasable(),
        need.getMaxUnitPrice(),
        need.getStatus());
  }

  /**
   * Tells whether a need's status still accepts pledges.
   *
   * @param status the need's status
   * @return {@code true} only for {@code OPEN}
   */
  static boolean acceptsPledges(@NotNull NeedStatus status) {
    return status == NeedStatus.OPEN;
  }
}
