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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.DefaultBlueprint;
import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.projection.BlueprintOwnerProduct;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for the multi-owner finders {@link PersonalBlueprintRepository} grew for the
 * blueprint availability overview (#364): {@link
 * PersonalBlueprintRepository#findAllByOwnerUserIdIn}, its two-column {@link
 * PersonalBlueprintRepository#findOwnerProductByOwnerUserIdIn} projection, and {@link
 * PersonalBlueprintRepository#findAllByProductKeyAndOwnerUserIdIn}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PersonalBlueprintRepositoryTest {

  private static final UUID OWNER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OWNER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OWNER_C = UUID.fromString("33333333-3333-3333-3333-333333333333");

  private static final String DEFAULT_KEY = "test-default-blueprint";

  @Autowired private PersonalBlueprintRepository repository;
  @Autowired private DefaultBlueprintRepository defaultBlueprintRepository;
  @Autowired private UserRepository userRepository;

  @BeforeEach
  void clean() {
    repository.deleteAll();
    // owner_user_id is a foreign key to app_user(id) since V235 (REQ-DATA-008), so the three owners
    // have to exist before any blueprint can reference them.
    seedOwner(OWNER_A);
    seedOwner(OWNER_B);
    seedOwner(OWNER_C);
  }

  /**
   * Creates the {@code app_user} row a blueprint owner id has to point at, unless it already
   * exists.
   *
   * @param id the owner id used as {@code owner_user_id}
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
  void findAllByOwnerUserIdIn_returnsRowsForGivenOwnersOnly() {
    repository.save(bp(OWNER_A, "aurora", "Aurora MR"));
    repository.save(bp(OWNER_B, "aurora", "Aurora MR"));
    repository.save(bp(OWNER_C, "cutlass", "Cutlass Black"));

    List<PersonalBlueprint> rows = repository.findAllByOwnerUserIdIn(Set.of(OWNER_A, OWNER_B));

    assertEquals(2, rows.size());
    assertTrue(rows.stream().allMatch(r -> Set.of(OWNER_A, OWNER_B).contains(r.getOwnerUserId())));
  }

  @Test
  void findOwnerProductByOwnerUserIdIn_projectsOwnerAndNameForGivenOwnersOnly() {
    repository.save(bp(OWNER_A, "aurora", "Aurora MR"));
    repository.save(bp(OWNER_B, "cutlass", "Cutlass Black"));
    repository.save(bp(OWNER_C, "gladius", "Gladius")); // owner out of scope

    List<BlueprintOwnerProduct> rows =
        repository.findOwnerProductByOwnerUserIdIn(Set.of(OWNER_A, OWNER_B));

    // Assert the exact (ownerUserId, productName) pairs for the in-scope owners only. Comparing the
    // whole projection (not just the owner subs) pins the constructor-argument order, so a swapped
    // projection — product name landing in ownerUserId — fails here instead of silently
    // mis-grouping
    // the family aggregation downstream; the absence of OWNER_C also proves the owner restriction.
    assertEquals(
        Set.of(
            new BlueprintOwnerProduct(OWNER_A, "Aurora MR"),
            new BlueprintOwnerProduct(OWNER_B, "Cutlass Black")),
        Set.copyOf(rows));
  }

  @Test
  void findAllByProductKeyAndOwnerUserIdIn_restrictsToProductAndOwners() {
    repository.save(bp(OWNER_A, "aurora", "Aurora MR"));
    repository.save(bp(OWNER_B, "aurora", "Aurora MR"));
    repository.save(bp(OWNER_C, "aurora", "Aurora MR")); // owner out of scope
    repository.save(bp(OWNER_A, "cutlass", "Cutlass Black")); // other product

    List<PersonalBlueprint> rows =
        repository.findAllByProductKeyAndOwnerUserIdIn("aurora", Set.of(OWNER_A, OWNER_B));

    Set<UUID> owners =
        rows.stream().map(PersonalBlueprint::getOwnerUserId).collect(Collectors.toSet());
    assertEquals(Set.of(OWNER_A, OWNER_B), owners);
  }

  @Test
  void deleteRemovableByOwnerUserId_removesOwnersRemovableRowsButKeepsDefaultsAndOtherOwners() {
    defaultBlueprintRepository.save(defaultBp(DEFAULT_KEY, "Test Default"));
    repository.save(bp(OWNER_A, "test-removable-1", "Removable One"));
    repository.save(bp(OWNER_A, "test-removable-2", "Removable Two"));
    repository.save(bp(OWNER_A, DEFAULT_KEY, "Test Default")); // granted default — must survive
    repository.save(bp(OWNER_B, "test-removable-1", "Removable One")); // other owner — untouched

    int removed = repository.deleteRemovableByOwnerUserId(OWNER_A);

    assertEquals(2, removed);
    List<PersonalBlueprint> ownerA = repository.findAllByOwnerUserIdIn(Set.of(OWNER_A));
    assertEquals(1, ownerA.size());
    assertEquals(DEFAULT_KEY, ownerA.get(0).getProductKey());
    assertEquals(1, repository.findAllByOwnerUserIdIn(Set.of(OWNER_B)).size());
  }

  @Test
  void deleteRemovableByOwnerUserId_removesAllWhenOwnerHoldsNoDefaults() {
    repository.save(bp(OWNER_A, "test-removable-1", "Removable One"));
    repository.save(bp(OWNER_A, "test-removable-2", "Removable Two"));

    int removed = repository.deleteRemovableByOwnerUserId(OWNER_A);

    assertEquals(2, removed);
    assertTrue(repository.findAllByOwnerUserIdIn(Set.of(OWNER_A)).isEmpty());
  }

  @Test
  void deleteAllRemovable_removesEveryOwnersRemovableRowsButKeepsDefaults() {
    defaultBlueprintRepository.save(defaultBp(DEFAULT_KEY, "Test Default"));
    repository.save(bp(OWNER_A, "test-removable-1", "Removable One"));
    repository.save(bp(OWNER_A, DEFAULT_KEY, "Test Default"));
    repository.save(bp(OWNER_B, "test-removable-2", "Removable Two"));
    repository.save(bp(OWNER_B, DEFAULT_KEY, "Test Default"));

    int removed = repository.deleteAllRemovable();

    assertEquals(2, removed);
    List<PersonalBlueprint> remaining =
        repository.findAllByOwnerUserIdIn(Set.of(OWNER_A, OWNER_B, OWNER_C));
    assertEquals(2, remaining.size());
    assertTrue(remaining.stream().allMatch(r -> DEFAULT_KEY.equals(r.getProductKey())));
  }

  private static PersonalBlueprint bp(UUID ownerUserId, String productKey, String productName) {
    return PersonalBlueprint.builder()
        .ownerUserId(ownerUserId)
        .productKey(productKey)
        .productName(productName)
        .build();
  }

  private static DefaultBlueprint defaultBp(String productKey, String productName) {
    return DefaultBlueprint.builder().productKey(productKey).productName(productName).build();
  }
}
