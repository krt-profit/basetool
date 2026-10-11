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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Verifies against real Postgres that the Lager's list and stack reads issue the same number of SQL
 * statements whether a page holds a few or many rows, each earmarked to its own mission and job
 * order (REQ-DATA-003, plan §7.5 P3-5d): the earmark targets' names and numbers come in per page,
 * never per row.
 */
@SpringBootTest
class InventoryEarmarkReadNoNPlusOneTest {

  private static final int FEW = 3;

  private static final int MANY = 25;

  /** Ceiling per read; a per-row lookup would exceed it at {@value #MANY} rows. */
  private static final int STATEMENT_BOUND = 15;

  @Autowired private InventoryItemService inventoryItemService;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private EntityManagerFactory entityManagerFactory;

  private Squadron unit;

  private final List<Object> created = new ArrayList<>();

  /**
   * One seeded Lager.
   *
   * @param owner the rows' owner
   * @param material the rows' material
   * @param location the rows' location
   * @param shared the mission every row is also earmarked to
   */
  private record Seed(User owner, Material material, Location location, Mission shared) {}

  @BeforeEach
  void signInAsAdmin() {
    String tag = UUID.randomUUID().toString().substring(0, 6);
    Squadron squadron = new Squadron();
    squadron.setName("Earmark Unit " + tag);
    squadron.setShorthand("E" + tag);
    squadron.setProfitEligible(true);
    unit = squadronRepository.save(squadron);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("earmark-reads")
                    .header("alg", "none")
                    .subject(UUID.randomUUID().toString())
                    .build(),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
  }

  @AfterEach
  void signOutAndCleanUp() {
    SecurityContextHolder.clearContext();
    for (Object entity : created.reversed()) {
      switch (entity) {
        case InventoryItem item -> inventoryItemRepository.deleteById(item.getId());
        case JobOrder order -> jobOrderRepository.deleteById(order.getId());
        case Mission mission -> missionRepository.deleteById(mission.getId());
        case Material material -> materialRepository.deleteById(material.getId());
        case Location location -> locationRepository.deleteById(location.getId());
        case User user -> userRepository.deleteById(user.getId());
        default -> throw new IllegalStateException("unexpected " + entity);
      }
    }
    created.clear();
    squadronRepository.deleteById(unit.getId());
  }

  @Test
  void theLagerReadsStayStatementBoundedWhateverTheEarmarks() {
    Seed few = seed(FEW);
    Seed many = seed(MANY);
    PageRequest page = PageRequest.of(0, 50);

    Map<String, Function<Seed, List<InventoryItemDto>>> reads = new LinkedHashMap<>();
    reads.put(
        "inventory by material",
        s -> inventoryItemService.getInventoryByMaterial(s.material().getId(), page).getContent());
    reads.put(
        "user inventory",
        s -> inventoryItemService.getUserInventory(s.owner().getId(), page).getContent());
    reads.put(
        "all inventory",
        s ->
            inventoryItemService
                .getAllInventory(
                    List.of(s.material().getId()),
                    List.of(s.location().getId()),
                    null,
                    null,
                    null,
                    page)
                .getContent());
    reads.put(
        "my stack entries",
        s ->
            inventoryItemService
                .getMyStackEntries(
                    s.owner().getId(),
                    s.material().getId(),
                    s.location().getId(),
                    500,
                    false,
                    false,
                    null,
                    page)
                .getContent());
    reads.put(
        "all stack entries",
        s ->
            inventoryItemService
                .getAllStackEntries(
                    s.material().getId(),
                    s.owner().getId(),
                    s.location().getId(),
                    500,
                    false,
                    null,
                    page)
                .getContent());
    reads.put(
        "mission inventory", s -> inventoryItemService.getMissionInventory(s.shared().getId()));

    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    for (Map.Entry<String, Function<Seed, List<InventoryItemDto>>> read : reads.entrySet()) {
      stats.clear();
      List<InventoryItemDto> fewRows = read.getValue().apply(few);
      long fewStatements = stats.getPrepareStatementCount();
      stats.clear();
      List<InventoryItemDto> manyRows = read.getValue().apply(many);
      long manyStatements = stats.getPrepareStatementCount();

      assertThat(fewRows).as(read.getKey() + " rows for the small Lager").hasSize(FEW);
      assertThat(manyRows).as(read.getKey() + " rows for the large Lager").hasSize(MANY);
      assertThat(manyRows)
          .as(read.getKey() + " carries every earmark's name and number")
          .allSatisfy(
              dto -> {
                assertThat(dto.missionAllocations()).hasSize(2);
                assertThat(dto.missionAllocations())
                    .allSatisfy(m -> assertThat(m.missionName()).isNotBlank());
                assertThat(dto.jobOrderAllocations()).hasSize(1);
                assertThat(dto.jobOrderAllocations().getFirst().jobOrderDisplayId()).isNotNull();
              });
      assertThat(manyStatements)
          .as(read.getKey() + " statements for " + MANY + " rows against " + FEW + " (N+1)")
          .isEqualTo(fewStatements)
          .isLessThanOrEqualTo(STATEMENT_BOUND);
    }
  }

  private Seed seed(int rows) {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    User owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("earmark-" + tag);
    owner = userRepository.save(owner);
    created.add(owner);
    Material material = new Material();
    material.setName("Earmark Ore " + tag);
    material.setType(MaterialType.REFINED);
    material.setQuantityType(QuantityType.SCU);
    material = materialRepository.save(material);
    created.add(material);
    Location location = new Location();
    location.setName("Earmark Depot " + tag);
    location = locationRepository.save(location);
    created.add(location);
    Mission shared = mission("Shared " + tag);
    for (int i = 0; i < rows; i++) {
      InventoryItem item = new InventoryItem();
      item.setUser(owner);
      item.setMaterial(material);
      item.setLocation(location);
      item.setQuality(500);
      item.setAmount(10.0);
      item.setPersonal(false);
      item.setStolen(false);
      InventoryAllocations.addMission(item, shared.getId(), 1.0);
      InventoryAllocations.addMission(item, mission("Own " + tag + " " + i).getId(), 2.0);
      InventoryAllocations.addJobOrder(
          item, jobOrder("earmark-" + tag + "-" + i).getId(), 3.0, false);
      created.add(inventoryItemRepository.save(item));
    }
    return new Seed(owner, material, location, shared);
  }

  private Mission mission(String name) {
    Mission mission = new Mission();
    mission.setName(name);
    Mission saved = missionRepository.save(mission);
    created.add(saved);
    return saved;
  }

  private JobOrder jobOrder(String handle) {
    JobOrder saved =
        jobOrderRepository.save(
            JobOrder.builder()
                .responsibleOrgUnit(unit)
                .requestingOrgUnit(unit)
                .handle(handle)
                .status(JobOrderStatus.OPEN)
                .build());
    created.add(saved);
    return saved;
  }
}
