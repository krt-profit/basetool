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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.joborder.api.CreateJobOrderItemLineDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Verifies against PostgreSQL that the MATERIAL and ITEM edits carry the comment version beside the
 * order version, and that each part raises only its own (REQ-ORDERS-066).
 */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class JobOrderCommentVersionIntegrationTest extends MaterialContributionTestSupport {

  private static final int GOOD_FLOOR = 650;

  @Autowired private GameItemRepository gameItems;

  @Autowired private BlueprintRepository blueprints;

  /** A comment change raises the comment version and leaves the order version. */
  @Test
  void aCommentEditRaisesOnlyTheCommentVersion() throws Exception {
    Fixture fixture = seed();
    JobOrderDto before = jobOrderQueryService.getJobOrderById(fixture.orderId());

    JobOrderDto edited =
        read(
            materialEdit(fixture, "Deliver to Area18", 10.0, before.commentVersion())
                .andExpect(status().isOk()));

    assertThat(edited.comment()).isEqualTo("Deliver to Area18");
    assertThat(edited.commentVersion()).isEqualTo(before.commentVersion() + 1);
    assertThat(edited.version()).isEqualTo(before.version());
  }

  /** A change of the requested amount alone leaves the comment version. */
  @Test
  void aMaterialEditKeepsTheCommentVersion() throws Exception {
    Fixture fixture = seed();
    JobOrderDto before = jobOrderQueryService.getJobOrderById(fixture.orderId());

    JobOrderDto edited =
        read(materialEdit(fixture, null, 12.0, before.commentVersion()).andExpect(status().isOk()));

    assertThat(edited.materials().getFirst().amount()).isEqualTo(12.0);
    assertThat(edited.commentVersion()).isEqualTo(before.commentVersion());
  }

  /** A stale or missing comment version refuses the whole edit, the materials included. */
  @Test
  void aStaleOrMissingCommentVersionRefusesTheMaterialEdit() throws Exception {
    Fixture fixture = seed();
    long observed = jobOrderQueryService.getJobOrderById(fixture.orderId()).commentVersion();
    materialEdit(fixture, "First note", 10.0, observed).andExpect(status().isOk());

    materialEdit(fixture, "Second note", 12.0, observed).andExpect(status().isConflict());
    materialEdit(fixture, "Second note", 12.0, null).andExpect(status().isConflict());

    JobOrderDto current = jobOrderQueryService.getJobOrderById(fixture.orderId());
    assertThat(current.comment()).isEqualTo("First note");
    assertThat(current.commentVersion()).isEqualTo(observed + 1);
    assertThat(current.materials().getFirst().amount()).isEqualTo(10.0);
  }

  /** The ITEM edit raises the comment version with a current one and refuses a stale one. */
  @Test
  void theItemEditChecksAndRaisesTheCommentVersion() throws Exception {
    Fixture fixture = seed();
    ItemLine line = itemLine(fixture);
    long observed = jobOrderQueryService.getJobOrderById(fixture.orderId()).commentVersion();

    JobOrderDto edited =
        read(itemEdit(fixture, line, "Item note", observed).andExpect(status().isOk()));
    itemEdit(fixture, line, "Later note", observed).andExpect(status().isConflict());

    assertThat(edited.comment()).isEqualTo("Item note");
    assertThat(edited.commentVersion()).isEqualTo(observed + 1);
    assertThat(jobOrderQueryService.getJobOrderById(fixture.orderId()).comment())
        .isEqualTo("Item note");
  }

  /**
   * The finished item and recipe of the one ITEM line {@link #itemLine} gives the seeded order.
   *
   * @param gameItemId the ordered finished item
   * @param blueprintId the recipe, which requires the seeded material
   */
  private record ItemLine(UUID gameItemId, UUID blueprintId) {}

  /**
   * Turns the seeded order into an ITEM order of one line whose recipe requires its material.
   *
   * @param fixture the seeded material order
   * @return the line's finished item and recipe
   */
  private @NotNull ItemLine itemLine(@NotNull Fixture fixture) {
    return transactionTemplate.execute(_ -> {
      Material material = materialRepository.findById(fixture.materialId()).orElseThrow();
      GameItem output = new GameItem();
      output.setName("Commented item " + UUID.randomUUID());
      output = gameItems.save(output);
      GameItem component = new GameItem();
      component.setName(material.getName());
      component = gameItems.save(component);
      BlueprintIngredient ingredient = new BlueprintIngredient();
      ingredient.setOrderIndex(0);
      ingredient.setKind(BlueprintIngredientKind.ITEM);
      ingredient.setGameItem(component);
      ingredient.setQuantityUnits(10);
      ingredient.setMinQuality(GOOD_FLOOR);
      Blueprint recipe = new Blueprint();
      recipe.setOutputItem(output);
      recipe.setOutputName(output.getName());
      recipe.addIngredient(ingredient);
      recipe = blueprints.saveAndFlush(recipe);
      JobOrder order = jobOrderRepository.findById(fixture.orderId()).orElseThrow();
      order.setType(JobOrderType.ITEM);
      order.getMaterials().clear();
      JobOrderItem item =
          JobOrderItem.builder().gameItem(output).blueprint(recipe).amount(1).build();
      item.addMaterial(
          JobOrderItemMaterial.builder()
              .material(material)
              .requiredQuantity(10.0)
              .qualityTier(qualityTierRepository.findByCode("GOOD").orElseThrow())
              .build());
      order.addItem(item);
      return new ItemLine(output.getId(), recipe.getId());
    });
  }

  private @NotNull ResultActions materialEdit(
      @NotNull Fixture fixture,
      @Nullable String comment,
      double requested,
      @Nullable Long commentVersion)
      throws Exception {
    JobOrderDto current = jobOrderQueryService.getJobOrderById(fixture.orderId());
    CreateJobOrderDto edit =
        new CreateJobOrderDto(
            null,
            null,
            current.handle(),
            comment,
            List.of(new CreateJobOrderMaterialDto(fixture.materialId(), GOOD_FLOOR, requested)),
            new JobOrderObservedVersions(current.version(), commentVersion));
    return send("/api/v1/orders/{id}", fixture.orderId(), edit);
  }

  private @NotNull ResultActions itemEdit(
      @NotNull Fixture fixture,
      @NotNull ItemLine line,
      @NotNull String comment,
      long commentVersion)
      throws Exception {
    JobOrderDto current = jobOrderQueryService.getJobOrderById(fixture.orderId());
    CreateJobOrderItemRequestDto edit =
        new CreateJobOrderItemRequestDto(
            null,
            null,
            current.handle(),
            comment,
            List.of(
                new CreateJobOrderItemLineDto(
                    current.items().getFirst().id(),
                    line.gameItemId(),
                    line.blueprintId(),
                    1,
                    List.of(),
                    null,
                    null)),
            new JobOrderObservedVersions(current.version(), commentVersion));
    return send("/api/v1/orders/{id}/items", fixture.orderId(), edit);
  }

  private @NotNull ResultActions send(@NotNull String path, @NotNull UUID orderId, Object body)
      throws Exception {
    return mvc()
        .perform(
            put(path, orderId)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
  }

  private @NotNull JobOrderDto read(@NotNull ResultActions response) throws Exception {
    return json.readValue(
        response.andReturn().getResponse().getContentAsString(), JobOrderDto.class);
  }
}
