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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemHandover;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemHandoverEntry;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocuments;
import de.greluc.krt.profit.basetool.backend.platform.internal.pdf.TaggedKrtPdfDocuments;
import de.greluc.krt.profit.basetool.backend.support.PdfConformance;
import de.greluc.krt.profit.basetool.backend.support.TaggedStructure;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.context.MessageSource;

/**
 * Unit tests for {@link JobOrderItemHandoverReportService}: a valid PDF is produced for a persisted
 * item handover (carrying the order number and produced item names), and a handover belonging to a
 * different order is rejected.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderItemHandoverReportServiceTest {

  @Mock private JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  @Mock private MessageSource messageSource;
  @InjectMocks private JobOrderItemHandoverReportService service;

  @Spy private KrtPdfDocuments pdfDocuments = new TaggedKrtPdfDocuments();

  @Test
  void generatesPdfContainingOrderNumberAndItemName() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID handoverId = UUID.randomUUID();
    JobOrderItemHandover handover = handover(orderId, "Ballista", 3);
    when(jobOrderItemHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    byte[] pdf = service.generateItemHandoverReport(orderId, handoverId, ZoneOffset.UTC);

    assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
    String content = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
    assertThat(content).contains("#42");
    assertThat(content).contains("Ballista");
    PdfConformance.assertConformant(pdf);
    TaggedStructure structure = TaggedStructure.read(pdf);
    assertThat(structure.kidTypes()).containsExactly("H1", "Table", "H2", "Table", "P");
    assertThat(structure.kids().get(3).kids().getFirst().kidTypes()).containsExactly("TH", "TH");
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
