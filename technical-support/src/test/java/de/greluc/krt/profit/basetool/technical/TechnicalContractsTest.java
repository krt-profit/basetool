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

package de.greluc.krt.profit.basetool.technical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;

/** Preserves the shared normalization and observation privacy contracts. */
class TechnicalContractsTest {
  @Test
  void normalizesNfcBeforeEnforcingTheLengthCap() {
    assertEquals("é", StringNormalization.normalize(" e\u0301 ", 1, false));
    assertEquals("", StringNormalization.normalize(" ", 1, false));
    assertNull(StringNormalization.normalize(" ", 1, true));
    assertNull(StringNormalization.normalize(null, 1, true));
    assertThrows(
        IllegalArgumentException.class, () -> StringNormalization.normalizeAndCap("long", 1));
  }

  @Test
  void preservesDistinctBlankAndUnicodeStripContracts() {
    assertNull(StringNormalization.blankToNull(null));
    assertNull(StringNormalization.blankToNull("\u2003"));
    assertEquals(" value ", StringNormalization.blankToNull(" value "));
    assertNull(StringNormalization.trimToNull(null));
    assertNull(StringNormalization.trimToNull("\u2003"));
    assertEquals("value", StringNormalization.trimToNull("\u2003value\u2003"));
  }

  @Test
  void scrubsOnlyRequestTargetKeysAtTheAppropriateCardinality() {
    var context = new Observation.Context();
    context.addLowCardinalityKeyValue(KeyValue.of("uri", "/api/42?token=synthetic"));
    context.addLowCardinalityKeyValue(KeyValue.of("method", "GET"));
    context.addLowCardinalityKeyValue(KeyValue.of("http.url", "/api/12?token=synthetic"));
    context.addHighCardinalityKeyValue(KeyValue.of("http.url", "/api/42?token=synthetic"));
    context.addHighCardinalityKeyValue(KeyValue.of("trace", "unchanged"));
    assertSame(context, ObservationPrivacy.map(context));
    assertEquals("/api/{id}", context.getLowCardinalityKeyValue("uri").getValue());
    assertEquals("/api/{id}", context.getLowCardinalityKeyValue("http.url").getValue());
    assertEquals("GET", context.getLowCardinalityKeyValue("method").getValue());
    assertEquals("/api/42", context.getHighCardinalityKeyValue("http.url").getValue());
    assertEquals("unchanged", context.getHighCardinalityKeyValue("trace").getValue());
  }

  @Test
  void retainsEmptyAndStableUrlsAndCollapsesUuidSegments() {
    assertNull(ObservationPrivacy.scrub(null, true));
    assertEquals("", ObservationPrivacy.scrub("", true));
    assertEquals("/api/static", ObservationPrivacy.scrub("/api/static", true));
    assertEquals(
        "/api/{id}/child/{id}",
        ObservationPrivacy.scrub("/api/12345678-abcd-abcd-abcd-123456789abc/child/23", true));
    var context = new Observation.Context();
    context.addLowCardinalityKeyValue(KeyValue.of("uri", "/api/static"));
    context.addHighCardinalityKeyValue(KeyValue.of("http.url", "/api/static"));
    assertSame(context, ObservationPrivacy.map(context));
  }
}
