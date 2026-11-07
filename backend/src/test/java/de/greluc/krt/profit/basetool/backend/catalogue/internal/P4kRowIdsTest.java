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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogueReader;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The cross-kind row ids of a run are read once per kind, however many reconcilers ask. */
class P4kRowIdsTest {

  @Test
  void everyReconcilerOfARun_sharesOneReadOfAKind() {
    P4kCatalogue catalogue =
        new P4kCatalogueReader(JsonMapper.builder().build())
            .read(new ByteArrayInputStream(P4kTestCatalogues.fixtureBytes()));
    P4kImportContext context = P4kImportContext.forPreview(catalogue, Instant.now(), Set.of());
    UUID guid = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    TypedQuery<P4kKnownRow> query = mock();
    when(query.getResultList())
        .thenReturn(List.of(new P4kKnownRow(id, guid, "Class", "Name", false, false)));
    EntityManager entityManager = mock();
    when(entityManager.createQuery(anyString(), eq(P4kKnownRow.class))).thenReturn(query);

    P4kRowIds forPaints = P4kRowIds.load(entityManager, P4kKind.ITEM, context);
    P4kRowIds forShops = P4kRowIds.load(entityManager, P4kKind.ITEM, context);

    assertThat(forShops).isSameAs(forPaints);
    assertThat(forShops.id(guid.toString())).isEqualTo(id);
    verify(entityManager, times(1)).createQuery(anyString(), eq(P4kKnownRow.class));
  }
}
