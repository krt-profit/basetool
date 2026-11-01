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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderNumberHitDto;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHit;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link JobOrderQuickAccessSearchContributor}: the scoped number lookup, with the
 * number as the only label.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderQuickAccessSearchContributorTest {

  private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");

  @Mock private JobOrderBackendClient jobOrderClient;

  @InjectMocks private JobOrderQuickAccessSearchContributor contributor;

  /** It searches job orders and accepts a single digit. */
  @Test
  void searchesJobOrdersFromOneDigit() {
    assertThat(contributor.kind()).isEqualTo(QuickAccessSearchKind.JOB_ORDER);
    assertThat(contributor.kind().minTermLength()).isEqualTo(1);
  }

  /** The digits and the limit go to the scoped number lookup. */
  @Test
  void asksTheNumberLookup() {
    when(jobOrderClient.ordersByNumber("12", 5)).thenReturn(List.of());

    contributor.results("12", 5);

    verify(jobOrderClient).ordersByNumber("12", 5);
  }

  /** Each order becomes a hit on its detail page labelled by number, with status and creation. */
  @Test
  void mapsOrdersToNumberHits() {
    Instant created = Instant.parse("2026-11-01T18:00:00Z");
    when(jobOrderClient.ordersByNumber("7", 5))
        .thenReturn(List.of(new JobOrderNumberHitDto(ORDER_ID, 7, "IN_PROGRESS", created)));

    assertThat(contributor.results("7", 5))
        .containsExactly(
            new QuickAccessHit(
                "/orders/00000000-0000-0000-0000-000000000007",
                "#7",
                "status.IN_PROGRESS",
                created));
  }

  /** A term that is not a number matches no order number and asks the backend nothing. */
  @Test
  void aNonNumericTermAsksNothing() {
    assertThat(contributor.results("mining", 5)).isEmpty();
    assertThat(contributor.results("1234567890", 5)).isEmpty();
    verifyNoInteractions(jobOrderClient);
  }

  /** A response without a body yields no hit. */
  @Test
  void noBodyYieldsNoHit() {
    when(jobOrderClient.ordersByNumber("9", 5)).thenReturn(null);

    assertThat(contributor.results("9", 5)).isEmpty();
  }
}
