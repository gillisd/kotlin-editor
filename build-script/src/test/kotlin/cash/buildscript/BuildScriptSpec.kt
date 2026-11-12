package cash.buildscript

import cash.buildscript.DependencyModifier.EnforcedPlatform
import cash.buildscript.DependencyModifier.Platform
import cash.buildscript.DependencyModifier.TestFixtures
import cash.buildscript.DependencyModifier.Unmodified
import cash.grammar.kotlindsl.parse.KotlinParseException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// Specs for the object layer. A spec stays @Disabled("pending") until it is written.
//
// Wording: a name in CamelCase (ExternalModuleDependency, ModuleVersion) is a type in the layer. A capitalized
// word that is not a type (Group, Name, Version Catalog) is a domain term. Kotlin does not allow a period or a
// colon in a test name, so strings are described, not quoted.
//
// A plugin has no plain string form: Kotlin would resolve a bare string to the wrong overload, so the short form for
// plugins takes a PluginId.
internal class BuildScriptSpec {

  @TempDir lateinit var directory: Path

  private val implementation = Configuration("implementation")
  private val okio = Module("com.squareup.okio", "okio")

  private val twoDependencies =
    """
      dependencies {
        implementation("com.squareup.okio:okio:3.18.2")
        testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
      }
    """.trimIndent()

  private val twoPlugins =
    """
      plugins {
        application
        kotlin("jvm") version "2.2.0"
      }
    """.trimIndent()

  private fun open(source: String): BuildScript {
    val file = directory.resolve("build.gradle.kts")
    Files.writeString(file, source)
    return BuildScript.open(file)
  }

  @Nested inner class `opening a build script` {

    @Test fun `lists each declaration in the dependencies block as a Dependency`() {
      val script = open(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.18.2")
            implementation(libs.picocli)
            implementation(project(":core"))
            testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
          }
        """.trimIndent()
      )

      assertThat(script.dependencies).containsExactly(
        ExternalModuleDependency(implementation, ModuleVersion(okio, RequiredVersion("3.18.2"))),
        CatalogDependency(implementation, TypeSafeAccessor("libs.picocli")),
        ProjectDependency(implementation, ProjectPath(":core")),
        ExternalModuleDependency(
          Configuration("testImplementation"),
          ModuleVersion(Module("org.junit.jupiter", "junit-jupiter"), RequiredVersion("6.1.3")),
        ),
      )
    }

    @Test fun `reads a declaration with a Group, a Name and a version as an ExternalModuleDependency on a ModuleVersion with a RequiredVersion`() {
      val script = open("""dependencies { implementation("com.squareup.okio:okio:3.18.2") }""")

      val dependency = script.dependencies.single() as ExternalModuleDependency
      assertThat(dependency.moduleVersion.module.group).isEqualTo("com.squareup.okio")
      assertThat(dependency.moduleVersion.module.name).isEqualTo("okio")
      assertThat(dependency.moduleVersion.version).isEqualTo(RequiredVersion("3.18.2"))
    }

    @Test fun `reads a declaration with a Group and a Name only as a ModuleVersion with UnspecifiedVersion`() {
      val script = open("""dependencies { testRuntimeOnly("org.junit.platform:junit-platform-launcher") }""")

      val dependency = script.dependencies.single() as ExternalModuleDependency
      assertThat(dependency.moduleVersion)
        .isEqualTo(ModuleVersion(Module("org.junit.platform", "junit-platform-launcher"), UnspecifiedVersion))
    }

    @Test fun `reads a declaration written through the Version Catalog as a CatalogDependency with its TypeSafeAccessor`() {
      val script = open("""dependencies { implementation(libs.okhttp) }""")

      assertThat(script.dependencies)
        .containsExactly(CatalogDependency(implementation, TypeSafeAccessor("libs.okhttp")))
    }

    @Test fun `reads project() as a ProjectDependency with its ProjectPath`() {
      val script = open("""dependencies { implementation(project(":core")) }""")

      assertThat(script.dependencies).containsExactly(ProjectDependency(implementation, ProjectPath(":core")))
    }

    @Test fun `reads platform(), enforcedPlatform() and testFixtures() as the DependencyModifier`() {
      val script = open(
        """
          dependencies {
            api(platform("tools.jackson:jackson-bom:3.3.0"))
            api(enforcedPlatform(libs.bom))
            testImplementation(testFixtures(project(":core")))
          }
        """.trimIndent()
      )

      assertThat(script.dependencies.map { it.modifier }).containsExactly(Platform, EnforcedPlatform, TestFixtures)
      assertThat(script.dependencies.map { it::class })
        .containsExactly(ExternalModuleDependency::class, CatalogDependency::class, ProjectDependency::class)
    }

    @Test fun `reads a declaration with no modifier as Unmodified`() {
      val script = open("""dependencies { implementation("com.squareup.okio:okio:3.18.2") }""")

      assertThat(script.dependencies.single().modifier).isEqualTo(Unmodified)
    }

    @Test fun `reads the Configuration of each Dependency`() {
      val script = open(
        """
          dependencies {
            api("com.squareup.okio:okio:3.18.2")
            testImplementation(libs.junit)
            "integrationTestImplementation"(project(":core"))
          }
        """.trimIndent()
      )

      assertThat(script.dependencies.map { it.configuration.name })
        .containsExactly("api", "testImplementation", "integrationTestImplementation")
    }

    @Test fun `does not list a declaration the layer does not model`() {
      val script = open(
        """
          val okioVersion = "3.18.2"

          dependencies {
            implementation(files("libs/local.jar"))
            implementation(projects.core)
            implementation("com.example:tool:1.0:all@zip")
            implementation("com.example:archive:1.0@zip")
            implementation("com.squareup.okio:okio:${'$'}okioVersion")
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
          }
        """.trimIndent()
      )

      assertThat(script.dependencies).containsExactly(
        ExternalModuleDependency(
          implementation,
          ModuleVersion(Module("com.squareup.okhttp3", "okhttp"), RequiredVersion("5.5.1")),
        ),
      )
    }

    @Test fun `lists each entry in the plugins block as a PluginDependency`() {
      val script = open(
        """
          plugins {
            application
            `java-library`
            kotlin("jvm") version "2.2.0"
            id("org.graalvm.buildtools.native") version "1.1.17"
            alias(libs.plugins.shadow)
          }
        """.trimIndent()
      )

      assertThat(script.pluginDependencies).containsExactly(
        Plugin(PluginId("application")),
        Plugin(PluginId("java-library")),
        Plugin(PluginId("org.jetbrains.kotlin.jvm"), RequiredVersion("2.2.0")),
        Plugin(PluginId("org.graalvm.buildtools.native"), RequiredVersion("1.1.17")),
        CatalogPlugin(TypeSafeAccessor("libs.plugins.shadow")),
      )
    }

    @Test fun `reads id() with a version as a Plugin with its PluginId and a RequiredVersion`() {
      val script = open("""plugins { id("com.example.plugin") version "1.0" apply false }""")

      assertThat(script.pluginDependencies)
        .containsExactly(Plugin(PluginId("com.example.plugin"), RequiredVersion("1.0")))
    }

    @Test fun `reads a plugin declared without a version as UnspecifiedVersion`() {
      val script = open("""plugins { id("com.example.convention") }""")

      assertThat((script.pluginDependencies.single() as Plugin).version).isEqualTo(UnspecifiedVersion)
    }

    @Test fun `reads alias() as a CatalogPlugin with its TypeSafeAccessor`() {
      val script = open("""plugins { alias(libs.plugins.kotlin.jvm) }""")

      assertThat(script.pluginDependencies)
        .containsExactly(CatalogPlugin(TypeSafeAccessor("libs.plugins.kotlin.jvm")))
    }

    @Test fun `does not list a plugin whose version is not a plain string`() {
      val script = open(
        """
          val kotlinVersion = "2.2.0"

          plugins {
            kotlin("jvm") version kotlinVersion
            application
          }
        """.trimIndent()
      )

      assertThat(script.pluginDependencies).containsExactly(Plugin(PluginId("application")))
    }

    @Test fun `ignores dependencies blocks and plugins blocks that are not top-level`() {
      val script = open(
        """
          pluginManagement {
            plugins {
              id("com.example.managed") version "1.0"
            }
          }

          buildscript {
            dependencies {
              classpath("com.example:gradle-plugin:1.0")
            }
          }

          plugins {
            application
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.18.2")
          }
        """.trimIndent()
      )

      assertThat(script.pluginDependencies).containsExactly(Plugin(PluginId("application")))
      assertThat(script.dependencies)
        .containsExactly(ExternalModuleDependency(implementation, ModuleVersion(okio, RequiredVersion("3.18.2"))))
    }

    @Test fun `returns the source byte-for-byte when nothing has changed`() {
      val source =
        """
          // the app
          plugins {
              application   // runs it
          }

          dependencies {
            implementation(  "com.squareup.okio:okio:3.18.2"  )


            runtimeOnly("org.xerial:sqlite-jdbc:3.54.1.0") {
              isTransitive = false
            }
          }

        """.trimIndent()

      assertThat(open(source).source).isEqualTo(source)
    }

    @Test fun `fails when the script does not parse`() {
      assertThatThrownBy { open("dependencies {\n  implementation(\"com.squareup.okio:okio:3.18.2\"\n}") }
        .isInstanceOf(KotlinParseException::class.java)
    }
  }

  @Nested inner class `adding a Dependency` {

    @Test fun `adds an ExternalModuleDependency after the last declaration on the same Configuration`() {
      val script = open(twoDependencies)

      script.add(ExternalModuleDependency(implementation, ModuleVersion(Module("com.squareup.okhttp3", "okhttp"), RequiredVersion("5.5.1"))))

      assertThat(script.source).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.18.2")
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
            testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
          }
        """.trimIndent()
      )
      assertThat(script.dependencies).hasSize(3)
    }

    @Test fun `adds a CatalogDependency`() {
      val script = open(twoDependencies)

      script.add(CatalogDependency(Configuration("testImplementation"), TypeSafeAccessor("libs.assertj")))

      assertThat(script.source).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.18.2")
            testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
            testImplementation(libs.assertj)
          }
        """.trimIndent()
      )
      assertThat(script.dependencies.last())
        .isEqualTo(CatalogDependency(Configuration("testImplementation"), TypeSafeAccessor("libs.assertj")))
    }

    @Test fun `adds a ProjectDependency`() {
      val script = open(twoDependencies)

      script.add(ProjectDependency(implementation, ProjectPath(":core")))

      assertThat(script.source).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.18.2")
            implementation(project(":core"))
            testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
          }
        """.trimIndent()
      )
    }

    @Test fun `writes the DependencyModifier around the dependency`() {
      val script = open(twoDependencies)

      script.add(ExternalModuleDependency(Configuration("api"), ModuleVersion(Module("tools.jackson", "jackson-bom"), RequiredVersion("3.3.0")), Platform))
      script.add(ProjectDependency(Configuration("testImplementation"), ProjectPath(":core"), TestFixtures))

      assertThat(script.source).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.18.2")
            testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
            testImplementation(testFixtures(project(":core")))
            api(platform("tools.jackson:jackson-bom:3.3.0"))
          }
        """.trimIndent()
      )
    }

    @Test fun `creates the dependencies block when the script has none`() {
      val script = open(twoPlugins)

      script.add(ExternalModuleDependency(implementation, ModuleVersion(okio, RequiredVersion("3.18.2"))))

      assertThat(script.source).isEqualTo(
        """
          plugins {
            application
            kotlin("jvm") version "2.2.0"
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.18.2")
          }
        """.trimIndent()
      )
    }

    @Test fun `adds the same Module again on a different Configuration`() {
      val script = open(twoDependencies)

      script.add(ExternalModuleDependency(Configuration("testImplementation"), ModuleVersion(okio, RequiredVersion("3.18.2"))))

      assertThat(script.dependencies.filterIsInstance<ExternalModuleDependency>().filter { it.moduleVersion.module == okio }.map { it.configuration.name })
        .containsExactly("implementation", "testImplementation")
    }

    @Test fun `changes nothing when the Module is on that Configuration at the same Version`() {
      val script = open(twoDependencies)

      script.add(ExternalModuleDependency(implementation, ModuleVersion(okio, RequiredVersion("3.18.2"))))
      // Asking for it with no Version is also satisfied by what is declared.
      script.add(ExternalModuleDependency(implementation, ModuleVersion(okio, UnspecifiedVersion)))

      assertThat(script.source).isEqualTo(twoDependencies)

      // A CatalogDependency or a ProjectDependency that is already declared is not written twice.
      script.add("libs.assertj", configuration = "testImplementation")
      script.add(":core")
      val once = script.source
      script.add("libs.assertj", configuration = "testImplementation")
      script.add(":core")

      assertThat(script.source).isEqualTo(once)
    }

    @Test fun `fails with ConflictingVersion, pointing to pin, when the Module is on that Configuration at a different Version`() {
      val script = open(twoDependencies)

      assertThatThrownBy { script.add(ExternalModuleDependency(implementation, ModuleVersion(okio, RequiredVersion("3.19.0")))) }
        .isInstanceOf(ConflictingVersion::class.java)
        .hasMessageContaining("com.squareup.okio:okio")
        .hasMessageContaining("at 3.18.2")
        .hasMessageContaining("pin")
      assertThat(script.source).isEqualTo(twoDependencies)
    }
  }

  @Nested inner class `adding a Dependency from a string` {

    @Test fun `reads three segments as a Group, a Name and a RequiredVersion`() {
      val script = open(twoDependencies)

      script.add("com.squareup.okhttp3:okhttp:5.5.1")

      assertThat(script.dependencies).contains(
        ExternalModuleDependency(implementation, ModuleVersion(Module("com.squareup.okhttp3", "okhttp"), RequiredVersion("5.5.1")))
      )
      assertThat(script.source).contains("""implementation("com.squareup.okhttp3:okhttp:5.5.1")""")
    }

    @Test fun `reads two segments as a Group and a Name with UnspecifiedVersion`() {
      val script = open(twoDependencies)

      script.add("org.junit.platform:junit-platform-launcher", configuration = "testRuntimeOnly")

      assertThat(script.dependencies).contains(
        ExternalModuleDependency(
          Configuration("testRuntimeOnly"),
          ModuleVersion(Module("org.junit.platform", "junit-platform-launcher"), UnspecifiedVersion),
        )
      )
      assertThat(script.source).contains("""testRuntimeOnly("org.junit.platform:junit-platform-launcher")""")
    }

    @Test fun `reads a dotted string with no colon as a TypeSafeAccessor`() {
      val script = open(twoDependencies)

      script.add("libs.okhttp")

      assertThat(script.dependencies).contains(CatalogDependency(implementation, TypeSafeAccessor("libs.okhttp")))
      assertThat(script.source).contains("implementation(libs.okhttp)")
    }

    @Test fun `reads a string that starts with a colon as a ProjectPath`() {
      val script = open(twoDependencies)

      script.add(":libs:network")

      assertThat(script.dependencies).contains(ProjectDependency(implementation, ProjectPath(":libs:network")))
      assertThat(script.source).contains("""implementation(project(":libs:network"))""")
    }

    @Test fun `takes the Version as a named argument instead of a third segment`() {
      val script = open(twoDependencies)

      script.add("com.squareup.okhttp3:okhttp", version = "5.5.1")

      assertThat(script.source).contains("""implementation("com.squareup.okhttp3:okhttp:5.5.1")""")
    }

    @Test fun `uses the implementation Configuration when none is named`() {
      val script = open(twoDependencies)

      script.add("com.squareup.okhttp3:okhttp:5.5.1")

      assertThat(script.dependencies.last { it is ExternalModuleDependency && it.moduleVersion.module.name == "okhttp" }.configuration)
        .isEqualTo(implementation)
    }

    @Test fun `uses the Configuration that is named`() {
      val script = open(twoDependencies)

      script.add("org.assertj:assertj-core:3.27.7", configuration = "testImplementation")

      assertThat(script.source).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.18.2")
            testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
            testImplementation("org.assertj:assertj-core:3.27.7")
          }
        """.trimIndent()
      )
    }

    @Test fun `uses Unmodified when no DependencyModifier is named`() {
      val script = open(twoDependencies)

      script.add("com.squareup.okhttp3:okhttp:5.5.1")

      assertThat(script.dependencies.map { it.modifier }).containsOnly(Unmodified)
    }

    @Test fun `uses the DependencyModifier that is named`() {
      val script = open(twoDependencies)

      script.add("tools.jackson:jackson-bom:3.3.0", configuration = "api", modifier = Platform)
      script.add("libs.bom", configuration = "api", modifier = EnforcedPlatform)

      assertThat(script.source).contains("""api(platform("tools.jackson:jackson-bom:3.3.0"))""")
      assertThat(script.source).contains("api(enforcedPlatform(libs.bom))")
    }

    @Test fun `writes the string as given without checking that it resolves`() {
      val script = open(twoDependencies)

      // A Version where the Name should be, and an accessor no catalog defines: both are written as given.
      script.add("com.squareup.okio:3.18.2", configuration = "api")
      script.add("libs.does.not.exist")

      assertThat(script.source).contains("""api("com.squareup.okio:3.18.2")""")
      assertThat(script.source).contains("implementation(libs.does.not.exist)")
    }

    @Test fun `fails with UnreadableDependency when the string has none of the four shapes`() {
      val script = open(twoDependencies)

      listOf("okio", "", "a:b:c:d", "a::1", "com.squareup okio:okio", ":", "a:b:1 0").forEach { unreadable ->
        assertThatThrownBy { script.add(unreadable) }
          .describedAs("'%s'", unreadable)
          .isInstanceOf(UnreadableDependency::class.java)
      }
      assertThat(script.source).isEqualTo(twoDependencies)
    }

    @Test fun `fails with UnreadableDependency when the Version is given both in the string and as a named argument`() {
      val script = open(twoDependencies)

      assertThatThrownBy { script.add("com.squareup.okhttp3:okhttp:5.5.1", version = "5.5.1") }
        .isInstanceOf(UnreadableDependency::class.java)
        .hasMessageContaining("twice")
    }

    @Test fun `fails with UnreadableDependency when a Version is named for a TypeSafeAccessor or a ProjectPath`() {
      val script = open(twoDependencies)

      assertThatThrownBy { script.add("libs.okhttp", version = "5.5.1") }.isInstanceOf(UnreadableDependency::class.java)
      assertThatThrownBy { script.add(":core", version = "1.0") }.isInstanceOf(UnreadableDependency::class.java)
      assertThat(script.source).isEqualTo(twoDependencies)
    }
  }

  @Nested inner class `pinning a ModuleVersion` {

    @Test fun `sets the Version of that Module on every Configuration it is declared on`() {
      val script = open(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
            testImplementation("com.squareup.okio:okio:3.8.0")
          }
        """.trimIndent()
      )

      script.pin(ModuleVersion(okio, RequiredVersion("3.18.2")))

      assertThat(script.source).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.18.2")
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
            testImplementation("com.squareup.okio:okio:3.18.2")
          }
        """.trimIndent()
      )
      assertThat(script.dependencies.filterIsInstance<ExternalModuleDependency>().filter { it.moduleVersion.module == okio }.map { it.moduleVersion.version })
        .containsExactly(RequiredVersion("3.18.2"), RequiredVersion("3.18.2"))
    }

    @Test fun `accepts a ModuleVersion or its string form`() {
      val fromParts = open(twoDependencies)
      val fromString = open(twoDependencies)

      fromParts.pin(ModuleVersion(okio, RequiredVersion("3.19.0")))
      fromString.pin("com.squareup.okio:okio:3.19.0")

      assertThat(fromString.source).isEqualTo(fromParts.source)
      assertThat(fromString.source).contains("""implementation("com.squareup.okio:okio:3.19.0")""")
    }

    @Test fun `takes the Version as a named argument instead of a third segment`() {
      val script = open(twoDependencies)

      script.pin("com.squareup.okio:okio", version = "3.19.0")

      assertThat(script.source).contains("""implementation("com.squareup.okio:okio:3.19.0")""")
    }

    @Test fun `keeps the DependencyModifier, a trailing block and comments`() {
      val script = open(
        """
          dependencies {
            // the bill of materials
            api(platform("tools.jackson:jackson-bom:3.2.0")) // pinned by hand
            implementation("com.squareup.okio:okio:3.9.0") {
              exclude(group = "org.jetbrains.kotlin")
            }
          }
        """.trimIndent()
      )

      script.pin("tools.jackson:jackson-bom:3.3.0")
      script.pin("com.squareup.okio:okio:3.18.2")

      assertThat(script.source).isEqualTo(
        """
          dependencies {
            // the bill of materials
            api(platform("tools.jackson:jackson-bom:3.3.0")) // pinned by hand
            implementation("com.squareup.okio:okio:3.18.2") {
              exclude(group = "org.jetbrains.kotlin")
            }
          }
        """.trimIndent()
      )
      assertThat(script.dependencies.first().modifier).isEqualTo(Platform)
    }

    @Test fun `changes nothing when the Module is already at that Version`() {
      val script = open(twoDependencies)

      script.pin("com.squareup.okio:okio:3.18.2")

      assertThat(script.source).isEqualTo(twoDependencies)
    }

    @Test fun `writes the Version when the Module is declared without one`() {
      val script = open(
        """
          dependencies {
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")
          }
        """.trimIndent()
      )

      script.pin("org.junit.platform:junit-platform-launcher", version = "6.1.3")

      assertThat(script.source).contains("""testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")""")
    }

    @Test fun `fails with UndeclaredDependency when the Module is not declared`() {
      val script = open(twoDependencies)

      assertThatThrownBy { script.pin("com.squareup.okhttp3:okhttp:5.5.1") }
        .isInstanceOf(UndeclaredDependency::class.java)
        .hasMessageContaining("com.squareup.okhttp3:okhttp")
      assertThat(script.source).isEqualTo(twoDependencies)
    }

    @Test fun `points to the Version Catalog when the Module is not declared and the script has a CatalogDependency`() {
      // The layer does not read the catalog, so it cannot tell which Module libs.okhttp is.
      val script = open(
        """
          dependencies {
            implementation(libs.okhttp)
          }
        """.trimIndent()
      )

      assertThatThrownBy { script.pin("com.squareup.okhttp3:okhttp:5.5.1") }
        .isInstanceOf(UndeclaredDependency::class.java)
        .hasMessageContaining("Version Catalog")
    }

    @Test fun `fails with VersionInCatalog when given a TypeSafeAccessor`() {
      val script = open(
        """
          dependencies {
            implementation(libs.okhttp)
          }
        """.trimIndent()
      )

      assertThatThrownBy { script.pin("libs.okhttp", version = "5.5.1") }
        .isInstanceOf(VersionInCatalog::class.java)
        .hasMessageContaining("libs.okhttp")
      // Whether or not the script declares it.
      assertThatThrownBy { script.pin("libs.okio", version = "3.18.2") }.isInstanceOf(VersionInCatalog::class.java)
    }

    @Test fun `fails with MissingVersion when no Version is given, in the string or by name`() {
      val script = open(twoDependencies)

      assertThatThrownBy { script.pin("com.squareup.okio:okio") }.isInstanceOf(MissingVersion::class.java)
      assertThatThrownBy { script.pin(ModuleVersion(okio, UnspecifiedVersion)) }.isInstanceOf(MissingVersion::class.java)
      assertThat(script.source).isEqualTo(twoDependencies)
    }

    @Test fun `fails with UnreadableDependency when the Version is given twice or the string is not a ModuleVersion`() {
      val script = open(twoDependencies)

      assertThatThrownBy { script.pin("com.squareup.okio:okio:3.19.0", version = "3.19.0") }
        .isInstanceOf(UnreadableDependency::class.java)
        .hasMessageContaining("twice")
      assertThatThrownBy { script.pin(":core") }.isInstanceOf(UnreadableDependency::class.java)
      assertThatThrownBy { script.pin("okio", version = "3.19.0") }.isInstanceOf(UnreadableDependency::class.java)
      assertThat(script.source).isEqualTo(twoDependencies)
    }
  }

  @Nested inner class `removing a Dependency` {

    @Disabled("pending") @Test fun `removes the declaration and its line`() {}

    @Disabled("pending") @Test fun `matches on the Group and the Name, whatever the Version`() {}

    @Disabled("pending") @Test fun `removes from the named Configuration only`() {}

    @Disabled("pending") @Test fun `removes from every Configuration when none is named`() {}

    @Disabled("pending") @Test fun `removes a CatalogDependency`() {}

    @Disabled("pending") @Test fun `removes a ProjectDependency`() {}

    @Disabled("pending") @Test fun `fails with UndeclaredDependency when it is not declared`() {}
  }

  @Nested inner class `adding a PluginDependency` {

    @Test fun `adds a Plugin to the plugins block`() {
      val script = open(twoPlugins)

      script.add(Plugin(PluginId("org.graalvm.buildtools.native"), RequiredVersion("1.1.18")))

      assertThat(script.source).isEqualTo(
        """
          plugins {
            application
            kotlin("jvm") version "2.2.0"
            id("org.graalvm.buildtools.native") version "1.1.18"
          }
        """.trimIndent()
      )
      assertThat(script.pluginDependencies.last())
        .isEqualTo(Plugin(PluginId("org.graalvm.buildtools.native"), RequiredVersion("1.1.18")))
    }

    @Test fun `adds a CatalogPlugin`() {
      val script = open(twoPlugins)

      script.add(CatalogPlugin(TypeSafeAccessor("libs.plugins.shadow")))
      script.add(CatalogPlugin(TypeSafeAccessor("libs.plugins.shadow")))

      assertThat(script.source).isEqualTo(
        """
          plugins {
            application
            kotlin("jvm") version "2.2.0"
            alias(libs.plugins.shadow)
          }
        """.trimIndent()
      )
    }

    @Test fun `creates the plugins block when the script has none`() {
      val script = open(twoDependencies)

      script.add(Plugin(PluginId("application")))

      assertThat(script.source).isEqualTo(
        """
          plugins {
            id("application")
          }

        """.trimIndent() + "\n" + twoDependencies
      )
    }

    @Test fun `changes nothing when that PluginId is there at the same Version`() {
      val script = open(twoPlugins)

      script.add(Plugin(PluginId("org.jetbrains.kotlin.jvm"), RequiredVersion("2.2.0")))
      script.add(Plugin(PluginId("application")))
      // Asking for it with no Version is also satisfied by what is declared.
      script.add(Plugin(PluginId("org.jetbrains.kotlin.jvm")))

      assertThat(script.source).isEqualTo(twoPlugins)
    }

    @Test fun `fails with ConflictingVersion, pointing to pin, when that PluginId is there at a different Version`() {
      val script = open(twoPlugins)

      assertThatThrownBy { script.add(Plugin(PluginId("org.jetbrains.kotlin.jvm"), RequiredVersion("2.2.21"))) }
        .isInstanceOf(ConflictingVersion::class.java)
        .hasMessageContaining("org.jetbrains.kotlin.jvm")
        .hasMessageContaining("at 2.2.0")
        .hasMessageContaining("pin")
      // A plugin declared with no Version conflicts with a request for one.
      assertThatThrownBy { script.add(Plugin(PluginId("application"), RequiredVersion("1.0"))) }
        .isInstanceOf(ConflictingVersion::class.java)
      assertThat(script.source).isEqualTo(twoPlugins)
    }
  }

  @Nested inner class `pinning a Plugin` {

    @Test fun `sets the Version of that PluginId`() {
      val script = open(twoPlugins)

      script.pin(Plugin(PluginId("org.jetbrains.kotlin.jvm"), RequiredVersion("2.2.21")))

      assertThat(script.source).isEqualTo(
        """
          plugins {
            application
            kotlin("jvm") version "2.2.21"
          }
        """.trimIndent()
      )
      assertThat(script.pluginDependencies.last())
        .isEqualTo(Plugin(PluginId("org.jetbrains.kotlin.jvm"), RequiredVersion("2.2.21")))
    }

    @Test fun `keeps apply false and everything else on the line`() {
      val script = open(
        """
          plugins {
            id("com.example.plugin") version "1.0" apply false // for the subprojects
          }
        """.trimIndent()
      )

      script.pin(PluginId("com.example.plugin"), version = "2.0")

      assertThat(script.source).isEqualTo(
        """
          plugins {
            id("com.example.plugin") version "2.0" apply false // for the subprojects
          }
        """.trimIndent()
      )
    }

    @Test fun `changes nothing when the Plugin is already at that Version`() {
      val script = open(twoPlugins)

      script.pin(PluginId("org.jetbrains.kotlin.jvm"), version = "2.2.0")

      assertThat(script.source).isEqualTo(twoPlugins)
    }

    @Test fun `fails with UndeclaredPlugin when that PluginId is not declared`() {
      val script = open(twoPlugins)

      assertThatThrownBy { script.pin(PluginId("com.example.plugin"), version = "2.0") }
        .isInstanceOf(UndeclaredPlugin::class.java)
        .hasMessageContaining("com.example.plugin")
      assertThat(script.source).isEqualTo(twoPlugins)
    }

    @Test fun `fails with MissingVersion when the Plugin is declared without a Version`() {
      val script = open(twoPlugins)

      assertThatThrownBy { script.pin(PluginId("application"), version = "1.0") }
        .isInstanceOf(MissingVersion::class.java)
        .hasMessageContaining("declared without a Version")
      assertThat(script.source).isEqualTo(twoPlugins)
    }

    @Test fun `fails with MissingVersion when the Plugin given has UnspecifiedVersion`() {
      val script = open(twoPlugins)

      assertThatThrownBy { script.pin(Plugin(PluginId("org.jetbrains.kotlin.jvm"))) }
        .isInstanceOf(MissingVersion::class.java)
    }

    @Test fun `points to the Version Catalog when that PluginId is not declared and the script has a CatalogPlugin`() {
      // The layer does not read the catalog, so it cannot tell which PluginId libs.plugins.shadow is.
      val script = open(
        """
          plugins {
            alias(libs.plugins.shadow)
          }
        """.trimIndent()
      )

      assertThatThrownBy { script.pin(PluginId("com.gradleup.shadow"), version = "9.4.1") }
        .isInstanceOf(UndeclaredPlugin::class.java)
        .hasMessageContaining("Version Catalog")
    }
  }

  @Nested inner class `removing a PluginDependency` {

    @Disabled("pending") @Test fun `removes the entry and its line`() {}

    @Disabled("pending") @Test fun `fails with UndeclaredPlugin when it is not declared`() {}
  }

  @Nested inner class `naming a plugin by its PluginId` {

    @Test fun `adds a Plugin from a PluginId and a named Version`() {
      val script = open(twoPlugins)

      script.add(PluginId("com.example.plugin"), version = "2.0")

      assertThat(script.source).contains("""id("com.example.plugin") version "2.0"""")
      assertThat(script.pluginDependencies).contains(Plugin(PluginId("com.example.plugin"), RequiredVersion("2.0")))
    }

    @Test fun `adds a Plugin from a PluginId alone, with UnspecifiedVersion`() {
      val script = open(twoPlugins)

      script.add(PluginId("com.example.convention"))

      assertThat(script.source).contains("""id("com.example.convention")""")
      assertThat(script.pluginDependencies).contains(Plugin(PluginId("com.example.convention"), UnspecifiedVersion))
    }

    @Test fun `pins a Plugin from a PluginId and a named Version`() {
      val script = open(twoPlugins)

      script.pin(PluginId("org.jetbrains.kotlin.jvm"), version = "2.2.21")

      assertThat(script.source).contains("""kotlin("jvm") version "2.2.21"""")
    }

    @Disabled("pending") @Test fun `removes a Plugin from a PluginId alone, whatever its Version`() {}
  }

  @Nested inner class `keeping the rest of the script as written` {

    @Disabled("pending") @Test fun `leaves every line an operation did not change byte-for-byte`() {}

    @Disabled("pending") @Test fun `leaves a declaration the layer does not model as written`() {}

    @Disabled("pending") @Test fun `leaves the script unchanged when an operation fails`() {}
  }

  @Nested inner class `saving` {

    @Disabled("pending") @Test fun `writes the source back to the file it was opened from`() {}

    @Disabled("pending") @Test fun `leaves the file untouched when nothing has changed`() {}

    @Disabled("pending") @Test fun `applies several operations in the order they were called`() {}

    @Disabled("pending") @Test fun `reads the same parts after saving and opening again`() {}
  }
}
