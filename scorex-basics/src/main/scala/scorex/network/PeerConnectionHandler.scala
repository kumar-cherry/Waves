package scorex.network

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef}
import akka.io.Tcp._
import akka.util.ByteString
import scorex.app.Application
import scorex.network.peer.PeerManager
import scorex.utils.ScorexLogging

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success}


class PeerConnectionHandler(application: Application,
                            connection: ActorRef,
                            remote: InetSocketAddress) extends Actor with ScorexLogging {

  import PeerConnectionHandler._

  private lazy val networkController = application.networkController

  context watch connection

//  context.system.scheduler.schedule(1.second, 5.seconds)(self ! SendBlockchainScore)

  /*
  private def handleMessage(message: message.Message[_]) = {
    log.debug("Handling message: " + message)
    message match {

      case GetPeersMessage =>
        self ! PeersMessage(peerManager.knownPeers().filter(_ != remote)) //excluding sender

      case PeersMessage(peers) =>
        peers.foreach { peer =>
          peerManager.addPeer(peer)
        }

      case ScoreMessage(height, score) =>
        networkController ! UpdateBlockchainScore(remote, height, score)

      case GetSignaturesMessage(signaturesGot) =>
        log.info(s"Got GetSignaturesMessage with ${signaturesGot.length} sigs within")

        signaturesGot.exists { parent =>
          val headers = application.blockchainImpl.getSignatures(parent, settings.MaxBlocksChunks)
          if (headers.nonEmpty) {
            self ! SignaturesMessage(Seq(parent) ++ headers)
            true
          } else false
        }

      case SignaturesMessage(signaturesGot) =>
        log.info(s"Got SignaturesMessage with ${signaturesGot.length} sigs")

        val common = signaturesGot.head
        require(blockchainStorage.contains(common))

        blockchainStorage.removeAfter(common)

        signaturesGot.tail.foreach { case sig =>
          self ! GetBlockMessage(sig)
        }

      case GetBlockMessage(signature) =>
        blockchainStorage.blockById(signature) match {
          case Some(block) => self ! BlockMessage(blockchainStorage.heightOf(block).get, block)
          case None => self ! Blacklist
        }

      case BlockMessage(height, block) =>
        require(block != null)
        log.info(s"Got block, height $height , local height: " + blockchainStorage.height())

        if (height == blockchainStorage.height() + 1) {
          if (block.isValid)
            networkController ! NewBlock(block, Some(remote))
          else
            log.info(s"Got non-valid block (height of a block: $height")
        }

      case TransactionMessage(transaction) =>
        if (!transaction.isSignatureValid || transaction.transactionType == TransactionType.GenesisTransaction) {
          self ! Blacklist
        } else if (transaction.hasMinimumFee && transaction.hasMinimumFeePerByte) {
          application.onNewOffchainTransaction(transaction)
        }

      case nonsense: Any => log.warn(s"PeerConnectionHandler: got something strange $nonsense")
    }
  } */

  override def receive = {

//    case SendBlockchainScore =>
//      self ! ScoreMessage(blockchainStorage.height(), blockchainStorage.score())

    case msg: message.Message =>
      self ! ByteString(msg.bytes)

    case data: ByteString =>
      connection ! Write(data)

    case CommandFailed(w: Write) =>
      log.info(s"Write failed : $w " + remote)
      peerManager.blacklistPeer(remote)
      connection ! Close

    case Received(data) =>
      application.messagesHandler.parse(data.toByteBuffer) match {
        case Success(message) =>
          log.info("received message " + message.getClass.getSimpleName + " from " + remote)
          handleMessage(message)

        case Failure(e) =>
          log.info(s"Corrupted data from: " + remote + " : " + e.getMessage)
          connection ! Close
        //context stop self
      }

    case cc: ConnectionClosed =>
      networkController ! NetworkController.PeerDisconnected(remote)
      log.info("Connection closed to : " + remote + ": " + cc.getErrorCause)

    case CloseConnection =>
      log.info(s"Enforced to abort communication with: " + remote)
      connection ! Close

    case Blacklist =>
      log.info(s"Going to blacklist " + remote)
    //todo: real blacklisting
    //  PeerManager.blacklistPeer(remote)
    //  connection ! Close

    case nonsense: Any => log.warn(s"Strange input: $nonsense")
  }
}

object PeerConnectionHandler {

  case object SendBlockchainScore

  case object CloseConnection

  case object Blacklist
}