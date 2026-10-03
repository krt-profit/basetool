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

package de.greluc.krt.profit.basetool.backend.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Folds compiled classes onto the source type they belong to, the unit the domain map assigns.
 *
 * <p>A nested, local or anonymous class belongs to its top-level class; a MapStruct implementation
 * {@code XMapperImpl} belongs to the {@code @Mapper} type {@code XMapper} it implements.
 */
public final class ModuleSubjects {

  /** The backend's root package. */
  public static final String BACKEND_PACKAGE = "de.greluc.krt.profit.basetool.backend";

  private static final String MAPSTRUCT_MAPPER = "org.mapstruct.Mapper";

  private static final String IMPL_SUFFIX = "Impl";

  private ModuleSubjects() {}

  /**
   * Imports the backend's production classes, generated MapStruct implementations included.
   *
   * @return every class compiled from the backend's main source set
   */
  public static @NotNull JavaClasses importBackendMainClasses() {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(BACKEND_PACKAGE);
  }

  /**
   * Finds the source type a compiled class belongs to.
   *
   * @param javaClass any imported class
   * @param classes the imported classes the subject is looked up in
   * @return the top-level source type, or the class itself when its top-level class is not imported
   */
  public static @NotNull JavaClass subjectOf(
      @NotNull JavaClass javaClass, @NotNull JavaClasses classes) {
    String topLevelName = javaClass.getName().split("\\$", 2)[0];
    JavaClass topLevel = classes.contain(topLevelName) ? classes.get(topLevelName) : javaClass;
    return implementedMapper(topLevel).orElse(topLevel);
  }

  /**
   * The source types of a class set: top-level classes that are not MapStruct implementations.
   *
   * @param classes the imported classes
   * @return the subjects, sorted by name
   */
  public static @NotNull @Unmodifiable List<JavaClass> subjects(@NotNull JavaClasses classes) {
    return topLevelClasses(classes)
        .filter(javaClass -> implementedMapper(javaClass).isEmpty())
        .toList();
  }

  /**
   * The top-level classes of a class set, generated ones included.
   *
   * @param classes the imported classes
   * @return the top-level classes, sorted by name
   */
  public static @NotNull Stream<JavaClass> topLevelClasses(@NotNull JavaClasses classes) {
    return classes.stream()
        .filter(javaClass -> !javaClass.getName().contains("$"))
        .sorted(Comparator.comparing(JavaClass::getName));
  }

  private static Optional<JavaClass> implementedMapper(JavaClass javaClass) {
    String name = javaClass.getSimpleName();
    if (!name.endsWith(IMPL_SUFFIX) || name.length() == IMPL_SUFFIX.length()) {
      return Optional.empty();
    }
    String mapperName =
        javaClass.getPackageName() + "." + name.substring(0, name.length() - IMPL_SUFFIX.length());
    return Stream.concat(
            javaClass.getRawSuperclass().stream(), javaClass.getRawInterfaces().stream())
        .filter(type -> type.getName().equals(mapperName))
        .filter(type -> type.isAnnotatedWith(MAPSTRUCT_MAPPER))
        .findFirst();
  }
}
