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

package de.greluc.krt.profit.basetool.backend.blueprint.internal;

import de.greluc.krt.profit.basetool.backend.blueprint.api.DefaultBlueprintKeys;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * In-memory cache of the product keys in the default-blueprint set (REQ-INV-016/017).
 *
 * <p>Answers whether an owned blueprint is a default, which marks a {@link
 * de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintResponse} non-removable and
 * guards the delete endpoint. Loaded lazily, warmed at startup and reloaded by {@link #refresh()}
 * on every change to the set.
 */
@Service
@RequiredArgsConstructor
public class DefaultBlueprintKeyService implements DefaultBlueprintKeys {

  private final DefaultBlueprintRepository repository;
  private final AtomicReference<Set<String>> cache = new AtomicReference<>();

  /**
   * Whether the given product key belongs to the default set (and is therefore non-removable).
   *
   * @param productKey product key, may be {@code null}
   * @return {@code true} if the key is a default product key
   */
  @Override
  public boolean isDefault(@Nullable String productKey) {
    return productKey != null && current().contains(productKey);
  }

  /** Reloads the cached default product-key set from the database. */
  public void refresh() {
    cache.set(load());
  }

  /**
   * Reloads the cached set once the current transaction commits, so a rolled-back change never
   * reaches the cache; outside a transaction it reloads at once.
   */
  public void refreshAfterCommit() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      refresh();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            refresh();
          }
        });
  }

  /** Warms the cache once the application context is fully started. */
  @EventListener(ApplicationReadyEvent.class)
  public void warmOnStartup() {
    refresh();
  }

  @NotNull
  private Set<String> current() {
    Set<String> snapshot = cache.get();
    if (snapshot == null) {
      snapshot = load();
      cache.set(snapshot);
    }
    return snapshot;
  }

  @NotNull
  private Set<String> load() {
    return Set.copyOf(repository.findAllProductKeys());
  }
}
