package com.wavesplatform.settings

import com.typesafe.config.ConfigFactory
import com.wavesplatform.settings.SynchronizationSettings.{HistoryReplierSettings, MicroblockSynchronizerSettings}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

class SynchronizationSettingsSpecification extends FlatSpec with Matchers {
  "SynchronizationSettings" should "read values" in {
    val config = ConfigFactory.parseString(
      """
        |waves {
        |  synchronization {
        |    max-rollback: 100
        |    max-chain-length: 101
        |    synchronization-timeout: 30s
        |    score-ttl: 90s
        |    history-replier {
        |      max-micro-block-cache-size = 5
        |      max-block-cache-size = 2
        |    }
        |
        |    micro-block-synchronizer {
        |      wait-response-timeout: 5s
        |      processed-micro-blocks-cache-timeout: 2s
        |      inv-cache-timeout: 3s
        |      next-inv-cache-timeout: 5m
        |    }
        |  }
        |}
      """.stripMargin).resolve()

    val settings = SynchronizationSettings.fromConfig(config)
    settings.maxRollback should be(100)
    settings.maxChainLength should be(101)
    settings.synchronizationTimeout should be(30.seconds)
    settings.scoreTTL should be(90.seconds)
    settings.microBlockSynchronizer shouldBe MicroblockSynchronizerSettings(
      waitResponseTimeout = 5.seconds,
      processedMicroBlocksCacheTimeout = 2.seconds,
      invCacheTimeout = 3.seconds,
      nextInvCacheTimeout = 5.minutes,
    )
    settings.historyReplierSettings shouldBe HistoryReplierSettings(
      maxMicroBlockCacheSize = 5,
      maxBlockCacheSize = 2
    )
  }
}
