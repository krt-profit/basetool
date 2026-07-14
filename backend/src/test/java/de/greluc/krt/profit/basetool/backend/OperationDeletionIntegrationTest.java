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

package de.greluc.krt.profit.basetool.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.*;
import de.greluc.krt.profit.basetool.backend.repository.*;
import de.greluc.krt.profit.basetool.backend.service.OperationService;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OperationDeletionIntegrationTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private OperationRepository operationRepository;

  @Autowired private OperationService operationService;

  @Autowired private MissionRepository missionRepository;

  @Autowired private InventoryItemRepository inventoryItemRepository;

  @Autowired private RefineryOrderRepository refineryOrderRepository;

  @Autowired private MissionFinanceEntryRepository missionFinanceEntryRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private MaterialRepository materialRepository;

  @Autowired private LocationRepository locationRepository;

  @Autowired private MissionParticipantRepository missionParticipantRepository;

  private User adminUser;
  private Operation operation;
  private Mission mission;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    adminUser = new User();
    adminUser.setId(UUID.randomUUID());
    adminUser.setUsername("admin");
    userRepository.save(adminUser);

    operation = new Operation();
    operation.setName("Deletion Test Op");
    operation.setStatus(OperationStatus.ACTIVE);
    operation = operationRepository.save(operation);

    mission = new Mission();
    mission.setName("Linked Mission");
    mission.setOperation(operation);
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    operation.getMissions().add(mission);
    operationRepository.save(operation);
  }

  @Test
  void testDeleteSimpleOperation() throws Exception {
    Operation op = new Operation();
    op.setName("Simple Op");
    op.setStatus(OperationStatus.PLANNED);
    op = operationRepository.save(op);

    mockMvc
        .perform(
            delete("/api/v1/operations/" + op.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(adminUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isNoContent());

    assertTrue(operationRepository.findById(op.getId()).isEmpty());
  }

  @Test
  void testDeleteOperationKeepsMissionAndAllItsReferences() throws Exception {
    // Given: InventoryItem linked to Mission
    Material material = new Material();
    material.setName("Test Ore");
    material.setType(MaterialType.RAW);
    material = materialRepository.save(material);

    Location location = new Location();
    location.setName("Test Moon");
    location = locationRepository.save(location);

    InventoryItem item = new InventoryItem();
    item.setUser(adminUser);
    item.setMaterial(material);
    item.setLocation(location);
    item.setQuality(100);
    item.setAmount(10.0);
    // Variante C: the mission earmark is a cascade-persisted allocation slice, not a scalar column.
    InventoryAllocations.addMission(item, mission, item.getAmount());
    item = inventoryItemRepository.save(item);

    // Given: RefineryOrder linked to Mission
    RefineryOrder order = new RefineryOrder();
    order.setOwner(adminUser);
    order.setLocation(location);
    order.setMission(mission);
    order.setDurationMinutes(60L);
    order.setExpenses(100.0);
    order = refineryOrderRepository.save(order);

    // Given: MissionParticipant + MissionFinanceEntry linked to Mission
    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);
    participant.setUser(adminUser);
    participant = missionParticipantRepository.save(participant);

    MissionFinanceEntry financeEntry = new MissionFinanceEntry();
    financeEntry.setMission(mission);
    financeEntry.setParticipant(participant);
    financeEntry.setType(FinanceType.EXPENSE);
    financeEntry.setAmount(new BigDecimal("50.00"));
    financeEntry = missionFinanceEntryRepository.save(financeEntry);

    UUID missionId = mission.getId();
    UUID itemId = item.getId();
    UUID orderId = order.getId();
    UUID financeEntryId = financeEntry.getId();
    UUID participantId = participant.getId();

    // When: Deleting the operation as admin
    mockMvc
        .perform(
            delete("/api/v1/operations/" + operation.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(adminUser.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isNoContent());

    // Then: Operation is gone
    assertTrue(operationRepository.findById(operation.getId()).isEmpty());

    // Then: Mission survives, only its back-reference to the operation is cleared
    Mission survivedMission = missionRepository.findById(missionId).orElseThrow();
    assertNull(
        survivedMission.getOperation(), "mission must no longer reference the deleted operation");

    // Then: InventoryItem is still linked to the (now operation-less) mission via its
    // mission allocation slice (Variante C)
    InventoryItem survivedItem = inventoryItemRepository.findById(itemId).orElseThrow();
    InventoryMissionAllocation survivedAllocation =
        survivedItem.getMissionAllocations().stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "inventory item must keep its mission link when the operation is deleted"));
    assertEquals(missionId, survivedAllocation.getMission().getId());

    // Then: RefineryOrder is still linked to the mission
    RefineryOrder survivedOrder = refineryOrderRepository.findById(orderId).orElseThrow();
    assertNotNull(
        survivedOrder.getMission(),
        "refinery order must keep its mission link when the operation is deleted");
    assertEquals(missionId, survivedOrder.getMission().getId());

    // Then: MissionFinanceEntry survives and stays linked to the mission
    MissionFinanceEntry survivedFinance =
        missionFinanceEntryRepository.findById(financeEntryId).orElseThrow();
    assertEquals(missionId, survivedFinance.getMission().getId());

    // Then: MissionParticipant survives
    assertTrue(
        missionParticipantRepository.findById(participantId).isPresent(),
        "mission participant must survive the operation delete");
  }
}
