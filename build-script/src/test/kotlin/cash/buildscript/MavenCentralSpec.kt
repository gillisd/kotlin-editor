package cash.buildscript

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

// Specs for searching Maven Central. A spec stays @Disabled("pending") until it is written.
//
// Wording follows BuildScriptSpec: a name in CamelCase is a type in the layer; a capitalized word that is not a type
// (Group, Name) is a domain term. Every spec runs against a recorded reply, never the live service.
internal class MavenCentralSpec {

  @Nested inner class `searching by keyword` {
    @Disabled("pending") @Test fun `returns a ModuleVersion for each module whose Group or Name matches`() {}

    @Disabled("pending") @Test fun `gives each ModuleVersion its latest Version as a RequiredVersion`() {}

    @Disabled("pending") @Test fun `keeps the order Maven Central ranks them in`() {}

    @Disabled("pending") @Test fun `returns an empty list when nothing matches`() {}

    @Disabled("pending") @Test fun `sends the keyword as typed, encoding spaces and symbols`() {}
  }

  @Nested inner class `limiting how many are returned` {
    @Disabled("pending") @Test fun `asks for 20 when no limit is named`() {}

    @Disabled("pending") @Test fun `asks for the limit that is named`() {}

    @Disabled("pending") @Test fun `returns fewer than the limit when fewer match`() {}
  }

  @Nested inner class `using what is found in a BuildScript` {
    @Disabled("pending") @Test fun `adds a found ModuleVersion as an ExternalModuleDependency`() {}

    @Disabled("pending") @Test fun `pins a declared Module to a found ModuleVersion`() {}
  }

  @Nested inner class `when it cannot answer` {
    @Disabled("pending") @Test fun `fails with UnreachableRepository when Maven Central does not answer`() {}

    @Disabled("pending") @Test fun `fails with UnreachableRepository when Maven Central answers with an error status`() {}

    @Disabled("pending") @Test fun `fails with UnreadableReply when the answer is not a search reply`() {}

    @Disabled("pending") @Test fun `fails with UnreadableReply when a module in the reply has no Group, Name or latest Version`() {}

    @Disabled("pending") @Test fun `refuses a blank keyword and a limit below one without asking Maven Central`() {}
  }
}
