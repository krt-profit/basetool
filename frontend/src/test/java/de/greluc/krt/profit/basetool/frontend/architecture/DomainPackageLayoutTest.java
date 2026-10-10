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
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.frontend.architecture.fixture.model.UnmarkedMirrorDto;
import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keeps the frontend packaged by domain (plan §5.9, F4): every controller lives in a {@code
 * <domain>.web} package, every {@link DtoMirror} type in a {@code <domain>.model} package or the
 * kernel {@code model}, every {@code *Dto}, {@code *Request} or {@code *Response} type of a model
 * package carries the marker the DTO contract tests select by, and no class returns to a retired
 * layer package. Each rule is proven against a planted violation.
 */
class DomainPackageLayoutTest {

  private static final String BASE = "de.greluc.krt.profit.basetool.frontend";

  private static final JavaClasses MAIN =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE);

  /** A domain's web package: {@code <base>.<domain>.web}. */
  private static final Pattern WEB_PACKAGE =
      Pattern.compile(Pattern.quote(BASE) + "\\.[a-z]+\\.web");

  /** A model package: the kernel {@code <base>.model} or {@code <base>.<domain>.model}. */
  private static final Pattern MODEL_PACKAGE =
      Pattern.compile(Pattern.quote(BASE) + "(\\.[a-z]+)?\\.model");

  /** A name the contract tests expect to be a marked DTO. */
  private static final Pattern DTO_NAME = Pattern.compile(".*(Dto|Request|Response)");

  /** The layer packages the move emptied. */
  private static final Set<String> RETIRED_PACKAGES =
      Set.of(BASE + ".controller", BASE + ".model.dto", BASE + ".model.form", BASE + ".oss");

  /** Controllers when the rule was introduced; fewer means the selection broke. */
  private static final int CONTROLLER_FLOOR = 100;

  /** {@link DtoMirror} types when the rule was introduced; fewer means the selection broke. */
  private static final int MIRROR_FLOOR = 389;

  @Test
  void everyControllerLivesInItsDomainWebPackage() {
    List<JavaClass> controllers = controllers(MAIN.stream().toList());

    assertThat(controllers).hasSizeGreaterThanOrEqualTo(CONTROLLER_FLOOR);
    assertThat(misplacedControllers(controllers))
        .as("controllers outside a <domain>.web package")
        .isEmpty();
  }

  @Test
  void everyDtoMirrorLivesInAModelPackage() {
    List<JavaClass> mirrors =
        MAIN.stream().filter(c -> c.isAnnotatedWith(DtoMirror.class)).toList();

    assertThat(mirrors).hasSizeGreaterThanOrEqualTo(MIRROR_FLOOR);
    assertThat(misplacedMirrors(mirrors))
        .as("@DtoMirror types outside the kernel model and the <domain>.model packages")
        .isEmpty();
  }

  @Test
  void everyDtoNamedModelTypeCarriesTheMarker() {
    assertThat(unmarkedDtos(MAIN.stream().toList()))
        .as(
            "*Dto, *Request and *Response types of a model package without @DtoMirror; the DTO"
                + " contract tests would not see them")
        .isEmpty();
  }

  @Test
  void noClassLivesInARetiredLayerPackage() {
    assertThat(
            MAIN.stream()
                .filter(c -> RETIRED_PACKAGES.contains(c.getPackageName()))
                .map(JavaClass::getName)
                .toList())
        .as("classes in a layer package the package-by-domain move retired")
        .isEmpty();
  }

  @Test
  void theRulesCatchPlantedViolations() {
    JavaClasses planted =
        new ClassFileImporter()
            .importClasses(PlantedController.class, PlantedMirror.class, UnmarkedMirrorDto.class);
    List<JavaClass> all = planted.stream().toList();

    assertThat(misplacedControllers(controllers(all)))
        .containsExactly(PlantedController.class.getName());
    assertThat(
            misplacedMirrors(all.stream().filter(c -> c.isAnnotatedWith(DtoMirror.class)).toList()))
        .containsExactly(PlantedMirror.class.getName());
    assertThat(unmarkedDtos(all)).containsExactly(UnmarkedMirrorDto.class.getName());
  }

  /**
   * Selects the Spring MVC controllers.
   *
   * @param classes the classes to search
   * @return the {@code @Controller} and {@code @RestController} classes
   */
  static List<JavaClass> controllers(Collection<JavaClass> classes) {
    return classes.stream()
        .filter(c -> c.isAnnotatedWith(Controller.class) || c.isAnnotatedWith(RestController.class))
        .toList();
  }

  /**
   * Finds the controllers outside a domain's web package.
   *
   * @param controllers the controllers to check
   * @return their names, sorted
   */
  static Set<String> misplacedControllers(Collection<JavaClass> controllers) {
    Set<String> found = new TreeSet<>();
    for (JavaClass controller : controllers) {
      if (!WEB_PACKAGE.matcher(controller.getPackageName()).matches()) {
        found.add(controller.getName());
      }
    }
    return found;
  }

  /**
   * Finds the marked types outside a model package.
   *
   * @param mirrors the marked types to check
   * @return their names, sorted
   */
  static Set<String> misplacedMirrors(Collection<JavaClass> mirrors) {
    Set<String> found = new TreeSet<>();
    for (JavaClass mirror : mirrors) {
      if (!MODEL_PACKAGE.matcher(mirror.getPackageName()).matches()) {
        found.add(mirror.getName());
      }
    }
    return found;
  }

  /**
   * Finds the top-level {@code *Dto}, {@code *Request} and {@code *Response} types of a model
   * package that carry no {@link DtoMirror}.
   *
   * @param classes the classes to search
   * @return their names, sorted
   */
  static Set<String> unmarkedDtos(Collection<JavaClass> classes) {
    Set<String> found = new TreeSet<>();
    for (JavaClass type : classes) {
      if (type.getEnclosingClass().isEmpty()
          && !type.isAnnotation()
          && type.getPackageName().endsWith(".model")
          && DTO_NAME.matcher(type.getSimpleName()).matches()
          && !type.isAnnotatedWith(DtoMirror.class)) {
        found.add(type.getName());
      }
    }
    return found;
  }

  /** A planted controller outside any web package; nested in a test, so no context scans it. */
  @Controller
  static final class PlantedController {}

  /**
   * A planted mirror outside any model package.
   *
   * @param id an identifier
   */
  @DtoMirror
  record PlantedMirror(String id) {}
}
