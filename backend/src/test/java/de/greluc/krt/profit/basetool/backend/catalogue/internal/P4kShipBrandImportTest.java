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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/** The brand a ship's English name begins with beats a contradicting GUID (REQ-P4K-059). */
@SpringBootTest
@Transactional
class P4kShipBrandImportTest {

  private static final UUID AEGS = UUID.fromString("cf4a74bf-eb2c-462a-9b78-f7f2724c31d2");
  private static final UUID ANVL = UUID.fromString("b922abdb-0634-4358-9eee-60118f3cf3a7");
  private static final UUID ORIG = UUID.fromString("1e47a9ec-a3f0-431d-be0e-377712e1ed84");

  /** The fixture's "Aegis Avenger Titan". */
  private static final UUID TITAN = UUID.fromString("0079c5d5-1678-4f8c-85ba-18ca8f642af6");

  /** The fixture's "Origin 890 Jump". */
  private static final UUID JUMP = UUID.fromString("05189197-09f7-4869-8db2-5fe21886cda3");

  @Autowired private P4kImportService imports;
  @Autowired private P4kImportJobRepository jobs;
  @Autowired private ManufacturerRepository manufacturers;
  @Autowired private ShipTypeRepository ships;

  private P4kImportRuns runs;
  private Manufacturer aegis;
  private Manufacturer anvil;
  private Manufacturer origin;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(imports, jobs);
    aegis = manufacturers.saveAndFlush(manufacturer("Aegis Dynamics", AEGS));
    anvil = manufacturers.saveAndFlush(manufacturer("Anvil Aerospace", ANVL));
    origin = manufacturers.saveAndFlush(manufacturer("Origin Jumpworks", ORIG));
  }

  @Test
  void theBrandAShipsNameBeginsWithBeatsAContradictingGuid() {
    ShipType titan = ships.saveAndFlush(ship("Aegis Avenger Titan", TITAN, anvil));
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.record(root, "ships", TITAN).put("manufacturerGuid", ANVL.toString());

    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(titan.getManufacturer()).isEqualTo(aegis);
  }

  @Test
  void aShipWhoseNameAgreesWithItsGuidKeepsThatBrand() {
    ShipType jump = ships.saveAndFlush(ship("Origin 890 Jump", JUMP, null));

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(jump.getManufacturer()).isEqualTo(origin);
  }

  private static Manufacturer manufacturer(String name, UUID p4kUuid) {
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName(name);
    manufacturer.setAbbreviation(name);
    manufacturer.setP4kUuid(p4kUuid);
    return manufacturer;
  }

  private static ShipType ship(String name, UUID p4kUuid, Manufacturer manufacturer) {
    ShipType ship = new ShipType();
    ship.setName(name);
    ship.setP4kUuid(p4kUuid);
    ship.setManufacturer(manufacturer);
    ship.setSourceSystems(GameItemSourceSystem.BOTH);
    return ship;
  }
}
