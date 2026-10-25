plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies { testImplementation("junit:junit:4.13.2") }
sourceSets.test { resources.srcDir(rootProject.file("app/src/main/assets")) }
sourceSets.test { resources.srcDir(rootProject.file("test-fixtures")) }
