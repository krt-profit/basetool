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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Sets one of the member's entries of one resource back to its state before a client's writes,
 * reading the journal state that resource's writer recorded (REQ-XCH-022, ADR-0285).
 */
interface ExchangeEntryRestorer {

  /**
   * Returns the resource whose entries this restorer sets back.
   *
   * @return the resource
   */
  @NotNull
  ExchangeResource resource();

  /**
   * Restores one entry to its state before the client's first write in the group.
   *
   * @param scope the member, the client and the member's memberships
   * @param group the client's writes to the entry, newest first
   * @return {@code null} when restored, else {@code CHANGED_AFTERWARDS} or {@code GONE}
   */
  @Nullable
  String restore(@NotNull UndoScope scope, @NotNull List<ExchangeJournalEntry> group);

  /**
   * Whether something other than the client's newest write changed an entry since, judged by the
   * change log; an entry whose latest change-log entry is missing counts as changed, because
   * nothing proves it was not.
   *
   * @param changes the change log
   * @param member the member
   * @param newest the client's newest write to the entry
   * @param feedKey the entry's key in the change log
   * @return {@code true} when the entry must be left alone
   */
  static boolean changedAfterwards(
      @NotNull ExchangeChangeRepository changes,
      @NotNull UUID member,
      @NotNull ExchangeJournalEntry newest,
      @NotNull String feedKey) {
    Optional<ExchangeChange> latest =
        changes.findLatestForKey(member, newest.getResource().name(), feedKey);
    return latest.isEmpty() || latest.get().getTx() != newest.getTx();
  }

  /**
   * Who an undo runs for.
   *
   * @param member the member whose entries are set back
   * @param clientId the client whose writes are undone
   * @param memberships the member's direct org-unit memberships, loaded on first use
   */
  record UndoScope(
      @NotNull UUID member, @NotNull String clientId, @NotNull Supplier<Set<UUID>> memberships) {}
}
