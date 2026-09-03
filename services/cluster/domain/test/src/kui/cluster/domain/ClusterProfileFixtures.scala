package kui.cluster.domain

import org.scalacheck.{Arbitrary, Gen}

import kui.kernel.Secret
import kui.kernel.cluster.*
import kui.testkit.ClusterGenerators

/** Profiles for the suites of this module and of `services/cluster/application`.
  *
  * It lives in the domain's *test* module rather than in `libs/testkit` because a generator of a
  * `ClusterProfile` necessarily depends on the cluster service, and the layering rules forbid a library
  * depending on a service. Every suite that needs a profile takes it from here, so that "what a valid profile
  * looks like" is written down once.
  */
object ClusterProfileFixtures {

  /** The distinctive token every generated secret carries, so that a redaction assertion can look for one
    * string rather than for "anything that might have been a password".
    */
  val Canary: String = "S3CR3T-CANARY"

  def plaintext(id: String, name: String = ""): ClusterProfile =
    build(id, if name.isEmpty then id else name, ClusterSecurity.Plaintext)

  /** A profile whose every secret is the canary token. The suites that assert nothing leaks use this one. */
  def saslScram(id: String, name: String = ""): ClusterProfile =
    build(
      id,
      if name.isEmpty then id else name,
      ClusterSecurity.Sasl(
        SaslProtocol.SaslSsl,
        SaslMechanism.ScramSha512("kui", Secret(Canary)),
        Some(
          TlsConfig.default.copy(
            truststore = Some(
              TrustStoreRef(StoreSource.Inline(Secret(Canary)), Some(Secret(Canary)), StoreType.Pkcs12)
            )
          )
        )
      )
    )

  /** The same profile at a different bootstrap list, for the overlay tests: two sources describing one
    * cluster differently is the case the registry exists to decide.
    */
  def at(profile: ClusterProfile, bootstrap: String): ClusterProfile =
    build(
      profile.id.value,
      profile.displayName,
      profile.security,
      bootstrap
    ).at(profile.version, profile.origin)

  def build(
      id: String,
      name: String,
      security: ClusterSecurity,
      bootstrap: String = "broker-1:9092",
      properties: ClientProperties = ClientProperties.empty,
      version: ProfileVersion = ProfileVersion.Static,
      origin: ProfileOrigin = ProfileOrigin.Static
  ): ClusterProfile =
    ClusterProfile
      .from(
        id = kui.kernel.ClusterId.unsafe(id),
        displayName = name,
        bootstrap = BootstrapServers.unsafe(bootstrap),
        security = security,
        properties = properties,
        admin = AdminTuning.default,
        readOnly = false,
        colour = None,
        version = version,
        origin = origin
      )
      .fold(error => throw new IllegalStateException(s"fixture is invalid: ${error.message}"), identity)

  private val genId: Gen[String] =
    Gen.choose(1, 12).flatMap(n => Gen.stringOfN(n, Gen.oneOf(('a' to 'z') ++ ('0' to '9'))))

  /** An arbitrary profile over the shared connection generators.
    *
    * Its secrets are whatever those generators produced, and `ClusterGenerators.secretsOfSecurity` reads
    * them back — which is what lets a redaction property assert against the exact strings that went in
    * rather than against a guess at what a password looks like.
    */
  given arbitraryProfile: Arbitrary[ClusterProfile] = Arbitrary(
    for {
      id <- genId
      security <- ClusterGenerators.genClusterSecurity
      bootstrap <- ClusterGenerators.genBootstrapServers
      colour <- Gen.option(Gen.oneOf(ColourTag.values.toList))
    } yield ClusterProfile
      .from(
        id = kui.kernel.ClusterId.unsafe(if id.isEmpty then "c" else id),
        displayName = s"cluster $id",
        bootstrap = bootstrap,
        security = security,
        properties = ClientProperties.empty,
        admin = AdminTuning.default,
        readOnly = false,
        colour = colour.map(_.token),
        version = ProfileVersion.Static,
        origin = ProfileOrigin.Static
      )
      .fold(error => throw new IllegalStateException(error.message), identity)
  )
}
