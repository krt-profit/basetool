import de.greluc.krt.profit.basetool.build.catalog
import de.greluc.krt.profit.basetool.build.requiredVersion

plugins { id("io.spring.dependency-management") }

val catalog = project.catalog()

extra["tomcat.version"] = "11.0.25"

extra["netty.version"] = "4.2.18.Final"

extra["jackson-2-bom.version"] = catalog.requiredVersion("jackson2")

extra["jackson-bom.version"] = catalog.requiredVersion("jackson3")
