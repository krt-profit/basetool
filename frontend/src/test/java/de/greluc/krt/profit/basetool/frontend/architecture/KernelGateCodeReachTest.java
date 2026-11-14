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

package de.greluc.krt.profit.basetool.frontend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGate;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGates;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Keeps the session gates' problem codes out of the frontend kernel (ADR-0385): the kernel tells a
 * gate's refusal by the backend's marker and finds the gate through {@link SessionGates}, so no
 * kernel class may carry a gate's code, as a literal or as an inlined constant of a domain class.
 *
 * <p>A compiled class holds every string constant it uses in its constant pool, so the check reads
 * the class files. The rule is proven against a planted code. Shares the context of {@code
 * ContextShapeTest}.
 */
@SpringBootTest
class KernelGateCodeReachTest {

  private static final String BASE = "de.greluc.krt.profit.basetool.frontend";

  private static final String PLANTED_CODE = "PLANTED_GATE_REQUIRED";

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @Autowired private SessionGates sessionGates;

  @Test
  void noKernelClassCarriesASessionGatesCode() {
    JavaClasses kernel =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE + ".kernel");
    List<String> codes = sessionGates.all().stream().map(SessionGate::problemCode).toList();

    assertThat(codes).as("the context holds the session gates").isNotEmpty();
    assertThat(carriers(kernel, codes))
        .as(
            "kernel classes carrying a session gate's code; recognise the refusal by its"
                + " marker and let the gate supply its code instead")
        .isEmpty();
  }

  @Test
  void theRuleCatchesAPlantedCode() {
    JavaClasses planted = new ClassFileImporter().importClasses(PlantedGateCode.class);

    assertThat(carriers(planted, List.of(PLANTED_CODE)))
        .containsExactly(PlantedGateCode.class.getName() + " -> " + PLANTED_CODE);
  }

  /**
   * Lists the classes whose class file holds one of the codes.
   *
   * @param classes the classes to search
   * @param codes the codes no class may hold
   * @return {@code class -> code}, sorted
   */
  private static @NotNull Set<String> carriers(
      @NotNull JavaClasses classes, @NotNull Collection<String> codes) {
    Set<String> found = new TreeSet<>();
    for (JavaClass type : classes) {
      String constants = classFile(type);
      for (String code : codes) {
        if (constants.contains(code)) {
          found.add(type.getName() + " -> " + code);
        }
      }
    }
    return found;
  }

  /**
   * Reads a class file as ISO-8859-1 text, which keeps the ASCII of its constant pool intact.
   *
   * @param type the imported class
   * @return the class file's bytes as text
   */
  private static @NotNull String classFile(@NotNull JavaClass type) {
    Source source =
        type.getSource().orElseThrow(() -> new AssertionError("no class file for " + type));
    try (InputStream in = source.getUri().toURL().openStream()) {
      return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A planted kernel-side class naming a gate's code. */
  static final class PlantedGateCode {

    /**
     * Tells whether a refusal carries the planted gate's code.
     *
     * @param problemCode the refusal's code
     * @return {@code true} for the planted code
     */
    boolean isPlantedGate(String problemCode) {
      return PLANTED_CODE.equals(problemCode);
    }
  }
}
