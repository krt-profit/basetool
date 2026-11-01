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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.UserReassignmentParticipant.Moved;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** Real-Postgres tests of the navigation part of the account merge (REQ-SEC-046). */
@SpringBootTest
@Transactional
class NavigationUserReassignmentTest {

  @Autowired private NavigationUserReassignment participant;
  @Autowired private NavigationPreferencesService service;
  @Autowired private NavigationPreferencesRepository repository;
  @Autowired private UserRepository userRepository;

  private UUID member() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("nav-" + UUID.randomUUID());
    user.setRank(1);
    user.setInKeycloak(false);
    return userRepository.saveAndFlush(user).getId();
  }

  private void store(UUID owner, String... keys) {
    service.replaceFavourites(owner, new FavouritesUpdateRequest(List.of(keys), 0L));
  }

  @Test
  @DisplayName("the favourites are unioned onto the target and the source's rows are gone")
  void unionsTheFavouritesAndRemovesTheSource() {
    UUID source = member();
    UUID target = member();
    store(source, "c.three", "a.one");
    store(target, "a.one", "b.two");

    List<Moved> moved = participant.reassign(source, target);

    assertThat(moved).containsExactly(new Moved("navigation_preferences.user_id", 1));
    assertThat(repository.findById(source)).isEmpty();
    FavouritesResponse merged = service.read(target).favourites();
    assertThat(merged.keys()).containsExactly("a.one", "b.two", "c.three");
    assertThat(merged.version()).isEqualTo(2);
  }

  @Test
  @DisplayName("a target without a row receives the source's favourites")
  void createsTheTargetsRow() {
    UUID source = member();
    UUID target = member();
    store(source, "a.one");

    participant.reassign(source, target);

    assertThat(service.read(target).favourites().keys()).containsExactly("a.one");
    assertThat(repository.findById(source)).isEmpty();
  }

  @Test
  @DisplayName("the hidden pages are unioned minus every page that is a favourite afterwards")
  void unionsTheHiddenPagesMinusTheFavourites() {
    UUID source = member();
    UUID target = member();
    service.replaceFavourites(target, new FavouritesUpdateRequest(List.of("a.one"), 0L));
    service.replaceHidden(target, new HiddenUpdateRequest(List.of("b.two"), 0L));
    service.replaceHidden(source, new HiddenUpdateRequest(List.of("a.one", "c.three"), 0L));

    participant.reassign(source, target);

    NavigationPreferencesResponse merged = service.read(target);
    assertThat(merged.favourites().keys()).containsExactly("a.one");
    assertThat(merged.hidden().keys()).containsExactly("b.two", "c.three");
    assertThat(merged.hidden().version()).isEqualTo(2);
  }

  @Test
  @DisplayName("a source without a row moves nothing")
  void sourceWithoutARowMovesNothing() {
    UUID target = member();
    store(target, "a.one");

    assertThat(participant.reassign(member(), target)).isEmpty();
    assertThat(service.read(target).favourites().keys()).containsExactly("a.one");
  }
}
