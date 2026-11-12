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

  private var declared: Declared = read(source)

  /** The declarations in the top-level dependencies block, in the order written. */
  public val dependencies: List<Dependency> get() = declared.dependencies

  /** The entries in the top-level plugins block, in the order written. */
  public val pluginDependencies: List<PluginDependency> get() = declared.pluginDependencies

  private class Declared(val dependencies: List<Dependency>, val pluginDependencies: List<PluginDependency>)

  public companion object {
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
