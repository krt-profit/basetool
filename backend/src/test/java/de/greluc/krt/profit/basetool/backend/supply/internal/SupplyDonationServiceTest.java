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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockBookingReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.supply.api.DeclineReason;
import de.greluc.krt.profit.basetool.backend.supply.api.DonationStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyProblemCode;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyProblemException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Pledging, receiving, declining, withdrawing and recording donations (REQ-SUP-007 … REQ-SUP-012,
 * REQ-SUP-016, REQ-SUP-017).
 */
@ExtendWith(MockitoExtension.class)
class SupplyDonationServiceTest {

  private static final UUID DONOR = UUID.randomUUID();
  private static final UUID HOLDER = UUID.randomUUID();

  @Mock private SupplyDonationRepository donationRepository;
  @Mock private SupplyNeedRepository needRepository;
  @Mock private SupplyUnitSettingRepository unitSettingRepository;
  @Mock private SupplyNeedService needService;
  @Mock private MaterialRepository materialRepository;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private UserRepository userRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private StockCommands stockCommands;
  @Mock private StockReads stockReads;
  @Mock private SupplySettingsService settingsService;
  @Mock private SupplyAccessPolicy accessPolicy;
  @Mock private SupplyMapper mapper;
  @Mock private AuditRecorder auditRecorder;
  @Mock private AuthHelperService authHelperService;
  @Mock private ApplicationEventPublisher eventPublisher;

  private SupplyDonationService service;
  private Material material;
  private Location location;
  private OrgUnit unit;
  private User donor;

  @BeforeEach
  void setUp() {
    service =
        new SupplyDonationService(
            donationRepository,
            needRepository,
            unitSettingRepository,
            needService,
            materialRepository,
            gameItemRepository,
            locationRepository,
            userRepository,
            orgUnitRepository,
            stockCommands,
            stockReads,
            settingsService,
            accessPolicy,
            mapper,
            auditRecorder,
            authHelperService,
            eventPublisher,
            new SimpleMeterRegistry());
    material = new Material();
    material.setId(UUID.randomUUID());
    material.setName("Quantainium");
    material.setQuantityType(QuantityType.SCU);
    location = new Location();
    location.setId(UUID.randomUUID());
    location.setName("Lorville");
    unit = new Squadron();
    unit.setId(UUID.randomUUID());
    unit.setName("Bereich Profit");
    donor = new User();
    donor.setId(DONOR);
  }

  private void callerIs(UUID caller) {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(caller));
  }

  private void unitAccepts(boolean accepts) {
    SupplyUnitSetting setting = new SupplyUnitSetting();
    setting.setId(unit.getId());
    setting.setAcceptsDonations(accepts);
    when(unitSettingRepository.findById(unit.getId())).thenReturn(Optional.of(setting));
  }

  private SupplyNeed openNeed(int minQuality) {
    SupplyNeed need = new SupplyNeed();
    need.setId(UUID.randomUUID());
    need.setOrgUnitId(unit.getId());
    need.setMaterial(material);
    QualityTier tier = new QualityTier();
    tier.setMinQuality(minQuality);
    need.setQualityTier(tier);
    need.setAmount(10.0);
    need.setStatus(NeedStatus.OPEN);
    return need;
  }

  private SupplyDonation pledged(double amount) {
    SupplyDonation donation = new SupplyDonation();
    donation.setId(UUID.randomUUID());
    donation.setDonorUserId(DONOR);
    donation.setReceivingOrgUnitId(unit.getId());
    donation.setMaterial(material);
    donation.setQuality(500);
    donation.setPledgedAmount(amount);
    donation.setStatus(DonationStatus.PLEDGED);
    donation.setVersion(2L);
    return donation;
  }

  private PledgeRequest plainPledge(UUID needId, double amount, Integer quality) {
    return new PledgeRequest(needId, unit.getId(), material.getId(), null, quality, amount, null);
  }

  @Test
  void aPledgeToAUnitThatTakesNoDonationsIsRefused() {
    callerIs(DONOR);
    unitAccepts(false);

    assertThatThrownBy(() -> service.pledge(plainPledge(null, 2, 500)))
        .isInstanceOfSatisfying(
            SupplyProblemException.class,
            e ->
                assertThat(e.getProblemCode())
                    .isEqualTo(SupplyProblemCode.SUPPLY_UNIT_CANNOT_RECEIVE));

    verify(donationRepository, never()).save(any());
  }

  @Test
  void aPledgeNeedsASignedInMember() {
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.pledge(plainPledge(null, 2, 500)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aPledgeToAClosedNeedIsRefused() {
    SupplyNeed need = openNeed(0);
    need.setStatus(NeedStatus.PAUSED);
    callerIs(DONOR);
    when(needRepository.findById(need.getId())).thenReturn(Optional.of(need));

    assertThatThrownBy(() -> service.pledge(plainPledge(need.getId(), 2, 500)))
        .isInstanceOfSatisfying(
            SupplyProblemException.class,
            e -> assertThat(e.getProblemCode()).isEqualTo(SupplyProblemCode.SUPPLY_NEED_NOT_OPEN));
  }

  @Test
  void aPledgeBelowTheNeedsTierDoesNotFit() {
    SupplyNeed need = openNeed(800);
    callerIs(DONOR);
    when(needRepository.findById(need.getId())).thenReturn(Optional.of(need));
    unitAccepts(true);

    assertThatThrownBy(() -> service.pledge(plainPledge(need.getId(), 2, 500)))
        .isInstanceOfSatisfying(
            SupplyProblemException.class,
            e ->
                assertThat(e.getProblemCode())
                    .isEqualTo(SupplyProblemCode.SUPPLY_DONATION_DOES_NOT_FIT_NEED));
  }

  @Test
  void aPledgeNamingAnotherUnitThanTheNeedsIsRefused() {
    SupplyNeed need = openNeed(0);
    callerIs(DONOR);
    when(needRepository.findById(need.getId())).thenReturn(Optional.of(need));

    PledgeRequest request =
        new PledgeRequest(need.getId(), UUID.randomUUID(), material.getId(), null, 500, 2.0, null);

    assertThatThrownBy(() -> service.pledge(request))
        .isInstanceOfSatisfying(
            SupplyProblemException.class,
            e ->
                assertThat(e.getProblemCode())
                    .isEqualTo(SupplyProblemCode.SUPPLY_NEED_UNIT_MISMATCH));
  }

  @Test
  void aPledgeFromAnotherMembersRowIsRefused() {
    UUID rowId = UUID.randomUUID();
    StockRow row = org.mockito.Mockito.mock(StockRow.class);
    User other = new User();
    other.setId(UUID.randomUUID());
    when(row.getUser()).thenReturn(other);
    callerIs(DONOR);
    unitAccepts(true);
    when(stockReads.findRow(rowId)).thenReturn(Optional.of(row));

    PledgeRequest request = new PledgeRequest(null, unit.getId(), null, null, null, 2.0, rowId);

    assertThatThrownBy(() -> service.pledge(request))
        .isInstanceOfSatisfying(
            SupplyProblemException.class,
            e -> assertThat(e.getProblemCode()).isEqualTo(SupplyProblemCode.SUPPLY_ROW_NOT_OWNED));
  }

  @Test
  void aPledgeStoresThePledgedStateAuditsItAndNotifiesTheUnit() {
    callerIs(DONOR);
    unitAccepts(true);
    when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
    when(orgUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
    when(userRepository.findById(DONOR)).thenReturn(Optional.of(donor));
    when(donationRepository.save(any(SupplyDonation.class)))
        .thenAnswer(invocation -> {
          SupplyDonation saved = invocation.getArgument(0);
          saved.setId(UUID.randomUUID());
          return saved;
        });

    service.pledge(plainPledge(null, 4.0, 700));

    ArgumentCaptor<SupplyDonation> saved = ArgumentCaptor.forClass(SupplyDonation.class);
    verify(donationRepository).save(saved.capture());
    assertThat(saved.getValue().getStatus()).isEqualTo(DonationStatus.PLEDGED);
    assertThat(saved.getValue().getDonorUserId()).isEqualTo(DONOR);
    assertThat(saved.getValue().getPledgedAmount()).isEqualTo(4.0);
    assertThat(saved.getValue().getQuality()).isEqualTo(700);
    verify(stockCommands, never()).earmarkForDonation(any(), any(), anyDouble());
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DONATION_PLEDGED),
            eq(saved.getValue().getId()),
            eq("Quantainium"),
            eq(DONOR),
            any());
    verify(eventPublisher).publishEvent(any(NoticeEvent.class));
  }

  @Test
  void receivingMoreThanPledgedIsRefused() {
    SupplyDonation donation = pledged(4.0);
    callerIs(HOLDER);
    when(donationRepository.findByIdForUpdate(donation.getId())).thenReturn(Optional.of(donation));
    when(accessPolicy.canManageUnit(unit.getId())).thenReturn(true);

    assertThatThrownBy(() ->
            service.receive(
                donation.getId(), new ReceiveDonationRequest(5.0, location.getId(), 2L)))
        .isInstanceOf(BadRequestException.class);

    verify(stockCommands, never()).bookIntoPool(any(), any(), any(), anyDouble(), any());
  }

  @Test
  void aCallerWhoDoesNotManageTheUnitCannotReceive() {
    SupplyDonation donation = pledged(4.0);
    callerIs(HOLDER);
    when(donationRepository.findByIdForUpdate(donation.getId())).thenReturn(Optional.of(donation));
    when(accessPolicy.canManageUnit(unit.getId())).thenReturn(false);

    assertThatThrownBy(() ->
            service.receive(
                donation.getId(), new ReceiveDonationRequest(4.0, location.getId(), 2L)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aStaleVersionConflictsOnReceive() {
    SupplyDonation donation = pledged(4.0);
    callerIs(HOLDER);
    when(donationRepository.findByIdForUpdate(donation.getId())).thenReturn(Optional.of(donation));

    assertThatThrownBy(() ->
            service.receive(
                donation.getId(), new ReceiveDonationRequest(4.0, location.getId(), 1L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void aDonationThatIsNoLongerPledgedCannotBeReceivedAgain() {
    SupplyDonation donation = pledged(4.0);
    donation.setStatus(DonationStatus.RECEIVED);
    callerIs(HOLDER);
    when(donationRepository.findByIdForUpdate(donation.getId())).thenReturn(Optional.of(donation));

    assertThatThrownBy(() ->
            service.receive(
                donation.getId(), new ReceiveDonationRequest(4.0, location.getId(), 2L)))
        .isInstanceOfSatisfying(
            SupplyProblemException.class,
            e ->
                assertThat(e.getProblemCode())
                    .isEqualTo(SupplyProblemCode.SUPPLY_DONATION_NOT_PLEDGED));
  }

  @Test
  void aFullReceiptBooksIntoThePoolCreditsTheNeedAndTellsTheDonor() {
    SupplyDonation donation = pledged(4.0);
    UUID needId = UUID.randomUUID();
    donation.setNeedId(needId);
    callerIs(HOLDER);
    when(donationRepository.findByIdForUpdate(donation.getId())).thenReturn(Optional.of(donation));
    when(accessPolicy.canManageUnit(unit.getId())).thenReturn(true);
    when(locationRepository.findById(location.getId())).thenReturn(Optional.of(location));
    when(orgUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
    when(donationRepository.saveAndFlush(donation)).thenReturn(donation);

    service.receive(donation.getId(), new ReceiveDonationRequest(4.0, location.getId(), 2L));

    assertThat(donation.getStatus()).isEqualTo(DonationStatus.RECEIVED);
    assertThat(donation.getReceivedAmount()).isEqualTo(4.0);
    assertThat(donation.getReceivedBy()).isEqualTo(HOLDER);
    verify(stockCommands)
        .bookIntoPool(eq(HOLDER), eq(unit), any(), eq(4.0), eq(StockBookingReason.DONATION));
    verify(needService).credit(needId, 4.0);
    verify(auditRecorder)
        .record(eq(AuditEventType.DONATION_RECEIVED), eq(donation.getId()), any(), any(), any());
    verify(eventPublisher).publishEvent(any(NoticeEvent.class));
  }

  @Test
  void aPartialReceiptKeepsThePartiallyReceivedState() {
    SupplyDonation donation = pledged(4.0);
    callerIs(HOLDER);
    when(donationRepository.findByIdForUpdate(donation.getId())).thenReturn(Optional.of(donation));
    when(accessPolicy.canManageUnit(unit.getId())).thenReturn(true);
    when(locationRepository.findById(location.getId())).thenReturn(Optional.of(location));
    when(orgUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
    when(donationRepository.saveAndFlush(donation)).thenReturn(donation);

    service.receive(donation.getId(), new ReceiveDonationRequest(1.5, location.getId(), 2L));

    assertThat(donation.getStatus()).isEqualTo(DonationStatus.PARTIALLY_RECEIVED);
    assertThat(donation.getReceivedAmount()).isEqualTo(1.5);
    verify(needService, never()).credit(any(), anyDouble());
  }

  @Test
  void aReceiptFromAnEarmarkedRowTransfersTheRowAndReleasesTheEarmark() {
    SupplyDonation donation = pledged(4.0);
    UUID rowId = UUID.randomUUID();
    donation.setSourceInventoryItemId(rowId);
    StockRow row = org.mockito.Mockito.mock(StockRow.class);
    when(row.getMaterial()).thenReturn(material);
    when(row.getLocation()).thenReturn(location);
    when(row.getQuality()).thenReturn(500);
    when(row.getStolen()).thenReturn(false);
    callerIs(HOLDER);
    when(donationRepository.findByIdForUpdate(donation.getId())).thenReturn(Optional.of(donation));
    when(accessPolicy.canManageUnit(unit.getId())).thenReturn(true);
    when(locationRepository.findById(location.getId())).thenReturn(Optional.of(location));
    when(orgUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
    when(stockReads.findRow(rowId)).thenReturn(Optional.of(row));
    when(row.getId()).thenReturn(rowId);
    when(donationRepository.saveAndFlush(donation)).thenReturn(donation);

    service.receive(donation.getId(), new ReceiveDonationRequest(4.0, location.getId(), 2L));

    verify(stockCommands).releaseDonationEarmark(donation.getId());
    verify(stockCommands)
        .transferToPool(
            eq(rowId),
            eq(4.0),
            eq(unit),
            eq(HOLDER),
            eq(location),
            eq(StockBookingReason.DONATION));
    verify(stockCommands, never()).bookIntoPool(any(), any(), any(), anyDouble(), any());
  }

  @Test
  void aDeclineStoresTheReasonReleasesTheEarmarkAndAudits() {
    SupplyDonation donation = pledged(4.0);
    donation.setSourceInventoryItemId(UUID.randomUUID());
    StockRow row = org.mockito.Mockito.mock(StockRow.class);
    when(row.getUser()).thenReturn(donor);
    when(row.getMaterial()).thenReturn(material);
    when(row.getLocation()).thenReturn(location);
    callerIs(HOLDER);
    when(donationRepository.findByIdForUpdate(donation.getId())).thenReturn(Optional.of(donation));
    when(accessPolicy.canManageUnit(unit.getId())).thenReturn(true);
    when(stockReads.findRow(donation.getSourceInventoryItemId())).thenReturn(Optional.of(row));
    when(orgUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
    when(donationRepository.saveAndFlush(donation)).thenReturn(donation);

    service.decline(donation.getId(), new DeclineDonationRequest(DeclineReason.WRONG_ITEM, 2L));

    assertThat(donation.getStatus()).isEqualTo(DonationStatus.DECLINED);
    assertThat(donation.getDeclineReason()).isEqualTo(DeclineReason.WRONG_ITEM);
    verify(stockCommands).releaseDonationEarmark(donation.getId());
    verify(auditRecorder)
        .record(eq(AuditEventType.DONATION_DECLINED), eq(donation.getId()), any(), any(), any());
    verify(eventPublisher).publishEvent(any(NoticeEvent.class));
  }

  @Test
  void onlyTheDonorOrAnAdminWithdrawsAPledge() {
    SupplyDonation donation = pledged(4.0);
    UUID stranger = UUID.randomUUID();
    callerIs(stranger);
    when(donationRepository.findByIdForUpdate(donation.getId())).thenReturn(Optional.of(donation));
    when(accessPolicy.canWithdrawPledge(DONOR, stranger)).thenReturn(false);

    assertThatThrownBy(() -> service.withdraw(donation.getId(), 2L))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(donation.getStatus()).isEqualTo(DonationStatus.PLEDGED);
  }

  @Test
  void theDonorWithdrawsAPledgeAndTheNoticeIsPublished() {
    SupplyDonation donation = pledged(4.0);
    callerIs(DONOR);
    when(donationRepository.findByIdForUpdate(donation.getId())).thenReturn(Optional.of(donation));
    when(accessPolicy.canWithdrawPledge(DONOR, DONOR)).thenReturn(true);
    when(donationRepository.saveAndFlush(donation)).thenReturn(donation);

    service.withdraw(donation.getId(), 2L);

    assertThat(donation.getStatus()).isEqualTo(DonationStatus.WITHDRAWN);
    verify(auditRecorder)
        .record(eq(AuditEventType.DONATION_WITHDRAWN), eq(donation.getId()), any(), any(), any());
    verify(eventPublisher).publishEvent(any(NoticeEvent.class));
  }

  @Test
  void aRecordedDonationBooksIntoThePoolAndIsReceivedAtOnce() {
    callerIs(HOLDER);
    when(accessPolicy.canManageUnit(unit.getId())).thenReturn(true);
    unitAccepts(true);
    when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
    when(locationRepository.findById(location.getId())).thenReturn(Optional.of(location));
    when(orgUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
    when(donationRepository.save(any(SupplyDonation.class)))
        .thenAnswer(invocation -> {
          SupplyDonation saved = invocation.getArgument(0);
          saved.setId(UUID.randomUUID());
          return saved;
        });

    service.record(
        new RecordDonationRequest(
            unit.getId(), null, material.getId(), null, 500, 3.0, null, location.getId()));

    ArgumentCaptor<SupplyDonation> saved = ArgumentCaptor.forClass(SupplyDonation.class);
    verify(donationRepository).save(saved.capture());
    assertThat(saved.getValue().getStatus()).isEqualTo(DonationStatus.RECEIVED);
    assertThat(saved.getValue().getReceivedAmount()).isEqualTo(3.0);
    assertThat(saved.getValue().getDonorUserId()).isNull();
    verify(stockCommands)
        .bookIntoPool(eq(HOLDER), eq(unit), any(), eq(3.0), eq(StockBookingReason.DONATION));
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DONATION_RECORDED),
            eq(saved.getValue().getId()),
            any(),
            any(),
            any());
    verify(eventPublisher, never()).publishEvent(any(NoticeEvent.class));
  }

  @Test
  void aRecordedDonationNeedsAManagerOfTheReceivingUnit() {
    callerIs(HOLDER);
    when(accessPolicy.canManageUnit(unit.getId())).thenReturn(false);

    assertThatThrownBy(() ->
            service.record(
                new RecordDonationRequest(
                    unit.getId(), null, material.getId(), null, 500, 3.0, null, location.getId())))
        .isInstanceOf(AccessDeniedException.class);

    verify(stockCommands, never()).bookIntoPool(any(), any(), any(), anyDouble(), any());
  }

  @Test
  void theOpenListOfAUnitIsOnlyForItsManagers() {
    when(accessPolicy.canManageUnit(unit.getId())).thenReturn(false);
    when(accessPolicy.isGoverningLeadership()).thenReturn(false);

    assertThatThrownBy(() -> service.listForUnit(unit.getId(), Set.of(), Pageable.unpaged()))
        .isInstanceOf(AccessDeniedException.class);
  }

  private StockRow ownRow(int quality, double amount, double pledged) {
    StockRow row = org.mockito.Mockito.mock(StockRow.class);
    when(row.getId()).thenReturn(UUID.randomUUID());
    when(row.getMaterial()).thenReturn(material);
    when(row.getLocation()).thenReturn(location);
    when(row.getQuality()).thenReturn(quality);
    when(row.getAmount()).thenReturn(amount);
    when(row.donationReserved()).thenReturn(pledged);
    return row;
  }

  @Test
  void theSourcesOfANeedAreTheCallersRowsAtItsTierWithAFreeAmount() {
    SupplyNeed need = openNeed(650);
    callerIs(DONOR);
    when(needRepository.findById(need.getId())).thenReturn(Optional.of(need));
    StockRow good = ownRow(700, 12.4, 2.4);
    StockRow tooLow = org.mockito.Mockito.mock(StockRow.class);
    when(tooLow.getQuality()).thenReturn(400);
    StockRow pledgedOut = org.mockito.Mockito.mock(StockRow.class);
    when(pledgedOut.getQuality()).thenReturn(900);
    when(pledgedOut.getAmount()).thenReturn(5.0);
    when(pledgedOut.donationReserved()).thenReturn(5.0);
    when(stockReads.donatableRows(DONOR, material.getId(), null))
        .thenReturn(List.of(good, tooLow, pledgedOut));

    assertThat(service.sources(need.getId(), null, null))
        .containsExactly(new DonationSourceDto(good.getId(), "Lorville", 700, 10.0, "SCU"));
  }

  @Test
  void theSourcesOfAFreePledgeAreTheCallersRowsOfTheNamedMaterial() {
    callerIs(DONOR);
    StockRow any = ownRow(100, 3.0, 0.0);
    when(stockReads.donatableRows(DONOR, material.getId(), null)).thenReturn(List.of(any));

    assertThat(service.sources(null, material.getId(), null))
        .containsExactly(new DonationSourceDto(any.getId(), "Lorville", 100, 3.0, "SCU"));
  }

  @Test
  void sourcesNeedANeedOrExactlyOneCatalogueEntry() {
    callerIs(DONOR);

    assertThatThrownBy(() -> service.sources(null, null, null))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.sources(null, material.getId(), UUID.randomUUID()))
        .isInstanceOf(BadRequestException.class);
    verify(stockReads, never()).donatableRows(any(), any(), any());
  }

  @Test
  void theTargetsOfARowAreTheOpenNeedsItFitsOfAcceptingUnits() {
    callerIs(DONOR);
    UUID rowId = UUID.randomUUID();
    StockRow row = org.mockito.Mockito.mock(StockRow.class);
    when(row.getUser()).thenReturn(donor);
    when(row.getMaterial()).thenReturn(material);
    when(row.getQuality()).thenReturn(700);
    when(stockReads.findRow(rowId)).thenReturn(Optional.of(row));
    SupplyNeed fits = openNeed(650);
    SupplyNeed tooHigh = openNeed(800);
    SupplyNeed closedUnit = openNeed(0);
    UUID otherUnit = UUID.randomUUID();
    closedUnit.setOrgUnitId(otherUnit);
    when(needRepository.findByStatusAndEntry(NeedStatus.OPEN, material.getId(), null))
        .thenReturn(List.of(fits, tooHigh, closedUnit));
    SupplyUnitSetting accepting = new SupplyUnitSetting();
    accepting.setId(unit.getId());
    accepting.setAcceptsDonations(true);
    SupplyUnitSetting refusing = new SupplyUnitSetting();
    refusing.setId(otherUnit);
    refusing.setAcceptsDonations(false);
    when(unitSettingRepository.findAllById(Set.of(unit.getId(), otherUnit)))
        .thenReturn(List.of(accepting, refusing));

    service.targets(rowId);

    verify(mapper).needContext(List.of(fits));
  }

  @Test
  void anotherMembersRowHasNoTargets() {
    callerIs(DONOR);
    UUID rowId = UUID.randomUUID();
    StockRow row = org.mockito.Mockito.mock(StockRow.class);
    User other = new User();
    other.setId(UUID.randomUUID());
    when(row.getUser()).thenReturn(other);
    when(stockReads.findRow(rowId)).thenReturn(Optional.of(row));

    assertThatThrownBy(() -> service.targets(rowId))
        .isInstanceOfSatisfying(
            SupplyProblemException.class,
            e -> assertThat(e.getProblemCode()).isEqualTo(SupplyProblemCode.SUPPLY_ROW_NOT_OWNED));
    verify(needRepository, never()).findByStatusAndEntry(any(), any(), any());
  }
}
