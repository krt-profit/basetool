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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintProductRow;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.support.LikePatterns;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves that the escaped fragments {@link LikePatterns#escape(String)} produces match literally in
 * the three blueprint-backed catalogue searches: {@code %} and {@code _} must not act as wildcards.
 */
@SpringBootTest
@Transactional
class BlueprintRepositoryLikeEscapeTest {

  private static final String TAG = "LikeProbe" + UUID.randomUUID().toString().substring(0, 8);

  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private GameItemRepository gameItemRepository;

  private String percentName;
  private String underscoreName;
  private String plainName;

  @BeforeEach
  void seed() {
    percentName = TAG + " 100% Drive";
    underscoreName = TAG + "_Drive";
    plainName = TAG + "X100x Driveq";
    for (String name : List.of(percentName, underscoreName, plainName)) {
      GameItem item = new GameItem();
      item.setName(name);
      item = gameItemRepository.saveAndFlush(item);
      Blueprint blueprint = new Blueprint();
      blueprint.setScwikiUuid(UUID.randomUUID());
      blueprint.setOutputName(name);
      blueprint.setOutputItem(item);
      blueprintRepository.saveAndFlush(blueprint);
    }
  }

  @Test
  void findActiveProductRows_treatsPercentAndUnderscoreLiterally() {
    assertThat(names(blueprintRepository.findActiveProductRows(LikePatterns.escape("100% D"))))
        .containsExactly(percentName);
    assertThat(names(blueprintRepository.findActiveProductRows(LikePatterns.escape(TAG + "_"))))
        .containsExactly(underscoreName);
  }

  @Test
  void findItemsWithActiveBlueprint_treatsPercentAndUnderscoreLiterally() {
    assertThat(
            blueprintRepository
                .findItemsWithActiveBlueprint(LikePatterns.escape("100% D"), PageRequest.of(0, 50))
                .map(GameItem::getName)
                .getContent())
        .containsExactly(percentName);
    assertThat(
            blueprintRepository
                .findItemsWithActiveBlueprint(LikePatterns.escape(TAG + "_"), PageRequest.of(0, 50))
                .map(GameItem::getName)
                .getContent())
        .containsExactly(underscoreName);
  }

  @Test
  void searchActive_treatsPercentAndUnderscoreLiterally() {
    assertThat(
            blueprintRepository
                .searchActive(LikePatterns.escape("100% D"), PageRequest.of(0, 50))
                .map(Blueprint::getOutputName)
                .getContent())
        .containsExactly(percentName);
  }

  @Test
  void unescapedWildcardsWouldHaveBroadenedTheMatch() {
    assertThat(names(blueprintRepository.findActiveProductRows(TAG + "_")))
        .contains(underscoreName, plainName);
  }

  private static List<String> names(List<BlueprintProductRow> rows) {
    return rows.stream().map(BlueprintProductRow::outputName).toList();
  }
}
