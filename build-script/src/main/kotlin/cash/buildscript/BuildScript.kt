package cash.buildscript

import cash.grammar.kotlindsl.model.DependencyDeclaration
import cash.grammar.kotlindsl.model.DependencyDeclaration.Capability
import cash.grammar.kotlindsl.parse.KotlinParseException
import cash.grammar.kotlindsl.parse.Parser
import cash.grammar.kotlindsl.utils.Blocks.isDependencies
import cash.grammar.kotlindsl.utils.Blocks.isPlugins
import cash.grammar.kotlindsl.utils.CollectingErrorListener
import cash.grammar.kotlindsl.utils.Context.leafRule
import cash.grammar.kotlindsl.utils.DependencyExtractor
import cash.grammar.kotlindsl.utils.PluginExtractor
import cash.grammar.kotlindsl.utils.Whitespace
import cash.recipes.dependencies.DependencyAdder
import cash.recipes.dependencies.DependencyRemover
import cash.recipes.dependencies.DependencyVersionMutator
import cash.recipes.dependencies.transform.Transform
import cash.recipes.plugins.PluginAdder
import cash.recipes.plugins.PluginRemover
import cash.recipes.plugins.PluginVersionMutator
import com.squareup.cash.grammar.KotlinParser.NamedBlockContext
import com.squareup.cash.grammar.KotlinParser.ScriptContext
import com.squareup.cash.grammar.KotlinParserBaseListener
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CommonTokenStream
import java.nio.file.Files
import java.nio.file.Path
import cash.grammar.kotlindsl.model.Plugin as PluginDeclaration

/**
 * A Gradle Kotlin DSL build script, read into the [dependencies] and [pluginDependencies] it declares.
 *
 * Only what the layer models is listed. A declaration it does not model is left out of the lists and stays in
 * [source] as written: a file dependency, a type-safe project accessor, a classifier or extension, and a version
 * that is not a plain string.
 */
public class BuildScript private constructor(private val file: Path, source: String) {

  /** The script's current text. */
  public var source: String = source
    private set

  // The text that is in the file.
  private var saved: String = source

  private var declared: Declared = read(source)

  /** The declarations in the top-level dependencies block, in the order written. */
  public val dependencies: List<Dependency> get() = declared.dependencies

  /** The entries in the top-level plugins block, in the order written. */
  public val pluginDependencies: List<PluginDependency> get() = declared.pluginDependencies

  private class Declared(val dependencies: List<Dependency>, val pluginDependencies: List<PluginDependency>)

  /**
   * Adds [dependency] after the last declaration on the same [Configuration]. Changes nothing when it is already
   * declared there.
   *
   * @throws ConflictingVersion if its [Module] is declared there at a different [Version]; use `pin` to change it.
   * @throws UnreadableDependency if a name in it cannot be written.
   */
  public fun add(dependency: Dependency) {
    if (dependency is ExternalModuleDependency) {
      val wanted = dependency.moduleVersion
      val declared = dependencies.filterIsInstance<ExternalModuleDependency>().firstOrNull {
        it.configuration == dependency.configuration && it.modifier == dependency.modifier &&
          it.moduleVersion.module == wanted.module
      }
      if (declared != null) {
        if (wanted.version is RequiredVersion && wanted.version != declared.moduleVersion.version) {
          throw ConflictingVersion(
            "${wanted.module.written()} is declared on ${dependency.configuration.name} " +
              "${declared.moduleVersion.version.written()}, not ${wanted.version.written()}. " +
              "Use pin to change its Version."
          )
        }
        return
      }
    }

    val written = when (dependency) {
      is ExternalModuleDependency -> Transform.Element.StringLiteral(dependency.moduleVersion.written())
      is CatalogDependency -> Transform.Element.Accessor(dependency.accessor.path)
      is ProjectDependency -> Transform.Element.StringLiteral(dependency.projectPath.path)
    }
    val capability = when (dependency.modifier) {
      DependencyModifier.Unmodified -> Capability.DEFAULT
      DependencyModifier.Platform -> Capability.PLATFORM
      DependencyModifier.EnforcedPlatform -> Capability.ENFORCED_PLATFORM
      DependencyModifier.TestFixtures -> Capability.TEST_FIXTURES
    }
    val addition = DependencyAdder.Addition(dependency.configuration.name, written, capability)
    rewrite { DependencyAdder.of(source, listOf(addition)).rewritten() }
  }

  /**
   * Adds the dependency that [dependency] describes by its shape: `group:name:version`, `group:name`, a type-safe
   * accessor such as `libs.okio`, or a project path such as `:core`. The [version] may be named instead of written
   * as the third segment.
   *
   * @throws UnreadableDependency if the string has none of those shapes, if the version is given twice, or if a
   *   version is named for an accessor or a project path.
   * @throws ConflictingVersion if its [Module] is declared there at a different [Version].
   */
  @JvmOverloads
  public fun add(
    dependency: String,
    version: String? = null,
    configuration: String = "implementation",
    modifier: DependencyModifier = DependencyModifier.Unmodified,
  ) {
    val named = Configuration(configuration)
    add(
      when (val shape = shapeOf(dependency, version)) {
        is ModuleVersion -> ExternalModuleDependency(named, shape, modifier)
        is TypeSafeAccessor -> CatalogDependency(named, shape, modifier)
        is ProjectPath -> ProjectDependency(named, shape, modifier)
        else -> error("unreachable")
      }
    )
  }

  /**
   * Adds [pluginDependency] after the last entry in the plugins block. Changes nothing when it is already there.
   *
   * @throws ConflictingVersion if that [PluginId] is declared at a different [Version]; use `pin` to change it.
   * @throws UnreadableDependency if a name in it cannot be written.
   */
  public fun add(pluginDependency: PluginDependency) {
    val addition = when (pluginDependency) {
      is CatalogPlugin -> PluginAdder.Addition.ByAlias(pluginDependency.accessor.path)
      is Plugin -> {
        val declared = pluginDependencies.filterIsInstance<Plugin>().firstOrNull { it.pluginId == pluginDependency.pluginId }
        if (declared != null) {
          if (pluginDependency.version is RequiredVersion && pluginDependency.version != declared.version) {
            throw ConflictingVersion(
              "${pluginDependency.pluginId.id} is declared ${declared.version.written()}, " +
                "not ${pluginDependency.version.written()}. Use pin to change its Version."
            )
          }
          return
        }
        PluginAdder.Addition.ById(pluginDependency.pluginId.id, (pluginDependency.version as? RequiredVersion)?.text)
      }
    }
    rewrite { PluginAdder.of(source, listOf(addition)).rewritten() }
  }

  /** Adds the [Plugin] with this [pluginId], at [version] when one is named. */
  @JvmOverloads
  public fun add(pluginId: PluginId, version: String? = null) {
    add(Plugin(pluginId, version?.let(::RequiredVersion) ?: UnspecifiedVersion))
  }

  /**
   * Sets the [Version] of [moduleVersion]'s [Module] on every [Configuration] it is declared on. A declaration with
   * no version gets one. Changes nothing when it is already at that version.
   *
   * @throws MissingVersion if [moduleVersion] has no [RequiredVersion].
   * @throws UndeclaredDependency if the script does not declare that [Module] as an [ExternalModuleDependency].
   */
  public fun pin(moduleVersion: ModuleVersion) {
    val module = moduleVersion.module
    val wanted = moduleVersion.version as? RequiredVersion
      ?: throw MissingVersion("pin needs a Version for ${module.written()}.")
    if (dependencies.none { it is ExternalModuleDependency && it.moduleVersion.module == module }) {
      val hint = if (dependencies.any { it is CatalogDependency }) {
        " If it is declared through a type-safe accessor, its Version is in the Version Catalog."
      } else {
        ""
      }
      throw UndeclaredDependency("${module.written()} is not declared.$hint")
    }
    rewrite { DependencyVersionMutator.of(source, mapOf(module.written() to wanted.text)).rewritten() }
  }

  /**
   * Pins the [ModuleVersion] that [moduleVersion] describes: `group:name:version`, or `group:name` with the
   * [version] named.
   *
   * @throws VersionInCatalog if the string is a type-safe accessor: its version is in the Version Catalog.
   * @throws MissingVersion if no version is given, in the string or by name.
   * @throws UnreadableDependency if the string is neither shape, or if the version is given twice.
   * @throws UndeclaredDependency if the script does not declare that [Module].
   */
  @JvmOverloads
  public fun pin(moduleVersion: String, version: String? = null) {
    if (accessorPattern.matches(moduleVersion)) {
      throw VersionInCatalog("The Version of $moduleVersion is in the Version Catalog; change it there.")
    }
    when (val shape = shapeOf(moduleVersion, version)) {
      is ModuleVersion -> pin(shape)
      else -> throw UnreadableDependency("Cannot pin '$moduleVersion': a project path has no Version.")
    }
  }

  /**
   * Sets the [Version] of the declared [Plugin] with [plugin]'s [PluginId]. Changes nothing when it is already at
   * that version.
   *
   * @throws MissingVersion if [plugin] has no [RequiredVersion], or if the script declares it without one.
   * @throws UndeclaredPlugin if the script does not declare that [PluginId].
   */
  public fun pin(plugin: Plugin) {
    val id = plugin.pluginId.id
    val wanted = plugin.version as? RequiredVersion ?: throw MissingVersion("pin needs a Version for $id.")
    val declared = pluginDependencies.filterIsInstance<Plugin>().firstOrNull { it.pluginId == plugin.pluginId }
    if (declared == null) {
      val hint = if (pluginDependencies.any { it is CatalogPlugin }) {
        " If it is declared through alias(…), its Version is in the Version Catalog."
      } else {
        ""
      }
      throw UndeclaredPlugin("$id is not declared.$hint")
    }
    if (declared.version !is RequiredVersion) {
      throw MissingVersion("$id is declared without a Version, so there is none to pin.")
    }
    rewrite { PluginVersionMutator.of(source, mapOf(id to wanted.text)).rewritten() }
  }

  /** Pins the [Plugin] with this [pluginId] at [version]. */
  public fun pin(pluginId: PluginId, version: String) {
    pin(Plugin(pluginId, RequiredVersion(version)))
  }

  /**
   * Removes [dependency] from its [Configuration], with its line. An [ExternalModuleDependency] is matched on its
   * [Module], whatever the [Version].
   *
   * @throws UndeclaredDependency if it is not declared on that [Configuration].
   */
  public fun remove(dependency: Dependency) {
    val named = when (dependency) {
      is ExternalModuleDependency -> Transform.Element.StringLiteral(dependency.moduleVersion.module.written())
      is CatalogDependency -> Transform.Element.Accessor(dependency.accessor.path)
      is ProjectDependency -> Transform.Element.StringLiteral(dependency.projectPath.path)
    }
    remove(named, dependency.configuration)
  }

  /**
   * Removes the dependency that [dependency] describes by its shape, as `add` reads it; a version in the string is
   * ignored. It is removed from the [configuration] that is named, or from every one when none is.
   *
   * @throws UnreadableDependency if the string has none of the shapes.
   * @throws UndeclaredDependency if it is not declared.
   */
  @JvmOverloads
  public fun remove(dependency: String, configuration: String? = null) {
    val named = when (val shape = shapeOf(dependency, version = null)) {
      is ModuleVersion -> Transform.Element.StringLiteral(shape.module.written())
      is TypeSafeAccessor -> Transform.Element.Accessor(shape.path)
      is ProjectPath -> Transform.Element.StringLiteral(shape.path)
      else -> error("unreachable")
    }
    remove(named, configuration?.let(::Configuration))
  }

  private fun remove(named: Transform.Element, configuration: Configuration?) {
    val remover = recipe { DependencyRemover.of(source, listOf(DependencyRemover.Removal(named, configuration?.name))) }
    if (remover.notFound().isNotEmpty()) {
      val where = configuration?.let { " on ${it.name}" } ?: ""
      throw UndeclaredDependency("${named.value()} is not declared$where.")
    }
    rewrite { remover.rewritten() }
  }

  /**
   * Removes [pluginDependency] from the plugins block, with its line. A [Plugin] is matched on its [PluginId],
   * whatever the [Version].
   *
   * @throws UndeclaredPlugin if it is not declared.
   */
  public fun remove(pluginDependency: PluginDependency) {
    val removal = when (pluginDependency) {
      is Plugin -> PluginRemover.Removal.ById(pluginDependency.pluginId.id)
      is CatalogPlugin -> PluginRemover.Removal.ByAlias(pluginDependency.accessor.path)
    }
    val remover = recipe { PluginRemover.of(source, listOf(removal)) }
    if (remover.notFound().isNotEmpty()) {
      val name = when (pluginDependency) {
        is Plugin -> pluginDependency.pluginId.id
        is CatalogPlugin -> pluginDependency.accessor.path
      }
      throw UndeclaredPlugin("$name is not declared.")
    }
    rewrite { remover.rewritten() }
  }

  /** Removes the [Plugin] with this [pluginId], whatever its [Version]. */
  public fun remove(pluginId: PluginId) {
    remove(Plugin(pluginId))
  }

  /** Writes [source] back to the file the script was opened from. Does nothing when nothing has changed. */
  public fun save() {
    if (source == saved) return
    Files.writeString(file, source)
    saved = source
  }

  /** Builds or runs a recipe; a name the recipe rejects is an [UnreadableDependency]. */
  private fun <T> recipe(use: () -> T): T = try {
    use()
  } catch (e: IllegalArgumentException) {
    throw UnreadableDependency(e.message ?: "The recipe rejected its input.")
  }

  /** Runs a recipe on the current source and reads the result back into parts. */
  private fun rewrite(rewriting: () -> String) {
    val rewritten = recipe(rewriting)
    if (rewritten != source) {
      declared = read(rewritten)
      source = rewritten
    }
  }

  /** The [ModuleVersion], [TypeSafeAccessor] or [ProjectPath] that [dependency] is, going by its shape. */
  private fun shapeOf(dependency: String, version: String?): Any {
    fun unreadable(why: String): Nothing = throw UnreadableDependency("Cannot read '$dependency': $why")
    return when {
      dependency.startsWith(':') -> {
        if (version != null) unreadable("a project path has no Version.")
        if (!projectPathPattern.matches(dependency)) unreadable("it is not a project path such as ':core'.")
        ProjectPath(dependency)
      }
      ':' in dependency -> {
        val segments = dependency.split(':')
        if (segments.size !in 2..3 || segments.any { !segmentPattern.matches(it) }) {
          unreadable("expected 'group:name' or 'group:name:version'.")
        }
        if (segments.size == 3 && version != null) unreadable("its Version is given twice.")
        val named = (segments.getOrNull(2) ?: version)?.let(::RequiredVersion) ?: UnspecifiedVersion
        ModuleVersion(Module(segments[0], segments[1]), named)
      }
      accessorPattern.matches(dependency) -> {
        if (version != null) unreadable("a type-safe accessor has its Version in the Version Catalog.")
        TypeSafeAccessor(dependency)
      }
      else -> unreadable(
        "expected 'group:name:version', 'group:name', a type-safe accessor such as 'libs.okio', " +
          "or a project path such as ':core'."
      )
    }
  }

  public companion object {
    private val segmentPattern = Regex("""[^\s:"]+""")
    private val projectPathPattern = Regex("""(:[^\s:"]+)+""")
    private val accessorPattern = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+""")

    private fun Module.written(): String = "$group:$name"

    private fun Transform.Element.value(): String = when (this) {
      is Transform.Element.StringLiteral -> value
      is Transform.Element.Accessor -> value
    }

    private fun ModuleVersion.written(): String = when (version) {
      is RequiredVersion -> "${module.written()}:${version.text}"
      UnspecifiedVersion -> module.written()
    }

    private fun Version.written(): String = when (this) {
      is RequiredVersion -> "at $text"
      UnspecifiedVersion -> "with no Version"
    }

    /**
     * Reads and parses [file].
     *
     * @throws KotlinParseException if the script does not parse.
     */
    @JvmStatic
    @Throws(KotlinParseException::class)
    public fun open(file: Path): BuildScript = BuildScript(file, Files.readString(file))

    private fun read(source: String): Declared {
      val errors = CollectingErrorListener()
      val walk = Parser(
        file = source.byteInputStream(),
        errorListener = errors,
        listenerFactory = { input, tokens, _ -> Walk(input, tokens) },
      ).listener()
      errors.getErrorMessages().takeIf { it.isNotEmpty() }?.let { throw KotlinParseException.withErrors(it) }
      return Declared(walk.dependencies.toList(), walk.pluginDependencies.toList())
    }
  }

  /** Collects what the top-level dependencies and plugins blocks declare. */
  private class Walk(input: CharStream, tokens: CommonTokenStream) : KotlinParserBaseListener() {
    val dependencies = mutableListOf<Dependency>()
    val pluginDependencies = mutableListOf<PluginDependency>()
    private val extractor = DependencyExtractor(input, tokens, Whitespace.computeIndent(tokens, input))

    override fun enterNamedBlock(ctx: NamedBlockContext) {
      extractor.onEnterBlock()
    }

    override fun exitNamedBlock(ctx: NamedBlockContext) {
      when {
        // isPlugins matches only the script's own plugins block. A dependencies block is top-level when its
        // statement's parent is the script; inside buildscript or a task it is not.
        ctx.isPlugins ->
          ctx.statements().statement().forEach { statement ->
            PluginExtractor.extractFromBlock(statement.leafRule())?.let(::pluginDependency)?.let(pluginDependencies::add)
          }
        ctx.isDependencies && ctx.parent.parent is ScriptContext ->
          extractor.collectDependencies(ctx).getDependencyDeclarations().forEach { declaration ->
            dependency(declaration)?.let(dependencies::add)
          }
      }
      extractor.onExitBlock()
    }

    private fun dependency(declaration: DependencyDeclaration): Dependency? {
      val configuration = Configuration(declaration.configuration.removeSurrounding("\""))
      val modifier = when (declaration.capability) {
        Capability.DEFAULT -> DependencyModifier.Unmodified
        Capability.PLATFORM -> DependencyModifier.Platform
        Capability.ENFORCED_PLATFORM -> DependencyModifier.EnforcedPlatform
        Capability.TEST_FIXTURES -> DependencyModifier.TestFixtures
      }
      val written = declaration.identifier.path
      val literal = written.takeIf { it.startsWith('"') && it.endsWith('"') }?.removeSurrounding("\"")

      return when (declaration.type) {
        DependencyDeclaration.Type.MODULE -> when {
          literal != null -> moduleVersion(literal)?.let { ExternalModuleDependency(configuration, it, modifier) }
          else -> CatalogDependency(configuration, TypeSafeAccessor(written), modifier)
        }
        // A type-safe project accessor (projects.core) has no ProjectPath in the script, so it is not modelled.
        DependencyDeclaration.Type.PROJECT -> literal?.let { ProjectDependency(configuration, ProjectPath(it), modifier) }
        else -> null
      }
    }

    /** `group:name` or `group:name:version` of plain text; anything else (a classifier, `@ext`, a template) is null. */
    private fun moduleVersion(literal: String): ModuleVersion? {
      if ('$' in literal || '@' in literal) return null
      val segments = literal.split(':')
      if (segments.size !in 2..3 || segments.any { it.isBlank() }) return null
      val version = segments.getOrNull(2)?.let(::RequiredVersion) ?: UnspecifiedVersion
      return ModuleVersion(Module(segments[0], segments[1]), version)
    }

    private fun pluginDependency(declaration: PluginDeclaration): PluginDependency? {
      val version = when (val written = declaration.version) {
        null -> UnspecifiedVersion
        else -> written.takeIf { it.startsWith('"') && it.endsWith('"') && '$' !in it }
          ?.let { RequiredVersion(it.removeSurrounding("\"")) }
          ?: return null // a variable or a template: not a plain string, so not modelled
      }
      return when (declaration.type) {
        PluginDeclaration.Type.BLOCK_ALIAS -> CatalogPlugin(TypeSafeAccessor(declaration.id))
        PluginDeclaration.Type.BLOCK_KOTLIN -> Plugin(PluginId("org.jetbrains.kotlin.${declaration.id}"), version)
        PluginDeclaration.Type.BLOCK_ID,
        PluginDeclaration.Type.BLOCK_SIMPLE,
        PluginDeclaration.Type.BLOCK_BACKTICK -> Plugin(PluginId(declaration.id), version)
        PluginDeclaration.Type.APPLY -> null
      }
    }
  }
}
