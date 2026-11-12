package cash.recipes.dependencies

import cash.grammar.kotlindsl.parse.Mutator
import cash.grammar.kotlindsl.parse.Parser
import cash.grammar.kotlindsl.utils.Blocks.isDependencies
import cash.grammar.kotlindsl.utils.CollectingErrorListener
import cash.grammar.kotlindsl.utils.DependencyExtractor
import com.squareup.cash.grammar.KotlinLexer
import com.squareup.cash.grammar.KotlinParser.NamedBlockContext
import com.squareup.cash.grammar.KotlinParser.ScriptContext
import com.squareup.cash.grammar.KotlinParser.StatementContext
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import java.io.InputStream
import java.nio.file.Path

/**
 * Changes the versions of modules declared in the top-level `dependencies` blocks of a build script, such as
 * `implementation("com.foo:bar:1.0")` to `"com.foo:bar:2.0"`.
 *
 * Modules are addressed by `group:name`, and every declaration of one is changed, on whatever configuration. Only the
 * version changes: a classifier, an `@ext`, a wrapper such as `platform(…)`, a trailing block and comments all stay. A
 * declaration with no version gets one. A declaration whose string is a template (`"com.foo:bar:$v"`) is left alone
 * and reported in [skipped]. Unlike [DependenciesMutator], this never replaces the whole declaration.
 */
public class DependencyVersionMutator private constructor(
  private val versions: Map<String, String>,
  input: CharStream,
  tokens: CommonTokenStream,
  errorListener: CollectingErrorListener,
) : Mutator(input, tokens, errorListener) {

  private val dependencyExtractor = DependencyExtractor(input, tokens, indent)
  private val changed = linkedSetOf<String>()
  private val skipped = linkedMapOf<String, String>()
  private val found = mutableSetOf<String>()

  /** The modules (`group:name`) with at least one declaration whose version was changed. */
  public fun changed(): Set<String> = changed.toSet()

  /** The modules that were found but have a declaration that was not changed, each with the reason. */
  public fun skipped(): Map<String, String> = skipped.toMap()

  /** The modules that are not declared in a top-level `dependencies` block. */
  public fun notFound(): Set<String> = versions.keys - found

  override fun isChanged(): Boolean = changed.isNotEmpty()

  override fun enterNamedBlock(ctx: NamedBlockContext) {
    dependencyExtractor.onEnterBlock()
  }

  override fun exitNamedBlock(ctx: NamedBlockContext) {
    // The parent is a StatementContext whose parent is the script; nested blocks have a StatementsContext there.
    if (ctx.parent.parent is ScriptContext && ctx.isDependencies) {
      for (element in dependencyExtractor.collectDependencies(ctx).getDependencyDeclarationsWithContext()) {
        pin(element.statement)
      }
    }
    dependencyExtractor.onExitBlock()
  }

  private fun pin(statement: StatementContext) {
    for (literal in literalsIn(statement)) {
      val module = moduleOf(literal.text) ?: continue
      val wanted = versions[module] ?: continue
      found += module

      if (!literal.plain) {
        skipped[module] = "version is not a string literal"
        return
      }
      val rewritten = withVersion(literal.text, wanted)
      if (rewritten == literal.text) {
        skipped[module] = "already at $wanted"
      } else {
        rewriter.replace(literal.open, literal.close, "\"$rewritten\"")
        changed += module
      }
      return
    }
  }

  /** A string literal: its quotes, the text between them, and whether that text is free of templates. */
  private class Literal(val open: Token, val close: Token, val text: String, val plain: Boolean)

  private fun literalsIn(statement: StatementContext): List<Literal> {
    val literals = mutableListOf<Literal>()
    var i = statement.start.tokenIndex
    val last = statement.stop.tokenIndex
    while (i <= last) {
      if (tokens.get(i).type == KotlinLexer.QUOTE_OPEN) {
        val open = tokens.get(i)
        val text = StringBuilder()
        var plain = true
        i++
        while (i <= last && tokens.get(i).type != KotlinLexer.QUOTE_CLOSE) {
          if (tokens.get(i).type != KotlinLexer.LineStrText) plain = false
          text.append(tokens.get(i).text)
          i++
        }
        if (i <= last) literals += Literal(open, tokens.get(i), text.toString(), plain)
      }
      i++
    }
    return literals
  }

  public companion object {
    private val segmentPattern = Regex("""[^\s:"@]+""")
    private val modulePattern = Regex("""[^\s:"@]+:[^\s:"@]+""")
    private val versionPattern = Regex("""[^\s:"@${'$'}\\]+""")

    /** `group:name` when [text] reads as `group:name[:version[:classifier]][@ext]`, else null. */
    private fun moduleOf(text: String): String? {
      val segments = text.substringBefore('@').split(':')
      if (segments.size !in 2..4) return null
      if (!segmentPattern.matches(segments[0]) || !segmentPattern.matches(segments[1])) return null
      return "${segments[0]}:${segments[1]}"
    }

    private fun withVersion(text: String, version: String): String {
      val extension = if ('@' in text) "@" + text.substringAfter('@') else ""
      val segments = text.substringBefore('@').split(':').toMutableList()
      if (segments.size == 2) segments += version else segments[2] = version
      return segments.joinToString(":") + extension
    }

    private fun validate(versions: Map<String, String>) {
      for ((module, version) in versions) {
        require(modulePattern.matches(module)) { "Invalid module: '$module'. Expected 'group:name' (no version)." }
        require(versionPattern.matches(version)) { "Invalid version for '$module': '$version'" }
      }
    }

    /**
     * Returns a [DependencyVersionMutator], which eagerly parses [buildScript]. [versions] maps `group:name` to the
     * version to set.
     *
     * @throws IllegalArgumentException if a module or version is malformed, or if [DependencyExtractor] sees an
     *   expression it doesn't understand.
     * @throws IllegalStateException if [DependencyExtractor] sees an expression it doesn't understand.
     */
    @JvmStatic
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    public fun of(buildScript: Path, versions: Map<String, String>): DependencyVersionMutator =
      of(Parser.readOnlyInputStream(buildScript), versions)

    /**
     * Returns a [DependencyVersionMutator], which eagerly parses [buildScript]. [versions] maps `group:name` to the
     * version to set.
     *
     * @throws IllegalArgumentException if a module or version is malformed, or if [DependencyExtractor] sees an
     *   expression it doesn't understand.
     * @throws IllegalStateException if [DependencyExtractor] sees an expression it doesn't understand.
     */
    @JvmStatic
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    public fun of(buildScript: String, versions: Map<String, String>): DependencyVersionMutator =
      of(buildScript.byteInputStream(), versions)

    private fun of(buildScript: InputStream, versions: Map<String, String>): DependencyVersionMutator {
      validate(versions)
      val errorListener = CollectingErrorListener()
      return Parser(
        file = buildScript,
        errorListener = errorListener,
        listenerFactory = { input, tokens, _ ->
          DependencyVersionMutator(versions = versions, input = input, tokens = tokens, errorListener = errorListener)
        },
      ).listener()
    }
  }
}
