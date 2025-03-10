package bloop.bloopgun.util

import java.io.IOException
import java.net.ConnectException
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.Properties

import libdaemonjvm.SocketPaths
import libdaemonjvm.internal.SocketHandler
import org.slf4j.LoggerFactory
import snailgun.Client
import snailgun.logging.Logger
import snailgun.protocol.Protocol
import snailgun.protocol.Streams

final case class DomainSocketClient(socketPaths: SocketPaths) extends Client {

  private def maxReattemptsWindows = 6
  private final val reattemptsInitialDelayWindows = 10.millis
  private lazy val log = LoggerFactory.getLogger(getClass)
  def run(
      cmd: String,
      args: Array[String],
      cwd: Path,
      env: Map[String, String],
      streams: Streams,
      logger: Logger,
      stop: AtomicBoolean,
      interactiveSession: Boolean
  ): Int = {

    @tailrec
    def openSocket(maxAttempts: Int, delay: FiniteDuration): Socket = {
      val socketOpt =
        if (Files.exists(socketPaths.path))
          try {
            val socket = libdaemonjvm.Util.socketFromChannel {
              SocketHandler.client(socketPaths)
            }
            Some(socket)
          } catch {
            case ex: libdaemonjvm.errors.SocketExceptionLike
                if maxAttempts > 1 && Properties.isWin && ex.getCause
                  .isInstanceOf[IOException] && ex.getCause.getMessage.contains("error code 231") =>
              log.debug(
                s"Caught All pipe instances are busy error on $socketPaths, trying again in $delay",
                ex
              )
              None
          }
        else
          throw new ConnectException(s"${socketPaths.path} not found")
      socketOpt match {
        case Some(socket) => socket
        case None =>
          Thread.sleep(delay.toMillis)
          openSocket(maxAttempts - 1, delay * 2)
      }
    }

    var socket: Socket = null

    try {
      socket = openSocket(maxReattemptsWindows, reattemptsInitialDelayWindows)
      val in = socket.getInputStream()
      val out = socket.getOutputStream()
      val protocol = new Protocol(streams, cwd, env, logger, stop, interactiveSession)
      protocol.sendCommand(cmd, args, out, in)
    } finally {
      if (socket != null)
        try {
          if (socket.isClosed()) ()
          else {
            try socket.shutdownInput()
            finally {
              try socket.shutdownOutput()
              finally socket.close()
            }
          }
        } catch {
          case t: IOException =>
            logger.debug("Tracing an ignored socket exception...")
            // logger.trace(t)
            ()
          case t: SocketException =>
            logger.debug("Tracing an ignored socket exception...")
            logger.trace(t)
            ()
        }
    }
  }
}
