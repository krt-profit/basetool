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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.platform.api.CacheConfig;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds that re-parenting a Staffel evicts the squadron cache, so the squadron admin list never
 * echoes the version the parent write has just superseded.
 */
@SpringBootTest
@Transactional
class OrgHierarchySquadronCacheTest {

  @Autowired private OrgHierarchyService orgHierarchyService;
  @Autowired private SquadronService squadronService;
  @Autowired private CacheManager cacheManager;

  @Test
  @DisplayName("setParent on a Staffel evicts the cached squadron list")
  void setParentEvictsTheSquadronCache() {
    String tag = UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT);
    Squadron staffel = new Squadron();
    staffel.setName("Cache-Staffel " + tag);
    staffel.setShorthand("S" + tag.substring(0, 4));
    Squadron saved = squadronService.createSquadron(staffel);
    Bereich bereich =
        orgHierarchyService.createBereich(
            "Cache-Bereich " + tag, "B" + tag.substring(0, 4), null, null, null);

    squadronService.getAllSquadrons(true);
    Cache cache = Objects.requireNonNull(cacheManager.getCache(CacheConfig.SQUADRONS_CACHE));
    assertThat(cache.get(Boolean.TRUE)).isNotNull();

    orgHierarchyService.setParent(saved.getId(), bereich.getId(), saved.getVersion());

    assertThat(cache.get(Boolean.TRUE)).isNull();
  }
}
