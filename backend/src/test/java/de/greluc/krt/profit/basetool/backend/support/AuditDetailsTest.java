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

package de.greluc.krt.profit.basetool.backend.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies the byte-equivalence contract of {@link AuditDetails}: {@code
 * AuditDetails.of(...).with(...).toString()} must produce a string character-identical to the
 * hand-written {@code "k=" + v + " k2=" + v2} concatenation it replaces (S8, #914), across every
 * value type that appears at the migrated call sites, plus the key-validation guard and the {@link
 * CharSequence} contract that lets {@code record(...)} accept the composer directly.
 */
class AuditDetailsTest {

  /** Local stand-in for the domain enums (e.g. {@code MissionStatus}) rendered in audit details. */
  private enum SampleStatus {
    PLANNED,
    ACTIVE
  }

  @Test
  void singlePair_matchesConcatenation() {
    SampleStatus status = SampleStatus.PLANNED;
    String built = AuditDetails.of("status", status).toString();
    assertEquals("status=" + status, built, "single pair must equal the + concatenation");
    assertEquals("status=PLANNED", built);
  }

  @Test
  void multiplePairs_matchConcatenation() {
    SampleStatus status = SampleStatus.ACTIVE;
    String built = AuditDetails.of("section", "full").with("status", status).toString();
    assertEquals("section=full status=" + status, built);
    assertEquals("section=full status=ACTIVE", built);
  }

  @Test
  void enumValue_rendersLikeConcatenation() {
    assertEquals(
        "status=" + SampleStatus.PLANNED,
        AuditDetails.of("status", SampleStatus.PLANNED).toString());
  }

  @Test
  void uuidValue_rendersLikeConcatenation() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    assertEquals("participant=" + id, AuditDetails.of("participant", id).toString());
    assertEquals(
        "participant=00000000-0000-0000-0000-000000000001",
        AuditDetails.of("participant", id).toString());
  }

  @Test
  void intValue_rendersLikeConcatenation() {
    int size = 7;
    assertEquals("materials=" + size, AuditDetails.of("materials", size).toString());
    assertEquals("materials=7", AuditDetails.of("materials", size).toString());
  }

  @Test
  void longValue_rendersLikeConcatenation() {
    long version = 42L;
    assertEquals("version=" + version, AuditDetails.of("version", version).toString());
  }

  @Test
  void booleanValue_rendersLikeConcatenation() {
    boolean flag = false;
    assertEquals("autoCompleted=" + flag, AuditDetails.of("autoCompleted", flag).toString());
    assertEquals("autoCompleted=false", AuditDetails.of("autoCompleted", flag).toString());
  }

  @Test
  void nullValue_rendersAsLiteralNullLikeConcatenation() {
    UUID nothing = null;
    assertEquals("participant=" + nothing, AuditDetails.of("participant", nothing).toString());
    assertEquals("participant=null", AuditDetails.of("participant", nothing).toString());
  }

  @Test
  void ternaryValue_rendersLikeConcatenation() {
    UUID finalUserId = null;
    String built =
        AuditDetails.of("participant", "p1")
            .with("type", finalUserId != null ? "user" : "external")
            .toString();
    assertEquals("participant=p1 type=" + (finalUserId != null ? "user" : "external"), built);
    assertEquals("participant=p1 type=external", built);
  }

  @Test
  void longChain_matchesConcatenation() {
    String built =
        AuditDetails.of("qty", 3)
            .with("q", "HIGH")
            .with("personal", true)
            .with("jobOrder", "jo1")
            .with("mission", "m1")
            .toString();
    assertEquals("qty=3 q=HIGH personal=true jobOrder=jo1 mission=m1", built);
  }

  @Test
  void valueContainingSpace_isNotAltered() {
    assertEquals("note=two words", AuditDetails.of("note", "two words").toString());
  }

  @Test
  void blankKey_throws() {
    assertThrows(IllegalArgumentException.class, () -> AuditDetails.of("", "v"));
    assertThrows(IllegalArgumentException.class, () -> AuditDetails.of("k", "v").with("", "v"));
  }

  @Test
  void keyWithEqualsOrWhitespace_throws() {
    assertThrows(IllegalArgumentException.class, () -> AuditDetails.of("a=b", "v"));
    assertThrows(IllegalArgumentException.class, () -> AuditDetails.of("a b", "v"));
    assertThrows(IllegalArgumentException.class, () -> AuditDetails.of("a\tb", "v"));
  }

  @Test
  void charSequenceContract_delegatesToRenderedPayload() {
    AuditDetails details = AuditDetails.of("section", "full").with("status", SampleStatus.ACTIVE);
    String rendered = "section=full status=ACTIVE";
    assertEquals(rendered.length(), details.length());
    assertEquals(rendered.charAt(0), details.charAt(0));
    assertEquals("section", details.subSequence(0, 7).toString());
  }

  @Test
  void usableAsCharSequence_matchesRenderedContent() {
    CharSequence details = AuditDetails.of("count", 3);
    assertTrue("count=3".contentEquals(details), "composer must be a CharSequence of its payload");
  }
}
