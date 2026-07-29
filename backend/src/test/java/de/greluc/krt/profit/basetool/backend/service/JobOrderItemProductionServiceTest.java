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
    // requiredQuantity holds the demand for the WHOLE ordered amount (4 units) → per-unit 40.
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
    // Variante C (REQ-INV-027): earmark the entry's full stock to this order, as the create path
    // does — the production guard reads this job-order slice.
    InventoryAllocations.addJobOrder(inventoryItem, order, 100.0, false);

    lenient().when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    lenient()
        .when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    lenient()
        .when(jobOrderItemService.toItemDtos(any()))
        .thenReturn(
            List.of(new JobOrderItemDto(lineId, null, null, 4, 1, 0, null, List.of(), false, 4L)));

    // REQ-INV-032: bookIn is required on every payload, so the fixture line carries a produced
    // game item and the book-in collaborators resolve the defaultBookIn() target (acting user,
    // fixture location, no picker output). Lenient — the guard-path tests throw before book-in.
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
    // Given — one unit is produced, consuming exactly its 40-SCU Steel demand from the entry.
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 40.0, INVENTORY_VERSION)),
            List.of(),
            defaultBookIn());

    // When
    JobOrderItemDto result = service.bookProduction(orderId, lineId, dto);

    // Then — the line advances to 1 manufactured, the entry drops to 60 and its slice follows;
    // the required bookIn additionally creates the produced stock row (REQ-INV-032).
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
    // Given — 3 of 4 already manufactured, so only 1 unit remains, but 2 are requested.
    line.setManufacturedAmount(3);
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(2, LINE_VERSION, List.of(), List.of(), defaultBookIn());

    // When & Then
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(ProductionAllocationException.class);
    assertThat(line.getManufacturedAmount()).isEqualTo(3);
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void bookProduction_consumptionUnderCoversDemand_throws422() {
    // Given — 30 SCU assigned against a 40-SCU demand: the plan under-covers the material.
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 30.0, INVENTORY_VERSION)),
            List.of(),
            defaultBookIn());

    // When & Then
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(ProductionAllocationException.class);
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void bookProduction_consumptionOverCoversDemand_throws422() {
    // Given — 50 SCU assigned against a 40-SCU demand: the plan over-covers the material.
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 50.0, INVENTORY_VERSION)),
            List.of(),
            defaultBookIn());

    // When & Then
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(ProductionAllocationException.class);
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void bookProduction_nonItemOrder_throwsBadRequest() {
    // Given — the order is a MATERIAL order, which has no item lines to produce.
    JobOrder materialOrder = JobOrder.builder().type(JobOrderType.MATERIAL).build();
    materialOrder.setId(orderId);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(materialOrder));
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(1, LINE_VERSION, List.of(), List.of(), defaultBookIn());

    // When & Then
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("not an item order");
  }

  @Test
  void bookProduction_lineVersionMismatch_throwsOptimisticLock() {
    // Given — the echoed line version does not match the persisted one.
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION + 996L,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 40.0, INVENTORY_VERSION)),
            List.of(),
            defaultBookIn());

    // When & Then
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(line.getManufacturedAmount()).isZero();
    verify(inventoryItemRepository, never()).save(any());
  }

  @Test
  void bookProduction_consumedEntryHasNoOrderSlice_throwsIllegalState() {
    // Given — the coverage plan is exact (40 SCU for the 40-SCU demand), but the entry carries no
    // slice earmarked to this order, so the per-entry guard rejects it.
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

    // When & Then
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not belong");
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void bookProduction_consumesFullStock_deletesEntry_stillAudits() {
    // Given — the entry holds exactly the 40-SCU demand, earmarked in full to the order, so
    // consuming it depletes the row.
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

    // When
    service.bookProduction(orderId, lineId, dto);

    // Then — the depleted row is deleted (never saved, never clamped), the line still advances
    // and the audit trail is emitted. The only save is the required bookIn's fresh stock row.
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
    // Given — an item line with no derivable material requirements: nothing is consumed.
    line.getMaterials().clear();
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(1, LINE_VERSION, List.of(), List.of(), defaultBookIn());

    // When
    service.bookProduction(orderId, lineId, dto);

    // Then — the counter advances with no consumption access; besides the booking audit only the
    // required bookIn's fresh stock row is written.
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
    // Given — the line's only material (Steel) is marked "nicht ausbuchen", so its 40-SCU demand is
    // dropped and the consumption plan is empty: production is recorded but no stock is touched.
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1, LINE_VERSION, List.of(), List.of(materialId), defaultBookIn());

    // When
    JobOrderItemDto result = service.bookProduction(orderId, lineId, dto);

    // Then — the counter advances with no consumption access; the earmarked entry is left fully
    // intact (the only save is the required bookIn's fresh stock row).
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

  // ---------------------------------------------------------------
  // production book-in (REQ-INV-032)
  // ---------------------------------------------------------------

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

  // The former nullBookIn legacy no-op test moved: a missing bookIn is now rejected as a 400
  // validation error at the API boundary (REQ-INV-032 flip), pinned by
  // JobOrderItemProductionCreateDtoValidationTest — the service never sees a null block.

  // covers REQ-INV-032 (bookIn creates the earmarked item row, merges after save, audits)
  @Test
  void bookProduction_bookIn_createsEarmarkedItemRow_mergesAfterSave_andAudits() {
    // Given a producible line, a named owner / location / org-unit target and the default
    // auto-earmark (allocateToOrder omitted)
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

    // When
    service.bookProduction(orderId, lineId, dto);

    // Then — one fresh item row: gameItem set, quality null (REQ-INV-029), stamped through the
    // create-on-behalf resolver, carrying the auto-earmark slice attached BEFORE the single save.
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
    // The org-unit stamp went through the owner-validated picker resolution (REQ-ORG-004/016).
    verify(ownerScopeService).resolveOrgUnitForPickerOutputNullable(owner, orgUnitId);
    // Slice-first-then-merge: the merge helper folds the saved row AFTER the save (item rows
    // always auto-merge, client flag false).
    org.mockito.InOrder callOrder = inOrder(inventoryItemRepository, inventoryCheckoutService);
    callOrder.verify(inventoryItemRepository).save(stockRow);
    callOrder.verify(inventoryCheckoutService).mergeStockIfRequested(stockRow, false);
    // The audit event carries the PII-free details payload — the order's #displayId ref (matching
    // the sibling consumption events) plus raw ids (REQ-AUDIT-001).
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
    // A named owner is used verbatim — the acting-user fallback is never consulted.
    verifyNoInteractions(userService);
  }

  // covers REQ-INV-032 (personal + allocateToOrder is contradictory — default true variant)
  @Test
  void bookProduction_bookIn_personalWithDefaultAllocate_throwsBadRequest() {
    // Given a personal book-in that leaves allocateToOrder at its true default
    givenProducibleLineWithoutMaterials();
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(),
            List.of(),
            bookIn(UUID.randomUUID(), null, null, true, null));

    // When / Then — personal stock never carries allocations; the earmark must be explicitly
    // deselected, never silently dropped
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(BadRequestException.class);
    verify(inventoryItemRepository, never()).save(any());
  }

  // covers REQ-INV-032 (personal + explicit allocateToOrder=true is equally contradictory)
  @Test
  void bookProduction_bookIn_personalWithExplicitAllocate_throwsBadRequest() {
    // Given a personal book-in explicitly requesting the order earmark
    givenProducibleLineWithoutMaterials();
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(),
            List.of(),
            bookIn(UUID.randomUUID(), null, null, true, true));

    // When / Then
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(BadRequestException.class);
    verify(inventoryItemRepository, never()).save(any());
  }

  // covers REQ-INV-032 (personal book-in with the earmark deselected creates a slice-less row)
  @Test
  void bookProduction_bookIn_personalWithAllocateFalse_createsPersonalRowWithoutSlice() {
    // Given a personal book-in that deselects the auto-earmark
    GameItem gameItem = givenProducibleLineWithoutMaterials();
    UUID ownerId = UUID.randomUUID();
    User owner = new User();
    owner.setId(ownerId);
    UUID locationId = UUID.randomUUID();
    Location location = new Location();
    location.setId(locationId);
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
    when(locationRepository.findById(locationId)).thenReturn(Optional.of(location));
    when(inventoryItemRepository.save(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(inventoryCheckoutService.mergeStockIfRequested(any(InventoryItem.class), eq(false)))
        .thenAnswer(inv -> inv.getArgument(0));
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1, LINE_VERSION, List.of(), List.of(), bookIn(locationId, ownerId, null, true, false));

    // When
    service.bookProduction(orderId, lineId, dto);

    // Then — the produced unit lands in the owner's personal pool with no earmark slice
    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertThat(captor.getValue().getPersonal()).isTrue();
    assertThat(captor.getValue().getGameItem()).isSameAs(gameItem);
    assertThat(captor.getValue().getJobOrderAllocations()).isEmpty();
  }

  // covers REQ-INV-032 (unknown book-in owner -> 404)
  @Test
  void bookProduction_bookIn_unknownOwner_throwsNotFound() {
    // Given a book-in naming an owner that does not exist
    givenProducibleLineWithoutMaterials();
    UUID unknownOwnerId = UUID.randomUUID();
    when(userRepository.findById(unknownOwnerId)).thenReturn(Optional.empty());
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(),
            List.of(),
            bookIn(UUID.randomUUID(), unknownOwnerId, null, null, null));

    // When / Then
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(NotFoundException.class);
    verify(inventoryItemRepository, never()).save(any());
  }

  // covers REQ-INV-032 (owner defaults to the acting user)
  @Test
  void bookProduction_bookIn_defaultsOwnerToActingUser() {
    // Given a book-in without an explicit owner
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

    // When
    service.bookProduction(orderId, lineId, dto);

    // Then — the row is created for the acting user; no owner lookup by id happens
    org.mockito.ArgumentCaptor<InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertThat(captor.getValue().getUser()).isSameAs(actor);
    verify(userRepository, never()).findById(any());
    verify(ownerScopeService).resolveOrgUnitForPickerOutputNullable(actor, null);
  }

  // covers REQ-INV-032 (a line without a game item cannot be booked in)
  @Test
  void bookProduction_bookIn_lineWithoutGameItem_throwsBadRequest() {
    // Given a book-in against a line explicitly stripped of its game item (the fixture seeds one)
    line.getMaterials().clear();
    line.setGameItem(null);
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(),
            List.of(),
            bookIn(UUID.randomUUID(), null, null, null, null));

    // When / Then
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("no game item");
    verify(inventoryItemRepository, never()).save(any());
  }
}
