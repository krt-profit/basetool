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

import de.greluc.krt.profit.basetool.backend.platform.api.ClientDirectory;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderProvenanceDto;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Names the clients of an order's provenance from the exchange registry, memoised per transaction:
 * the first miss reads the names of every client the orders of the persistence context carry in one
 * query, so a page of orders costs one registry read, not one per row (REQ-DATA-003).
 */
@Component
@RequiredArgsConstructor
public class RefineryOrderProvenanceView implements RefineryOrderProvenanceLookup {

  /** The exchange registry's display names. */
  private final ClientDirectory clientDirectory;

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
    if (clientId == null) {
      return null;
    }
    Map<String, String> names = RefineryTransactionMemo.bound(this, Memo.class, Memo::new).names;
    String known = names.get(clientId);
    if (known != null) {
      return known;
    }
    Set<String> wanted = clientsInContext();
    wanted.add(clientId);
    wanted.removeAll(names.keySet());
    Map<String, String> found = clientDirectory.displayNames(wanted);
    for (String id : wanted) {
      names.put(id, found.getOrDefault(id, id));
    }
    return names.get(clientId);
  }

  private @NotNull Set<String> clientsInContext() {
    Set<String> ids = new LinkedHashSet<>();
    for (RefineryOrder order : RefineryTransactionMemo.ordersInContext(entityManager)) {
      RefineryOrderProvenance provenance = order.getProvenance();
      addIfPresent(ids, provenance.getCreatedClient());
      addIfPresent(ids, provenance.getLastChangeClient());
      addIfPresent(ids, provenance.getPickedUpClient());
    }
    return ids;
  }

  private static void addIfPresent(@NotNull Set<String> ids, @Nullable String clientId) {
    if (clientId != null) {
      ids.add(clientId);
    }
  }

  /** One transaction's memo. */
  private static final class Memo {

    /** The display names by client id; an unregistered id maps to itself. */
    private final Map<String, String> names = new HashMap<>();
  }
}
