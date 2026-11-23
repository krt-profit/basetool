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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreateCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderWriterRule;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates an aUEC order and its target in one transaction, for the web and for a caller that passes
 * its own writer rule and actor (REQ-ORDERS-053, ADR-0405).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class JobOrderAuecCreation implements JobOrderCreateCommand {

  private final JobOrderService orders;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderStockProjectionService projection;
  private final AuthHelperService authHelper;

  /**
   * Creates an aUEC order for the web caller under their contextual authorities and the honoured
   * pin.
   *
   * @param dto the minimal order request
   * @return the new order with shared lifecycle and participation fields
   */
  public @NotNull JobOrderDto create(@NotNull CreateAuecJobOrderDto dto) {
    JobOrderCreateCommand.AuecOrder order =
        new JobOrderCreateCommand.AuecOrder(
            dto.responsibleOrgUnitId(),
            dto.requestingOrgUnitId(),
            dto.handle(),
            dto.comment(),
            WholeAuec.positive(dto.target()));
    return projection.mapToDtoWithStock(
        persist(
            order,
            JobOrderWriterRule.CONTEXTUAL,
            new JobOrderActor.Web(authHelper.currentUserId().orElse(null))));
  }

  @Override
  public @NotNull UUID createAuec(
      @NotNull AuecOrder order, @NotNull JobOrderWriterRule rule, @NotNull JobOrderActor actor) {
    return persist(order, rule, actor).getId();
  }

  private @NotNull JobOrder persist(
      @NotNull AuecOrder order, @NotNull JobOrderWriterRule rule, @NotNull JobOrderActor actor) {
    JobOrder created = orders.createAuecJobOrder(order, rule, actor);
    targets.save(new JobOrderMoneyTarget(created.getId(), order.target()));
    return created;
  }
}
