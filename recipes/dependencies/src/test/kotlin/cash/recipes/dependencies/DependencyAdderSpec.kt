package cash.recipes.dependencies

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class DependencyAdderSpec {

  @Nested inner class `adding to an existing dependencies block` {

    @Test fun `adds a string-literal dependency at the end of the block`() {}

    @Test fun `adds a version catalog accessor dependency`() {}

    @Test fun `adds after the last declaration with the same configuration`() {}

    @Test fun `adds several dependencies in the order given`() {}

    @Test fun `wraps the dependency in platform() or testFixtures() when a capability is given`() {}

    @Test fun `adds to an empty multi-line dependencies block`() {}

    @Test fun `adds to an empty one-line dependencies block`() {}

    @Test fun `matches the indentation already used in the file`() {}

    @Test fun `keeps a comment just before the closing brace above the closing brace`() {}

    @Test fun `leaves existing declarations, their trailing lambdas and comments untouched`() {}

    @Test fun `ignores dependencies blocks that are not top-level, such as buildscript or a task`() {}
  }

  @Nested inner class `when the script has no dependencies block` {

    @Test fun `creates a dependencies block at the end of the script`() {}

    @Test fun `creates the block after the plugins and repositories blocks`() {}

    @Test fun `creates the block below imports when there is nothing else`() {}
  }

  @Nested inner class `when the dependency is already declared` {

    @Test fun `skips a dependency already declared with the same version`() {}

    @Test fun `skips a dependency already declared with a different version by default`() {}

    @Test fun `updates the version instead when asked to`() {}

    @Test fun `still adds the same coordinates on a different configuration`() {}

    @Test fun `recognises an already declared version catalog accessor`() {}
  }

  @Nested inner class `reporting what changed` {

    @Test fun `reports no change and returns the script byte-for-byte when nothing is added`() {}

    @Test fun `reports which dependencies were added and which were skipped`() {}

    @Test fun `reports the catalog accessors it used so the version catalog can be updated`() {}
  }

  @Nested inner class `rejecting bad input` {

    @Test fun `throws when the script does not parse`() {}

    @Test fun `throws when coordinates are not group-colon-name with an optional version`() {}

    @Test fun `throws when the same dependency is requested twice with different versions`() {}
  }
}
