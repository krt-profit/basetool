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
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** Verifies a public subassembly suggestion cannot wrap its whole-unit count. */
@SpringBootTest
class ItemOrderSubassemblyQuantityIntegrationTest {
  @Autowired private WebApplicationContext context;
  @Autowired private BlueprintRepository blueprints;
  @Autowired private GameItemRepository gameItems;
  @Autowired private TransactionTemplate transactions;

  @Test
  void previewRefusesUnrepresentableSubassemblyCountInsteadOfReturningWrappedUnits()
      throws Exception {
    UUID blueprintId =
        transactions.execute(_ -> {
          GameItem component = new GameItem();
          component.setName("Subassembly boundary " + UUID.randomUUID());
          component = gameItems.save(component);
          Blueprint child = new Blueprint();
          child.setOutputItem(component);
          child.setOutputName(component.getName());
          blueprints.save(child);
          Blueprint parent = new Blueprint();
          parent.setOutputName("Subassembly boundary output");
          BlueprintIngredient ingredient = new BlueprintIngredient();
          ingredient.setKind(BlueprintIngredientKind.ITEM);
          ingredient.setGameItem(component);
          ingredient.setQuantityUnits(Integer.MAX_VALUE);
          ingredient.setOrderIndex(0);
          parent.addIngredient(ingredient);
          return blueprints.saveAndFlush(parent).getId();
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
