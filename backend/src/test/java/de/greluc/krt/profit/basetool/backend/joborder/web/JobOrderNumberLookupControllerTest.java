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

package de.greluc.krt.profit.basetool.backend.joborder.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderNumberHitDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderQueryService;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link JobOrderNumberLookupController}. */
@ExtendWith(MockitoExtension.class)
class JobOrderNumberLookupControllerTest {

  @Mock private JobOrderQueryService jobOrderQueryService;

  @InjectMocks private JobOrderNumberLookupController controller;

  /** The digits are forwarded and the limit is clamped to the maximum. */
  @Test
  void forwardsTheDigitsAndClampsTheLimit() {
    JobOrderNumberHitDto hit = new JobOrderNumberHitDto(UUID.randomUUID(), 12, null, null);
    when(jobOrderQueryService.findByNumberPrefix("12", 20)).thenReturn(List.of(hit));

    assertThat(controller.findByNumber("12", 500)).containsExactly(hit);
  }

  /** A limit below one is raised to one. */
  @Test
  void raisesANonPositiveLimitToOne() {
    when(jobOrderQueryService.findByNumberPrefix("3", 1)).thenReturn(List.of());

    assertThat(controller.findByNumber("3", 0)).isEmpty();
  }

  /** Anything but one to nine digits is refused before the service is asked. */
  @Test
  void refusesANonNumericTerm() {
    assertThatThrownBy(() -> controller.findByNumber("12a", 5))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> controller.findByNumber("", 5))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> controller.findByNumber("1234567890", 5))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(jobOrderQueryService);
  }
}
