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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import de.greluc.krt.profit.basetool.backend.kernel.TransactionMemo;
import de.greluc.krt.profit.basetool.backend.platform.api.ClientNames;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Names the exchange clients job-order writes came through by the registry's display name, or by
 * the client id once the registry no longer holds it (REQ-ORDERS-078).
 *
 * <p>The names come from {@link ClientNames}, memoised per transaction: a miss reads the names of
 * every client the orders of the persistence context carry, together with the primed ones, in one
 * query, so a page of orders costs one registry read, not one per row (REQ-DATA-003).
 */
@Component
@RequiredArgsConstructor
public class JobOrderClientNames {

  /** The registry's display names, memoised per transaction. */
  private final ClientNames clientNames;

  /** The persistence context the orders of the running transaction live in. */
  private final EntityManager entityManager;

  /**
   * Returns a client's display name.
   *
   * @param clientId the client's registry id, or {@code null} for the web
   * @return the display name, the id once the registry no longer holds it, or {@code null} for the
   *     web
   */
  public @Nullable String nameOf(@Nullable String clientId) {
    return clientNames.nameOf(clientId, this::clientsInContext);
  }

  /**
   * Returns the name of the client an actor writes through, for a notice that names the
   * application.
   *
   * @param actor who writes
   * @return the client's display name or id, or {@code null} for a write in the web
   */
  public @Nullable String appOf(@NotNull JobOrderActor actor) {
    return nameOf(actor.clientId());
  }

  /**
   * Reads the names of clients a projection is about to name beside the orders of the persistence
   * context, in one query with theirs.
   *
   * @param clientIds the client ids; {@code null} entries are skipped
   */
  public void prime(@NotNull Collection<String> clientIds) {
    clientNames.prime(clientIds, this::clientsInContext);
  }

  /**
   * Lists the clients the orders of the persistence context were created and commented through.
   *
   * @return the client ids, {@code null} for a write in the web
   */
  private @NotNull List<String> clientsInContext() {
    return TransactionMemo.entitiesInContext(entityManager, JobOrder.class).stream()
        .flatMap(order -> Stream.of(order.getCreatedByClientId(), order.getCommentClientId()))
        .toList();
  }
}
