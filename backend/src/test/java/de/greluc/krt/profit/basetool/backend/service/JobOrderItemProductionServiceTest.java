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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.exception.ProductionAllocationException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemProductionConsumptionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemProductionCreateDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for {@link JobOrderItemProductionService#bookProduction}: the happy-path counter bump
 * + inventory reduction + audit, the amount / demand-coverage 422s, the non-item-order and
 * missing-slice guards, the stale-version 409, the depleted-row delete branch, the
 * no-materials/empty-consumption line, and a material marked "nicht ausbuchen" (skipped: recorded
 * but not booked out). Pure Mockito over the five collaborators; a Steel/SCU item line (amount 4,
 * per-unit demand 40) linked to a 100-SCU inventory entry earmarked in full to the order backs
 * every scenario. Every payload carries a {@code bookIn} block — the field is {@code @NotNull}
 * since the production modal shipped its book-in section (REQ-INV-032; a missing block is a 400 at
 * the API boundary, pinned by {@code JobOrderItemProductionCreateDtoValidationTest}) — so the
 * fixture line carries a game item and the book-in collaborators resolve a default target.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderItemProductionServiceTest {

  private static final long LINE_VERSION = 3L;
  private static final long INVENTORY_VERSION = 7L;
  private static final UUID BOOK_IN_LOCATION_ID = UUID.randomUUID();

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private MaterialExchangeOfferRepository materialExchangeOfferRepository;
  @Mock private JobOrderItemService jobOrderItemService;
  @Mock private AuditService auditService;
  @Mock private UserService userService;
  @Mock private UserRepository userRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private InventoryCheckoutService inventoryCheckoutService;
  @Mock private AuthHelperService authHelperService;
  @InjectMocks private JobOrderItemProductionService service;

  private UUID orderId;
  private UUID lineId;
  private UUID inventoryId;
  private UUID materialId;
  private JobOrder order;
  private JobOrderItem line;
  private Material material;
  private InventoryItem inventoryItem;

  @BeforeEach
  void setUp() {
    orderId = UUID.randomUUID();
    lineId = UUID.randomUUID();
    inventoryId = UUID.randomUUID();
    materialId = UUID.randomUUID();

    material = new Material();
    material.setId(materialId);
    material.setName("Steel");
    material.setQuantityType(QuantityType.SCU);

    line =
        JobOrderItem.builder()
            .id(lineId)
            .amount(4)
            .manufacturedAmount(0)
            .deliveredAmount(0)
            .build();
    line.setVersion(LINE_VERSION);
    JobOrderItemMaterial req =
        JobOrderItemMaterial.builder()
            .id(UUID.randomUUID())
            .material(material)
            .requiredQuantity(160.0)
            .qualityRequirement(QualityRequirement.NONE)
            .build();
    line.addMaterial(req);

    order = JobOrder.builder().type(JobOrderType.ITEM).build();
    order.setId(orderId);
    order.setDisplayId(42);
    order.setHandle("Widget");
    order.addItem(line);

    inventoryItem = new InventoryItem();
    inventoryItem.setId(inventoryId);
    inventoryItem.setMaterial(material);
    inventoryItem.setAmount(100.0);
    inventoryItem.setVersion(INVENTORY_VERSION);
    InventoryAllocations.addJobOrder(inventoryItem, order, 100.0, false);

    lenient().when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    lenient()
        .when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    lenient()
        .when(jobOrderItemService.toItemDtos(any()))
        .thenReturn(
            List.of(new JobOrderItemDto(lineId, null, null, 4, 1, 0, null, List.of(), false, 4L)));

    GameItem fixtureGameItem = new GameItem();
    fixtureGameItem.setId(UUID.randomUUID());
    fixtureGameItem.setName("Quantum Drive");
    line.setGameItem(fixtureGameItem);
    User actor = new User();
    actor.setId(UUID.randomUUID());
    Location bookInLocation = new Location();
    bookInLocation.setId(BOOK_IN_LOCATION_ID);
    bookInLocation.setName("ARC-L1");
    lenient().when(userService.getCurrentUser()).thenReturn(Optional.of(actor));
    lenient()
        .when(locationRepository.findById(BOOK_IN_LOCATION_ID))
        .thenReturn(Optional.of(bookInLocation));
    lenient()
        .when(inventoryItemRepository.save(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient()
        .when(inventoryCheckoutService.mergeStockIfRequested(any(InventoryItem.class), eq(false)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  /**
   * The standard book-in block of the consumption-focused tests: the fixture location, the acting
   * user as owner (defaulted), no org-unit picker output, non-personal, default auto-earmark.
   *
   * @return the assembled default book-in target
   */
  private static JobOrderItemProductionCreateDto.BookInDto defaultBookIn() {
    return new JobOrderItemProductionCreateDto.BookInDto(
        BOOK_IN_LOCATION_ID, null, null, null, null);
  }

  @Test
  void bookProduction_happyPath_bumpsManufactured_reducesInventoryAndSlice_audits() {
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 40.0, INVENTORY_VERSION)),
            List.of(),
            defaultBookIn());

    JobOrderItemDto result = service.bookProduction(orderId, lineId, dto);

    assertThat(result.id()).isEqualTo(lineId);
    assertThat(line.getManufacturedAmount()).isEqualTo(1);
    assertThat(inventoryItem.getAmount()).isEqualTo(60.0);
    assertThat(inventoryItem.getJobOrderAllocations()).hasSize(1);
    assertThat(inventoryItem.getJobOrderAllocations().get(0).getAmount()).isEqualTo(60.0);
    verify(inventoryItemRepository).save(inventoryItem);
    verify(inventoryItemRepository, never()).delete(any());
    verify(auditService, times(1))
        .record(eq(AuditEventType.JOB_ORDER_PRODUCTION_BOOKED), any(), any(), any(), any());
    verify(auditService, times(1))
        .record(eq(AuditEventType.INVENTORY_CONSUMED_BY_PRODUCTION), any(), any(), any(), any());
    verify(auditService, times(1))
        .record(eq(AuditEventType.INVENTORY_RECEIVED_FROM_PRODUCTION), any(), any(), any(), any());
  }

  @Test
  void bookProduction_amountExceedsRemainingToManufacture_throws422_noSave() {
    line.setManufacturedAmount(3);
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(2, LINE_VERSION, List.of(), List.of(), defaultBookIn());

    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(ProductionAllocationException.class);
    assertThat(line.getManufacturedAmount()).isEqualTo(3);
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void bookProduction_consumptionUnderCoversDemand_throws422() {
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 30.0, INVENTORY_VERSION)),
            List.of(),
            defaultBookIn());

    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(ProductionAllocationException.class);
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void bookProduction_consumptionOverCoversDemand_throws422() {
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 50.0, INVENTORY_VERSION)),
            List.of(),
            defaultBookIn());

    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(ProductionAllocationException.class);
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void bookProduction_nonItemOrder_throwsBadRequest() {
    JobOrder materialOrder = JobOrder.builder().type(JobOrderType.MATERIAL).build();
    materialOrder.setId(orderId);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(materialOrder));
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(1, LINE_VERSION, List.of(), List.of(), defaultBookIn());

    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("not an item order");
  }

  @Test
  void bookProduction_lineVersionMismatch_throwsOptimisticLock() {
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION + 996L,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 40.0, INVENTORY_VERSION)),
            List.of(),
            defaultBookIn());

    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(line.getManufacturedAmount()).isZero();
    verify(inventoryItemRepository, never()).save(any());
  }

  @Test
  void bookProduction_consumedEntryHasNoOrderSlice_throwsBadRequest() {
    inventoryItem.getJobOrderAllocations().clear();
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 40.0, INVENTORY_VERSION)),
            List.of(),
            defaultBookIn());

    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(JobOrderHandoverService.ERROR_ITEM_NOT_LINKED_TO_ORDER);
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void bookProduction_consumesFullStock_deletesEntry_stillAudits() {
    inventoryItem.setAmount(40.0);
    inventoryItem.getJobOrderAllocations().clear();
    InventoryAllocations.addJobOrder(inventoryItem, order, 40.0, false);
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 40.0, INVENTORY_VERSION)),
            List.of(),
            defaultBookIn());

    service.bookProduction(orderId, lineId, dto);

    assertThat(line.getManufacturedAmount()).isEqualTo(1);
    verify(inventoryItemRepository).delete(inventoryItem);
    verify(inventoryItemRepository, never()).save(inventoryItem);
    verify(materialExchangeOfferRepository, never()).clampOfferedAmountToStock(any(), anyDouble());
    verify(auditService, times(1))
        .record(eq(AuditEventType.INVENTORY_CONSUMED_BY_PRODUCTION), any(), any(), any(), any());
    verify(auditService, times(1))
        .record(eq(AuditEventType.JOB_ORDER_PRODUCTION_BOOKED), any(), any(), any(), any());
  }

  @Test
  void bookProduction_lineWithoutMaterials_emptyConsumption_bumpsManufactured_noInventoryWrites() {
    line.getMaterials().clear();
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(1, LINE_VERSION, List.of(), List.of(), defaultBookIn());

    service.bookProduction(orderId, lineId, dto);

    assertThat(line.getManufacturedAmount()).isEqualTo(1);
    verify(inventoryItemRepository, never()).findByIdForUpdate(any());
    verify(inventoryItemRepository, never()).delete(any());
    verify(auditService, times(1))
        .record(eq(AuditEventType.JOB_ORDER_PRODUCTION_BOOKED), any(), any(), any(), any());
    verify(auditService, never())
        .record(eq(AuditEventType.INVENTORY_CONSUMED_BY_PRODUCTION), any(), any(), any(), any());
  }

  @Test
  void bookProduction_materialMarkedSkip_notBookedOut_bumpsManufactured_noInventoryWrites() {
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1, LINE_VERSION, List.of(), List.of(materialId), defaultBookIn());

    JobOrderItemDto result = service.bookProduction(orderId, lineId, dto);

    assertThat(result.id()).isEqualTo(lineId);
    assertThat(line.getManufacturedAmount()).isEqualTo(1);
    assertThat(inventoryItem.getAmount()).isEqualTo(100.0);
    assertThat(inventoryItem.getJobOrderAllocations().get(0).getAmount()).isEqualTo(100.0);
    verify(inventoryItemRepository, never()).findByIdForUpdate(any());
    verify(inventoryItemRepository, never()).save(inventoryItem);
    verify(inventoryItemRepository, never()).delete(any());
    verify(auditService, times(1))
        .record(eq(AuditEventType.JOB_ORDER_PRODUCTION_BOOKED), any(), any(), any(), any());
    verify(auditService, never())
        .record(eq(AuditEventType.INVENTORY_CONSUMED_BY_PRODUCTION), any(), any(), any(), any());
  }

  /**
   * Prepares the fixture line for a book-in scenario: no material requirements (so the consumption
   * plan is empty and the inventory mocks stay silent) and a produced {@link GameItem} to book in.
   *
   * @return the line's game item.
   */
  private GameItem givenProducibleLineWithoutMaterials() {
    line.getMaterials().clear();
    GameItem gameItem = new GameItem();
    gameItem.setId(UUID.randomUUID());
    gameItem.setName("Quantum Drive");
    line.setGameItem(gameItem);
    return gameItem;
  }

  /**
   * Builds a book-in target for the production payload.
   *
   * @param locationId the storage location ("wo")
   * @param ownerUserId the stock owner ("bei wem"), or {@code null} for the acting user
   * @param owningOrgUnitId the org-unit picker output, or {@code null}
   * @param personal the personal-pool flag, or {@code null}
   * @param allocateToOrder the auto-earmark opt-out, or {@code null} (defaults to earmarking)
   * @return the assembled book-in block
   */
  private static JobOrderItemProductionCreateDto.BookInDto bookIn(
      UUID locationId,
      UUID ownerUserId,
      UUID owningOrgUnitId,
      Boolean personal,
      Boolean allocateToOrder) {
    return new JobOrderItemProductionCreateDto.BookInDto(
        locationId, ownerUserId, owningOrgUnitId, personal, allocateToOrder);
  }

  @Test
  void bookProduction_bookIn_createsEarmarkedItemRow_mergesAfterSave_andAudits() {
    GameItem gameItem = givenProducibleLineWithoutMaterials();
    UUID ownerId = UUID.randomUUID();
    User owner = new User();
    owner.setId(ownerId);
    UUID locationId = UUID.randomUUID();
    Location location = new Location();
    location.setId(locationId);
    location.setName("ARC-L1");
    UUID orgUnitId = UUID.randomUUID();
    Squadron orgUnit = new Squadron();
    orgUnit.setId(orgUnitId);
    when(ownerScopeService.canManageUserInventory(ownerId)).thenReturn(true);
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(owner, orgUnitId))
        .thenReturn(orgUnit);
    when(inventoryItemRepository.save(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(inventoryCheckoutService.mergeStockIfRequested(any(InventoryItem.class), eq(false)))
        .thenAnswer(inv -> inv.getArgument(0));
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            2,
            LINE_VERSION,
            List.of(),
            List.of(),
            bookIn(locationId, ownerId, orgUnitId, null, null));

    service.bookProduction(orderId, lineId, dto);

    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    InventoryItem stockRow = captor.getValue();
    assertThat(stockRow.getGameItem()).isSameAs(gameItem);
    assertThat(stockRow.getMaterial()).isNull();
    assertThat(stockRow.getQuality()).isNull();
    assertThat(stockRow.getUser()).isSameAs(owner);
    assertThat(stockRow.getOwningOrgUnit()).isSameAs(orgUnit);
    assertThat(stockRow.getPersonal()).isFalse();
    assertThat(stockRow.getAmount()).isEqualTo(2.0);
    assertThat(stockRow.getJobOrderAllocations()).hasSize(1);
    var slice = stockRow.getJobOrderAllocations().get(0);
    assertThat(slice.getJobOrder()).isSameAs(order);
    assertThat(slice.getAmount()).isEqualTo(2.0);
    assertThat(slice.getDelivered()).isFalse();
    verify(ownerScopeService).resolveOrgUnitForPickerOutputNullable(owner, orgUnitId);
    org.mockito.InOrder callOrder = inOrder(inventoryItemRepository, inventoryCheckoutService);
    callOrder.verify(inventoryItemRepository).save(stockRow);
    callOrder.verify(inventoryCheckoutService).mergeStockIfRequested(stockRow, false);
    org.mockito.ArgumentCaptor<CharSequence> details =
        org.mockito.ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_RECEIVED_FROM_PRODUCTION),
            any(),
            any(),
            eq(ownerId),
            details.capture());
    assertThat(details.getValue().toString())
        .contains("jobOrder=#42")
        .contains("gameItemId=" + gameItem.getId())
        .contains("amount=2")
        .contains("locationId=" + locationId);
    verifyNoInteractions(userService);
  }

  @Test
  void bookProduction_bookIn_personalWithDefaultAllocate_throwsBadRequest() {
    givenProducibleLineWithoutMaterials();
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(),
            List.of(),
            bookIn(UUID.randomUUID(), null, null, true, null));

    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(BadRequestException.class);
    verify(inventoryItemRepository, never()).save(any());
  }

  @Test
  void bookProduction_bookIn_personalWithExplicitAllocate_throwsBadRequest() {
    givenProducibleLineWithoutMaterials();
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(),
            List.of(),
            bookIn(UUID.randomUUID(), null, null, true, true));

    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(BadRequestException.class);
    verify(inventoryItemRepository, never()).save(any());
  }

  @Test
  void bookProduction_bookIn_personalWithAllocateFalse_createsPersonalRowWithoutSlice() {
    GameItem gameItem = givenProducibleLineWithoutMaterials();
    UUID ownerId = UUID.randomUUID();
    User owner = new User();
    owner.setId(ownerId);
    UUID locationId = UUID.randomUUID();
    Location location = new Location();
    location.setId(locationId);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));
    when(inventoryItemRepository.save(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(inventoryCheckoutService.mergeStockIfRequested(any(InventoryItem.class), eq(false)))
        .thenAnswer(inv -> inv.getArgument(0));
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1, LINE_VERSION, List.of(), List.of(), bookIn(locationId, ownerId, null, true, false));

    service.bookProduction(orderId, lineId, dto);

    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertThat(captor.getValue().getPersonal()).isTrue();
    assertThat(captor.getValue().getGameItem()).isSameAs(gameItem);
    assertThat(captor.getValue().getJobOrderAllocations()).isEmpty();
  }

  @Test
  void bookProduction_bookIn_unknownOwner_throwsNotFound() {
    givenProducibleLineWithoutMaterials();
    UUID unknownOwnerId = UUID.randomUUID();
    when(ownerScopeService.canManageUserInventory(unknownOwnerId)).thenReturn(true);
    when(userRepository.findById(unknownOwnerId)).thenReturn(Optional.empty());
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(),
            List.of(),
            bookIn(UUID.randomUUID(), unknownOwnerId, null, null, null));

    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(NotFoundException.class);
    verify(inventoryItemRepository, never()).save(any());
  }

  @Test
  void bookProduction_bookIn_defaultsOwnerToActingUser() {
    givenProducibleLineWithoutMaterials();
    User actor = new User();
    actor.setId(UUID.randomUUID());
    UUID locationId = UUID.randomUUID();
    Location location = new Location();
    location.setId(locationId);
    when(userService.getCurrentUser()).thenReturn(Optional.of(actor));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));
    when(inventoryItemRepository.save(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(inventoryCheckoutService.mergeStockIfRequested(any(InventoryItem.class), eq(false)))
        .thenAnswer(inv -> inv.getArgument(0));
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1, LINE_VERSION, List.of(), List.of(), bookIn(locationId, null, null, null, null));

    service.bookProduction(orderId, lineId, dto);

    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertThat(captor.getValue().getUser()).isSameAs(actor);
    verify(userRepository, never()).findById(any());
    verify(ownerScopeService).resolveOrgUnitForPickerOutputNullable(actor, null);
  }

  @Test
  void bookProduction_bookIn_lineWithoutGameItem_throwsBadRequest() {
    line.getMaterials().clear();
    line.setGameItem(null);
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(),
            List.of(),
            bookIn(UUID.randomUUID(), null, null, null, null));

    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("no game item");
    verify(inventoryItemRepository, never()).save(any());
  }

  @Test
  void bookProduction_bookIn_ownerOutsideCallerScope_throwsAccessDenied_noLookupNoSave() {
    UUID callerId = UUID.randomUUID();
    UUID foreignOwnerId = UUID.randomUUID();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    when(ownerScopeService.canManageUserInventory(foreignOwnerId)).thenReturn(false);
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(),
            List.of(),
            bookIn(UUID.randomUUID(), foreignOwnerId, null, null, null));

    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(AccessDeniedException.class);
    verify(inventoryItemRepository, never()).save(any());
    verify(userRepository, never()).findById(any());
    verify(jobOrderRepository, never()).findById(any());
    verifyNoInteractions(auditService);
    assertThat(line.getManufacturedAmount()).isZero();
  }

  @Test
  void bookProduction_bookIn_personalOnBehalfOfOther_throwsAccessDenied_noSave() {
    UUID callerId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    when(ownerScopeService.canManageUserInventory(ownerId)).thenReturn(true);
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(),
            List.of(),
            bookIn(UUID.randomUUID(), ownerId, null, true, false));

    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("personal");
    verify(inventoryItemRepository, never()).save(any());
    verify(userRepository, never()).findById(any());
  }

  @Test
  void bookProduction_bookIn_ownerInCallerScope_passesGate_andBooksIntoOwnersLedger() {
    givenProducibleLineWithoutMaterials();
    UUID callerId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    User owner = new User();
    owner.setId(ownerId);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    when(ownerScopeService.canManageUserInventory(ownerId)).thenReturn(true);
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(),
            List.of(),
            bookIn(BOOK_IN_LOCATION_ID, ownerId, null, null, null));

    service.bookProduction(orderId, lineId, dto);

    verify(ownerScopeService).canManageUserInventory(ownerId);
    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertThat(captor.getValue().getUser()).isSameAs(owner);
  }

  @Test
  void bookProduction_bookIn_ownerIsCaller_skipsOnBehalfGate() {
    givenProducibleLineWithoutMaterials();
    UUID callerId = UUID.randomUUID();
    User caller = new User();
    caller.setId(callerId);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(),
            List.of(),
            bookIn(BOOK_IN_LOCATION_ID, callerId, null, null, null));

    service.bookProduction(orderId, lineId, dto);

    verify(ownerScopeService, never()).canManageUserInventory(any());
    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertThat(captor.getValue().getUser()).isSameAs(caller);
  }
}
