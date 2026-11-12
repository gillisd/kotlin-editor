package cash.buildscript

/** One entry in a build script's plugins block. */
public sealed interface PluginDependency

/** A [PluginDependency] requested by id, such as `id("org.jetbrains.kotlin.jvm") version "2.2.21"` or `application`. */
public data class Plugin @JvmOverloads constructor(
  public val pluginId: PluginId,
  public val version: Version = UnspecifiedVersion,
) : PluginDependency

/** A [PluginDependency] requested through the version catalog, such as `alias(libs.plugins.kotlin.jvm)`. */
public data class CatalogPlugin(public val accessor: TypeSafeAccessor) : PluginDependency

/** A plugin's id, such as `org.jetbrains.kotlin.jvm`. */
public data class PluginId(public val id: String)
