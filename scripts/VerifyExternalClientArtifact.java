/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Verifies the external-client constructor qualifiers in a Docker-built backend boot jar. */
public final class VerifyExternalClientArtifact {
  private static final String CLASS_PREFIX =
      "BOOT-INF/classes/de/greluc/krt/profit/basetool/backend/catalogue/internal/";
  private static final List<String> CLIENTS = List.of("UexClient", "ScWikiClient");
  private static final String QUALIFIER_TYPE =
      "Lorg/springframework/beans/factory/annotation/Qualifier;";
  private static final String EXTERNAL_BUILDER = "externalRestClientBuilder";

  private VerifyExternalClientArtifact() {}

  /**
   * Checks the supplied boot jar, or exercises the checker against in-memory class-file fixtures.
   *
   * @param args one boot-jar path, or {@code --selftest}
   * @throws IOException when the artifact cannot be read
   * @throws IllegalStateException when a required constructor qualifier is absent or wrong
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      throw new IllegalArgumentException("Expected a backend boot-jar path or --selftest");
    }
    if (args[0].equals("--selftest")) {
      selftest();
      System.out.println("External-client artifact checker self-test passed");
      return;
    }
    try (InputStream archive = Files.newInputStream(Path.of(args[0]))) {
      verify(archive);
    }
    System.out.println("Docker-built external-client constructor qualifiers verified");
  }

  private static void verify(InputStream archive) throws IOException {
    var remaining = new HashSet<>(CLIENTS);
    try (var zip = new ZipInputStream(archive)) {
      for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
        for (String client : CLIENTS) {
          if (entry.getName().equals(CLASS_PREFIX + client + ".class")) {
            verifyConstructor(client, zip.readAllBytes());
            remaining.remove(client);
          }
        }
      }
    }
    if (!remaining.isEmpty()) {
      throw new IllegalStateException("Backend artifact is missing external clients: " + remaining);
    }
  }

  private static void verifyConstructor(String client, byte[] bytes) {
    List<MethodModel> constructors =
        ClassFile.of().parse(bytes).methods().stream()
            .filter(method -> method.methodName().equalsString("<init>"))
            .toList();
    if (constructors.size() != 1) {
      throw new IllegalStateException(client + " must have exactly one injection constructor");
    }
    var parameters =
        constructors.getFirst()
            .findAttribute(Attributes.runtimeVisibleParameterAnnotations())
            .map(RuntimeVisibleParameterAnnotationsAttribute::parameterAnnotations)
            .orElse(List.of());
    if (parameters.isEmpty()
        || parameters.getFirst().stream().noneMatch(VerifyExternalClientArtifact::isExternalBuilder)) {
      throw new IllegalStateException(
          client + " artifact constructor must select @Qualifier(\"" + EXTERNAL_BUILDER + "\")");
    }
  }

  private static boolean isExternalBuilder(Annotation annotation) {
    return annotation.className().equalsString(QUALIFIER_TYPE)
        && annotation.elements().stream()
            .anyMatch(
                element ->
                    element.name().equalsString("value")
                        && element.value() instanceof AnnotationValue.OfString value
                        && value.stringValue().equals(EXTERNAL_BUILDER));
  }

  private static void selftest() throws IOException {
    var correct = Optional.of(EXTERNAL_BUILDER);
    verify(new ByteArrayInputStream(fixture(Map.of("UexClient", correct, "ScWikiClient", correct))));
    expectRefusal(fixture(Map.of("UexClient", Optional.empty(), "ScWikiClient", correct)));
    expectRefusal(
        fixture(Map.of("UexClient", correct, "ScWikiClient", Optional.of("restClientBuilder"))));
    expectRefusal(fixture(Map.of("UexClient", correct)));
  }

  private static void expectRefusal(byte[] archive) throws IOException {
    try {
      verify(new ByteArrayInputStream(archive));
    } catch (IllegalStateException expected) {
      return;
    }
    throw new IllegalStateException("Checker accepted an artifact without both external qualifiers");
  }

  private static byte[] fixture(Map<String, Optional<String>> qualifiers) throws IOException {
    var archive = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(archive)) {
      for (var fixture : qualifiers.entrySet()) {
        String entry = CLASS_PREFIX + fixture.getKey() + ".class";
        String className =
            entry.substring("BOOT-INF/classes/".length(), entry.length() - ".class".length());
        byte[] bytes =
            ClassFile.of()
                .build(
                    ClassDesc.of(className.replace('/', '.')),
                    builder ->
                        builder.withMethod(
                            "<init>",
                            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object),
                            ClassFile.ACC_PUBLIC,
                            method -> {
                              fixture.getValue()
                                  .ifPresent(
                                      qualifier -> {
                                        Annotation selection =
                                            Annotation.of(
                                                ClassDesc.ofDescriptor(QUALIFIER_TYPE),
                                                AnnotationElement.ofString("value", qualifier));
                                        method.with(
                                            RuntimeVisibleParameterAnnotationsAttribute.of(
                                                List.of(List.of(selection))));
                                      });
                              method.withCode(
                                  code ->
                                      code.aload(0)
                                          .invokespecial(
                                              ConstantDescs.CD_Object,
                                              "<init>",
                                              MethodTypeDesc.of(ConstantDescs.CD_void))
                                          .return_());
                            }));
        zip.putNextEntry(new ZipEntry(entry));
        zip.write(bytes);
        zip.closeEntry();
      }
    }
    return archive.toByteArray();
  }
}
