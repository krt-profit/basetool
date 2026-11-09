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

package de.greluc.krt.profit.basetool.backend.inventory.web;

import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.persistedProfitArea;
import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.withinProfit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.AllocationReductionDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.CheckoutType;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAllocationDimension;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAllocationInput;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAllocationWriteDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgHierarchyService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Lager earmark names only a job order the caller sees within the tenancy scope; one of another
 * org unit is refused like an unknown id (REQ-ORDERS-018, REQ-ORG-003). A member of two Staffeln
 * earmarks the stock of one to an order of the other, which is how a cross-Staffel supply reaches
 * an order (UC-08, UC-09).
 */
@SpringBootTest
@Transactional
class InventoryJobOrderScopeIntegrationTest {

  @Autowired private OrgUnitRepository orgUnitRepository;

  private static final String PATH = "/api/v1/inventory";
  private static final int QUALITY = 500;

  @Autowired private WebApplicationContext context;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private CityRepository cityRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private QualityTierRepository qualityTierRepository;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private OrgHierarchyService hierarchy;
  @Autowired private EntityManager entityManager;

  private final JsonMapper json = JsonMapper.builder().build();

  private MockMvc mockMvc;
  private Squadron iridium;
  private Squadron foreign;
  private User member;
  private Material material;
  private Location location;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    withinProfit(iridium, persistedProfitArea(orgUnitRepository));
    iridium = squadronRepository.save(iridium);
    foreign = new Squadron();
    foreign.setName("Lager-Foreign");
    foreign.setShorthand("LFX");
    withinProfit(foreign, persistedProfitArea(orgUnitRepository));
    foreign = squadronRepository.save(foreign);

    member = new User();
    member.setId(UUID.randomUUID());
    member.setUsername("lager-scope-member");
    member = userRepository.save(member);
    joinSquadron(iridium);

    material = new Material();
    material.setName("Lager-Scope-Ore");
    material.setType(MaterialType.RAW);
    material = materialRepository.save(material);
    Location place = new Location();
    place.setName("Lager-Scope-Place");
    location = locationRepository.save(TestPlaces.onOwnCity(place, cityRepository));
  }

  @Test
  void mergePreviewAnswersBeforeAnyStockBooking() throws Exception {
    mockMvc
        .perform(
            get(PATH + "/merge-candidates")
                .with(asMember())
                .param("materialId", material.getId().toString())
                .param("locationId", location.getId().toString())
                .param("quality", Integer.toString(QUALITY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exists").value(false));
  }

  @Test
  void createRefusesAJobOrderOfAnotherSquadronLikeAnUnknownOne() throws Exception {
    JobOrder foreignOrder = openJobOrderNeeding(foreign);

    create(foreignOrder.getId()).andExpect(status().isNotFound());
    create(UUID.randomUUID()).andExpect(status().isNotFound());

    assertThat(inventoryItemRepository.findMaterialRowsByUser(member, Pageable.unpaged()))
        .as("nothing is booked for a refused earmark")
        .isEmpty();
  }

  @Test
  void createEarmarksAJobOrderOfTheCallersOwnSquadron() throws Exception {
    JobOrder ownOrder = openJobOrderNeeding(iridium);

    create(ownOrder.getId())
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.jobOrderAllocations[0].jobOrderId").value(ownOrder.getId().toString()));
  }

  @Test
  void addAllocationRefusesAJobOrderOfAnotherSquadronLikeAnUnknownOne() throws Exception {
    JobOrder foreignOrder = openJobOrderNeeding(foreign);
    JsonNode row = unearmarkedRow();
    UUID id = UUID.fromString(row.get("id").asString());
    long version = row.get("version").asLong();

    addAllocation(id, foreignOrder.getId(), version).andExpect(status().isNotFound());
    addAllocation(id, UUID.randomUUID(), version).andExpect(status().isNotFound());

    assertThat(inventoryItemRepository.findById(id).orElseThrow().getJobOrderAllocations())
        .isEmpty();
  }

  @Test
  void addAllocationEarmarksAJobOrderOfTheCallersOwnSquadron() throws Exception {
    JobOrder ownOrder = openJobOrderNeeding(iridium);
    JsonNode row = unearmarkedRow();
    UUID id = UUID.fromString(row.get("id").asString());

    addAllocation(id, ownOrder.getId(), row.get("version").asLong()).andExpect(status().isOk());
  }

  @Test
  void createEarmarksStockOfTheCallersOtherSquadronToAnOrderOfASquadronTheyAlsoBelongTo()
      throws Exception {
    joinSquadron(foreign);
    JobOrder iridiumOrder = openJobOrderNeeding(iridium);

    JsonNode row =
        json.readTree(
            create(iridiumOrder.getId(), foreign.getId())
                .andExpect(status().isOk())
                .andExpect(
                    jsonPath("$.jobOrderAllocations[0].jobOrderId")
                        .value(iridiumOrder.getId().toString()))
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(
            inventoryItemRepository
                .findById(UUID.fromString(row.get("id").asString()))
                .orElseThrow()
                .getOwningOrgUnit()
                .getId())
        .as("the stock stays in the other Staffel's pool while it serves the order")
        .isEqualTo(foreign.getId());
  }

  @Test
  void addAllocationEarmarksStockOfTheCallersOtherSquadronToAnOrderOfASquadronTheyAlsoBelongTo()
      throws Exception {
    joinSquadron(foreign);
    JobOrder iridiumOrder = openJobOrderNeeding(iridium);
    JsonNode row = unearmarkedRow(foreign);
    UUID id = UUID.fromString(row.get("id").asString());

    addAllocation(id, iridiumOrder.getId(), row.get("version").asLong()).andExpect(status().isOk());
  }

  @ParameterizedTest
  @EnumSource(LinkedStockMutation.class)
  void validResponsibilityAllowsTheStockOwnerAndCurrentWriterToMutateLinkedStock(
      LinkedStockMutation mutation) throws Exception {
    JobOrder order = openJobOrderNeeding(iridium);
    JsonNode row = earmarkedRow(order);

    mutateLinkedStock(mutation, order.getId(), row).andExpect(status().is2xxSuccessful());

    if (mutation == LinkedStockMutation.CHECKOUT) {
      assertThat(ownRows()).noneMatch(entry -> entry.get("id").equals(row.get("id")));
    } else {
      JsonNode changed = ownRow(UUID.fromString(row.get("id").asString()));
      assertThat(changed.get("amount").asDouble())
          .isEqualTo(mutation == LinkedStockMutation.BOOK_OUT ? 4.0 : 5.0);
      if (mutation == LinkedStockMutation.CHANGE || mutation == LinkedStockMutation.BOOK_OUT) {
        assertThat(changed.get("jobOrderAllocations").get(0).get("amount").asDouble())
            .isEqualTo(1.0);
      } else {
        assertThat(changed.get("jobOrderAllocations").isEmpty()).isTrue();
      }
    }
  }

  @ParameterizedTest
  @EnumSource(LinkedStockMutation.class)
  void invalidResponsibilityRefusesLinkedStockMutationWithoutChangingStockOrAllocations(
      LinkedStockMutation mutation) throws Exception {
    JobOrder order = openJobOrderNeeding(iridium);
    JsonNode row = earmarkedRow(order);
    UUID rowId = UUID.fromString(row.get("id").asString());
    hierarchy.setParent(iridium.getId(), null, iridium.getVersion());
    JsonNode before = ownRow(rowId);
    assertThat(before.get("canEdit").asBoolean()).isTrue();
    assertThat(before.get("amount").asDouble()).isEqualTo(5.0);
    assertThat(before.get("jobOrderAllocations").get(0).get("amount").asDouble()).isEqualTo(2.0);

    mutateLinkedStock(mutation, order.getId(), before).andExpect(status().isBadRequest());

    assertThat(ownRow(rowId)).isEqualTo(before);
  }

  private enum LinkedStockMutation {
    RELEASE_ROW,
    RELEASE_MATERIAL,
    CHANGE,
    REMOVE,
    BOOK_OUT,
    CHECKOUT
  }

  private @NotNull ResultActions mutateLinkedStock(
      LinkedStockMutation mutation, UUID orderId, JsonNode row) throws Exception {
    UUID rowId = UUID.fromString(row.get("id").asString());
    long version = row.get("version").asLong();
    InventoryAllocationWriteDto allocation =
        new InventoryAllocationWriteDto(
            InventoryAllocationDimension.JOB_ORDER, orderId, 1.0, version, null);
    MockHttpServletRequestBuilder request =
        switch (mutation) {
          case RELEASE_ROW -> delete("/api/v1/orders/" + orderId + "/allocations/" + rowId);
          case RELEASE_MATERIAL ->
              delete("/api/v1/orders/" + orderId + "/materials/" + material.getId());
          case CHANGE ->
              patch(PATH + "/" + rowId + "/allocation")
                  .content(json.writeValueAsString(allocation));
          case REMOVE ->
              delete(PATH + "/" + rowId + "/allocation")
                  .content(json.writeValueAsString(allocation));
          case BOOK_OUT ->
              post(PATH + "/" + rowId + "/book-out")
                  .content(
                      json.writeValueAsString(
                          new InventoryItemBookOutDto(
                              1.0,
                              null,
                              null,
                              CheckoutType.DISCARD,
                              null,
                              null,
                              version,
                              null,
                              null,
                              List.of(new AllocationReductionDto(orderId, 1.0)),
                              null)));
          case CHECKOUT ->
              post(PATH + "/bulk-checkout")
                  .content(json.writeValueAsString(new BulkCheckoutRequest(List.of(rowId))));
        };
    return mockMvc.perform(request.with(asCurrentWriter()).contentType(MediaType.APPLICATION_JSON));
  }

  private @NotNull JsonNode earmarkedRow(JobOrder order) throws Exception {
    JsonNode row =
        json.readTree(
            create(order.getId())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    entityManager.flush();
    entityManager.clear();
    return row;
  }

  private @NotNull JsonNode ownRow(UUID id) throws Exception {
    return ownRows().stream()
        .filter(entry -> entry.get("id").asString().equals(id.toString()))
        .findFirst()
        .orElseThrow();
  }

  private @NotNull List<JsonNode> ownRows() throws Exception {
    JsonNode content =
        json.readTree(
                mockMvc
                    .perform(get(PATH + "/my-inventory").with(asMember()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("content");
    return content.valueStream().toList();
  }

  private @NotNull RequestPostProcessor asCurrentWriter() {
    return jwt()
        .jwt(builder -> builder.subject(member.getId().toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, iridium.getId()));
  }

  private void joinSquadron(@NotNull Squadron squadron) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(member.getId(), squadron.getId()));
    membership.setUser(member);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
  }

  private @NotNull JsonNode unearmarkedRow() throws Exception {
    return unearmarkedRow(iridium);
  }

  private @NotNull JsonNode unearmarkedRow(@NotNull OrgUnit owner) throws Exception {
    return json.readTree(
        create(null, owner.getId())
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private @NotNull JobOrder openJobOrderNeeding(@NotNull OrgUnit responsible) {
    JobOrder jobOrder =
        JobOrder.builder()
            .responsibleOrgUnit(responsible)
            .requestingOrgUnit(responsible)
            .handle("lager-scope-test")
            .status(JobOrderStatus.OPEN)
            .build();
    jobOrder.addMaterial(
        JobOrderMaterial.builder()
            .material(material)
            .qualityTier(qualityTierRepository.findByCode("NONE").orElseThrow())
            .amount(10.0)
            .build());
    return jobOrderRepository.save(jobOrder);
  }

  private @NotNull ResultActions create(@Nullable UUID jobOrderId) throws Exception {
    return create(jobOrderId, iridium.getId());
  }

  private @NotNull ResultActions create(@Nullable UUID jobOrderId, @NotNull UUID owningOrgUnitId)
      throws Exception {
    List<InventoryAllocationInput> allocations =
        jobOrderId == null ? null : List.of(new InventoryAllocationInput(jobOrderId, 2.0, null));
    InventoryItemCreateDto dto =
        new InventoryItemCreateDto(
            null,
            material.getId(),
            null,
            location.getId(),
            QUALITY,
            5.0,
            false,
            false,
            null,
            null,
            owningOrgUnitId,
            false,
            allocations,
            null);
    return mockMvc.perform(
        post(PATH)
            .with(asMember())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(dto)));
  }

  private @NotNull ResultActions addAllocation(
      @NotNull UUID id, @NotNull UUID jobOrderId, long version) throws Exception {
    InventoryAllocationWriteDto dto =
        new InventoryAllocationWriteDto(
            InventoryAllocationDimension.JOB_ORDER, jobOrderId, 2.0, version, null);
    return mockMvc.perform(
        post(PATH + "/" + id + "/allocation")
            .with(asMember())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(dto)));
  }

  private @NotNull RequestPostProcessor asMember() {
    return jwt()
        .jwt(builder -> builder.subject(member.getId().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }
}
