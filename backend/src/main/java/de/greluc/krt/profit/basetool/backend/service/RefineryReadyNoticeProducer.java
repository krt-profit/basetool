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

import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.RefineryGood;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.notification.api.TimedNoticeProducer;
import de.greluc.krt.profit.basetool.backend.refinery.api.events.RefineryNotices;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Tells the owner of a refinery order when its run has ended and the output can be collected
 * (REQ-REFINERY-023), once per order: the marker is set by an atomic update that does not touch the
 * order's version, and only the caller whose update changed the row publishes.
 */
@Component
@RequiredArgsConstructor
public class RefineryReadyNoticeProducer implements TimedNoticeProducer {

  private final RefineryOrderRepository refineryOrderRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  @NotNull
  public String kind() {
    return "refinery_ready";
  }

  @Override
  public int produce(@NotNull Instant now) {
    int raised = 0;
    for (UUID id : refineryOrderRepository.findReadyUnannouncedIds(now)) {
      if (refineryOrderRepository.markReadyNotified(id, now) != 1) {
        continue;
      }
      RefineryOrder order = refineryOrderRepository.findById(id).orElse(null);
      if (order == null || order.getOwner() == null) {
        continue;
      }
      eventPublisher.publishEvent(
          RefineryNotices.ready(
              order.getId(),
              order.getOwner().getId(),
              order.getLocation() == null ? null : order.getLocation().getName(),
              outputs(order)));
      raised++;
    }
    return raised;
  }

  private static String outputs(RefineryOrder order) {
    List<String> lines = new ArrayList<>();
    for (RefineryGood good : order.getGoods()) {
      if (good.getOutputMaterial() == null || good.getOutputQuantity() == null) {
        continue;
      }
      boolean scu = good.getOutputMaterial().getQuantityType() == QuantityType.SCU;
      String amount =
          BigDecimal.valueOf(good.getOutputQuantity(), scu ? 2 : 0)
              .stripTrailingZeros()
              .toPlainString();
      lines.add(amount + (scu ? " SCU " : "x ") + good.getOutputMaterial().getName());
    }
    return String.join(", ", lines);
  }
}
