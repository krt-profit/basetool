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

package de.greluc.krt.profit.basetool.frontend.contract;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * Finds the frontend's hand-written backend DTOs by their {@link DtoMirror} marker, so the DTO
 * contract tests select the same types wherever their domain package puts them (plan §5.9).
 */
public final class DtoMirrorScan {

  /** The package every frontend class lives under. */
  static final String FRONTEND_PACKAGE = "de.greluc.krt.profit.basetool.frontend";

  private DtoMirrorScan() {}

  /**
   * Returns every marked main type and every independent type nested in one, sorted by name; a
   * marked test fixture is not a mirror.
   *
   * @return the DTO mirror classes, top-level and nested, loaded and initialized
   */
  public static @NotNull @Unmodifiable List<Class<?>> types() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(DtoMirrorScan::isMirror);
    URL mainOutput = DtoMirror.class.getProtectionDomain().getCodeSource().getLocation();
    List<Class<?>> types = new ArrayList<>();
    for (BeanDefinition definition : scanner.findCandidateComponents(FRONTEND_PACKAGE)) {
      Class<?> type;
      try {
        type = Class.forName(definition.getBeanClassName());
      } catch (ClassNotFoundException ex) {
        throw new IllegalStateException(
            "Scanned DTO mirror could not be loaded: " + definition.getBeanClassName(), ex);
      }
      if (mainOutput.equals(type.getProtectionDomain().getCodeSource().getLocation())) {
        types.add(type);
      }
    }
    types.sort(Comparator.comparing(Class::getName));
    return List.copyOf(types);
  }

  /**
   * Returns the marked top-level types only.
   *
   * @return the top-level DTO mirror classes, sorted by name
   */
  public static @NotNull @Unmodifiable List<Class<?>> topLevelTypes() {
    return types().stream().filter(type -> type.getEnclosingClass() == null).toList();
  }

  /**
   * Whether a scanned class carries the marker itself or sits inside a class that does.
   *
   * @param reader the scanned class's metadata
   * @param factory the factory that reads an enclosing class's metadata
   * @return {@code true} for a marked class or one nested in a marked class
   * @throws IOException if an enclosing class's metadata cannot be read
   */
  private static boolean isMirror(@NotNull MetadataReader reader, MetadataReaderFactory factory)
      throws IOException {
    MetadataReader current = reader;
    while (true) {
      if (current.getAnnotationMetadata().hasAnnotation(DtoMirror.class.getName())) {
        return true;
      }
      String enclosing = current.getClassMetadata().getEnclosingClassName();
      if (enclosing == null) {
        return false;
      }
      current = factory.getMetadataReader(enclosing);
    }
  }
}
