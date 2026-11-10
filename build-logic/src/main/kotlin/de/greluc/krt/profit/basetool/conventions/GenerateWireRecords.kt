package de.greluc.krt.profit.basetool.conventions

import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Generates the explicitly documented flat-scalar frontend wire-record pilot. */
@CacheableTask
abstract class GenerateWireRecords : DefaultTask() {
  /** The backend OpenAPI contract. */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val openApiFile: RegularFileProperty

  /** The generated Java source root. */
  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  /** Validates the pilot's schema and writes its Java record with stable UTF-8/LF bytes. */
  @TaskAction
  fun generate() {
    val document = objectValue(JsonSlurper().parse(openApiFile.get().asFile, "UTF-8"), "OpenAPI")
    val components = objectValue(document["components"], "OpenAPI components")
    val schemas = objectValue(components["schemas"], "OpenAPI schemas")
    val rendered = MODELS.map { model -> model to render(model, schemas[model.name]) }
    val root = outputDirectory.get().asFile.toPath()
    for ((model, source) in rendered) {
      val directory = root.resolve(packageName(model).replace('.', '/'))
      Files.createDirectories(directory)
      Files.writeString(directory.resolve("${model.name}.java"), source, StandardCharsets.UTF_8)
    }
  }

  private fun render(model: WireModel, rawSchema: Any?): String {
    val schema = objectValue(rawSchema, model.name)
    rejectComposite(schema)
    if (
      schema["type"] != "object" ||
        (schema.containsKey("additionalProperties") && schema["additionalProperties"] != false)
    ) {
      throw GradleException("Missing flat object schema ${model.name}")
    }
    val properties = objectValue(schema["properties"], "${model.name} properties")
    if (properties.keys != model.fields.keys) {
      throw GradleException("Document the changed fields of ${model.name}")
    }
    val required = schema["required"] ?: emptyList<String>()
    if (required !is List<*> || required.any { it !is String || it !in properties }) {
      throw GradleException("Invalid required fields of ${model.name}")
    }
    val recordComponents =
      properties.entries.joinToString(", ") { (field, rawField) ->
        val fieldSchema = objectValue(rawField, "${model.name}.$field")
        val annotation = if (field in required) "@NotNull " else ""
        "$annotation${fieldType(fieldSchema)} $field"
      }
    return buildString {
      appendLine("/*")
      appendLine(" * Profit Basetool - squadron-management web app.")
      appendLine(" * Copyright (C) 2026 Lucas Greuloch")
      appendLine(" *")
      appendLine(" * SPDX-License-Identifier: GPL-3.0-only")
      appendLine(" */")
      appendLine()
      appendLine("package ${packageName(model)};")
      appendLine()
      appendLine("import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;")
      appendLine("import jakarta.validation.constraints.NotNull;")
      appendLine()
      appendLine("/**")
      appendLine(" * ${model.summary}")
      appendLine(" *")
      for (field in properties.keys) appendLine(" * @param $field ${model.fields[field]}")
      appendLine(" */")
      appendLine("@DtoMirror")
      appendLine("public record ${model.name}($recordComponents) {}")
    }
  }

  private fun fieldType(schema: Map<String, Any?>): String {
    rejectComposite(schema)
    val format = schema["format"]
    return when (schema["type"]) {
      "integer" ->
        when (format) {
          null,
          "int32" -> "Integer"
          "int64" -> "Long"
          else -> throw GradleException("Unsupported integer format $format")
        }
      "number" ->
        when (format) {
          null,
          "double" -> "Double"
          "float" -> "Float"
          else -> throw GradleException("Unsupported number format $format")
        }
      "boolean" -> {
        if (format != null) throw GradleException("Unsupported boolean format $format")
        "Boolean"
      }
      "string" -> {
        if (format != null) throw GradleException("Unsupported scalar format $format")
        "String"
      }
      else -> throw GradleException("Unsupported scalar type ${schema["type"]}")
    }
  }

  private fun rejectComposite(schema: Map<String, Any?>) {
    if (UNSUPPORTED_SHAPES.any(schema::containsKey)) {
      throw GradleException("The wire-record pilot requires a flat scalar contract")
    }
  }

  private fun objectValue(value: Any?, label: String): Map<String, Any?> {
    if (value !is Map<*, *>) {
      throw GradleException("Missing object $label")
    }
    return value.entries.associate { (key, item) ->
      if (key !is String) throw GradleException("Invalid object key in $label")
      key to item
    }
  }

  private fun packageName(model: WireModel): String =
    "de.greluc.krt.profit.basetool.frontend.${model.domain}.model"

  private data class WireModel(
    val name: String,
    val domain: String,
    val summary: String,
    val fields: Map<String, String>,
  )

  private companion object {
    val UNSUPPORTED_SHAPES =
      setOf("enum", "\$ref", "allOf", "oneOf", "anyOf", "not", "discriminator")
    val MODELS =
      listOf(
        WireModel(
          "PurchasingVersionRequest",
          "purchasing",
          "Carries the expected optimistic-lock version for a purchasing write.",
          mapOf("version" to "the echoed optimistic-lock version"),
        ),
      )
  }
}
