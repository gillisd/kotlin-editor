package cash.recipes.plugins

import cash.grammar.kotlindsl.parse.KotlinParseException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class PluginVersionMutatorSpec {

  @Nested inner class `changing versions` {

    @Test fun `changes the version of an id plugin`() {
      // Given
      val buildScript =
        """
          plugins {
            id("org.graalvm.buildtools.native") version "1.1.17"
          }
        """.trimIndent()

      // When
      val mutator = PluginVersionMutator.of(buildScript, mapOf("org.graalvm.buildtools.native" to "1.1.18"))

      // Then
      assertThat(mutator.rewritten()).isEqualTo(
        """
          plugins {
            id("org.graalvm.buildtools.native") version "1.1.18"
          }
        """.trimIndent()
      )
    }

    @Test fun `changes the version of a kotlin plugin addressed by its full id`() {
      // Given
      val buildScript =
        """
          plugins {
            kotlin("jvm") version "2.2.0"
          }
        """.trimIndent()

      // When
      val mutator = PluginVersionMutator.of(buildScript, mapOf("org.jetbrains.kotlin.jvm" to "2.2.21"))

      // Then
      assertThat(mutator.rewritten()).isEqualTo(
        """
          plugins {
            kotlin("jvm") version "2.2.21"
          }
        """.trimIndent()
      )
      assertThat(mutator.changed()).containsExactly("org.jetbrains.kotlin.jvm")
    }

    @Test fun `keeps apply false after the version`() {
      // Given
      val buildScript =
        """
          plugins {
            id("com.example.plugin") version "1.0" apply false
          }
        """.trimIndent()

      // When
      val mutator = PluginVersionMutator.of(buildScript, mapOf("com.example.plugin" to "2.0"))

      // Then
      assertThat(mutator.rewritten()).isEqualTo(
        """
          plugins {
            id("com.example.plugin") version "2.0" apply false
          }
        """.trimIndent()
      )
    }

    @Test fun `changes several plugins in one pass`() {
      // Given
      val buildScript =
        """
          plugins {
            application
            kotlin("jvm") version "2.2.0"
            id("org.graalvm.buildtools.native") version "1.1.17"
            id("com.example.plugin") version "1.0" apply false
          }
        """.trimIndent()

      // When
      val mutator = PluginVersionMutator.of(
        buildScript,
        mapOf(
          "jvm" to "2.2.21",
          "org.graalvm.buildtools.native" to "1.1.18",
          "com.example.plugin" to "2.0",
        ),
      )

      // Then
      assertThat(mutator.rewritten()).isEqualTo(
        """
          plugins {
            application
            kotlin("jvm") version "2.2.21"
            id("org.graalvm.buildtools.native") version "1.1.18"
            id("com.example.plugin") version "2.0" apply false
          }
        """.trimIndent()
      )
    }

    @Test fun `ignores plugins blocks that are not top-level, such as pluginManagement`() {
      // Given a settings script with the plugin in pluginManagement and in its own plugins block
      val settingsScript =
        """
          pluginManagement {
            plugins {
              id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
            }
          }

          plugins {
            id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
          }
        """.trimIndent()

      // When
      val mutator = PluginVersionMutator.of(
        settingsScript,
        mapOf("org.gradle.toolchains.foojay-resolver-convention" to "1.0.0"),
      )

      // Then only the top-level block changes
      assertThat(mutator.rewritten()).isEqualTo(
        """
          pluginManagement {
            plugins {
              id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
            }
          }

          plugins {
            id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
          }
        """.trimIndent()
      )
    }
  }

  @Nested inner class `leaving things alone` {

    @Test fun `skips a plugin declared without a version`() {
      // Given
      val buildScript =
        """
          plugins {
            id("com.example.convention")
          }
        """.trimIndent()

      // When
      val mutator = PluginVersionMutator.of(buildScript, mapOf("com.example.convention" to "2.0"))

      // Then
      assertThat(mutator.isChanged()).isFalse()
      assertThat(mutator.rewritten()).isEqualTo(buildScript)
      assertThat(mutator.skipped()).containsEntry("com.example.convention", "no version declared")
    }

    @Test fun `skips a plugin applied through a version catalog alias`() {
      // Given
      val buildScript =
        """
          plugins {
            alias(libs.plugins.kotlin.jvm)
          }
        """.trimIndent()

      // When
      val mutator = PluginVersionMutator.of(buildScript, mapOf("libs.plugins.kotlin.jvm" to "2.2.21"))

      // Then
      assertThat(mutator.isChanged()).isFalse()
      assertThat(mutator.rewritten()).isEqualTo(buildScript)
      assertThat(mutator.skipped()).containsKey("libs.plugins.kotlin.jvm")
      assertThat(mutator.skipped()["libs.plugins.kotlin.jvm"]).contains("version catalog")
    }

    @Test fun `skips a version that is not a string literal`() {
      // Given one version from a variable and one from a string template
      val buildScript =
        """
          val kotlinVersion = "2.2.0"

          plugins {
            kotlin("jvm") version kotlinVersion
            id("com.example.plugin") version "${'$'}kotlinVersion"
          }
        """.trimIndent()

      // When
      val mutator = PluginVersionMutator.of(
        buildScript,
        mapOf("org.jetbrains.kotlin.jvm" to "2.2.21", "com.example.plugin" to "2.0"),
      )

      // Then
      assertThat(mutator.rewritten()).isEqualTo(buildScript)
      assertThat(mutator.skipped()).containsEntry("org.jetbrains.kotlin.jvm", "version is not a string literal")
      assertThat(mutator.skipped()).containsEntry("com.example.plugin", "version is not a string literal")
    }

    @Test fun `leaves the rest of the file byte-for-byte`() {
      // Given comments, odd spacing and other plugins around the one being changed
      val buildScript =
        """
          // build plugins
          plugins {
              `java-library`
            id(  "com.example.plugin"  )   version   "1.0"   apply false // pinned
            /* kotlin */ kotlin("jvm") version "2.2.0"
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
          }

        """.trimIndent()

      // When
      val mutator = PluginVersionMutator.of(buildScript, mapOf("com.example.plugin" to "2.0"))

      // Then only the version string changes
      assertThat(mutator.rewritten()).isEqualTo(buildScript.replace("\"1.0\"", "\"2.0\""))
    }
  }

  @Nested inner class `reporting what changed` {

    @Test fun `reports no change and returns the script byte-for-byte when already at that version`() {
      // Given
      val buildScript =
        """
          plugins {
            id("com.example.plugin") version "2.0"
          }
        """.trimIndent()

      // When
      val mutator = PluginVersionMutator.of(buildScript, mapOf("com.example.plugin" to "2.0"))

      // Then
      assertThat(mutator.isChanged()).isFalse()
      assertThat(mutator.rewritten()).isEqualTo(buildScript)
      assertThat(mutator.skipped()).containsEntry("com.example.plugin", "already at 2.0")
    }

    @Test fun `reports which plugins changed, which were skipped and why, and which were not found`() {
      // Given
      val buildScript =
        """
          plugins {
            id("com.example.plugin") version "1.0"
            id("com.example.convention")
          }
        """.trimIndent()

      // When
      val mutator = PluginVersionMutator.of(
        buildScript,
        mapOf("com.example.plugin" to "2.0", "com.example.convention" to "2.0", "com.example.missing" to "2.0"),
      )

      // Then
      assertThat(mutator.isChanged()).isTrue()
      assertThat(mutator.changed()).containsExactly("com.example.plugin")
      assertThat(mutator.skipped()).containsExactlyEntriesOf(mapOf("com.example.convention" to "no version declared"))
      assertThat(mutator.notFound()).containsExactly("com.example.missing")
    }
  }

  @Nested inner class `rejecting bad input` {

    @Test fun `throws when the script does not parse`() {
      // Given a script with an unclosed call
      val buildScript =
        """
          plugins {
            id("com.example.plugin" version "1.0"
          }
        """.trimIndent()

      // Then the parse errors surface when the result is asked for
      assertThatThrownBy { PluginVersionMutator.of(buildScript, mapOf("com.example.plugin" to "2.0")).rewritten() }
        .isInstanceOf(KotlinParseException::class.java)
    }

    @Test fun `throws when a plugin id or version is malformed`() {
      val buildScript = "plugins {}"

      listOf("", "com example", "com.example\"").forEach { badId ->
        assertThatThrownBy { PluginVersionMutator.of(buildScript, mapOf(badId to "1.0")) }
          .describedAs("id '%s'", badId)
          .isInstanceOf(IllegalArgumentException::class.java)
          .hasMessageContaining("Invalid plugin id")
      }
      listOf("", "1 .0", "\$v", "1.0\"").forEach { badVersion ->
        assertThatThrownBy { PluginVersionMutator.of(buildScript, mapOf("com.example.plugin" to badVersion)) }
          .describedAs("version '%s'", badVersion)
          .isInstanceOf(IllegalArgumentException::class.java)
          .hasMessageContaining("Invalid version")
      }
    }
  }
}
