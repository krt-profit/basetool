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
 * <p>Answers whether an owned blueprint is a default, which marks a {@link
 * de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse} non-removable and
 * guards the delete endpoint. Loaded lazily, warmed at startup and reloaded by {@link #refresh()}
 * on every change to the set.
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
