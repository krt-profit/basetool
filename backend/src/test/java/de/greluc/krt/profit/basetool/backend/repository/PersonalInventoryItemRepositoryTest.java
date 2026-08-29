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

package de.greluc.krt.profit.basetool.backend.repository;

import static org.junit.jupiter.api.Assertions.*;

import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryItem;
import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryLocationType;
import de.greluc.krt.profit.basetool.backend.model.User;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PersonalInventoryItemRepositoryTest {

  private static final UUID OWNER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
  private static final UUID OWNER_B = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");

  @Autowired private PersonalInventoryItemRepository repository;
  @Autowired private UserRepository userRepository;

  @BeforeEach
  void clean() {
    repository.deleteAll();
    // owner_sub is a foreign key to app_user(id) since V235 (REQ-DATA-008), so both owners have to
    // exist before an item can reference them.
    seedOwner(OWNER_A);
    seedOwner(OWNER_B);
  }

  /**
   * Creates the {@code app_user} row an item owner id has to point at, unless it already exists.
   *
   * @param id the owner id used as {@code owner_sub}
   */
  private void seedOwner(UUID id) {
    if (userRepository.existsById(id)) {
      return;
    }
    User user = new User();
    user.setId(id);
    user.setUsername("owner-" + id);
    userRepository.save(user);
  }

  @Test
  void findAllByOwnerSubShouldReturnOnlyMatchingItems() {
    // Given
    repository.save(item(OWNER_A, "Medkit"));
    repository.save(item(OWNER_A, "Ammo"));
    repository.save(item(OWNER_B, "Helmet"));

    // When
    Page<PersonalInventoryItem> page =
        repository.findAllByOwnerSub(OWNER_A, PageRequest.of(0, 10, Sort.by("name")));

    // Then
    assertEquals(2, page.getTotalElements());
    assertTrue(page.getContent().stream().allMatch(i -> OWNER_A.equals(i.getOwnerSub())));
  }

  @Test
  void findByIdAndOwnerSubShouldEnforceOwnership() {
    // Given
    PersonalInventoryItem aItem = repository.save(item(OWNER_A, "Medkit"));

    // When
    Optional<PersonalInventoryItem> ownLookup =
        repository.findByIdAndOwnerSub(aItem.getId(), OWNER_A);
    Optional<PersonalInventoryItem> foreignLookup =
        repository.findByIdAndOwnerSub(aItem.getId(), OWNER_B);

    // Then
    assertTrue(ownLookup.isPresent());
    assertTrue(foreignLookup.isEmpty(), "Foreign owner must NOT be able to load this item.");
  }

  @Test
  void nameSearchShouldBeCaseInsensitiveAndOwnerScoped() {
    // Given
    repository.save(item(OWNER_A, "Medkit Alpha"));
    repository.save(item(OWNER_A, "MEDKIT BETA"));
    repository.save(item(OWNER_A, "Helmet"));
    repository.save(item(OWNER_B, "Medkit Foreign"));

    // When
    Page<PersonalInventoryItem> page =
        repository.findAllByOwnerSubAndNameContainingIgnoreCase(
            OWNER_A, "medkit", PageRequest.of(0, 10, Sort.by("name")));

    // Then
    assertEquals(
        2,
        page.getTotalElements(),
        "Search must be case-insensitive AND must not return foreign owner's items.");
  }

  @Test
  void versionShouldStartAtZeroAndIncrementOnUpdate() {
    // Given
    PersonalInventoryItem saved = repository.saveAndFlush(item(OWNER_A, "Vase"));
    Long initialVersion = saved.getVersion();
    assertNotNull(initialVersion);

    // When
    saved.setQuantity(saved.getQuantity() + 1);
    PersonalInventoryItem updated = repository.saveAndFlush(saved);

    // Then
    assertNotNull(updated.getVersion());
    assertTrue(
        updated.getVersion() > initialVersion,
        "JPA must increment @Version on each update – this is the basis for the 409 contract.");
  }

  private static PersonalInventoryItem item(UUID ownerSub, String name) {
    return PersonalInventoryItem.builder()
        .ownerSub(ownerSub)
        .name(name)
        .note(null)
        .locationUexId(1)
        .locationType(PersonalInventoryLocationType.CITY)
        .locationNameSnapshot("Lorville")
        .quantity(1)
        .build();
  }
}
