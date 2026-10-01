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

package de.greluc.krt.profit.basetool.testsupport.logging;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

/**
 * Ratchet for credential-bearing value objects (REQ-OBS-004): a type with a field named like a
 * credential must be on a reviewed list of types that print it redacted, and a listed class must
 * declare its own {@code toString()} instead of a Lombok-generated one.
 *
 * <p>A record's compiler-generated {@code toString()} is indistinguishable from a hand-written one
 * in bytecode, so a listed record is vouched for by its entry and its own redaction test.
 */
public final class CredentialFieldRule {

  /** Field names that denote a credential. */
  private static final Pattern CREDENTIAL_NAME =
      Pattern.compile("(?i).*(password|passwd|secret|token|apikey).*");

  /** Raw field types that can hold a credential value. */
  private static final Set<String> CREDENTIAL_TYPES =
      Set.of("java.lang.String", "char[]", "byte[]");

  /** Non-instantiable holder of the rule. */
  private CredentialFieldRule() {}

  /**
   * Finds every violation of the rule among the given classes.
   *
   * @param classes the imported production classes
   * @param reviewedRedactingTypes fully-qualified names of the reviewed types that print their
   *     credential redacted
   * @return one message per violation, sorted; empty when the rule holds
   */
  public static @NotNull List<String> violations(
      @NotNull JavaClasses classes, @NotNull Set<String> reviewedRedactingTypes) {
    TreeSet<String> out = new TreeSet<>();
    Set<String> seen = new TreeSet<>();
    for (JavaClass type : classes) {
      List<String> fields = credentialFields(type);
      if (fields.isEmpty()) {
        continue;
      }
      seen.add(type.getName());
      if (!reviewedRedactingTypes.contains(type.getName())) {
        out.add(type.getName() + " holds " + fields + " but is not on the reviewed list");
      } else if (!type.isRecord() && !declaresOwnToString(type)) {
        out.add(type.getName() + " holds " + fields + " without its own toString()");
      }
    }
    for (String listed : reviewedRedactingTypes) {
      if (!seen.contains(listed)) {
        out.add(listed + " is on the reviewed list but holds no credential field any more");
      }
    }
    return new ArrayList<>(out);
  }

  /**
   * Lists the credential-named, credential-typed instance fields of a class.
   *
   * @param type the class to inspect
   * @return the matching field names, in declaration order
   */
  private static @NotNull List<String> credentialFields(@NotNull JavaClass type) {
    List<String> names = new ArrayList<>();
    for (JavaField field : type.getFields()) {
      if (!field.getModifiers().contains(JavaModifier.STATIC)
          && CREDENTIAL_NAME.matcher(field.getName()).matches()
          && CREDENTIAL_TYPES.contains(field.getRawType().getName())) {
        names.add(field.getName());
      }
    }
    return names;
  }

  /**
   * Whether the class declares a {@code toString()} that Lombok did not generate.
   *
   * @param type the class to inspect
   * @return {@code true} for a hand-written {@code toString()}
   */
  private static boolean declaresOwnToString(@NotNull JavaClass type) {
    return type.getMethods().stream()
        .anyMatch(
            m ->
                m.getName().equals("toString")
                    && m.getRawParameterTypes().isEmpty()
                    && !m.isAnnotatedWith("lombok.Generated"));
  }
}
