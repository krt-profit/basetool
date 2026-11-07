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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDomain;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * A starmap object is visible only inside a linked live star system, with a position and {@code
 * hideInStarmap} false; an admin's override wins and survives every APPLY (REQ-P4K-038).
 */
@SpringBootTest
@Transactional
class StarmapVisibilityTest {

  private static final Set<String> LIVE = Set.of("stanton");
  private static final UUID OUTPOST = UUID.fromString("5a000000-0000-4000-8000-000000000021");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private StarmapObjectRepository objectRepository;
  @Autowired private StarSystemRepository starSystemRepository;
  @Autowired private PlanetRepository planetRepository;
  @Autowired private CityRepository cityRepository;
  @Autowired private StarmapAdminService adminService;
  @Autowired private AuditService auditService;

  private static StarmapObject object(Boolean hideInStarmap, boolean placed, String system) {
    StarmapObject object = new StarmapObject();
    object.setHideInStarmap(hideInStarmap);
    object.setStarSystem(system);
    if (placed) {
      object.setPositionX(1.0);
      object.setPositionY(2.0);
      object.setPositionZ(3.0);
    }
    return object;
  }

  @Test
  void aPlacedShownObjectOfALiveSystem_isVisible() {
    assertThat(StarmapVisibility.isVisible(object(false, true, "stanton"), LIVE)).isTrue();
  }

  @Test
  void hideInStarmapNullOrTrue_orNoPosition_orNoLiveSystem_isHidden() {
    assertThat(StarmapVisibility.isVisible(object(null, true, "stanton"), LIVE)).isFalse();
    assertThat(StarmapVisibility.isVisible(object(true, true, "stanton"), LIVE)).isFalse();
    assertThat(StarmapVisibility.isVisible(object(false, false, "stanton"), LIVE)).isFalse();
    assertThat(StarmapVisibility.isVisible(object(false, true, "nyx"), LIVE)).isFalse();
    assertThat(StarmapVisibility.isVisible(object(false, true, null), LIVE)).isFalse();
  }

  @Test
  void anOverrideWinsOverTheRule_andAStampedObjectIsNeverVisible() {
    StarmapObject hidden = object(true, false, null);
    hidden.setVisibilityOverride(StarmapVisibilityOverride.VISIBLE);
    StarmapObject shown = object(false, true, "stanton");
    shown.setVisibilityOverride(StarmapVisibilityOverride.HIDDEN);
    StarmapObject stamped = object(false, true, "stanton");
    stamped.setP4kDeletedAt(Instant.now());

    assertThat(StarmapVisibility.isVisible(hidden, LIVE)).isTrue();
    assertThat(StarmapVisibility.isVisible(shown, LIVE)).isFalse();
    assertThat(StarmapVisibility.isVisible(stamped, LIVE)).isFalse();
  }

  @Test
  void anAdminOverrideToVisible_survivesTheNextApply_andIsAudited() {
    new P4kStarmapFixtures(starSystemRepository, planetRepository, cityRepository)
        .starSystem(P4kStarmapFixtures.SYSTEM, true);
    ObjectNode record = P4kStarmapFixtures.object(OUTPOST, "Outpost", "Krt Hidden Outpost", null);
    record.put("hideInStarmap", true);
    P4kImportRuns runs = new P4kImportRuns(service, jobRepository);
    byte[] catalogue = P4kTestCatalogues.bytes(P4kStarmapFixtures.catalogue(record));
    runs.apply(catalogue, false);
    StarmapObject stored = objectRepository.findByP4kUuid(OUTPOST).orElseThrow();

    StarmapObjectAdminDto overridden =
        adminService.overrideVisibility(
            stored.getId(), StarmapVisibilityOverride.VISIBLE, stored.getVersion());
    runs.apply(catalogue, false);

    assertThat(overridden.visible()).isTrue();
    StarmapObject after = objectRepository.findByP4kUuid(OUTPOST).orElseThrow();
    assertThat(after.getVisibilityOverride()).isEqualTo(StarmapVisibilityOverride.VISIBLE);
    assertThat(
            auditService
                .getEvents(
                    AuditDomain.CATALOGUE,
                    null,
                    null,
                    null,
                    AuditEventType.CATALOGUE_STARMAP_VISIBILITY_OVERRIDDEN,
                    null,
                    PageRequest.of(0, 50))
                .getContent())
        .filteredOn(e -> stored.getId().equals(e.subjectId()))
        .singleElement()
        .satisfies(e -> assertThat(e.details()).contains("VISIBLE"));
  }
}
