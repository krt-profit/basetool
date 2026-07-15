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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.mapper.JobOrderItemHandoverMapper;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandover;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverEntryCreateDto;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
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
 * the non-item-order guard, and auto-completion when every line is fully delivered.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderItemHandoverServiceTest {

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  @Mock private JobOrderItemHandoverMapper jobOrderItemHandoverMapper;
  @Mock private JobOrderService jobOrderService;
  @Mock private UserService userService;
  @Mock private OrgUnitMembershipService orgUnitMembershipService;
  @Mock private SquadronRepository squadronRepository;
  @Mock private AuditService auditService;
  @InjectMocks private JobOrderItemHandoverService service;

  private UUID orderId;
  private UUID lineId;
  private JobOrder order;
  private JobOrderItem line;

  @BeforeEach
  void setUp() {
    orderId = UUID.randomUUID();
    lineId = UUID.randomUUID();
    // Fully manufactured so delivery up to the ordered amount is allowed (delivery is gated by
    // manufacture, REQ-ORDERS-025).
    line =
        JobOrderItem.builder()
            .id(lineId)
            .amount(5)
            .deliveredAmount(0)
            .manufacturedAmount(5)
            .build();
    order = JobOrder.builder().type(JobOrderType.ITEM).status(JobOrderStatus.OPEN).build();
    order.addItem(line);

    lenient().when(userService.getCurrentUser()).thenReturn(Optional.empty());
    lenient()
        .when(jobOrderItemHandoverRepository.save(any(JobOrderItemHandover.class)))
        .thenAnswer(inv -> inv.getArgument(0));
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

  private static JobOrderItemHandoverCreateDto payload(UUID jobOrderItemId, int amount) {
    return new JobOrderItemHandoverCreateDto(
        Instant.parse("2026-01-01T00:00:00Z"),
        "recipient",
        List.of(new JobOrderItemHandoverEntryCreateDto(jobOrderItemId, amount)));
  }
}
