package litd.domain

import org.bson.types.ObjectId
import org.bson.Document

import java.util.Date

final case class TournamentDocument(
    _id: Option[ObjectId] = None,
    name: String,
    teamId: String,
    timeControlInitialSeconds: Int,
    timeControlIncrementSeconds: Int,
    rated: Boolean,
    status: String,
    configuredMaxRounds: Int,
    effectiveMaxRounds: Int,
    createdAt: Date,
    updatedAt: Date
)

final case class RegistrationDocument(
    _id: Option[ObjectId] = None,
    tournamentId: ObjectId,
    lichessUserId: String,
    status: String,
    effectiveRound: Int,
    createdAt: Date
)

final case class RoundDocument(
    _id: Option[ObjectId] = None,
    tournamentId: ObjectId,
    roundNumber: Int,
    status: String,
    createdAt: Date,
    completedAt: Option[Date]
)

final case class PairingDocument(
    _id: Option[ObjectId] = None,
    tournamentId: ObjectId,
    roundId: ObjectId,
    roundNumber: Int,
    gameId: String,
    challengeId: Option[String] = None,
    challengeIssuedAt: Option[Date] = None,
    gameStartedAt: Option[Date] = None,
    whiteLichessUserId: String,
    blackLichessUserId: String,
    playerIds: Seq[String],
    result: Option[String],
    isOfficial: Boolean,
    createdAt: Date
)

final case class ByeDocument(
    _id: Option[ObjectId] = None,
    tournamentId: ObjectId,
    roundId: ObjectId,
    roundNumber: Int,
    lichessUserId: String,
    scoreAwarded: Double,
    reason: String,
    createdAt: Date
)

final case class TiebreaksDocument(
    buchholz: Double,
    sonnebornBerger: Double
)

final case class PlayerTournamentStateDocument(
    _id: Option[ObjectId] = None,
    tournamentId: ObjectId,
    lichessUserId: String,
    points: Double,
    gamesPlayed: Int,
    opponents: Seq[String],
    colors: Seq[String],
    resultsByRound: Map[String, String],
    tiebreaks: TiebreaksDocument,
    updatedAt: Date
)

final case class OverrideDocument(
    _id: Option[ObjectId] = None,
    pairingId: ObjectId,
    reason: String,
    appliedBy: String,
    createdAt: Date
)

final case class AuditEventDocument(
    _id: Option[ObjectId] = None,
    tournamentId: ObjectId,
    `type`: String,
    payload: Document,
    createdAt: Date
)

final case class OAuthTokenDocument(
    _id: Option[ObjectId] = None,
    lichessUserId: String,
    encryptedAccessToken: String,
    tokenType: String,
    scope: String,
    expiresAt: Option[Date],
    sessionTokenHash: String,
    createdAt: Date,
    updatedAt: Date
)

final case class TeamMembershipCacheDocument(
    _id: Option[ObjectId] = None,
    teamId: String,
    lichessUserId: String,
    isMember: Boolean,
    expiresAt: Date,
    updatedAt: Date
)
