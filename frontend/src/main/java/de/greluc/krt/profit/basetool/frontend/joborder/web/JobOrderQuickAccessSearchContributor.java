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

import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderNumberHitDto;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHit;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchContributor;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Finds job orders by the digits their number starts with, through the backend's number lookup, so
 * a hit is an order the order list or the assignee grant lets the caller open (REQ-UI-033).
 */
@Component
@Order(7)
@RequiredArgsConstructor
public class JobOrderQuickAccessSearchContributor implements QuickAccessSearchContributor {

  private static final Pattern NUMBER = Pattern.compile("[0-9]{1,9}");

  private final JobOrderBackendClient jobOrderClient;

  /**
   * Searches job orders.
   *
   * @return {@link QuickAccessSearchKind#JOB_ORDER}
   */
  @Override
  public @NotNull QuickAccessSearchKind kind() {
    return QuickAccessSearchKind.JOB_ORDER;
  }

  /**
   * Reads the orders whose number starts with the term; a term that is not one to nine digits
   * matches no number and asks the backend nothing.
   *
   * @param query the search term
   * @param limit the most hits
   * @return the orders as hits on their detail pages, labelled by number only
   */
  @Override
  public @NotNull List<QuickAccessHit> results(@NotNull String query, int limit) {
    if (!NUMBER.matcher(query).matches()) {
      return List.of();
    }
    List<JobOrderNumberHitDto> orders = jobOrderClient.ordersByNumber(query, limit);
    if (orders == null) {
      return List.of();
    }
    return orders.stream()
        .map(o ->
            new QuickAccessHit(
                "/orders/" + o.id(),
                "#" + o.displayId(),
                o.status() == null ? null : "status." + o.status(),
                o.createdAt()))
        .toList();
  }
}
