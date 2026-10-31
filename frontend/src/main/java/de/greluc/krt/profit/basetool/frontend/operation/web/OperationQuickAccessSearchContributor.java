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

package de.greluc.krt.profit.basetool.frontend.operation.web;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHit;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchContributor;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.operation.client.OperationBackendClient;
import de.greluc.krt.profit.basetool.frontend.operation.client.OperationBackendClient.OperationSearch;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationDto;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Finds operations by name through the operation-list read, so a hit is an operation the list would
 * show the caller in any period (REQ-UI-033).
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class OperationQuickAccessSearchContributor implements QuickAccessSearchContributor {

  private static final String EVERY_PERIOD = "ALL";

  private final OperationBackendClient operationClient;

  /**
   * Searches operations.
   *
   * @return {@link QuickAccessSearchKind#OPERATION}
   */
  @Override
  public @NotNull QuickAccessSearchKind kind() {
    return QuickAccessSearchKind.OPERATION;
  }

  /**
   * Reads the first page of matching operations, newest first.
   *
   * @param query the search term
   * @param limit the page size
   * @return the operations as hits on their detail pages, dated by their creation
   */
  @Override
  public @NotNull List<QuickAccessHit> results(@NotNull String query, int limit) {
    PageResponse<OperationDto> page =
        operationClient.searchOperations(
            new OperationSearch(query, null, null, 0, limit, EVERY_PERIOD));
    if (page == null || page.content() == null) {
      return List.of();
    }
    return page.content().stream()
        .map(o ->
            new QuickAccessHit(
                "/operations/" + o.id(),
                o.name(),
                o.status() == null
                    ? null
                    : "operation.status." + o.status().toLowerCase(Locale.ROOT),
                o.createdAt()))
        .toList();
  }
}
