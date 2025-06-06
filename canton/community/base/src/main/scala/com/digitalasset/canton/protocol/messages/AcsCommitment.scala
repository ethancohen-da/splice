// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.protocol.messages

import cats.syntax.either.*
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.ProtoDeserializationError.CryptoDeserializationError
import com.digitalasset.canton.crypto.{Hash, HashAlgorithm, HashPurpose}
import com.digitalasset.canton.data.{CantonTimestamp, CantonTimestampSecond}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.messages.SignedProtocolMessageContent.SignedMessageContentCast
import com.digitalasset.canton.protocol.v30
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.serialization.DeserializationError
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.store.db.DbDeserializationException
import com.digitalasset.canton.time.PositiveSeconds
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.util.NoCopy
import com.digitalasset.canton.version.{
  HasProtocolVersionedWrapper,
  ProtoVersion,
  ProtocolVersion,
  RepresentativeProtocolVersion,
  VersionedProtoCodec,
  VersioningCompanionMemoization,
}
import com.google.protobuf.ByteString
import slick.jdbc.{GetResult, GetTupleResult, SetParameter}

import scala.math.Ordering.Implicits.*

final case class CommitmentPeriod(
    fromExclusive: CantonTimestampSecond,
    periodLength: PositiveSeconds,
) extends PrettyPrinting {
  val toInclusive: CantonTimestampSecond = fromExclusive + periodLength

  def overlaps(other: CommitmentPeriod): Boolean =
    fromExclusive < other.toInclusive && toInclusive > other.fromExclusive

  override protected def pretty: Pretty[CommitmentPeriod] =
    prettyOfClass(
      param("fromExclusive", _.fromExclusive),
      param("toInclusive", _.toInclusive),
    )
}

object CommitmentPeriod {
  def create(
      fromExclusive: CantonTimestamp,
      periodLength: PositiveSeconds,
      interval: PositiveSeconds,
  ): Either[String, CommitmentPeriod] = for {
    from <- CantonTimestampSecond.fromCantonTimestamp(fromExclusive)
    _ <- Either.cond(
      periodLength.unwrap >= interval.unwrap || from == CantonTimestampSecond.MinValue,
      (),
      s"The period must be at least as large as the interval or start at MinValue, but is $periodLength and the interval is $interval",
    )
    _ <- Either.cond(
      from.getEpochSecond % interval.unwrap.getSeconds == 0 || from == CantonTimestampSecond.MinValue,
      (),
      s"The commitment period must start at a commitment tick or at MinValue, but it starts on $from, and the tick interval is $interval",
    )
    toInclusive = from + periodLength
    _ <- Either.cond(
      toInclusive.getEpochSecond % interval.unwrap.getSeconds == 0,
      (),
      s"The commitment period must end at a commitment tick, but it ends on $toInclusive, and the tick interval is $interval",
    )
  } yield CommitmentPeriod(
    fromExclusive = from,
    periodLength = periodLength,
  )

  def create(
      fromExclusive: CantonTimestamp,
      toInclusive: CantonTimestamp,
      interval: PositiveSeconds,
  ): Either[String, CommitmentPeriod] =
    PositiveSeconds
      .between(fromExclusive, toInclusive)
      .flatMap(CommitmentPeriod.create(fromExclusive, _, interval))

  def create(
      fromExclusive: CantonTimestampSecond,
      toInclusive: CantonTimestampSecond,
  ): Either[String, CommitmentPeriod] =
    PositiveSeconds.between(fromExclusive, toInclusive).map(CommitmentPeriod(fromExclusive, _))

  implicit val getCommitmentPeriod: GetResult[CommitmentPeriod] =
    new GetTupleResult[(CantonTimestampSecond, CantonTimestampSecond)](
      GetResult[CantonTimestampSecond],
      GetResult[CantonTimestampSecond],
    ).andThen { case (from, to) =>
      PositiveSeconds
        .between(from, to)
        .map(CommitmentPeriod(from, _))
        .valueOr(err => throw new DbDeserializationException(err))
    }

}

/** A commitment to the active contract set (ACS) that is shared between two participants on a given
  * synchronizer at a given time.
  *
  * Given a commitment scheme to the ACS, the semantics are as follows: the sender declares that the
  * shared ACS was exactly the one committed to, at every commitment tick during the specified
  * period and as determined by the period's interval.
  *
  * The interval is assumed to be a round number of seconds. The ticks then start at the Java EPOCH
  * time, and are exactly `interval` apart.
  */
abstract sealed case class AcsCommitment private (
    synchronizerId: SynchronizerId,
    sender: ParticipantId,
    counterParticipant: ParticipantId,
    period: CommitmentPeriod,
    commitment: AcsCommitment.HashedCommitmentType,
)(
    override val representativeProtocolVersion: RepresentativeProtocolVersion[AcsCommitment.type],
    override val deserializedFrom: Option[ByteString],
) extends HasProtocolVersionedWrapper[AcsCommitment]
    with SignedProtocolMessageContent
    with NoCopy {

  @transient override protected lazy val companionObj: AcsCommitment.type = AcsCommitment

  override def signingTimestamp: Option[CantonTimestamp] = Some(period.toInclusive.forgetRefinement)

  protected def toProtoV30: v30.AcsCommitment =
    v30.AcsCommitment(
      synchronizerId = synchronizerId.toProtoPrimitive,
      sendingParticipantUid = sender.uid.toProtoPrimitive,
      counterParticipantUid = counterParticipant.uid.toProtoPrimitive,
      fromExclusive = period.fromExclusive.toProtoPrimitive,
      toInclusive = period.toInclusive.toProtoPrimitive,
      commitment = AcsCommitment.hashedCommitmentTypeToProto(commitment),
    )

  override protected[this] def toByteStringUnmemoized: ByteString =
    super[HasProtocolVersionedWrapper].toByteString

  override protected[messages] def toProtoTypedSomeSignedProtocolMessage
      : v30.TypedSignedProtocolMessageContent.SomeSignedProtocolMessage =
    v30.TypedSignedProtocolMessageContent.SomeSignedProtocolMessage.AcsCommitment(
      getCryptographicEvidence
    )

  override lazy val pretty: Pretty[AcsCommitment] =
    prettyOfClass(
      param("synchronizerId", _.synchronizerId),
      param("sender", _.sender),
      param("counterParticipant", _.counterParticipant),
      param("period", _.period),
      param("commitment", _.commitment),
    )
}

object AcsCommitment extends VersioningCompanionMemoization[AcsCommitment] {
  override val name: String = "AcsCommitment"

  val versioningTable: VersioningTable = VersioningTable(
    ProtoVersion(30) -> VersionedProtoCodec(ProtocolVersion.v33)(v30.AcsCommitment)(
      supportedProtoVersionMemoized(_)(fromProtoV30),
      _.toProtoV30,
    )
  )

  type CommitmentType = ByteString
  implicit val getResultCommitmentType: GetResult[CommitmentType] =
    DbStorage.Implicits.getResultByteString
  implicit val setCommitmentType: SetParameter[CommitmentType] =
    DbStorage.Implicits.setParameterByteString

  type HashedCommitmentType = Hash

  def hashedCommitmentTypeToProto(commitment: HashedCommitmentType): ByteString =
    commitment.getCryptographicEvidence
  def hashedCommitmentTypeFromByteString(
      bytes: ByteString
  ): Either[DeserializationError, HashedCommitmentType] = Hash.fromByteString(bytes)
  def hashCommitment(commitment: CommitmentType): HashedCommitmentType =
    Hash.digest(HashPurpose.HashedAcsCommitment, commitment, HashAlgorithm.Sha256)

  def create(
      synchronizerId: SynchronizerId,
      sender: ParticipantId,
      counterParticipant: ParticipantId,
      period: CommitmentPeriod,
      commitment: CommitmentType,
      protocolVersion: ProtocolVersion,
  ): AcsCommitment =
    new AcsCommitment(
      synchronizerId,
      sender,
      counterParticipant,
      period,
      hashCommitment(commitment),
    )(
      protocolVersionRepresentativeFor(protocolVersion),
      None,
    ) {}

  private def fromProtoV30(protoMsg: v30.AcsCommitment)(
      bytes: ByteString
  ): ParsingResult[AcsCommitment] =
    for {
      synchronizerId <- SynchronizerId.fromProtoPrimitive(
        protoMsg.synchronizerId,
        "AcsCommitment.synchronizerId",
      )
      sender <- UniqueIdentifier
        .fromProtoPrimitive(
          protoMsg.sendingParticipantUid,
          "AcsCommitment.sending_participant_uid",
        )
        .map(ParticipantId(_))
      counterParticipant <- UniqueIdentifier
        .fromProtoPrimitive(
          protoMsg.counterParticipantUid,
          "AcsCommitment.counter_participant_uid",
        )
        .map(ParticipantId(_))
      fromExclusive <- CantonTimestampSecond.fromProtoPrimitive(
        "from_exclusive",
        protoMsg.fromExclusive,
      )
      toInclusive <- CantonTimestampSecond.fromProtoPrimitive("to_inclusive", protoMsg.toInclusive)

      periodLength <- PositiveSeconds
        .between(fromExclusive, toInclusive)
        .leftMap { _ =>
          ProtoDeserializationError.InvariantViolation(
            field = None,
            error = s"Illegal commitment period length: $fromExclusive, $toInclusive",
          )
        }

      period = CommitmentPeriod(fromExclusive, periodLength)
      cmt = protoMsg.commitment
      commitment <- hashedCommitmentTypeFromByteString(cmt).leftMap(
        CryptoDeserializationError.apply
      )
      rpv <- protocolVersionRepresentativeFor(ProtoVersion(30))
    } yield new AcsCommitment(synchronizerId, sender, counterParticipant, period, commitment)(
      rpv,
      Some(bytes),
    ) {}

  implicit val acsCommitmentCast: SignedMessageContentCast[AcsCommitment] =
    SignedMessageContentCast.create[AcsCommitment]("AcsCommitment") {
      case m: AcsCommitment => Some(m)
      case _ => None
    }

  def getAcsCommitmentResultReader(
      synchronizerId: SynchronizerId,
      protocolVersion: ProtocolVersion,
  ): GetResult[AcsCommitment] =
    new GetTupleResult[(ParticipantId, ParticipantId, CommitmentPeriod, HashedCommitmentType)](
      GetResult[ParticipantId],
      GetResult[ParticipantId],
      GetResult[CommitmentPeriod],
      GetResult[Hash],
    ).andThen { case (sender, counterParticipant, period, commitment) =>
      new AcsCommitment(synchronizerId, sender, counterParticipant, period, commitment)(
        protocolVersionRepresentativeFor(protocolVersion),
        None,
      ) {}
    }

}
