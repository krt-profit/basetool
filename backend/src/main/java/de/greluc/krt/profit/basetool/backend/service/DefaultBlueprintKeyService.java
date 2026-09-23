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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.repository.DefaultBlueprintRepository;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * In-memory cache of the normalized product keys in the default-blueprint set (REQ-INV-016/017).
 *
 * <p>The set is tiny and changes only when an admin curates it, so it is cached in an {@link
 * AtomicReference} and refreshed on mutation rather than queried per row. It answers the hot-path
 * question "is this owned blueprint a default?" used to (a) flag a {@link
 * de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse} as non-removable so
 * the UI hides its delete control and (b) guard the delete endpoint server-side.
 *
 * <p>The cache loads lazily on first access and is warmed at startup; {@link #refresh()} is called
 * whenever the default set is added to or removed from.
 */
@Service
@RequiredArgsConstructor
public class DefaultBlueprintKeyService {

  private final DefaultBlueprintRepository repository;
  private final AtomicReference<Set<String>> cache = new AtomicReference<>();

  /**
   * Whether the given normalized product key belongs to the default set (and is therefore
   * non-removable).
   *
   * @param productKey normalized product key, may be {@code null}
   * @return {@code true} if the key is a default product key
   */
  public boolean isDefault(@Nullable String productKey) {
    return productKey != null && current().contains(productKey);
  }

  /** Reloads the cached default product-key set from the database. */
  public void refresh() {
    cache.set(load());
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
