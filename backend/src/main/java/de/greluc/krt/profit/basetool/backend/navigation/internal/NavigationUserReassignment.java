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

import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRows;
import de.greluc.krt.profit.basetool.backend.platform.api.UserReassignmentParticipant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The navigation module's part of the account merge: the target's sections win, the favourites are
 * unioned, and the emptied account's preference rows go (REQ-SEC-046).
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class NavigationUserReassignment implements UserReassignmentParticipant {

  static final int POSITION = 210;

  private static final List<OwnedRows> FOLLOWS =
      List.of(OwnedRows.of("navigation_preferences", "user_id"));

  private final NavigationPreferencesRepository repository;

  @Override
  public int position() {
    return POSITION;
  }

  @Override
  public @NotNull List<OwnedRows> followsTheMember() {
    return FOLLOWS;
  }

  @Override
  public @NotNull List<String> staysWithTheAct() {
    return List.of();
  }

  @Override
  public void assertMergeable(@NotNull UUID source, @NotNull UUID target) {}

  @Override
  public @NotNull List<Moved> reassign(@NotNull UUID source, @NotNull UUID target) {
    Optional<NavigationPreferences> emptied = repository.findById(source);
    if (emptied.isEmpty()) {
      return List.of();
    }
    List<String> sourceFavourites = List.copyOf(emptied.get().getFavourites());
    Set<String> sourceHidden = Set.copyOf(emptied.get().getHidden());
    repository.delete(emptied.get());
    repository.flush();
    if (!sourceFavourites.isEmpty() || !sourceHidden.isEmpty()) {
      repository.insertIfAbsent(target);
      NavigationPreferences surviving = repository.findById(target).orElseThrow();
      List<String> favourites =
          FavouritesMerge.union(List.copyOf(surviving.getFavourites()), sourceFavourites);
      surviving.replaceHidden(HiddenMerge.union(surviving.getHidden(), sourceHidden, favourites));
      surviving.replaceFavourites(favourites);
      repository.saveAndFlush(surviving);
      repository.bumpFavouritesVersion(target);
      repository.bumpHiddenVersion(target);
    }
    return List.of(new Moved(FOLLOWS.getFirst().qualified(), 1));
  }
}
