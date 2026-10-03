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

import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialPrice;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.OperationStatus;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialPriceOverviewDto;
import de.greluc.krt.profit.basetool.backend.support.LikePatterns;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves against PostgreSQL that the catalogue and planning searches treat {@code %} and {@code _}
 * of a {@link LikePatterns#escape} fragment literally while an ordinary fragment still matches
 * (REQ-DATA-019).
 */
@SpringBootTest
@Transactional
class LikeEscapeCatalogRepositoriesDataTest {

  private static final String TAG = "Lk" + UUID.randomUUID().toString().substring(0, 8);

  @Autowired private LocationRepository locationRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private TerminalRepository terminalRepository;
  @Autowired private MaterialPriceRepository materialPriceRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private OperationRepository operationRepository;
  @Autowired private SquadronRepository squadronRepository;

  @Test
  void locationSearchReference_matchesLiterally() {
    saveLocation(TAG + " Hub%A");
    saveLocation(TAG + " HubXA");
    saveLocation(TAG + " Hub_B");
    saveLocation(TAG + " HubYB");

    assertThat(locationNames(TAG + " Hub%A")).containsExactly(TAG + " Hub%A");
    assertThat(locationNames(TAG + " Hub_B")).containsExactly(TAG + " Hub_B");
    assertThat(locationNames(TAG + " HubXA")).containsExactly(TAG + " HubXA");
  }

  @Test
  void materialSearchPickerAndPriceOverview_matchLiterally() {
    Material percent = saveMaterial(TAG + " Ore%A");
    Material plain = saveMaterial(TAG + " OreXA");
    Material underscore = saveMaterial(TAG + " Ore_B");
    Material other = saveMaterial(TAG + " OreYB");
    Terminal terminal = saveTerminal();
    for (Material material : List.of(percent, plain, underscore, other)) {
      savePrice(material, terminal);
    }

    assertThat(pickerNames(TAG + " Ore%A")).containsExactly(percent.getName());
    assertThat(pickerNames(TAG + " Ore_B")).containsExactly(underscore.getName());
    assertThat(pickerNames(TAG + " OreXA")).containsExactly(plain.getName());
    assertThat(overviewNames(TAG + " Ore%A")).containsExactly(percent.getName());
    assertThat(overviewNames(TAG + " Ore_B")).containsExactly(underscore.getName());
    assertThat(overviewNames(TAG + " OreXA")).containsExactly(plain.getName());
  }

  @Test
  void missionSearch_matchesNameAndDescriptionLiterally() {
    OrgUnit owner = saveSquadron();
    saveMission(owner, TAG + " Run%A", "plain");
    saveMission(owner, TAG + " RunXA", "plain");
    saveMission(owner, TAG + " Run_B", "plain");
    saveMission(owner, TAG + " RunYB", "plain");
    saveMission(owner, TAG + " Desc", "note 50%_off");
    saveMission(owner, TAG + " Desc2", "note 50Xoff");

    assertThat(missionNames(TAG + " Run%A")).containsExactly(TAG + " Run%A");
    assertThat(missionNames(TAG + " Run_B")).containsExactly(TAG + " Run_B");
    assertThat(missionNames(TAG + " RunXA")).containsExactly(TAG + " RunXA");
    assertThat(missionNames("50%_off")).containsExactly(TAG + " Desc");
  }

  @Test
  void operationSearch_matchesNameAndDescriptionLiterally() {
    OrgUnit owner = saveSquadron();
    saveOperation(owner, TAG + " Op%A", "plain");
    saveOperation(owner, TAG + " OpXA", "plain");
    saveOperation(owner, TAG + " Op_B", "plain");
    saveOperation(owner, TAG + " OpYB", "plain");
    saveOperation(owner, TAG + " OpDesc", "note 50%_off");
    saveOperation(owner, TAG + " OpDesc2", "note 50Xoff");

    assertThat(operationNames(TAG + " Op%A")).containsExactly(TAG + " Op%A");
    assertThat(operationNames(TAG + " Op_B")).containsExactly(TAG + " Op_B");
    assertThat(operationNames(TAG + " OpXA")).containsExactly(TAG + " OpXA");
    assertThat(operationNames("50%_off")).containsExactly(TAG + " OpDesc");
  }

  private List<String> locationNames(String fragment) {
    return locationRepository
        .searchReference(LikePatterns.escape(fragment), PageRequest.of(0, 50))
        .getContent()
        .stream()
        .map(reference -> reference.name())
        .toList();
  }

  private List<String> pickerNames(String fragment) {
    return materialRepository
        .searchPicker(
            LikePatterns.escape(fragment), false, MaterialType.RAW, false, PageRequest.of(0, 50))
        .getContent()
        .stream()
        .map(Material::getName)
        .toList();
  }

  private List<String> overviewNames(String fragment) {
    return materialRepository
        .getMaterialPriceOverview(LikePatterns.escape(fragment), PageRequest.of(0, 50))
        .getContent()
        .stream()
        .map(MaterialPriceOverviewDto::name)
        .toList();
  }

  private List<String> missionNames(String fragment) {
    return missionRepository
        .searchMissions(
            LikePatterns.escape(fragment),
            null,
            null,
            List.of("PLANNED"),
            null,
            null,
            true,
            null,
            Set.of(),
            true,
            PageRequest.of(0, 50))
        .getContent()
        .stream()
        .map(Mission::getName)
        .toList();
  }

  private List<String> operationNames(String fragment) {
    return operationRepository
        .searchOperations(
            LikePatterns.escape(fragment),
            null,
            null,
            List.of("PLANNED"),
            true,
            null,
            Set.of(),
            true,
            null,
            PageRequest.of(0, 50))
        .getContent()
        .stream()
        .map(Operation::getName)
        .toList();
  }

  private void saveLocation(String name) {
    Location location = new Location();
    location.setName(name);
    locationRepository.save(location);
  }

  private Material saveMaterial(String name) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.RAW);
    material.setIsIllegal(0);
    material.setIsVolatileQt(0);
    material.setIsVolatileTime(0);
    return materialRepository.save(material);
  }

  private Terminal saveTerminal() {
    Terminal terminal = new Terminal();
    terminal.setName(TAG + " Terminal");
    terminal.setStarSystemName(TAG + " System");
    terminal.setHasLoadingDock(false);
    terminal.setIsAutoLoad(false);
    terminal.setHidden(false);
    return terminalRepository.save(terminal);
  }

  private void savePrice(Material material, Terminal terminal) {
    MaterialPrice price = new MaterialPrice();
    price.setMaterial(material);
    price.setTerminal(terminal);
    price.setPriceBuy(BigDecimal.TEN);
    price.setPriceSell(BigDecimal.ONE);
    price.setStatusBuy(true);
    price.setStatusSell(true);
    materialPriceRepository.save(price);
  }

  private OrgUnit saveSquadron() {
    Squadron squadron = new Squadron();
    squadron.setName("LikeSq-" + TAG);
    squadron.setShorthand("L" + UUID.randomUUID().toString().substring(0, 6));
    return squadronRepository.save(squadron);
  }

  private void saveMission(OrgUnit owner, String name, String description) {
    Mission mission = new Mission();
    mission.setName(name);
    mission.setDescription(description);
    mission.setStatus("PLANNED");
    mission.setIsInternal(false);
    mission.setOwningOrgUnit(owner);
    missionRepository.save(mission);
  }

  private void saveOperation(OrgUnit owner, String name, String description) {
    Operation operation = new Operation();
    operation.setName(name);
    operation.setDescription(description);
    operation.setStatus(OperationStatus.PLANNED);
    operation.setOwningOrgUnit(owner);
    operationRepository.save(operation);
  }
}
