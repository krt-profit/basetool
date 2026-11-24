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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderWithTargetDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderWrittenDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReads;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Reads the terse answers of a client's job-order writes from the written order's exchange read, in
 * the write's transaction (REQ-OXCH-004 to REQ-OXCH-008).
 */
@Component
@RequiredArgsConstructor
class ExchangeJobOrderAnswers {

  private final JobOrderExchangeReads reads;

  /**
   * Answers a write on an existing order with the order's id, status and versions.
   *
   * @param orderId the written order
   * @return its terse answer
   */
  @NotNull
  ExchangeJobOrderWrittenDto written(@NotNull UUID orderId) {
    JobOrderExchangeRead read = read(orderId);
    return new ExchangeJobOrderWrittenDto(
        read.id(), read.status(), ExchangeJobOrderDto.Versions.of(read.versions()));
  }

  /**
   * Answers a create or a target change with the order's id, status, versions and stored target.
   *
   * @param orderId the created or written aUEC order
   * @return its terse answer
   * @throws IllegalStateException when the order is of another kind than aUEC
   */
  @NotNull
  ExchangeJobOrderWithTargetDto withTarget(@NotNull UUID orderId) {
    JobOrderExchangeRead read = read(orderId);
    return new ExchangeJobOrderWithTargetDto(
        read.id(),
        read.status(),
        ExchangeJobOrderDto.Versions.of(read.versions()),
        ExchangeAmounts.auecTarget(read));
  }

  /**
   * Reads the written order's exchange read.
   *
   * @param orderId the written order, which exists in the write's transaction
   * @return its member-independent read
   */
  private @NotNull JobOrderExchangeRead read(@NotNull UUID orderId) {
    return Objects.requireNonNull(reads.project(List.of(orderId)).get(orderId), "written order");
  }
}
