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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.service.RoleService;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies that no paged finder fetch-joins a collection and that Hibernate's {@code
 * fail_on_pagination_over_collection_fetch} gate is active (REQ-DATA-003).
 */
@SpringBootTest
@ActiveProfiles("test")
class PagedFindersNoCollectionFetchTest {

  private static final PageRequest PAGE = PageRequest.of(0, 20);

  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private RoleService roleService;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  void gateIsLive_aPagedCollectionFetchThatMustPageInMemoryFails() {
    assertThrows(
        RuntimeException.class,
        () ->
            transactionTemplate.executeWithoutResult(
                status ->
                    entityManager
                        .createQuery(
                            "SELECT u FROM User u LEFT JOIN FETCH u.roles r ORDER BY r.name",
                            Object.class)
                        .setMaxResults(5)
                        .getResultList()));
  }

  @Test
  void formerlyGraphedPagedFinders_leaveTheirCollectionsUnfetched() {
    transactionTemplate.executeWithoutResult(
        status -> {
          status.setRollbackOnly();
          String tag = UUID.randomUUID().toString().substring(0, 8);
          User user = seedUser("paged-" + tag);
          Squadron staffel = seedStaffel(tag);
          seedMission(user);
          seedJobOrder(staffel, tag);
          entityManager.flush();
          entityManager.clear();

          assertUnfetched(userRepository.findAll(PAGE).getContent(), User::getRoles);
          assertUnfetched(userRepository.findAllScoped(null, PAGE).getContent(), User::getRoles);
          assertUnfetched(
              userRepository.searchScoped("paged-", null, PAGE).getContent(), User::getRoles);
          assertUnfetched(
              userRepository
                  .findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
                      "paged-", "paged-", PAGE)
                  .getContent(),
              User::getRoles);
          userRepository.findEvaluatableMembers(null, PAGE);
          assertUnfetched(
              missionRepository
                  .searchMissions(
                      null,
                      null,
                      null,
                      List.of("PLANNED", "ACTIVE", "COMPLETED", "CANCELLED"),
                      null,
                      null,
                      true,
                      null,
                      Set.of(),
                      true,
                      PAGE)
                  .getContent(),
              Mission::getParticipants);
          List<JobOrderStatus> statuses = List.of(JobOrderStatus.values());
          List<JobOrder> scoped =
              jobOrderRepository
                  .findScopedJobOrders(
                      statuses, true, Set.of(new UUID(0L, 0L)), true, null, Set.of(), PAGE)
                  .getContent();
          assertUnfetched(scoped, JobOrder::getMaterials);
          assertUnfetched(scoped, JobOrder::getAssignees);
          assertUnfetched(scoped, JobOrder::getHandovers);
          assertUnfetched(
              jobOrderRepository
                  .findRequestedOrders(statuses, Set.of(staffel.getId()), PAGE)
                  .getContent(),
              JobOrder::getMaterials);
        });
  }

  @Test
  void rolePage_arrivesWithPermissionsInitialisedOutsideItsTransaction() {
    Page<Role> roles = roleService.getAllRoles(PageRequest.of(0, 50));

    assertFalse(roles.isEmpty(), "DataInitializer seeds the roles");
    for (Role role : roles) {
      assertTrue(
          Hibernate.isInitialized(role.getPermissions()),
          () -> role.getCode() + " permissions must be initialised before the page is cached");
    }
  }

  /** Every row's collection must still be a lazy proxy: no graph fetched it with the page. */
  private static <T> void assertUnfetched(
      List<T> rows, Function<T, ? extends Collection<?>> collection) {
    assertFalse(rows.isEmpty(), "the seeded row must be on the page");
    for (T row : rows) {
      assertFalse(
          Hibernate.isInitialized(collection.apply(row)),
          "a paged finder fetched a collection with the page (REQ-DATA-003)");
    }
  }

  private User seedUser(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    Role role = roleRepository.findAllWithPermissions().getFirst();
    user.getRoles().add(role);
    return userRepository.save(user);
  }

  private Squadron seedStaffel(String tag) {
    Squadron staffel = new Squadron();
    staffel.setName("Paged-" + tag);
    staffel.setShorthand("PG" + tag);
    return squadronRepository.save(staffel);
  }

  private void seedMission(User participantUser) {
    Mission mission = new Mission();
    mission.setOwningOrgUnit(squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow());
    mission.setName("Paged mission");
    mission.setStatus("PLANNED");
    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setUser(participantUser);
    mission.getParticipants().add(participant);
    missionRepository.save(mission);
  }

  private void seedJobOrder(Squadron staffel, String tag) {
    Material material = new Material();
    material.setName("PagedMat-" + tag);
    material.setType(MaterialType.RAW);
    material = materialRepository.save(material);
    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(staffel)
            .requestingOrgUnit(staffel)
            .handle("paged-" + tag)
            .status(JobOrderStatus.OPEN)
            .type(JobOrderType.MATERIAL)
            .build();
    order.addMaterial(JobOrderMaterial.builder().material(material).amount(1.0).build());
    jobOrderRepository.save(order);
  }
}
