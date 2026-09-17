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
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandover;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandoverEntry;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

/**
 * Unit tests for {@link JobOrderItemHandoverReportService}: a valid PDF is produced for a persisted
 * item handover (carrying the order number and produced item names), and a handover belonging to a
 * different order is rejected.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderItemHandoverReportServiceTest {

  @Mock private JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  // The report now resolves one label from the bundle: the placeholder an Art. 17 erasure
  // leaves in a recipient handle (REQ-SEC-062). Without the mock the field is null and
  // every PDF fails at that lookup.
  @Mock private MessageSource messageSource;
  @InjectMocks private JobOrderItemHandoverReportService service;

  @Test
  void generatesPdfContainingOrderNumberAndItemName() {
    UUID orderId = UUID.randomUUID();
    UUID handoverId = UUID.randomUUID();
    JobOrderItemHandover handover = handover(orderId, "Ballista", 3);
    when(jobOrderItemHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    byte[] pdf = service.generateItemHandoverReport(orderId, handoverId, ZoneOffset.UTC);

    String content = new String(pdf, StandardCharsets.ISO_8859_1);
    assertThat(content).startsWith("%PDF");
    assertThat(content).contains("#42");
    assertThat(content).contains("Ballista");
  }

  @Test
  void rejectsHandoverBelongingToDifferentOrder() {
    UUID handoverId = UUID.randomUUID();
    JobOrderItemHandover handover = handover(UUID.randomUUID(), "Ballista", 1);
    when(jobOrderItemHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    assertThatThrownBy(
            () -> service.generateItemHandoverReport(UUID.randomUUID(), handoverId, ZoneOffset.UTC))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("does not belong");
  }

  private static JobOrderItemHandover handover(UUID orderId, String itemName, int amount) {
    GameItem gameItem = new GameItem();
    gameItem.setName(itemName);
    JobOrderItem line = JobOrderItem.builder().gameItem(gameItem).amount(amount).build();

    JobOrder order = JobOrder.builder().build();
    order.setId(orderId);
    order.setDisplayId(42);

    JobOrderItemHandover handover = new JobOrderItemHandover();
    handover.setJobOrder(order);
    handover.setRecipientHandle("recipient");
    handover.setHandoverTime(Instant.parse("2026-01-01T10:00:00Z"));
    JobOrderItemHandoverEntry entry = new JobOrderItemHandoverEntry();
    entry.setJobOrderItem(line);
    entry.setAmount(amount);
    handover.addEntry(entry);
    return handover;
  }
}
