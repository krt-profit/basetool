plugins { id("basetool.java-conventions") }

tasks.javadoc {
  options { (this as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet") }
  destinationDir = project.file("docs/javadoc")
}
