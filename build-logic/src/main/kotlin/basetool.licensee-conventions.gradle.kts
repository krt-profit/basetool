import app.cash.licensee.UnusedAction
import de.greluc.krt.profit.basetool.build.OssLicensePolicy

plugins {
  id("basetool.java-conventions")
  id("app.cash.licensee")
}

licensee {
  OssLicensePolicy.allowedLicenses.forEach { allow(it) }
  OssLicensePolicy.urlAliases
    .filterValues { it in OssLicensePolicy.allowedLicenses }
    .forEach { (url, spdxId) -> allowUrl(url) { because("the linked text is $spdxId") } }
  OssLicensePolicy.coordinateOverrides.forEach { (coordinate, versionAndId) ->
    val (group, artifact) = coordinate.split(':')
    allowDependency(group, artifact, versionAndId.first) {
      because("POM names no usable licence; the jar's LICENSE.txt is ${versionAndId.second}")
    }
  }
  unusedAction(UnusedAction.IGNORE)
}

val licenseeReport = tasks.named<app.cash.licensee.LicenseeTask>("licensee")

val exportLicenseeReport =
  tasks.register<Copy>("exportLicenseeReport") {
    val reportName = "${project.name}.json"
    description = "Copies this module's Licensee report under the module's own name."
    from(licenseeReport.flatMap { it.jsonOutput })
    into(layout.buildDirectory.dir("licensee-export"))
    rename { reportName }
  }

configurations.consumable("ossLicenseReportElements") {
  val reportName = "${project.name}.json"
  attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("oss-license-report")) }
  outgoing.artifact(exportLicenseeReport.map { it.destinationDir.resolve(reportName) }) {
    builtBy(exportLicenseeReport)
  }
}
