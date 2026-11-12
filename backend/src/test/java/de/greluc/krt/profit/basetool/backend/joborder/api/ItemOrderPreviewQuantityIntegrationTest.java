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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** Verifies public recipe quantities cannot silently saturate their exact storage range. */
@SpringBootTest
class ItemOrderPreviewQuantityIntegrationTest {
  @Autowired private WebApplicationContext context;
  @Autowired private BlueprintRepository blueprints;
  @Autowired private MaterialRepository materials;
  @Autowired private TransactionTemplate transactions;

  @ParameterizedTest
  @ValueSource(doubles = {1.0e20, Double.NaN, Double.POSITIVE_INFINITY})
  void previewRefusesUnrepresentableRequirementInsteadOfReturningASaturatedQuantity(double quantity)
      throws Exception {
    UUID blueprintId =
        transactions.execute(_ -> {
          Material material = new Material();
          material.setName("Quantity boundary " + UUID.randomUUID());
          material.setType(MaterialType.RAW);
          material = materials.save(material);
          Blueprint blueprint = new Blueprint();
          blueprint.setOutputName("Quantity boundary output");
          BlueprintIngredient ingredient = new BlueprintIngredient();
          ingredient.setKind(BlueprintIngredientKind.RESOURCE);
          ingredient.setMaterial(material);
          ingredient.setQuantityScu(quantity);
          ingredient.setOrderIndex(0);
          blueprint.addIngredient(ingredient);
          return blueprints.saveAndFlush(blueprint).getId();
        });
    var mvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();
    mvc.perform(
            get("/api/v1/blueprints/{id}/derivation", blueprintId).param("amount", "2").with(jwt()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("ILLEGAL_ARGUMENT"));
  }
}
