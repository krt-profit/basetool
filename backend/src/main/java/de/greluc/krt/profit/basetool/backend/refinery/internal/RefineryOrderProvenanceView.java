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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.kernel.TransactionMemo;
import de.greluc.krt.profit.basetool.backend.platform.api.ClientNames;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderProvenanceDto;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Names the clients of an order's provenance from the exchange registry through {@link
 * ClientNames}, memoised per transaction: the first miss reads the names of every client the orders
 * of the persistence context carry in one query, so a page of orders costs one registry read, not
 * one per row (REQ-DATA-003).
 */
@Component
@RequiredArgsConstructor
public class RefineryOrderProvenanceView implements RefineryOrderProvenanceLookup {

  /** The registry's display names, memoised per transaction. */
  private final ClientNames clientNames;

  /** The persistence context the orders of the running transaction live in. */
  private final EntityManager entityManager;

  @Override
  public @Nullable RefineryOrderProvenanceDto provenance(@Nullable RefineryOrder order) {
    if (order == null) {
      return null;
    }
    RefineryOrderProvenance provenance = order.getProvenance();
    return new RefineryOrderProvenanceDto(
        nameOf(provenance.getCreatedClient()),
        nameOf(provenance.getLastChangeClient()),
        provenance.getLastChangedAt(),
        provenance.getPickedUpAt(),
        nameOf(provenance.getPickedUpClient()),
        order.getStoredAt());
  }

  /**
   * Returns a client's display name, or its id when the registry no longer holds it.
   *
   * @param clientId the client id, or {@code null} for the web
   * @return the name, or {@code null} for the web
   */
  private @Nullable String nameOf(@Nullable String clientId) {
    return clientNames.nameOf(clientId, this::clientsInContext);
  }

  /**
   * Lists the clients the orders of the persistence context were created, changed and picked up
   * through.
   *
   * @return the client ids, {@code null} for a step in the web
   */
  private @NotNull List<String> clientsInContext() {
    return TransactionMemo.entitiesInContext(entityManager, RefineryOrder.class).stream()
        .map(RefineryOrder::getProvenance)
        .flatMap(provenance ->
            Stream.of(
                provenance.getCreatedClient(),
                provenance.getLastChangeClient(),
                provenance.getPickedUpClient()))
        .toList();
  }
}
