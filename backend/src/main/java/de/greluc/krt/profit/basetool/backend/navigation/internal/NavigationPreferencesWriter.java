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
import java.util.Collection;
import java.util.EnumSet;
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
 * A page is never both a favourite and hidden: every write refuses a result in which it is.
 */
@Service
@RequiredArgsConstructor
public class NavigationPreferencesWriter {

  private static final String REPEATED_KEY = "An entry key may appear only once.";

  private static final String FAVOURITE_AND_HIDDEN =
      "An entry cannot be a favourite and hidden at the same time.";

  private final NavigationPreferencesRepository repository;
  private final NavigationAppearanceWriter appearance;

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
        .map(this::toResponse)
        .orElseGet(() ->
            new NavigationPreferencesResponse(
                new FavouritesResponse(List.of(), 0),
                new HiddenResponse(List.of(), 0),
                new AppearanceResponse(DesktopMode.DRAWER, 0)));
  }

  /**
   * Replaces the favourites when the echoed version is current.
   *
   * @param userId the owner's {@code app_user.id}
   * @param keys the new favourite keys in display order
   * @param expectedVersion the favourites version the caller last saw
   * @return the stored favourites with the bumped version
   * @throws BadRequestException when a key appears twice or is hidden
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   */
  @Transactional
  public @NotNull FavouritesResponse replaceFavourites(
      @NotNull UUID userId, @NotNull List<String> keys, long expectedVersion) {
    requireDistinct(keys);
    repository.insertIfAbsent(userId);
    requireDisjoint(keys, repository.findById(userId).orElseThrow().getHidden());
    if (repository.bumpFavouritesVersionIfMatches(userId, expectedVersion) == 0) {
      throw stale(userId);
    }
    NavigationPreferences preferences = repository.findById(userId).orElseThrow();
    preferences.replaceFavourites(keys);
    repository.saveAndFlush(preferences);
    return favouritesOf(preferences);
  }

  /**
   * Replaces the hidden pages when the echoed version is current.
   *
   * @param userId the owner's {@code app_user.id}
   * @param keys the new hidden page keys
   * @param expectedVersion the hidden version the caller last saw
   * @return the stored hidden pages with the bumped version
   * @throws BadRequestException when a key appears twice or is a favourite
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   */
  @Transactional
  public @NotNull HiddenResponse replaceHidden(
      @NotNull UUID userId, @NotNull List<String> keys, long expectedVersion) {
    requireDistinct(keys);
    repository.insertIfAbsent(userId);
    requireDisjoint(keys, repository.findById(userId).orElseThrow().getFavourites());
    if (repository.bumpHiddenVersionIfMatches(userId, expectedVersion) == 0) {
      throw stale(userId);
    }
    NavigationPreferences preferences = repository.findById(userId).orElseThrow();
    preferences.replaceHidden(keys);
    repository.saveAndFlush(preferences);
    return toResponse(preferences).hidden();
  }

  /**
   * Replaces the favourites and the hidden pages in one write, checking both versions; a stale
   * version of either changes neither.
   *
   * @param userId the owner's {@code app_user.id}
   * @param favourites the new favourite keys in display order
   * @param favouritesVersion the favourites version the caller last saw
   * @param hidden the new hidden page keys
   * @param hiddenVersion the hidden version the caller last saw
   * @return both sections with their bumped versions
   * @throws BadRequestException when a key appears twice or in both lists
   * @throws ObjectOptimisticLockingFailureException when either version is stale
   */
  @Transactional
  public @NotNull NavigationPreferencesResponse replaceFavouritesAndHidden(
      @NotNull UUID userId,
      @NotNull List<String> favourites,
      long favouritesVersion,
      @NotNull List<String> hidden,
      long hiddenVersion) {
    requireDistinct(favourites);
    requireDistinct(hidden);
    requireDisjoint(favourites, hidden);
    repository.insertIfAbsent(userId);
    if (repository.bumpBothVersionsIfMatch(userId, favouritesVersion, hiddenVersion) == 0) {
      throw stale(userId);
    }
    NavigationPreferences preferences = repository.findById(userId).orElseThrow();
    preferences.replaceFavourites(favourites);
    preferences.replaceHidden(hidden);
    repository.saveAndFlush(preferences);
    return toResponse(preferences);
  }

  /**
   * Deletes the named keys from every section, bumping the version of each section that changed.
   *
   * @param userId the owner's {@code app_user.id}
   * @param keys the entry keys the member may no longer see
   * @return the sections in which a stored entry was deleted
   */
  @Transactional
  public @NotNull Set<NavigationSection> prune(@NotNull UUID userId, @NotNull List<String> keys) {
    Set<String> gone = Set.copyOf(keys);
    Set<NavigationSection> changed = EnumSet.noneOf(NavigationSection.class);
    repository
        .findById(userId)
        .ifPresent(preferences -> {
          if (pruneFavourites(preferences, gone)) {
            changed.add(NavigationSection.FAVOURITES);
          }
          if (pruneHidden(preferences, gone)) {
            changed.add(NavigationSection.HIDDEN);
          }
        });
    return changed;
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

  private boolean pruneHidden(NavigationPreferences preferences, Set<String> gone) {
    NavigationPreferences current = repository.findById(preferences.getUserId()).orElseThrow();
    List<String> kept = current.getHidden().stream().filter(k -> !gone.contains(k)).toList();
    if (kept.size() == current.getHidden().size()) {
      return false;
    }
    current.replaceHidden(kept);
    repository.saveAndFlush(current);
    repository.bumpHiddenVersion(current.getUserId());
    return true;
  }

  private static void requireDistinct(List<String> keys) {
    if (new HashSet<>(keys).size() != keys.size()) {
      throw new BadRequestException(REPEATED_KEY);
    }
  }

  private static void requireDisjoint(Collection<String> first, Collection<String> second) {
    Set<String> other = new HashSet<>(second);
    if (first.stream().anyMatch(other::contains)) {
      throw new BadRequestException(FAVOURITE_AND_HIDDEN);
    }
  }

  private static ObjectOptimisticLockingFailureException stale(UUID userId) {
    return new ObjectOptimisticLockingFailureException(NavigationPreferences.class, userId);
  }

  private NavigationPreferencesResponse toResponse(NavigationPreferences preferences) {
    return new NavigationPreferencesResponse(
        favouritesOf(preferences),
        new HiddenResponse(
            preferences.getHidden().stream().sorted().toList(), preferences.getHiddenVersion()),
        appearance.read(preferences.getUserId(), preferences.getAppearanceVersion()));
  }

  private static FavouritesResponse favouritesOf(NavigationPreferences preferences) {
    return new FavouritesResponse(
        List.copyOf(preferences.getFavourites()), preferences.getFavouritesVersion());
  }
}
