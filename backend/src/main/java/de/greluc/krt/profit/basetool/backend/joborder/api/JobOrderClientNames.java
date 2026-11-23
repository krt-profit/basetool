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
import de.greluc.krt.profit.basetool.backend.platform.api.ClientDirectory;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Names the exchange clients job-order writes came through by the registry's display name, or by
 * the client id once the registry no longer holds it (REQ-ORDERS-078).
 *
 * <p>The names are memoised per transaction: the first miss reads the names of every client the
 * orders of the persistence context carry, together with the primed ones, in one query, so a page
 * of orders costs one registry read, not one per row (REQ-DATA-003).
 */
@Component
@RequiredArgsConstructor
public class JobOrderClientNames {

  /** The exchange registry's display names. */
  private final ClientDirectory clientDirectory;

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
    if (clientId == null) {
      return null;
    }
    Map<String, String> names = memo();
    String known = names.get(clientId);
    if (known != null) {
      return known;
    }
    read(names, Set.of(clientId));
    return names.get(clientId);
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
    read(memo(), clientIds);
  }

  private @NotNull Map<String, String> memo() {
    return TransactionMemo.bound(this, Memo.class, Memo::new).names;
  }

  private void read(@NotNull Map<String, String> names, @NotNull Collection<String> requested) {
    Set<String> wanted = new LinkedHashSet<>();
    requested.stream().filter(Objects::nonNull).forEach(wanted::add);
    for (JobOrder order : TransactionMemo.entitiesInContext(entityManager, JobOrder.class)) {
      addIfPresent(wanted, order.getCreatedByClientId());
      addIfPresent(wanted, order.getCommentClientId());
    }
    wanted.removeAll(names.keySet());
    if (wanted.isEmpty()) {
      return;
    }
    Map<String, String> found = clientDirectory.displayNames(wanted);
    for (String id : wanted) {
      names.put(id, found.getOrDefault(id, id));
    }
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
