// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.protocol

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.protocol.ExampleTransactionFactory.transactionVersion
import com.digitalasset.canton.serialization.HasCryptographicEvidenceTest
import com.digitalasset.daml.lf.data.ImmArray
import com.digitalasset.daml.lf.transaction.Versioned
import com.digitalasset.daml.lf.value.{Value, ValueCoder}
import com.google.protobuf.ByteString
import org.scalatest.prop.TableFor3
import org.scalatest.wordspec.AnyWordSpec

class SerializableRawContractInstanceTest
    extends AnyWordSpec
    with HasCryptographicEvidenceTest
    with BaseTest {
  import ExampleTransactionFactory.suffixedId

  "SerializableContractInstance" should {
    val absContractId1 = suffixedId(0, 1)
    val absContractId2 = suffixedId(0, 2)
    val absContractId3 = suffixedId(3, 4)

    val contractInst1 = ExampleTransactionFactory.contractInstance(List(absContractId1))
    val contractInst2 = ExampleTransactionFactory.contractInstance(List(absContractId2))
    val contractInst3 = ExampleTransactionFactory.contractInstance(List(absContractId3))
    val contractInst12 =
      ExampleTransactionFactory.contractInstance(List(absContractId1, absContractId2))

    val scenarios =
      new TableFor3[String, SerializableRawContractInstance, SerializableRawContractInstance](
        ("test description", "first contract instance", "second contract instance"),
        (
          "same transaction ID",
          SerializableRawContractInstance.create(contractInst1).value,
          SerializableRawContractInstance.create(contractInst2).value,
        ),
        (
          "different transaction ID",
          SerializableRawContractInstance.create(contractInst2).value,
          SerializableRawContractInstance.create(contractInst3).value,
        ),
        (
          "same contract ID, but different capture",
          SerializableRawContractInstance.create(contractInst1).value,
          SerializableRawContractInstance.create(contractInst12).value,
        ),
      )

    scenarios.forEvery { case (name, coinst1, coinst2) =>
      name should { behave like hasCryptographicEvidenceSerialization(coinst1, coinst2) }
    }

    "for a non-serializable instance" should {
      val nonSerializableContractInst = ExampleTransactionFactory.veryDeepContractInstance

      "fail if no serialization is given" in {
        SerializableRawContractInstance
          .create(nonSerializableContractInst)
          .left
          .value shouldBe a[ValueCoder.EncodeError]
      }

      "not attempt serialization if the serialization is provided" in {
        SerializableRawContractInstance.createWithSerialization(nonSerializableContractInst)(
          ByteString.EMPTY
        )
      }
    }

    "hashing" should {

      def inst(arg: Value): SerializableRawContractInstance =
        SerializableRawContractInstance.createWithSerialization(
          LfContractInst(
            packageName = ExampleTransactionFactory.packageName,
            template = ExampleTransactionFactory.templateId,
            arg = Versioned(transactionVersion, arg),
          )
        )(ByteString.EMPTY)

      val unNormalizedArg = Value.ValueRecord(
        None,
        ImmArray(
          (None, Value.ValueOptional(Some(Value.ValueTrue))),
          (None, Value.ValueOptional(None)),
        ),
      )

      val normalizedArg = Value.ValueRecord(
        None,
        ImmArray(
          (None, Value.ValueOptional(Some(Value.ValueTrue)))
        ),
      )

      val unNormalizedInst = inst(unNormalizedArg)
      val normalizedInst = inst(normalizedArg)

      "create different hashes for normalized and un-normalized values when not using upgrade friendly hashing" in {
        unNormalizedInst.contractHash(false) != normalizedInst.contractHash(false)
      }

      "create identical hashes for normalized and un-normalized values when using upgrade friendly hashing" in {
        unNormalizedInst.contractHash(true) == normalizedInst.contractHash(true)
      }

    }

  }
}

object SerializableRawContractInstanceTest {
  def toHexString(byte: Byte): String = {
    val s = byte.toInt.toHexString
    if (s.length < 2) "0" + s else s
  }

}
