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

package de.greluc.krt.profit.basetool.backend.platform.api;

import de.greluc.krt.profit.basetool.backend.kernel.TransactionMemo;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Names the exchange clients a write came through by the registry's display name, or by the client
 * id once the registry no longer holds it, memoised per transaction.
 *
 * <p>A miss reads the requested id together with the ids the caller names beside it, the clients
 * the rows of its persistence context carry, in one query, so a page of rows costs one registry
 * read, not one per row (REQ-DATA-003).
 */
@Component
@RequiredArgsConstructor
public class ClientNames {

  /** The exchange registry's display names. */
  private final ClientDirectory clientDirectory;

  /**
   * Returns a client's display name.
   *
   * @param clientId the client's registry id, or {@code null} for the web
   * @param beside the client ids to read with a miss, asked only on a miss; {@code null} entries
   *     are skipped
   * @return the display name, the id once the registry no longer holds it, or {@code null} for the
   *     web
   */
  public @Nullable String nameOf(
      @Nullable String clientId, @NotNull Supplier<? extends Collection<String>> beside) {
    if (clientId == null) {
      return null;
    }
    Map<String, String> names = memo();
    String known = names.get(clientId);
    if (known != null) {
      return known;
    }
    read(names, Set.of(clientId), beside.get());
    return names.get(clientId);
  }

  /**
   * Reads the names of clients a projection is about to name, together with the ids the caller
   * names beside them, in one query.
   *
   * @param clientIds the client ids; {@code null} entries are skipped
   * @param beside the client ids to read with them; {@code null} entries are skipped
   */
  public void prime(
      @NotNull Collection<String> clientIds,
      @NotNull Supplier<? extends Collection<String>> beside) {
    read(memo(), clientIds, beside.get());
  }

  private @NotNull Map<String, String> memo() {
    return TransactionMemo.bound(this, Memo.class, Memo::new).names;
  }

  private void read(
      @NotNull Map<String, String> names,
      @NotNull Collection<String> requested,
      @NotNull Collection<String> beside) {
    Set<String> wanted = new LinkedHashSet<>();
    Stream.concat(requested.stream(), beside.stream())
        .filter(Objects::nonNull)
        .forEach(wanted::add);
    wanted.removeAll(names.keySet());
    if (wanted.isEmpty()) {
      return;
    }
    Map<String, String> found = clientDirectory.displayNames(wanted);
    for (String id : wanted) {
      names.put(id, found.getOrDefault(id, id));
    }
  }

  /** One transaction's memo. */
  private static final class Memo {

    /** The display names by client id; an unregistered id maps to itself. */
    private final Map<String, String> names = new HashMap<>();
  }
}
