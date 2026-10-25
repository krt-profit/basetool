### Fixed

- Release-SBOMs (CycloneDX) tragen jetzt die echte Release-Version statt `0.0.1-SNAPSHOT`: `release-prepare.yml` übergibt die Version an Gradle (`-PreleaseVersion`), und `verifyCyclonedxBom` bricht ab, wenn die Version im SBOM nicht stimmt.
