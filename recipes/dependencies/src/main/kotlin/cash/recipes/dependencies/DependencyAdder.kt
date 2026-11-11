package cash.recipes.dependencies

import cash.grammar.kotlindsl.model.DependencyDeclaration
import cash.grammar.kotlindsl.model.DependencyDeclaration.Capability
import cash.grammar.kotlindsl.parse.Mutator
import cash.grammar.kotlindsl.parse.Parser
import cash.grammar.kotlindsl.utils.Blocks.isDependencies
import cash.grammar.kotlindsl.utils.CollectingErrorListener
import cash.grammar.kotlindsl.utils.DependencyExtractor
import cash.recipes.dependencies.transform.Transform
import com.squareup.cash.grammar.KotlinLexer
import com.squareup.cash.grammar.KotlinParser.NamedBlockContext
import com.squareup.cash.grammar.KotlinParser.ScriptContext
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import java.io.InputStream
import java.nio.file.Path

/**
 * Adds dependency declarations to the top-level `dependencies` block of a build script.
 *
 * A new declaration goes on the line after the last declaration with the same configuration, or after the block's
 * last statement when there is none. A script without a top-level `dependencies` block gets one after its
 * `repositories` block, else after `plugins`, else at the end. A dependency that is already declared on the same
 * configuration (with any version) is skipped, not changed: use [DependenciesMutator] to change existing declarations.
 */
public class DependencyAdder private constructor(
  private val additions: List<Addition>,
  input: CharStream,
  tokens: CommonTokenStream,
  errorListener: CollectingErrorListener,
) : Mutator(input, tokens, errorListener) {

  /** One dependency to add, e.g. `implementation("com.foo:bar:1.0")` or `api(platform(libs.bom))`. */
  public data class Addition @JvmOverloads constructor(
    public val configuration: String,
    public val dependency: Transform.Element,
    public val capability: Capability = Capability.DEFAULT,
  ) {
    /** The declaration as it will be written, without indentation. */
    public fun render(): String {
      val dep = dependency.render()
      val wrapped = when (capability) {
        Capability.DEFAULT -> dep
        Capability.PLATFORM -> "platform($dep)"
        Capability.ENFORCED_PLATFORM -> "enforcedPlatform($dep)"
        Capability.TEST_FIXTURES -> "testFixtures($dep)"
      }
      return "$configuration($wrapped)"
    }

    internal fun key(): String = key(configuration, capability, dependency.render())
  }

  private val dependencyExtractor = DependencyExtractor(input, tokens, indent)
  private val added = mutableListOf<Addition>()
  private val skipped = mutableListOf<Addition>()
  private var handledBlock = false

  // Where a new dependencies block goes when the script has none.
  private var pluginsBlock: NamedBlockContext? = null
  private var repositoriesBlock: NamedBlockContext? = null

  /** The additions that were inserted, in the order given. */
  public fun added(): List<Addition> = added.toList()

  /** The additions that were not inserted because the script already declares them. */
  public fun skipped(): List<Addition> = skipped.toList()

  /** The `libs.*` style accessors that were inserted, so a caller can make sure the version catalog has them. */
  public fun usedAccessors(): List<String> =
    added.mapNotNull { (it.dependency as? Transform.Element.Accessor)?.value }.distinct()

  override fun isChanged(): Boolean = added.isNotEmpty()

  override fun enterNamedBlock(ctx: NamedBlockContext) {
    dependencyExtractor.onEnterBlock()
  }

  override fun exitNamedBlock(ctx: NamedBlockContext) {
    // The parent is a StatementContext whose parent is the script. A block nested inside
    // `buildscript { }` or a task has a StatementsContext there instead.
    if (ctx.parent.parent is ScriptContext) {
      when {
        ctx.isDependencies && !handledBlock -> {
          handledBlock = true
          addTo(ctx)
        }
        ctx.name().text == "plugins" -> pluginsBlock = ctx
        ctx.name().text == "repositories" -> repositoriesBlock = ctx
      }
    }
    dependencyExtractor.onExitBlock()
  }

  override fun exitScript(ctx: ScriptContext) {
    if (!handledBlock) addNewBlock(ctx)
  }

  /** Records each addition as added or skipped (already in [present]), and returns the ones to insert. */
  private fun selectNew(present: MutableSet<String>): List<Addition> {
    val toAdd = mutableListOf<Addition>()
    for (addition in additions) {
      if (present.add(addition.key())) toAdd += addition else skipped += addition
    }
    added += toAdd
    return toAdd
  }

  private fun addTo(block: NamedBlockContext) {
    val existing = dependencyExtractor.collectDependencies(block).getDependencyDeclarationsWithContext()
    val toAdd = selectNew(existing.mapTo(mutableSetOf()) { it.declaration.key() })
    val lastStatement = block.statements()?.statement()?.lastOrNull()

    // Lines are grouped by where they go and inserted once per spot: the rewriter would reverse several
    // separate inserts at the same token, and the order given must be kept.
    val linesAfter = LinkedHashMap<Token, MutableList<String>>()
    val sameLineAfter = LinkedHashMap<Token, MutableList<String>>()
    val linesInEmptyBlock = mutableListOf<String>()

    for (addition in toAdd) {
      val anchor = existing.lastOrNull { it.declaration.configuration == addition.configuration }?.statement
        ?: lastStatement
      when {
        anchor == null -> linesInEmptyBlock += addition.render()
        // The block's closing brace is on the anchor's line, as in `dependencies { implementation(x) }`: stay on
        // that line rather than land after the brace, keeping the existing text as it is.
        endOfLine(anchor.stop).tokenIndex > block.stop.tokenIndex ->
          sameLineAfter.getOrPut(anchor.stop) { mutableListOf() } += addition.render()
        else -> linesAfter.getOrPut(endOfLine(anchor.stop)) { mutableListOf() } += addition.render()
      }
    }

    linesAfter.forEach { (newlineToken, lines) ->
      rewriter.insertBefore(newlineToken, lines.joinToString(separator = "") { "\n$indent$it" })
    }
    sameLineAfter.forEach { (statementEnd, lines) ->
      rewriter.insertAfter(statementEnd, lines.joinToString(separator = "") { "; $it" })
    }
    if (linesInEmptyBlock.isNotEmpty()) {
      insertIntoEmptyBlock(block, linesInEmptyBlock)
    }
  }

  /**
   * Inserts a new `dependencies` block after the top-level `repositories` block, else after `plugins`, else after the
   * script's last statement, separated by a blank line.
   */
  private fun addNewBlock(script: ScriptContext) {
    val toAdd = selectNew(mutableSetOf())
    if (toAdd.isEmpty()) return

    val block = "dependencies {\n" + toAdd.joinToString(separator = "") { "$indent${it.render()}\n" } + "}"
    val anchor = (repositoriesBlock ?: pluginsBlock)?.stop ?: script.statement().lastOrNull()?.stop

    if (anchor == null) {
      // Nothing else in the script.
      rewriter.insertBefore(script.start, "$block\n")
    } else {
      rewriter.insertBefore(endOfLine(anchor), "\n\n$block")
    }
  }

  /**
   * Returns the newline token ending the line that [token] is on (or EOF), so an insert lands after anything else on
   * that line, such as a trailing comment.
   */
  private fun endOfLine(token: Token): Token {
    var i = token.tokenIndex + 1
    while (i < tokens.size()) {
      val t = tokens.get(i)
      if (t.type == KotlinLexer.NL || t.type == Token.EOF) return t
      i++
    }
    return tokens.get(tokens.size() - 1)
  }

  private fun insertIntoEmptyBlock(block: NamedBlockContext, lines: List<String>) {
    val closingBrace = block.stop
    val body = lines.joinToString(separator = "") { "$indent$it\n" }
    if (closingBrace.line == block.start.line) {
      // `dependencies {}` on one line: open it up.
      rewriter.insertBefore(closingBrace, "\n$body")
    } else {
      // The closing brace starts its own line, so each new line ends with a newline before it.
      rewriter.insertBefore(closingBrace, body)
    }
  }

  public companion object {
    /** Compares dependencies by configuration, capability and `group:name` (ignoring the version), or by accessor. */
    private fun key(configuration: String, capability: Capability, rendered: String): String {
      val text = rendered.removeSurrounding("\"")
      val coordinates = if (':' in text) text.split(':').take(2).joinToString(":") else text
      return "$configuration|$capability|$coordinates"
    }

    private fun DependencyDeclaration.key(): String = key(configuration, capability, identifier.path)

    private val coordinatesPattern = Regex("""[^\s:"]+:[^\s:"]+(:[^\s:"]+)?""")
    private val accessorPattern = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+""")
    private val configurationPattern = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    /**
     * Rejects additions that would write an invalid declaration, and pairs of additions that ask for the same dependency
     * with different versions (an exact repeat is fine: the second is reported as skipped).
     */
    private fun validate(additions: List<Addition>) {
      for (addition in additions) {
        require(configurationPattern.matches(addition.configuration)) {
          "Invalid configuration name: '${addition.configuration}'"
        }
        when (val dependency = addition.dependency) {
          is Transform.Element.StringLiteral -> require(coordinatesPattern.matches(dependency.value)) {
            "Invalid coordinates: '${dependency.value}'. Expected 'group:name' or 'group:name:version'."
          }
          is Transform.Element.Accessor -> require(accessorPattern.matches(dependency.value)) {
            "Invalid accessor: '${dependency.value}'. Expected something like 'libs.foo.bar'."
          }
        }
      }

      additions.groupBy { it.key() }.values
        .filter { sameDependency -> sameDependency.map { it.render() }.distinct().size > 1 }
        .forEach { conflicting ->
          throw IllegalArgumentException(
            "Conflicting additions for the same dependency: ${conflicting.joinToString { it.render() }}"
          )
        }
    }

    /**
     * Returns a [DependencyAdder], which eagerly parses [buildScript].
     *
     * @throws IllegalArgumentException if an addition is malformed (configuration, coordinates or accessor), if two
     *   additions ask for the same dependency with different versions, or if [DependencyExtractor] sees an expression
     *   it doesn't understand.
     * @throws IllegalStateException if [DependencyExtractor] sees an expression it doesn't understand.
     */
    @JvmStatic
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    public fun of(buildScript: Path, additions: List<Addition>): DependencyAdder =
      of(Parser.readOnlyInputStream(buildScript), additions)

    /**
     * Returns a [DependencyAdder], which eagerly parses [buildScript].
     *
     * @throws IllegalArgumentException if an addition is malformed (configuration, coordinates or accessor), if two
     *   additions ask for the same dependency with different versions, or if [DependencyExtractor] sees an expression
     *   it doesn't understand.
     * @throws IllegalStateException if [DependencyExtractor] sees an expression it doesn't understand.
     */
    @JvmStatic
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    public fun of(buildScript: String, additions: List<Addition>): DependencyAdder =
      of(buildScript.byteInputStream(), additions)

    private fun of(buildScript: InputStream, additions: List<Addition>): DependencyAdder {
      validate(additions)
      val errorListener = CollectingErrorListener()
      return Parser(
        file = buildScript,
        errorListener = errorListener,
        listenerFactory = { input, tokens, _ ->
          DependencyAdder(
            additions = additions,
            input = input,
            tokens = tokens,
            errorListener = errorListener,
          )
        },
      ).listener()
    }
  }
}
