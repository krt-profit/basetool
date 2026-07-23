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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.mapper.JobOrderItemHandoverMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandover;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverEntryCreateDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link JobOrderItemHandoverService}: delivered-count increments, over-delivery
 * (capped at the manufactured-but-undelivered quantity, REQ-ORDERS-025) and foreign-line rejection,
 * the non-item-order guard, auto-completion when every line is fully delivered, and the best-effort
 * delivery-consumes-earmarked-item-stock step (REQ-ORDERS-030) — including the this-order-slice cap
 * (free rest / sibling order untouched), the per-game-item aggregation across lines, and the
 * stock-backed Materialbörse item-offer ratchet on a reduced row (REQ-MARKET-013/014).
 */
@ExtendWith(MockitoExtension.class)
class JobOrderItemHandoverServiceTest {

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  @Mock private JobOrderItemHandoverMapper jobOrderItemHandoverMapper;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private MaterialExchangeOfferRepository materialExchangeOfferRepository;
  @Mock private JobOrderService jobOrderService;
  @Mock private UserService userService;
  @Mock private OrgUnitMembershipService orgUnitMembershipService;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private AuditService auditService;
  @InjectMocks private JobOrderItemHandoverService service;

  private UUID orderId;
  private UUID lineId;
  private UUID gameItemId;
  private JobOrder order;
  private JobOrderItem line;
  private GameItem gameItem;
  private Location location;

  @BeforeEach
  void setUp() {
    orderId = UUID.randomUUID();
    lineId = UUID.randomUUID();
    gameItemId = UUID.randomUUID();

    gameItem = new GameItem();
    gameItem.setId(gameItemId);
    gameItem.setName("Ballista");
    location = new Location();
    location.setName("Port Olisar");

    // Fully manufactured so delivery up to the ordered amount is allowed (delivery is gated by
    // manufacture, REQ-ORDERS-025).
    line =
        JobOrderItem.builder()
            .id(lineId)
            .gameItem(gameItem)
            .amount(5)
            .deliveredAmount(0)
            .manufacturedAmount(5)
            .build();
    order =
        JobOrder.builder().id(orderId).type(JobOrderType.ITEM).status(JobOrderStatus.OPEN).build();
    order.addItem(line);

    lenient().when(userService.getCurrentUser()).thenReturn(Optional.empty());
    lenient()
        .when(jobOrderItemHandoverRepository.save(any(JobOrderItemHandover.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    // Default: no earmarked item stock for the delivered game item — the best-effort consumption is
    // a no-op unless a test stubs concrete rows (REQ-ORDERS-030).
    lenient()
        .when(
            inventoryItemRepository.findGameItemRowsByJobOrderAndGameItemForUpdate(
                any(UUID.class), any(UUID.class)))
        .thenReturn(List.of());
    lenient()
        .when(jobOrderItemHandoverMapper.toDto(any(JobOrderItemHandover.class)))
        .thenReturn(
            new JobOrderItemHandoverDto(
                UUID.randomUUID(),
                orderId,
                Instant.parse("2026-01-01T00:00:00Z"),
                "recipient",
                null,
                null,
                List.of(),
                0L));
  }

  /**
   * Builds a game-item stock row earmarked to {@link #order} — an entry stocking {@code amount}
   * whole units with an {@code earmark}-unit job-order slice for this order, mirroring the shape
   * the production book-in creates (REQ-INV-032).
   *
   * @param amount the row's total stock
   * @param earmark the whole units earmarked to this order
   * @return the earmarked game-item row
   */
  private InventoryItem itemRow(double amount, double earmark) {
    InventoryItem row = new InventoryItem();
    row.setId(UUID.randomUUID());
    row.setGameItem(gameItem);
    row.setLocation(location);
    row.setAmount(amount);
    InventoryAllocations.addJobOrder(row, order, earmark, false);
    return row;
  }

  @Test
  void createItemHandoverIncrementsDeliveredAndDoesNotCompleteWhenPartiallyDelivered() {
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

    service.createItemHandover(orderId, payload(lineId, 2));

    assertThat(line.getDeliveredAmount()).isEqualTo(2);
    verify(jobOrderItemHandoverRepository).save(any(JobOrderItemHandover.class));
    verify(jobOrderService, never()).completeJobOrderWithinTransaction(any());
  }

  @Test
  void createItemHandoverCompletesOrderWhenAllLinesFullyDelivered() {
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

    service.createItemHandover(orderId, payload(lineId, 5));

    assertThat(line.getDeliveredAmount()).isEqualTo(5);
    verify(jobOrderService).completeJobOrderWithinTransaction(order);
  }

  @Test
  void createItemHandoverRejectsOverDelivery() {
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> service.createItemHandover(orderId, payload(lineId, 6)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("manufactured");
    assertThat(line.getDeliveredAmount()).isZero();
  }

  @Test
  void createItemHandoverRejectsDeliveryBeyondManufactured() {
    // Only 2 of 5 manufactured — a unit can only be delivered once it has been manufactured.
    line.setManufacturedAmount(2);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> service.createItemHandover(orderId, payload(lineId, 3)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("manufactured");
    assertThat(line.getDeliveredAmount()).isZero();
  }

  @Test
  void createItemHandoverRejectsEntryForForeignLine() {
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> service.createItemHandover(orderId, payload(UUID.randomUUID(), 1)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("does not belong");
  }

  @Test
  void createItemHandoverRejectsNonItemOrder() {
    JobOrder materialOrder = JobOrder.builder().type(JobOrderType.MATERIAL).build();
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(materialOrder));

    assertThatThrownBy(() -> service.createItemHandover(orderId, payload(lineId, 1)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("not an item order");
  }

  @Test
  void createItemHandoverConsumesEarmarkedItemStockDeletingDepletedRow() {
    // covers REQ-ORDERS-030 — a full delivery draws the whole earmark out of the Lager, so the
    // phantom stock left behind by the delivery disappears (the depleted row is deleted).
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    InventoryItem row = itemRow(5.0, 5.0);
    UUID rowId = row.getId();
    when(inventoryItemRepository.findGameItemRowsByJobOrderAndGameItemForUpdate(
            orderId, gameItemId))
        .thenReturn(List.of(row));

    service.createItemHandover(orderId, payload(lineId, 5));

    assertThat(line.getDeliveredAmount()).isEqualTo(5);
    verify(inventoryItemRepository).delete(row);
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
    // One INVENTORY_HANDED_OVER for the consumed row (the shared cross-domain handover event).
    verify(auditService)
        .record(eq(AuditEventType.INVENTORY_HANDED_OVER), eq(rowId), any(), isNull(), any());
  }

  @Test
  void createItemHandoverPartiallyConsumesLeavingRemainderEarmarked() {
    // covers REQ-ORDERS-030 — hand over 3 of a 5-unit earmarked row: the row and its this-order
    // slice both shrink to 2 and the row survives (not deleted).
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    InventoryItem row = itemRow(5.0, 5.0);
    when(inventoryItemRepository.findGameItemRowsByJobOrderAndGameItemForUpdate(
            orderId, gameItemId))
        .thenReturn(List.of(row));

    service.createItemHandover(orderId, payload(lineId, 3));

    assertThat(row.getAmount()).isEqualTo(2.0);
    var slice = InventoryAllocations.jobOrderSlice(row, orderId);
    assertThat(slice).isNotNull();
    assertThat(slice.getAmount()).isEqualTo(2.0);
    verify(inventoryItemRepository).save(row);
    verify(inventoryItemRepository, never()).delete(any(InventoryItem.class));
  }

  @Test
  void createItemHandoverBestEffortDeliversLegacyLineWithoutEarmarkedStock() {
    // covers REQ-ORDERS-030 — a legacy line (manufacturedAmount > 0, no earmarked item stock) still
    // delivers: nothing is consumed and no INVENTORY_HANDED_OVER is emitted.
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

    service.createItemHandover(orderId, payload(lineId, 4));

    assertThat(line.getDeliveredAmount()).isEqualTo(4);
    verify(inventoryItemRepository, never()).delete(any(InventoryItem.class));
    verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
    verify(auditService, never())
        .record(eq(AuditEventType.INVENTORY_HANDED_OVER), any(), any(), any(), any());
  }

  @Test
  void createItemHandoverBestEffortConsumesPartialStockAndStillDeliversTheRest() {
    // covers REQ-ORDERS-030 — earmark 2, hand over 5: consume the 2 available (row deleted) and
    // still deliver all 5. A stock shortfall never blocks the delivery.
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    InventoryItem row = itemRow(2.0, 2.0);
    when(inventoryItemRepository.findGameItemRowsByJobOrderAndGameItemForUpdate(
            orderId, gameItemId))
        .thenReturn(List.of(row));

    service.createItemHandover(orderId, payload(lineId, 5));

    assertThat(line.getDeliveredAmount()).isEqualTo(5);
    verify(inventoryItemRepository).delete(row);
  }

  @Test
  void createItemHandoverConsumesMultipleRowsOldestFirst() {
    // covers REQ-ORDERS-030 — the earmark spread over two rows (3 + 3), hand over 4: the older row
    // is drained (deleted) and the newer row gives up only 1 (decremented to 2, not deleted).
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    InventoryItem older = itemRow(3.0, 3.0);
    InventoryItem newer = itemRow(3.0, 3.0);
    // The repository returns the rows oldest-first (createdAt, id) — the consumption honours that.
    when(inventoryItemRepository.findGameItemRowsByJobOrderAndGameItemForUpdate(
            orderId, gameItemId))
        .thenReturn(List.of(older, newer));

    service.createItemHandover(orderId, payload(lineId, 4));

    verify(inventoryItemRepository).delete(older);
    assertThat(newer.getAmount()).isEqualTo(2.0);
    verify(inventoryItemRepository).save(newer);
    verify(inventoryItemRepository, never()).delete(newer);
  }

  @Test
  void createItemHandoverConsumingMultipleRowsCompletesOrderWithoutRefetch() {
    // covers REQ-ORDERS-030 — a delivery that both completes the order and consumes two earmarked
    // rows must complete the order exactly once on the SAME managed aggregate. The flow runs no
    // context-clearing bulk update, so it must NOT re-fetch the order (the re-fetch is the
    // material-handover shape that guards against a detach) — a re-fetch here would be the symptom
    // of a double @Version bump / 409 regression.
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    InventoryItem r1 = itemRow(3.0, 3.0);
    InventoryItem r2 = itemRow(2.0, 2.0);
    when(inventoryItemRepository.findGameItemRowsByJobOrderAndGameItemForUpdate(
            orderId, gameItemId))
        .thenReturn(List.of(r1, r2));

    service.createItemHandover(orderId, payload(lineId, 5));

    assertThat(line.getDeliveredAmount()).isEqualTo(5);
    verify(jobOrderService, times(1)).completeJobOrderWithinTransaction(order);
    // The order aggregate is fetched exactly once (at the start) and never re-loaded.
    verify(jobOrderRepository, times(1)).findById(orderId);
    verify(inventoryItemRepository).delete(r1);
    verify(inventoryItemRepository).delete(r2);
  }

  @Test
  void createItemHandoverDrawsOnlyThisOrdersSliceLeavingTheFreeRest() {
    // covers REQ-ORDERS-030 — a row stocking 5 units earmarks only 3 to this order (2 free): a
    // handover of 5 draws only this order's 3-unit slice (best-effort — the 2-unit shortfall is
    // delivered without stock backing), so the row survives with its 2 free (unearmarked) units and
    // the now-zero this-order slice is dropped. Handing over MORE than the slice makes the slice
    // cap
    // load-bearing: capping at the row amount instead would draw 5, deplete and DELETE the row here
    // (destroying the free rest), which the amount==2.0 / never-delete assertions catch (Variante C
    // R5, REQ-INV-027 — the physical remainder never goes negative).
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    InventoryItem row = itemRow(5.0, 3.0);
    when(inventoryItemRepository.findGameItemRowsByJobOrderAndGameItemForUpdate(
            orderId, gameItemId))
        .thenReturn(List.of(row));

    service.createItemHandover(orderId, payload(lineId, 5));

    assertThat(line.getDeliveredAmount()).isEqualTo(5);
    assertThat(row.getAmount()).isEqualTo(2.0);
    // The slice was drawn to zero and dropped; the row keeps only its unearmarked free rest.
    assertThat(InventoryAllocations.jobOrderSlice(row, orderId)).isNull();
    verify(inventoryItemRepository).save(row);
    verify(inventoryItemRepository, never()).delete(any(InventoryItem.class));
  }

  @Test
  void createItemHandoverLeavesASiblingOrdersEarmarkSliceUntouched() {
    // covers REQ-ORDERS-030 — a row split-earmarked to THIS order (3) and a sibling ITEM order (2),
    // 5 units total: a handover of 5 draws only this order's 3-unit slice (best-effort — the 2-unit
    // shortfall delivers without backing); the sibling's 2-unit slice and the row's remaining 2
    // units
    // survive, and the row is not deleted. Handing over MORE than this order's slice makes the cap
    // load-bearing: capping at the row amount instead would draw 5, deplete and DELETE the row —
    // dropping the sibling's slice — which the sibling-survives / never-delete assertions catch
    // (Variante C R5, REQ-INV-027).
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    UUID siblingOrderId = UUID.randomUUID();
    JobOrder siblingOrder = JobOrder.builder().id(siblingOrderId).type(JobOrderType.ITEM).build();
    InventoryItem row = itemRow(5.0, 3.0);
    InventoryAllocations.addJobOrder(row, siblingOrder, 2.0, false);
    when(inventoryItemRepository.findGameItemRowsByJobOrderAndGameItemForUpdate(
            orderId, gameItemId))
        .thenReturn(List.of(row));

    service.createItemHandover(orderId, payload(lineId, 5));

    assertThat(row.getAmount()).isEqualTo(2.0);
    assertThat(InventoryAllocations.jobOrderSlice(row, orderId)).isNull();
    var siblingSlice = InventoryAllocations.jobOrderSlice(row, siblingOrderId);
    assertThat(siblingSlice).isNotNull();
    assertThat(siblingSlice.getAmount()).isEqualTo(2.0);
    verify(inventoryItemRepository).save(row);
    verify(inventoryItemRepository, never()).delete(any(InventoryItem.class));
  }

  @Test
  void createItemHandoverAggregatesTwoLinesOfTheSameGameItemIntoOneEarmarkDraw() {
    // covers REQ-ORDERS-030 — two ordered lines requesting the SAME game item share one (row,
    // order)
    // earmark pool, so a handover delivering both lines draws their combined units from that single
    // pool. Deliver 3 (line 1) + 2 (line 2) = 5 out of a 5-unit earmarked row → the pool is loaded
    // once (not once per line) and the row is drained and deleted exactly once.
    UUID secondLineId = UUID.randomUUID();
    JobOrderItem secondLine =
        JobOrderItem.builder()
            .id(secondLineId)
            .gameItem(gameItem)
            .amount(2)
            .deliveredAmount(0)
            .manufacturedAmount(2)
            .build();
    order.addItem(secondLine);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    InventoryItem row = itemRow(5.0, 5.0);
    when(inventoryItemRepository.findGameItemRowsByJobOrderAndGameItemForUpdate(
            orderId, gameItemId))
        .thenReturn(List.of(row));

    service.createItemHandover(
        orderId,
        new JobOrderItemHandoverCreateDto(
            Instant.parse("2026-01-01T00:00:00Z"),
            "recipient",
            List.of(
                new JobOrderItemHandoverEntryCreateDto(lineId, 3),
                new JobOrderItemHandoverEntryCreateDto(secondLineId, 2))));

    assertThat(line.getDeliveredAmount()).isEqualTo(3);
    assertThat(secondLine.getDeliveredAmount()).isEqualTo(2);
    verify(inventoryItemRepository, times(1))
        .findGameItemRowsByJobOrderAndGameItemForUpdate(orderId, gameItemId);
    verify(inventoryItemRepository).delete(row);
  }

  @Test
  void createItemHandoverRatchetsAStockBackedItemOfferOnTheReducedRow() {
    // covers REQ-ORDERS-030 + REQ-MARKET-013/014 — a partially consumed row that still exists must
    // ratchet any active stock-backed Materialbörse item offer on it down to the row's reduced
    // whole-unit stock (the item-offer sibling of the material handover's
    // clampOfferedAmountToStock,
    // ADR-0108). Hand over 3 of a 5-unit row → the offer is clamped to the remaining 2 units.
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    InventoryItem row = itemRow(5.0, 5.0);
    UUID rowId = row.getId();
    when(inventoryItemRepository.findGameItemRowsByJobOrderAndGameItemForUpdate(
            orderId, gameItemId))
        .thenReturn(List.of(row));

    service.createItemHandover(orderId, payload(lineId, 3));

    verify(materialExchangeOfferRepository).clampItemQuantityToStock(rowId, 2);
  }

  @Test
  void createItemHandoverDoesNotClampAnItemOfferForADepletedRow() {
    // covers REQ-ORDERS-030 + REQ-MARKET-013/014 — a depleted row is deleted and its stock-backed
    // item offer is cascade-removed with it (V210 ON DELETE CASCADE), so no explicit clamp runs.
    // Mirrors the material handover's `if (!depleted)` guard.
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    InventoryItem row = itemRow(5.0, 5.0);
    when(inventoryItemRepository.findGameItemRowsByJobOrderAndGameItemForUpdate(
            orderId, gameItemId))
        .thenReturn(List.of(row));

    service.createItemHandover(orderId, payload(lineId, 5));

    verify(inventoryItemRepository).delete(row);
    verify(materialExchangeOfferRepository, never())
        .clampItemQuantityToStock(any(UUID.class), anyInt());
  }

  private static JobOrderItemHandoverCreateDto payload(UUID jobOrderItemId, int amount) {
    return new JobOrderItemHandoverCreateDto(
        Instant.parse("2026-01-01T00:00:00Z"),
        "recipient",
        List.of(new JobOrderItemHandoverEntryCreateDto(jobOrderItemId, amount)));
  }
}
