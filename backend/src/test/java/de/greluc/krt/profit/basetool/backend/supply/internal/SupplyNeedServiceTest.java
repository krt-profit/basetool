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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedLifecycleObserver;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedPriority;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedRef;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.PurchaseReason;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyProblemCode;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyProblemException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Raising, editing and closing needs, and crediting what donations cover (REQ-SUP-001, REQ-SUP-003
 * … REQ-SUP-005, REQ-SUP-017, REQ-SUP-019).
 */
@ExtendWith(MockitoExtension.class)
class SupplyNeedServiceTest {

  private static final UUID UNIT = UUID.randomUUID();
  private static final UUID CALLER = UUID.randomUUID();

  @Mock private SupplyNeedRepository needRepository;
  @Mock private SupplyUnitSettingRepository unitSettingRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private QualityTierRepository qualityTierRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private SupplySettingsService settingsService;
  @Mock private SupplyAccessPolicy accessPolicy;
  @Mock private SupplyMapper mapper;
  @Mock private AuditRecorder auditRecorder;
  @Mock private AuthHelperService authHelperService;
  @Mock private ObjectProvider<NeedLifecycleObserver> lifecycleObservers;
  @Mock private NeedLifecycleObserver observer;

  private SupplyNeedService service;
  private Material material;
  private QualityTier baseTier;

  @BeforeEach
  void setUp() {
    service =
        new SupplyNeedService(
            needRepository,
            unitSettingRepository,
            materialRepository,
            gameItemRepository,
            qualityTierRepository,
            jobOrderRepository,
            settingsService,
            accessPolicy,
            mapper,
            auditRecorder,
            authHelperService,
            new SimpleMeterRegistry(),
            lifecycleObservers);
    lenient()
        .when(lifecycleObservers.orderedStream())
        .thenAnswer(invocation -> Stream.of(observer));
    material = new Material();
    material.setId(UUID.randomUUID());
    material.setName("Quantainium");
    material.setQuantityType(QuantityType.SCU);
    baseTier = new QualityTier();
    baseTier.setId(UUID.randomUUID());
    baseTier.setCode("BASE");
  }

  private CreateNeedRequest createRequest(
      UUID materialId, UUID gameItemId, double amount, boolean purchasable, Long price) {
    return new CreateNeedRequest(
        UNIT,
        materialId,
        gameItemId,
        null,
        amount,
        NeedPriority.HIGH,
        purchasable,
        purchasable ? PurchaseReason.RARE_SOURCE : null,
        price,
        null);
  }

  private void raisingUnit() {
    SupplyUnitSetting setting = new SupplyUnitSetting();
    setting.setId(UNIT);
    setting.setMayRaiseNeeds(true);
    when(unitSettingRepository.findById(UNIT)).thenReturn(Optional.of(setting));
  }

  private SupplyNeed storedNeed(NeedStatus status, double amount, double fulfilled) {
    SupplyNeed need = new SupplyNeed();
    need.setId(UUID.randomUUID());
    need.setOrgUnitId(UNIT);
    need.setMaterial(material);
    need.setQualityTier(baseTier);
    need.setAmount(amount);
    need.setFulfilledAmount(fulfilled);
    need.setStatus(status);
    need.setVersion(3L);
    return need;
  }

  @Test
  void aDisabledSurfaceRefusesTheRequestBeforeAnyLookup() {
    doThrow(new SupplyProblemException(SupplyProblemCode.SUPPLY_DISABLED, "off"))
        .when(settingsService)
        .requireEnabled();

    assertThatThrownBy(() -> service.create(createRequest(material.getId(), null, 2, false, null)))
        .isInstanceOfSatisfying(
            SupplyProblemException.class,
            e -> assertThat(e.getProblemCode()).isEqualTo(SupplyProblemCode.SUPPLY_DISABLED));

    verify(needRepository, never()).save(any());
  }

  @Test
  void aCallerWhoDoesNotManageTheUnitIsRefused() {
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(false);

    assertThatThrownBy(() -> service.create(createRequest(material.getId(), null, 2, false, null)))
        .isInstanceOf(AccessDeniedException.class);

    verify(needRepository, never()).save(any());
  }

  @Test
  void aUnitThatMayNotRaiseNeedsIsRefused() {
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);
    when(unitSettingRepository.findById(UNIT)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(createRequest(material.getId(), null, 2, false, null)))
        .isInstanceOfSatisfying(
            SupplyProblemException.class,
            e ->
                assertThat(e.getProblemCode())
                    .isEqualTo(SupplyProblemCode.SUPPLY_UNIT_CANNOT_RAISE));
  }

  @Test
  void aNeedNamesExactlyOneOfMaterialAndGameItem() {
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);
    raisingUnit();

    assertThatThrownBy(() -> service.create(createRequest(null, null, 2, false, null)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                service.create(createRequest(material.getId(), UUID.randomUUID(), 2, false, null)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aRaisedMaterialNeedTakesTheBaseTierAndRecordsItsAuditEvent() {
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);
    raisingUnit();
    when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
    when(qualityTierRepository.findByMinQuality(0)).thenReturn(Optional.of(baseTier));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(needRepository.save(any(SupplyNeed.class)))
        .thenAnswer(
            invocation -> {
              SupplyNeed need = invocation.getArgument(0);
              need.setId(UUID.randomUUID());
              return need;
            });

    service.create(createRequest(material.getId(), null, 12.5, true, 4000L));

    ArgumentCaptor<SupplyNeed> saved = ArgumentCaptor.forClass(SupplyNeed.class);
    verify(needRepository).save(saved.capture());
    SupplyNeed need = saved.getValue();
    assertThat(need.getOrgUnitId()).isEqualTo(UNIT);
    assertThat(need.getQualityTier()).isSameAs(baseTier);
    assertThat(need.getAmount()).isEqualTo(12.5);
    assertThat(need.getStatus()).isEqualTo(NeedStatus.OPEN);
    assertThat(need.isPurchasable()).isTrue();
    assertThat(need.getPurchaseReason()).isEqualTo(PurchaseReason.RARE_SOURCE);
    assertThat(need.getMaxUnitPrice()).isEqualTo(4000L);
    assertThat(need.getCreatedBy()).isEqualTo(CALLER);
    verify(auditRecorder)
        .record(eq(AuditEventType.NEED_RAISED), eq(need.getId()), eq("Quantainium"), any(), any());
  }

  @Test
  void aPurchasableNeedWithoutAMaximumPriceIsRefused() {
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);
    raisingUnit();
    when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
    when(qualityTierRepository.findByMinQuality(0)).thenReturn(Optional.of(baseTier));

    assertThatThrownBy(() -> service.create(createRequest(material.getId(), null, 2, true, null)))
        .isInstanceOf(BadRequestException.class);
    verify(needRepository, never()).save(any());
  }

  @Test
  void aPieceMaterialIsCountedInWholeUnits() {
    material.setQuantityType(QuantityType.PIECE);
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);
    raisingUnit();
    when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
    when(qualityTierRepository.findByMinQuality(0)).thenReturn(Optional.of(baseTier));

    assertThatThrownBy(
            () -> service.create(createRequest(material.getId(), null, 2.5, false, null)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void anEditWithAStaleVersionConflicts() {
    SupplyNeed need = storedNeed(NeedStatus.OPEN, 10, 0);
    when(needRepository.findByIdForUpdate(need.getId())).thenReturn(Optional.of(need));
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);

    assertThatThrownBy(() -> service.withdraw(need.getId(), 2L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(need.getStatus()).isEqualTo(NeedStatus.OPEN);
  }

  @Test
  void anAmountBelowWhatIsCoveredIsRefused() {
    SupplyNeed need = storedNeed(NeedStatus.OPEN, 10, 6);
    when(needRepository.findByIdForUpdate(need.getId())).thenReturn(Optional.of(need));
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);

    UpdateNeedRequest request =
        new UpdateNeedRequest(5.0, NeedPriority.NORMAL, false, null, null, null, 3L);

    assertThatThrownBy(() -> service.update(need.getId(), request))
        .isInstanceOf(BadRequestException.class);
    verify(needRepository, never()).saveAndFlush(any());
  }

  @Test
  void anEditThatLowersTheAmountToWhatIsCoveredFulfilsTheNeed() {
    SupplyNeed need = storedNeed(NeedStatus.OPEN, 10, 6);
    when(needRepository.findByIdForUpdate(need.getId())).thenReturn(Optional.of(need));
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);
    when(needRepository.saveAndFlush(need)).thenReturn(need);

    service.update(
        need.getId(), new UpdateNeedRequest(6.0, NeedPriority.LOW, false, null, null, null, 3L));

    assertThat(need.getStatus()).isEqualTo(NeedStatus.FULFILLED);
    verify(auditRecorder)
        .record(eq(AuditEventType.NEED_UPDATED), eq(need.getId()), any(), any(), any());
    verify(auditRecorder)
        .record(eq(AuditEventType.NEED_FULFILLED), eq(need.getId()), any(), any(), any());
  }

  @Test
  void aNeedMovesOpenPausedOpenAndWithdrawnWithOneAuditEventEach() {
    SupplyNeed need = storedNeed(NeedStatus.OPEN, 10, 0);
    when(needRepository.findByIdForUpdate(need.getId())).thenReturn(Optional.of(need));
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);
    when(needRepository.saveAndFlush(need)).thenReturn(need);

    service.pause(need.getId(), 3L);
    assertThat(need.getStatus()).isEqualTo(NeedStatus.PAUSED);
    service.resume(need.getId(), 3L);
    assertThat(need.getStatus()).isEqualTo(NeedStatus.OPEN);
    service.withdraw(need.getId(), 3L);
    assertThat(need.getStatus()).isEqualTo(NeedStatus.WITHDRAWN);

    verify(auditRecorder)
        .record(eq(AuditEventType.NEED_PAUSED), eq(need.getId()), any(), any(), any());
    verify(auditRecorder)
        .record(eq(AuditEventType.NEED_RESUMED), eq(need.getId()), any(), any(), any());
    verify(auditRecorder)
        .record(eq(AuditEventType.NEED_WITHDRAWN), eq(need.getId()), any(), any(), any());
  }

  @Test
  void aTerminalNeedCannotBePausedOrWithdrawn() {
    SupplyNeed need = storedNeed(NeedStatus.FULFILLED, 10, 10);
    when(needRepository.findByIdForUpdate(need.getId())).thenReturn(Optional.of(need));
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);

    assertThatThrownBy(() -> service.pause(need.getId(), 3L))
        .isInstanceOfSatisfying(
            SupplyProblemException.class,
            e -> assertThat(e.getProblemCode()).isEqualTo(SupplyProblemCode.SUPPLY_NEED_NOT_OPEN));
    assertThatThrownBy(() -> service.withdraw(need.getId(), 3L))
        .isInstanceOf(SupplyProblemException.class);
  }

  @Test
  void creditingAnUnknownOrClosedNeedChangesNothingFurther() {
    UUID id = UUID.randomUUID();
    when(needRepository.addFulfilled(eq(id), anyDouble(), any(), any())).thenReturn(0);

    service.credit(id, 3.0);

    verify(needRepository, never()).markFulfilledIfCovered(any(), anyDouble(), any(), any(), any());
  }

  @Test
  void creditingWhatCoversTheNeedFulfilsItAndAuditsIt() {
    SupplyNeed need = storedNeed(NeedStatus.FULFILLED, 10, 10);
    when(needRepository.addFulfilled(need.getId(), 4.0, NeedStatus.OPEN, NeedStatus.PAUSED))
        .thenReturn(1);
    when(needRepository.markFulfilledIfCovered(
            eq(need.getId()),
            anyDouble(),
            eq(NeedStatus.FULFILLED),
            eq(NeedStatus.OPEN),
            eq(NeedStatus.PAUSED)))
        .thenReturn(1);
    when(needRepository.findById(need.getId())).thenReturn(Optional.of(need));

    service.credit(need.getId(), 4.0);

    verify(auditRecorder)
        .record(eq(AuditEventType.NEED_FULFILLED), eq(need.getId()), any(), any(), any());
  }

  @Test
  void creditingAPartOfTheNeedLeavesItOpen() {
    UUID id = UUID.randomUUID();
    when(needRepository.addFulfilled(id, 2.0, NeedStatus.OPEN, NeedStatus.PAUSED)).thenReturn(1);
    when(needRepository.markFulfilledIfCovered(eq(id), anyDouble(), any(), any(), any()))
        .thenReturn(0);

    service.credit(id, 2.0);

    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
    verify(needRepository, never()).findById(any());
  }

  @Test
  void aPurchasableNeedIsReleasedToTheObserversWhenItIsRaised() {
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);
    raisingUnit();
    when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
    when(qualityTierRepository.findByMinQuality(0)).thenReturn(Optional.of(baseTier));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(needRepository.save(any(SupplyNeed.class)))
        .thenAnswer(
            invocation -> {
              SupplyNeed need = invocation.getArgument(0);
              need.setId(UUID.randomUUID());
              return need;
            });

    service.create(createRequest(material.getId(), null, 5, true, 4000L));

    ArgumentCaptor<NeedRef> released = ArgumentCaptor.forClass(NeedRef.class);
    verify(observer).onReleasedForPurchase(released.capture(), eq(CALLER));
    assertThat(released.getValue().purchasable()).isTrue();
    assertThat(released.getValue().maxUnitPrice()).isEqualTo(4000L);
    assertThat(released.getValue().label()).isEqualTo("Quantainium");
  }

  @Test
  void aNeedThatIsNotPurchasableIsNotReleased() {
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);
    raisingUnit();
    when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
    when(qualityTierRepository.findByMinQuality(0)).thenReturn(Optional.of(baseTier));
    when(needRepository.save(any(SupplyNeed.class)))
        .thenAnswer(
            invocation -> {
              SupplyNeed need = invocation.getArgument(0);
              need.setId(UUID.randomUUID());
              return need;
            });

    service.create(createRequest(material.getId(), null, 5, false, null));

    verify(observer, never()).onReleasedForPurchase(any(), any());
  }

  @Test
  void settingAnOpenNeedPurchasableReleasesItButEditingAPurchasableOneDoesNot() {
    SupplyNeed need = storedNeed(NeedStatus.OPEN, 10, 0);
    when(needRepository.findByIdForUpdate(need.getId())).thenReturn(Optional.of(need));
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);
    when(needRepository.saveAndFlush(need)).thenReturn(need);

    service.update(
        need.getId(),
        new UpdateNeedRequest(
            10.0, NeedPriority.NORMAL, true, PurchaseReason.RARE_SOURCE, 900L, null, 3L));
    verify(observer).onReleasedForPurchase(any(NeedRef.class), any());

    need.setVersion(4L);
    service.update(
        need.getId(),
        new UpdateNeedRequest(
            11.0, NeedPriority.NORMAL, true, PurchaseReason.RARE_SOURCE, 900L, null, 4L));
    verify(observer, times(1)).onReleasedForPurchase(any(), any());
  }

  @Test
  void aWithdrawnNeedIsReportedToTheObservers() {
    SupplyNeed need = storedNeed(NeedStatus.OPEN, 10, 0);
    when(needRepository.findByIdForUpdate(need.getId())).thenReturn(Optional.of(need));
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);
    when(needRepository.saveAndFlush(need)).thenReturn(need);

    service.withdraw(need.getId(), 3L);

    verify(observer).onWithdrawn(need.getId());
  }

  @Test
  void aFulfilledNeedIsReportedToTheObservers() {
    SupplyNeed need = storedNeed(NeedStatus.FULFILLED, 10, 10);
    when(needRepository.addFulfilled(need.getId(), 4.0, NeedStatus.OPEN, NeedStatus.PAUSED))
        .thenReturn(1);
    when(needRepository.markFulfilledIfCovered(eq(need.getId()), anyDouble(), any(), any(), any()))
        .thenReturn(1);
    when(needRepository.findById(need.getId())).thenReturn(Optional.of(need));

    service.credit(need.getId(), 4.0);

    verify(observer).onFulfilled(need.getId());
  }

  @Test
  void anAcceptedPurchaseIsAddedAsCoveredAndPurchasedAmount() {
    SupplyNeed need = storedNeed(NeedStatus.FULFILLED, 10, 10);
    when(needRepository.addPurchased(need.getId(), 6.0, NeedStatus.OPEN, NeedStatus.PAUSED))
        .thenReturn(1);
    when(needRepository.markFulfilledIfCovered(eq(need.getId()), anyDouble(), any(), any(), any()))
        .thenReturn(1);
    when(needRepository.findById(need.getId())).thenReturn(Optional.of(need));

    service.creditPurchase(need.getId(), 6.0);

    verify(needRepository, never()).addFulfilled(any(), anyDouble(), any(), any());
    verify(auditRecorder)
        .record(eq(AuditEventType.NEED_FULFILLED), eq(need.getId()), any(), any(), any());
  }

  @Test
  void aPurchaseOnAClosedNeedChangesNothing() {
    UUID id = UUID.randomUUID();
    when(needRepository.addPurchased(eq(id), anyDouble(), any(), any())).thenReturn(0);

    service.creditPurchase(id, 2.0);

    verify(needRepository, never()).markFulfilledIfCovered(any(), anyDouble(), any(), any(), any());
  }

  @Test
  void openNeedsOfAUnitComeFromTheRepository() {
    when(needRepository.findAll(ArgumentMatchers.<Specification<SupplyNeed>>any()))
        .thenReturn(List.of());
    when(mapper.needContext(List.of()))
        .thenReturn(
            new SupplyMapper.NeedContext(Map.of(), false, false, Set.of(), false, Map.of()));

    assertThat(service.openNeedsOf(UNIT)).isEmpty();
  }

  private JobOrder order(UUID responsibleUnit, JobOrderStatus status) {
    Squadron unit = new Squadron();
    unit.setId(responsibleUnit);
    JobOrder order = new JobOrder();
    order.setId(UUID.randomUUID());
    order.setDisplayId(42);
    order.setHandle("Stileron");
    order.setStatus(status);
    order.setResponsibleOrgUnit(unit);
    return order;
  }

  private UpdateNeedRequest linkRequest(UUID jobOrderId) {
    return new UpdateNeedRequest(10.0, NeedPriority.NORMAL, false, null, null, jobOrderId, 3L);
  }

  @Test
  void anEditLinksAnOpenOrderOfTheRaisingUnit() {
    SupplyNeed need = storedNeed(NeedStatus.OPEN, 10, 0);
    JobOrder order = order(UNIT, JobOrderStatus.IN_PROGRESS);
    when(needRepository.findByIdForUpdate(need.getId())).thenReturn(Optional.of(need));
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);
    when(jobOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    when(needRepository.saveAndFlush(need)).thenReturn(need);

    service.update(need.getId(), linkRequest(order.getId()));

    assertThat(need.getJobOrderId()).isEqualTo(order.getId());
  }

  @Test
  void anOrderOfAnotherUnitOrAClosedOrderCannotBeLinked() {
    SupplyNeed need = storedNeed(NeedStatus.OPEN, 10, 0);
    JobOrder foreign = order(UUID.randomUUID(), JobOrderStatus.OPEN);
    JobOrder completed = order(UNIT, JobOrderStatus.COMPLETED);
    when(needRepository.findByIdForUpdate(need.getId())).thenReturn(Optional.of(need));
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);
    when(jobOrderRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));
    when(jobOrderRepository.findById(completed.getId())).thenReturn(Optional.of(completed));

    assertThatThrownBy(() -> service.update(need.getId(), linkRequest(foreign.getId())))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.update(need.getId(), linkRequest(completed.getId())))
        .isInstanceOf(BadRequestException.class);
    assertThat(need.getJobOrderId()).isNull();
  }

  @Test
  void anEditKeepsALinkToAnOrderThatHasSinceClosed() {
    SupplyNeed need = storedNeed(NeedStatus.OPEN, 10, 0);
    UUID closedOrder = UUID.randomUUID();
    need.setJobOrderId(closedOrder);
    when(needRepository.findByIdForUpdate(need.getId())).thenReturn(Optional.of(need));
    when(accessPolicy.canManageUnit(UNIT)).thenReturn(true);
    when(needRepository.saveAndFlush(need)).thenReturn(need);

    service.update(need.getId(), linkRequest(closedOrder));

    assertThat(need.getJobOrderId()).isEqualTo(closedOrder);
    verify(jobOrderRepository, never()).findById(any());
  }

  @Test
  void theOrderPickerListsTheOpenOrdersOfTheManagedUnits() {
    JobOrder order = order(UNIT, JobOrderStatus.OPEN);
    when(accessPolicy.managedUnitIds()).thenReturn(Set.of(UNIT));
    when(jobOrderRepository.findByResponsibleOrgUnitsAndStatuses(
            Set.of(JobOrderStatus.OPEN, JobOrderStatus.IN_PROGRESS), Set.of(UNIT)))
        .thenReturn(List.of(order));

    assertThat(service.jobOrderOptions())
        .containsExactly(new NeedJobOrderOptionDto(order.getId(), UNIT, 42));
  }

  @Test
  void aCallerWhoManagesNoUnitGetsNoOrders() {
    when(accessPolicy.managedUnitIds()).thenReturn(Set.of());

    assertThat(service.jobOrderOptions()).isEmpty();
    verify(jobOrderRepository, never()).findByResponsibleOrgUnitsAndStatuses(any(), any());
  }
}
