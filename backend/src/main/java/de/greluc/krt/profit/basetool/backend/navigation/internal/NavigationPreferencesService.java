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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * The caller's own navigation preferences (REQ-UI-032, ADR-0298): owner-only, unaudited and not
 * pushed live. Every write is counted once it has committed or been refused, so the service itself
 * holds no transaction.
 */
@Service
@RequiredArgsConstructor
public class NavigationPreferencesService {

  private final NavigationPreferencesWriter writer;
  private final NavigationTabBarWriter tabBarWriter;
  private final NavigationAppearanceWriter appearanceWriter;
  private final NavigationMetrics metrics;

  /**
   * Reads the caller's sections.
   *
   * @param userId the caller's {@code app_user.id}
   * @return every section with its version
   */
  public @NotNull NavigationPreferencesResponse read(@NotNull UUID userId) {
    return writer.read(userId);
  }

  /**
   * Replaces the caller's favourites.
   *
   * @param userId the caller's {@code app_user.id}
   * @param request the new keys and the version the caller last saw
   * @return the stored favourites with the new version
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   * @throws BadRequestException when a key repeats or is hidden
   */
  public @NotNull FavouritesResponse replaceFavourites(
      @NotNull UUID userId, @NotNull FavouritesUpdateRequest request) {
    return counted(
        Set.of(NavigationSection.FAVOURITES),
        () -> writer.replaceFavourites(userId, request.keys(), request.version()));
  }

  /**
   * Replaces the caller's hidden pages.
   *
   * @param userId the caller's {@code app_user.id}
   * @param request the new keys and the version the caller last saw
   * @return the stored hidden pages with the new version
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   * @throws BadRequestException when a key repeats or is a favourite
   */
  public @NotNull HiddenResponse replaceHidden(
      @NotNull UUID userId, @NotNull HiddenUpdateRequest request) {
    return counted(
        Set.of(NavigationSection.HIDDEN),
        () -> writer.replaceHidden(userId, request.keys(), request.version()));
  }

  /**
   * Replaces the caller's favourites and hidden pages in one write that checks both versions.
   *
   * @param userId the caller's {@code app_user.id}
   * @param request both sections' new keys and the versions the caller last saw
   * @return both sections with their new versions
   * @throws ObjectOptimisticLockingFailureException when either version is stale
   * @throws BadRequestException when a key repeats or sits in both lists
   */
  public @NotNull NavigationPreferencesResponse replaceFavouritesAndHidden(
      @NotNull UUID userId, @NotNull FavouritesAndHiddenUpdateRequest request) {
    return counted(
        Set.of(NavigationSection.FAVOURITES, NavigationSection.HIDDEN),
        () ->
            writer.replaceFavouritesAndHidden(
                userId,
                request.favourites().keys(),
                request.favourites().version(),
                request.hidden().keys(),
                request.hidden().version()));
  }

  /**
   * Replaces the stored order of one scope of one of the caller's modes.
   *
   * @param userId the caller's {@code app_user.id}
   * @param mode the mode tag, {@code member} or {@code admin}
   * @param scope {@code groups} or the key of the group whose links are ordered
   * @param request the scope's keys in their new order and the version the caller last saw
   * @return the mode's order with the new version
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   * @throws BadRequestException when the mode or scope is malformed or a key repeats
   */
  public @NotNull OrderModeResponse replaceOrderScope(
      @NotNull UUID userId,
      @NotNull String mode,
      @NotNull String scope,
      @NotNull OrderScopeUpdateRequest request) {
    return counted(
        Set.of(NavigationSection.ORDER),
        () ->
            writer.replaceOrderScope(
                userId,
                NavigationMode.fromSegment(mode),
                scope,
                request.keys(),
                request.version()));
  }

  /**
   * Clears the stored order of one of the caller's modes, back to the default order.
   *
   * @param userId the caller's {@code app_user.id}
   * @param mode the mode tag, {@code member} or {@code admin}
   * @param request the version the caller last saw
   * @return the empty order with the new version
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   * @throws BadRequestException when the mode is unknown
   */
  public @NotNull OrderModeResponse resetOrder(
      @NotNull UUID userId, @NotNull String mode, @NotNull OrderResetRequest request) {
    return counted(
        Set.of(NavigationSection.ORDER),
        () -> writer.resetOrder(userId, NavigationMode.fromSegment(mode), request.version()));
  }

  /**
   * Replaces the caller's phone tab bar of one mode.
   *
   * @param userId the caller's {@code app_user.id}
   * @param mode the mode whose bar changes
   * @param request the three slots and the version the caller last saw
   * @return the stored bar with the new version
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   * @throws BadRequestException when a key repeats
   */
  public @NotNull TabBarResponse replaceTabBar(
      @NotNull UUID userId, @NotNull NavigationMode mode, @NotNull TabBarUpdateRequest request) {
    return counted(
        Set.of(NavigationSection.TAB_BAR),
        () -> tabBarWriter.replace(userId, mode, request.slots(), request.version()));
  }

  /**
   * Replaces the caller's "Darstellung" section.
   *
   * @param userId the caller's {@code app_user.id}
   * @param request the new desktop mode and shortcut switch, and the version the caller last saw
   * @return the stored appearance with the new version
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   */
  public @NotNull AppearanceResponse replaceAppearance(
      @NotNull UUID userId, @NotNull AppearanceUpdateRequest request) {
    try {
      AppearanceResponse stored =
          appearanceWriter.replace(
              userId, request.desktopMode(), request.shortcutsEnabled(), request.version());
      metrics.recordWrite(NavigationSection.APPEARANCE, NavigationWriteOutcome.OK);
      return stored;
    } catch (ObjectOptimisticLockingFailureException e) {
      metrics.recordWrite(NavigationSection.APPEARANCE, NavigationWriteOutcome.CONFLICT);
      throw e;
    }
  }

  /**
   * Deletes entry keys the caller may no longer see from every section.
   *
   * @param userId the caller's {@code app_user.id}
   * @param keys the entry keys to delete
   * @return the caller's sections after the deletion
   */
  public @NotNull NavigationPreferencesResponse prune(
      @NotNull UUID userId, @NotNull List<String> keys) {
    writer.prune(userId, keys).forEach(this::countOk);
    return writer.read(userId);
  }

  private <T> T counted(Set<NavigationSection> sections, Supplier<T> write) {
    try {
      T stored = write.get();
      sections.forEach(this::countOk);
      return stored;
    } catch (ObjectOptimisticLockingFailureException e) {
      sections.forEach(s -> metrics.recordWrite(s, NavigationWriteOutcome.CONFLICT));
      throw e;
    } catch (BadRequestException e) {
      sections.forEach(s -> metrics.recordWrite(s, NavigationWriteOutcome.REJECTED));
      throw e;
    }
  }

  private void countOk(NavigationSection section) {
    metrics.recordWrite(section, NavigationWriteOutcome.OK);
  }
}
