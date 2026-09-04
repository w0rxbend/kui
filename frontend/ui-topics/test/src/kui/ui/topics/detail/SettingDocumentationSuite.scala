package kui.ui.topics.detail

/** That the settings table shows sentences, not markup.
  *
  * The strings quoted here are Apache Kafka's own, copied from what a 4.3.1 broker answered
  * `describeConfigs` with in the demonstration environment on 2026-09-04 — which is where the defect was
  * found: the settings tab of `orders.v1` showed the angle brackets to the reader.
  */
final class SettingDocumentationSuite extends munit.FunSuite {

  test("anAnchorLosesItsTagsAndKeepsItsWords") {
    // The exact string a Kafka 4.3.1 broker returns for `cleanup.policy`, abridged at both ends.
    val kafka =
      """The "compact" policy will enable <a href="#compaction">log compaction</a>, which retains the latest""" +
        " value for each key."

    assertEquals(
      SettingDocumentation.plainText(kafka),
      Some(
        """The "compact" policy will enable log compaction, which retains the latest value for each key."""
      )
    )
  }

  test("theCodeAndBreakTagsOfMinInsyncReplicasBecomeOrdinarySpacedWords") {
    val kafka =
      "Specifies the minimum number of replicas<br>When used together, <code>min.insync.replicas</code> and" +
        " <code>acks</code> allow you to enforce greater durability guarantees."

    assertEquals(
      SettingDocumentation.plainText(kafka),
      Some(
        "Specifies the minimum number of replicas When used together, min.insync.replicas and acks allow" +
          " you to enforce greater durability guarantees."
      )
    )
  }

  test("anEscapedAngleBracketSurvivesAsAnAngleBracket") {
    // `<` written as an entity is content, not markup, and a reader who is being told about a comparison
    // needs to see the comparison.
    assertEquals(
      SettingDocumentation.plainText("A value &lt; 0 means the segment is never rolled."),
      Some("A value < 0 means the segment is never rolled.")
    )
  }

  test("aPlainSentenceIsUntouched") {
    val plain = "The time to wait before deleting a file from the filesystem"
    assertEquals(SettingDocumentation.plainText(plain), Some(plain))
  }

  test("documentationThatIsNothingButMarkupIsNoDocumentationAtAll") {
    // Better no description than an empty grey line under the setting's name pretending to be one.
    assertEquals(SettingDocumentation.plainText("<p></p>  <br/>"), None)
    assertEquals(SettingDocumentation.plainText("   "), None)
  }
}
