package cash.buildscript

/** One declaration in a build script's dependencies block. */
public sealed interface Dependency {
  public val configuration: Configuration
  public val modifier: DependencyModifier
}

/** A [Dependency] on a library from a repository, such as `implementation("com.squareup.okio:okio:3.18.2")`. */
public data class ExternalModuleDependency @JvmOverloads constructor(
  override val configuration: Configuration,
  public val moduleVersion: ModuleVersion,
  override val modifier: DependencyModifier = DependencyModifier.Unmodified,
) : Dependency

/** A [Dependency] written through the version catalog, such as `implementation(libs.okio)`. */
public data class CatalogDependency @JvmOverloads constructor(
  override val configuration: Configuration,
  public val accessor: TypeSafeAccessor,
  override val modifier: DependencyModifier = DependencyModifier.Unmodified,
) : Dependency

/** A [Dependency] on another project in the same build, such as `implementation(project(":core"))`. */
public data class ProjectDependency @JvmOverloads constructor(
  override val configuration: Configuration,
  public val projectPath: ProjectPath,
  override val modifier: DependencyModifier = DependencyModifier.Unmodified,
) : Dependency

/** What a [Dependency] is grouped into, such as `implementation` or `testImplementation`. */
public data class Configuration(public val name: String)

/** What `platform(…)`, `enforcedPlatform(…)` or `testFixtures(…)` selects of the target; [Unmodified] when none. */
public enum class DependencyModifier { Unmodified, Platform, EnforcedPlatform, TestFixtures }

/** A [Module] at a [Version], written `group:name:version`. */
public data class ModuleVersion(public val module: Module, public val version: Version)

/** A library identified by its group and its name, with no [Version]. */
public data class Module(public val group: String, public val name: String)

/** Either a [RequiredVersion] or [UnspecifiedVersion]. */
public sealed interface Version

/** A plain version, such as `3.18.2`. */
public data class RequiredVersion(public val text: String) : Version

/** No version in the script: it is left to a platform, a constraint or, for a plugin, to Gradle itself. */
public data object UnspecifiedVersion : Version

/** The generated reference to a version catalog alias, such as `libs.okio` or `libs.plugins.kotlin.jvm`. */
public data class TypeSafeAccessor(public val path: String)

/** A project's path in the build, such as `:core`. */
public data class ProjectPath(public val path: String)
