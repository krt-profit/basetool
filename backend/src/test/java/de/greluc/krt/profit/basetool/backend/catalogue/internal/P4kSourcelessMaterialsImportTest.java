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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * A UEX commodity no game source knows, without a price and without a reference, leaves the lists
 * and pickers, and comes back once a source or a price arrives (REQ-P4K-075, ADR-0396).
 */
@SpringBootTest
@Transactional
class P4kSourcelessMaterialsImportTest {

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private AuditRecorder auditRecorder;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  private Material uexCommodity(String name, int uexId) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.NO_REFINE);
    material.setIdCommodity(uexId);
    return materialRepository.save(material);
  }

  private boolean offered(Material material) {
    materialRepository.flush();
    return materialRepository.findAllReference().stream()
        .map(MaterialReferenceDto::id)
        .anyMatch(material.getId()::equals);
  }

  @Test
  void aUexCommodityNoGameSourceKnows_withoutPriceOrReference_isHiddenAndAudited() {
    Material boron = uexCommodity("Boron", 90147);
    Instant before = Instant.now().minusSeconds(1);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(boron.getSourcelessHiddenAt()).isNotNull();
    assertThat(boron.isSourcelessHidden()).isTrue();
    assertThat(offered(boron)).isFalse();
    assertThat(
            auditRecorder.recordedSince(
                AuditEventType.CATALOGUE_MATERIAL_SOURCELESS_VISIBILITY_CHANGED,
                boron.getId(),
                before))
        .isTrue();
  }

  @Test
  void aHiddenMaterialShowsAgainWhenItGetsAPrice_andTheNextApplyLiftsTheMark() {
    Material boron = uexCommodity("Boron", 90147);
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    boron.setPriceSell(12.5);

    assertThat(offered(boron)).isTrue();
    assertThat(boron.isSourcelessHidden()).isFalse();
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    assertThat(boron.getSourcelessHiddenAt()).isNull();
  }

  @Test
  void aReferencedMaterial_isNotHidden() {
    Material boron = uexCommodity("Boron", 90147);
    Material ore = uexCommodity("Boron Ore", 90148);
    ore.setRefinedMaterial(boron);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(boron.getSourcelessHiddenAt()).isNull();
    assertThat(offered(boron)).isTrue();
  }

  @Test
  void aPricedMaterial_orOneTheGameKnows_isNotHidden() {
    Material ammo = uexCommodity("Ship Ammunition - Size 1", 90201);
    ammo.setPriceBuy(6868.0);
    Material aslarite = uexCommodity("Aslarite", 90160);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(ammo.getSourcelessHiddenAt()).isNull();
    assertThat(aslarite.getSourcelessHiddenAt()).isNull();
  }

  @Test
  void aPreviewReportsTheHide_andWritesNothing() {
    Material boron = uexCommodity("Boron", 90147);

    List<P4kImportChange> changes =
        runs.previewWithReport(P4kTestCatalogues.fixtureBytes()).changes();

    assertThat(boron.getSourcelessHiddenAt()).isNull();
    assertThat(changes)
        .anyMatch(change ->
            change.getKind() == P4kKind.COMMODITY
                && change.getChangeType() == P4kChangeType.CHANGED
                && P4kSourcelessMaterials.COLUMN.equals(change.getFieldName())
                && "Boron".equals(change.getNameEn()));
  }

  @Test
  void aMaterialAnotherSourceListsAgain_isOfferedAgain() {
    Material boron = uexCommodity("Boron", 90147);
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    boron.setScwikiUuid(UUID.fromString("0f000000-0000-4000-8000-0000000c1001"));

    assertThat(offered(boron)).isTrue();
  }
}
