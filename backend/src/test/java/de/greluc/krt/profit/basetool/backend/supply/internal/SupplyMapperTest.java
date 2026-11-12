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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialCoverage;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialCoverage.BucketCoverage;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialCoverage.OrderCoverage;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.supply.api.DonationStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.PurchaseReason;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The redaction of the purchase fields and the job-order link of a need and of the donor of a
 * donation, and the manage flags inside the active context (REQ-SUP-003, REQ-SUP-006, REQ-SUP-012,
 * REQ-SUP-023).
 */
class SupplyMapperTest {

  private static final UUID DONOR = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();
  private static final UUID ANOTHER_UNIT = UUID.randomUUID();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private SupplyMapper mapper;
  private Squadron unit;
  private Material material;
  private User donor;

  @BeforeEach
  void setUp() {
    mapper =
        new SupplyMapper(
            mock(SupplyAccessPolicy.class),
            mock(OrgUnitRepository.class),
            mock(UserRepository.class),
            mock(AuthHelperService.class),
            mock(JobOrderMaterialCoverage.class));
    unit = new Squadron();
    unit.setId(UUID.randomUUID());
    unit.setName("Alpha");
    unit.setShorthand("ALP");
    material = new Material();
    material.setId(UUID.randomUUID());
    material.setName("Quantainium");
    material.setQuantityType(QuantityType.SCU);
    material.setPriceBuy(88.0);
    donor = new User();
    donor.setId(DONOR);
    donor.setDisplayName("Donor Dana");
  }

  private SupplyNeed purchasableNeed() {
    SupplyNeed need = new SupplyNeed();
    need.setId(UUID.randomUUID());
    need.setOrgUnitId(unit.getId());
    need.setMaterial(material);
    need.setAmount(10.0);
    need.setFulfilledAmount(2.0);
    need.setPurchasedAmount(1.0);
    need.setPurchasable(true);
    need.setPurchaseReason(PurchaseReason.TIME_CRITICAL);
    need.setMaxUnitPrice(5000L);
    need.setStatus(NeedStatus.OPEN);
    return need;
  }

  private SupplyMapper.NeedContext needContext(
      boolean everyUnit, boolean leadership, Set<UUID> managed, boolean delegate) {
    return new SupplyMapper.NeedContext(
        Map.of(unit.getId(), unit),
        leadership,
        new SupplyManagedUnits(everyUnit, managed),
        delegate,
        Map.of());
  }

  private SupplyNeed linkedNeed(UUID orderId, QualityTier tier) {
    SupplyNeed need = purchasableNeed();
    need.setQualityTier(tier);
    need.setJobOrderId(orderId);
    return need;
  }

  private SupplyMapper.NeedContext linkedContext(Set<UUID> managed, OrderCoverage order) {
    return new SupplyMapper.NeedContext(
        Map.of(unit.getId(), unit),
        false,
        new SupplyManagedUnits(false, managed),
        false,
        Map.of(order.id(), order));
  }

  /**
   * A mapper that decides by the real manage rule for the given caller.
   *
   * @param policy the caller's policy
   * @param coverage the coverage of the linked orders
   * @return the mapper
   */
  private static SupplyMapper mapperFor(
      SupplyAccessPolicy policy, JobOrderMaterialCoverage coverage) {
    return new SupplyMapper(
        policy,
        mock(OrgUnitRepository.class),
        mock(UserRepository.class),
        mock(AuthHelperService.class),
        coverage);
  }

  private static QualityTier goodTier() {
    QualityTier good = new QualityTier();
    good.setId(UUID.randomUUID());
    return good;
  }

  @Test
  void anAdminPinnedToAnotherUnitGetsNeitherTheManageFlagNorTheOrderLinkOfANeed() {
    UUID orderId = UUID.randomUUID();
    SupplyNeed need = linkedNeed(orderId, goodTier());
    JobOrderMaterialCoverage coverage = mock(JobOrderMaterialCoverage.class);
    SupplyMapper pinned = mapperFor(SupplyAccessPolicies.admin(ANOTHER_UNIT), coverage);

    NeedDto dto = pinned.toDto(need, pinned.needContext(List.of(need)));

    assertThat(dto.canManage()).isFalse();
    assertThat(dto.jobOrderId()).isNull();
    assertThat(dto.jobOrder()).isNull();
    assertThat(dto.maxUnitPrice()).isEqualTo(5000L);
    verify(coverage).coverageOf(Set.of());
  }

  @Test
  void anAdminWithoutAPinOrPinnedToTheRaisingUnitManagesTheNeed() {
    UUID orderId = UUID.randomUUID();
    SupplyNeed need = linkedNeed(orderId, goodTier());
    JobOrderMaterialCoverage coverage = mock(JobOrderMaterialCoverage.class);
    when(coverage.coverageOf(Set.of(orderId)))
        .thenReturn(
            Map.of(orderId, new OrderCoverage(orderId, 42, JobOrderStatus.OPEN, List.of(), null)));
    SupplyMapper unpinned = mapperFor(SupplyAccessPolicies.admin(null), coverage);
    SupplyMapper pinned = mapperFor(SupplyAccessPolicies.admin(unit.getId()), coverage);

    NeedDto everyUnit = unpinned.toDto(need, unpinned.needContext(List.of(need)));
    NeedDto pinnedUnit = pinned.toDto(need, pinned.needContext(List.of(need)));

    assertThat(everyUnit.canManage()).isTrue();
    assertThat(everyUnit.jobOrder()).isNotNull();
    assertThat(pinnedUnit.canManage()).isTrue();
    assertThat(pinnedUnit.jobOrderId()).isEqualTo(orderId);
    assertThat(pinnedUnit.jobOrder()).isNotNull();
  }

  @Test
  void theUnitsManagerSeesHowFarTheLinkedOrderCoversTheNeed() {
    QualityTier good = goodTier();
    UUID orderId = UUID.randomUUID();
    OrderCoverage order =
        new OrderCoverage(
            orderId,
            42,
            JobOrderStatus.IN_PROGRESS,
            List.of(new BucketCoverage(material.getId(), good.getId(), 12.4, 12.4)),
            null);

    NeedDto dto =
        mapper.toDto(linkedNeed(orderId, good), linkedContext(Set.of(unit.getId()), order));

    assertThat(dto.jobOrderId()).isEqualTo(orderId);
    assertThat(dto.jobOrder())
        .isEqualTo(new NeedOrderDto(orderId, 42, JobOrderStatus.IN_PROGRESS, 12.4, 12.4));
  }

  @Test
  void aLinkedOrderWithoutTheNeedsBucketCarriesNoCoverage() {
    QualityTier good = goodTier();
    UUID orderId = UUID.randomUUID();
    OrderCoverage order = new OrderCoverage(orderId, 42, JobOrderStatus.OPEN, List.of(), null);

    NeedDto dto =
        mapper.toDto(linkedNeed(orderId, good), linkedContext(Set.of(unit.getId()), order));

    assertThat(dto.jobOrder()).isNotNull();
    assertThat(dto.jobOrder().requiredAmount()).isNull();
    assertThat(dto.jobOrder().bookedAmount()).isNull();
  }

  @Test
  void aMemberWhoDoesNotManageTheUnitAndAClosedNeedGetNoLinkedOrder() {
    QualityTier good = goodTier();
    UUID orderId = UUID.randomUUID();
    OrderCoverage order = new OrderCoverage(orderId, 42, JobOrderStatus.OPEN, List.of(), null);
    SupplyNeed fulfilled = linkedNeed(orderId, good);
    fulfilled.setStatus(NeedStatus.FULFILLED);

    NeedDto foreign = mapper.toDto(linkedNeed(orderId, good), linkedContext(Set.of(), order));

    assertThat(foreign.jobOrder()).isNull();
    assertThat(foreign.jobOrderId()).isNull();
    assertThat(mapper.toDto(fulfilled, linkedContext(Set.of(unit.getId()), order)).jobOrder())
        .isNull();
  }

  private static JsonNode onTheWire(NeedDto dto) {
    return JSON.readTree(JSON.writeValueAsString(dto));
  }

  @Test
  void theResponseCarriesTheOrderLinkOnlyForAManagerOfTheUnit() {
    QualityTier good = goodTier();
    UUID orderId = UUID.randomUUID();
    OrderCoverage order = new OrderCoverage(orderId, 42, JobOrderStatus.OPEN, List.of(), null);
    SupplyNeed need = linkedNeed(orderId, good);

    JsonNode foreign = onTheWire(mapper.toDto(need, linkedContext(Set.of(), order)));
    JsonNode managed = onTheWire(mapper.toDto(need, linkedContext(Set.of(unit.getId()), order)));

    assertThat(foreign.has("jobOrderId")).isFalse();
    assertThat(foreign.has("jobOrder")).isFalse();
    assertThat(managed.get("jobOrderId").asString()).isEqualTo(orderId.toString());
  }

  @Test
  void anUnpinnedAdminReceivesTheOrderLinkButTheLeadershipAndADelegateWithoutManagingDoNot() {
    QualityTier good = goodTier();
    UUID orderId = UUID.randomUUID();
    SupplyNeed need = linkedNeed(orderId, good);

    NeedDto admin = mapper.toDto(need, needContext(true, false, Set.of(), false));
    NeedDto leadership = mapper.toDto(need, needContext(false, true, Set.of(), false));
    NeedDto delegate = mapper.toDto(need, needContext(false, false, Set.of(), true));

    assertThat(admin.jobOrderId()).isEqualTo(orderId);
    assertThat(leadership.maxUnitPrice()).isEqualTo(5000L);
    assertThat(leadership.jobOrderId()).isNull();
    assertThat(delegate.maxUnitPrice()).isEqualTo(5000L);
    assertThat(delegate.jobOrderId()).isNull();
  }

  @Test
  void aPlainMemberDoesNotReceiveThePurchaseFields() {
    NeedDto dto = mapper.toDto(purchasableNeed(), needContext(false, false, Set.of(), false));

    assertThat(dto.purchasable()).isTrue();
    assertThat(dto.purchaseReason()).isEqualTo(PurchaseReason.TIME_CRITICAL);
    assertThat(dto.maxUnitPrice()).isNull();
    assertThat(dto.purchasedAmount()).isNull();
    assertThat(dto.uexReferencePrice()).isNull();
    assertThat(dto.canManage()).isFalse();
    assertThat(dto.openAmount()).isEqualTo(8.0);
  }

  @Test
  void theRaisingUnitsManagerReceivesThePurchaseFields() {
    NeedDto dto =
        mapper.toDto(purchasableNeed(), needContext(false, false, Set.of(unit.getId()), false));

    assertThat(dto.maxUnitPrice()).isEqualTo(5000L);
    assertThat(dto.purchasedAmount()).isEqualTo(1.0);
    assertThat(dto.uexReferencePrice()).isEqualTo(88.0);
    assertThat(dto.canManage()).isTrue();
  }

  @Test
  void theGoverningLeadershipAndATradeDelegateReceiveThePurchaseFieldsWithoutManaging() {
    NeedDto leadership = mapper.toDto(purchasableNeed(), needContext(false, true, Set.of(), false));
    NeedDto delegate = mapper.toDto(purchasableNeed(), needContext(false, false, Set.of(), true));

    assertThat(leadership.maxUnitPrice()).isEqualTo(5000L);
    assertThat(leadership.canManage()).isFalse();
    assertThat(delegate.maxUnitPrice()).isEqualTo(5000L);
    assertThat(delegate.canManage()).isFalse();
  }

  @Test
  void aNeedThatIsNotPurchasableCarriesNoPurchasedAmountOrReferencePrice() {
    SupplyNeed need = purchasableNeed();
    need.setPurchasable(false);

    NeedDto dto = mapper.toDto(need, needContext(true, false, Set.of(), false));

    assertThat(dto.purchasedAmount()).isNull();
    assertThat(dto.uexReferencePrice()).isNull();
  }

  private SupplyDonation donation(DonationStatus status) {
    SupplyDonation donation = new SupplyDonation();
    donation.setId(UUID.randomUUID());
    donation.setDonorUserId(DONOR);
    donation.setReceivingOrgUnitId(unit.getId());
    donation.setMaterial(material);
    donation.setQuality(500);
    donation.setPledgedAmount(3.0);
    donation.setStatus(status);
    return donation;
  }

  private SupplyMapper.DonationContext donationContext(
      UUID caller, boolean admin, boolean leadership, Set<UUID> managed) {
    return new SupplyMapper.DonationContext(
        Map.of(unit.getId(), unit),
        Map.of(DONOR, donor),
        caller,
        admin,
        leadership,
        new SupplyManagedUnits(false, managed));
  }

  @Test
  void anAdminConfirmsOnlyThePledgesOfThePinnedUnit() {
    SupplyDonation pledge = donation(DonationStatus.PLEDGED);
    SupplyMapper foreign =
        mapperFor(SupplyAccessPolicies.admin(ANOTHER_UNIT), mock(JobOrderMaterialCoverage.class));
    SupplyMapper own =
        mapperFor(SupplyAccessPolicies.admin(unit.getId()), mock(JobOrderMaterialCoverage.class));

    DonationDto elsewhere = foreign.toDto(pledge, foreign.donationContext(List.of(pledge)));
    DonationDto pinned = own.toDto(pledge, own.donationContext(List.of(pledge)));

    assertThat(elsewhere.canReceive()).isFalse();
    assertThat(pinned.canReceive()).isTrue();
  }

  @Test
  void theDonorTheReceivingUnitTheLeadershipAndAnAdminSeeTheDonor() {
    SupplyDonation donation = donation(DonationStatus.RECEIVED);

    assertThat(mapper.toDto(donation, donationContext(DONOR, false, false, Set.of())).donorName())
        .isEqualTo("Donor Dana");
    assertThat(
            mapper
                .toDto(donation, donationContext(OTHER, false, false, Set.of(unit.getId())))
                .donorName())
        .isEqualTo("Donor Dana");
    assertThat(mapper.toDto(donation, donationContext(OTHER, false, true, Set.of())).donorName())
        .isEqualTo("Donor Dana");
    assertThat(mapper.toDto(donation, donationContext(OTHER, true, false, Set.of())).donorName())
        .isEqualTo("Donor Dana");
  }

  @Test
  void anotherMemberDoesNotSeeTheDonor() {
    assertThat(
            mapper
                .toDto(
                    donation(DonationStatus.RECEIVED),
                    donationContext(OTHER, false, false, Set.of()))
                .donorName())
        .isNull();
  }

  @Test
  void onlyAPledgedDonationOffersReceiveAndWithdraw() {
    SupplyMapper.DonationContext manager =
        donationContext(OTHER, false, false, Set.of(unit.getId()));
    SupplyMapper.DonationContext owner = donationContext(DONOR, false, false, Set.of());

    DonationDto pledgedForManager = mapper.toDto(donation(DonationStatus.PLEDGED), manager);
    DonationDto pledgedForOwner = mapper.toDto(donation(DonationStatus.PLEDGED), owner);
    DonationDto received = mapper.toDto(donation(DonationStatus.RECEIVED), manager);

    assertThat(pledgedForManager.canReceive()).isTrue();
    assertThat(pledgedForManager.canWithdraw()).isFalse();
    assertThat(pledgedForOwner.canWithdraw()).isTrue();
    assertThat(pledgedForOwner.canReceive()).isFalse();
    assertThat(pledgedForOwner.donorIsMe()).isTrue();
    assertThat(received.canReceive()).isFalse();
    assertThat(received.canWithdraw()).isFalse();
  }

  @Test
  void theQuantityTypeNamesThePieceAndItemEntries() {
    assertThat(SupplyMapper.quantityType(null)).isEqualTo("ITEM");
    assertThat(SupplyMapper.quantityType(material)).isEqualTo("SCU");
    material.setQuantityType(QuantityType.PIECE);
    assertThat(SupplyMapper.quantityType(material)).isEqualTo("PIECE");
  }

  @Test
  void onlyAnOpenNeedAcceptsPledges() {
    assertThat(SupplyMapper.acceptsPledges(NeedStatus.OPEN)).isTrue();
    assertThat(SupplyMapper.acceptsPledges(NeedStatus.PAUSED)).isFalse();
    assertThat(SupplyMapper.acceptsPledges(NeedStatus.FULFILLED)).isFalse();
  }

  @Test
  void aNeedAndADonationForAShieldCarryItsTypeSizeGradeAndClass() {
    GameItem shield = new GameItem();
    shield.setId(UUID.randomUUID());
    shield.setName("FR-66");
    shield.setKind(GameItemKind.VEHICLE_ITEM);
    shield.setWikiType("Shield");
    shield.setSizeClass(1);
    shield.setGrade("A");
    shield.setItemClass(ShipComponentClass.MILITARY);
    SupplyNeed need = purchasableNeed();
    need.setMaterial(null);
    need.setGameItem(shield);
    SupplyDonation donation = donation(DonationStatus.PLEDGED);
    donation.setMaterial(null);
    donation.setGameItem(shield);
    GameItemReferenceDto expected =
        new GameItemReferenceDto(
            shield.getId(), "FR-66", "VEHICLE_ITEM", "SHIELD", 1, "A", "MILITARY");

    assertThat(mapper.toDto(need, needContext(false, false, Set.of(), false)).gameItem())
        .isEqualTo(expected);
    assertThat(mapper.toDto(donation, donationContext(DONOR, false, false, Set.of())).gameItem())
        .isEqualTo(expected);
  }

  @Test
  void aGameItemThatIsNoComponentCarriesItsKindOnly() {
    GameItem paint = new GameItem();
    paint.setId(UUID.randomUUID());
    paint.setName("Paint");
    paint.setKind(GameItemKind.VEHICLE_ITEM);
    paint.setWikiType("Paints");
    paint.setGrade("A");
    SupplyNeed need = purchasableNeed();
    need.setMaterial(null);
    need.setGameItem(paint);

    assertThat(mapper.toDto(need, needContext(false, false, Set.of(), false)).gameItem())
        .isEqualTo(new GameItemReferenceDto(paint.getId(), "Paint", "VEHICLE_ITEM"));
  }
}
