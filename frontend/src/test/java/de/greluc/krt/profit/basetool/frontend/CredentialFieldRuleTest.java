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

package de.greluc.krt.profit.basetool.frontend;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import de.greluc.krt.profit.basetool.testsupport.logging.CredentialFieldRule;
import java.util.Set;
import lombok.Data;
import org.junit.jupiter.api.Test;

/** Proves {@link CredentialFieldRule} rejects the shapes it exists to catch. */
class CredentialFieldRuleTest {

  /** Lombok-generated {@code toString()} that would print the password. */
  @Data
  static class LeakyBean {
    private String password;
  }

  /** Hand-written redacting {@code toString()}. */
  static class RedactingBean {
    private String password;

    @Override
    public String toString() {
      return "RedactingBean[password=<redacted>]";
    }
  }

  /** Credential-named field of a type that is not a credential. */
  static class HarmlessBean {
    private int refillTokens;
    private boolean secretMode;
  }

  private static JavaClasses of(Class<?>... types) {
    return new ClassFileImporter().importClasses(types);
  }

  @Test
  void unreviewedTypeIsReported() {
    assertThat(CredentialFieldRule.violations(of(RedactingBean.class), Set.of()))
        .singleElement()
        .asString()
        .contains("not on the reviewed list");
  }

  @Test
  void reviewedLombokGeneratedToStringIsReported() {
    assertThat(
            CredentialFieldRule.violations(of(LeakyBean.class), Set.of(LeakyBean.class.getName())))
        .singleElement()
        .asString()
        .contains("without its own toString()");
  }

  @Test
  void reviewedHandWrittenToStringPasses() {
    assertThat(
            CredentialFieldRule.violations(
                of(RedactingBean.class), Set.of(RedactingBean.class.getName())))
        .isEmpty();
  }

  @Test
  void nonCredentialTypesAreIgnored() {
    assertThat(CredentialFieldRule.violations(of(HarmlessBean.class), Set.of())).isEmpty();
  }

  @Test
  void staleReviewedEntryIsReported() {
    assertThat(CredentialFieldRule.violations(of(HarmlessBean.class), Set.of("x.Gone")))
        .singleElement()
        .asString()
        .contains("holds no credential field any more");
  }
}
