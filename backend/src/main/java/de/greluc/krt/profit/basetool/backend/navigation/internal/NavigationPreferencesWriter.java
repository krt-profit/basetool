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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional reads and writes of the navigation preferences; {@link
 * NavigationPreferencesService} wraps them with the write metrics.
 *
 * <p>A section write bumps the section's counter with a conditional {@code UPDATE} first, which
 * both rejects a stale version and locks the member's root row until the commit, so two racing
 * writers of one section serialise and a write of another section is never blocked by its version.
 */
@Service
@RequiredArgsConstructor
public class NavigationPreferencesWriter {

  private final NavigationPreferencesRepository repository;

  /**
   * Reads every section of the member; a member without a row holds the empty defaults.
   *
   * @param userId the owner's {@code app_user.id}
   * @return the sections with their versions
   */
  @Transactional(readOnly = true)
  public @NotNull NavigationPreferencesResponse read(@NotNull UUID userId) {
    return repository
        .findById(userId)
        .map(NavigationPreferencesWriter::toResponse)
        .orElseGet(() -> new NavigationPreferencesResponse(new FavouritesResponse(List.of(), 0)));
  }

  /**
   * Replaces the favourites when the echoed version is current.
   *
   * @param userId the owner's {@code app_user.id}
   * @param keys the new favourite keys in display order
   * @param expectedVersion the favourites version the caller last saw
   * @return the stored favourites with the bumped version
   * @throws BadRequestException when a key appears twice
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   */
  @Transactional
  public @NotNull FavouritesResponse replaceFavourites(
      @NotNull UUID userId, @NotNull List<String> keys, long expectedVersion) {
    if (new HashSet<>(keys).size() != keys.size()) {
      throw new BadRequestException("A favourite entry key may appear only once.");
    }
    repository.insertIfAbsent(userId);
    if (repository.bumpFavouritesVersionIfMatches(userId, expectedVersion) == 0) {
      throw new ObjectOptimisticLockingFailureException(NavigationPreferences.class, userId);
    }
    NavigationPreferences preferences = repository.findById(userId).orElseThrow();
    preferences.replaceFavourites(keys);
    repository.saveAndFlush(preferences);
    return toResponse(preferences).favourites();
  }

  /**
   * Deletes the named keys from every section, bumping the version of each section that changed.
   *
   * @param userId the owner's {@code app_user.id}
   * @param keys the entry keys the member may no longer see
   * @return {@code true} when a stored entry was deleted
   */
  @Transactional
  public boolean prune(@NotNull UUID userId, @NotNull List<String> keys) {
    Set<String> gone = Set.copyOf(keys);
    return repository
        .findById(userId)
        .map(preferences -> pruneFavourites(preferences, gone))
        .orElse(false);
  }

  private boolean pruneFavourites(NavigationPreferences preferences, Set<String> gone) {
    List<String> kept =
        preferences.getFavourites().stream().filter(k -> !gone.contains(k)).toList();
    if (kept.size() == preferences.getFavourites().size()) {
      return false;
    }
    preferences.replaceFavourites(kept);
    repository.saveAndFlush(preferences);
    repository.bumpFavouritesVersion(preferences.getUserId());
    return true;
  }

  private static NavigationPreferencesResponse toResponse(NavigationPreferences preferences) {
    return new NavigationPreferencesResponse(
        new FavouritesResponse(
            List.copyOf(preferences.getFavourites()), preferences.getFavouritesVersion()));
  }
}
