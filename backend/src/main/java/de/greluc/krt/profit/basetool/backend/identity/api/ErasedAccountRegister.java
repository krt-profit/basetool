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

package de.greluc.krt.profit.basetool.backend.identity.api;

import de.greluc.krt.profit.basetool.backend.identity.api.events.AccountErasedEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The erasure register (REQ-DATA-048): the former ids of deleted accounts, kept for 12 months so a
 * surviving token or Keycloak user never re-creates the account and a restore re-applies the
 * deletion.
 */
public interface ErasedAccountRegister {

  /**
   * Registers a deleted account inside the deletion's transaction and publishes {@link
   * AccountErasedEvent}; registering an id twice keeps the first time.
   *
   * @param userId the deleted account's former {@code app_user.id}
   */
  void record(@NotNull UUID userId);

  /**
   * Answers whether {@code userId} belongs to a deleted account.
   *
   * @param userId the account id to check
   * @return {@code true} when the id is registered
   */
  boolean isErased(@NotNull UUID userId);

  /**
   * Returns every registered id, oldest deletion first.
   *
   * @return the registered ids
   */
  @NotNull
  @Unmodifiable
  List<UUID> erasedAccounts();

  /**
   * Counts the registered ids that still have an account row.
   *
   * @return the number of deletions waiting to be re-applied
   */
  long pendingReapplications();

  /**
   * Drops the entries of accounts deleted before {@code cutoff}.
   *
   * @param cutoff the oldest deletion time that stays registered
   * @return the number of entries dropped
   */
  int expireBefore(@NotNull Instant cutoff);
}
