package org.scalajs.jsenv.selenium

import org.scalajs.core.tools.io.{MemVirtualJSFile, VirtualJSFile}
import org.scalajs.core.tools.jsdep.ResolvedJSDependency
import org.scalajs.jsenv.{ComJSEnv, ComJSRunner}
import java.util.concurrent.TimeoutException

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Try

class SeleniumComJSRunner(browserProvider: SeleniumBrowser,
    libs: Seq[ResolvedJSDependency], code: VirtualJSFile, keepAlive: Boolean, materializer: FileMaterializer)
    extends SeleniumAsyncJSRunner(browserProvider, libs, code, keepAlive, materializer)
    with ComJSRunner {

  private final val MESSAGE_TAG = "M"
  private final val CLOSE_TAG = "CLOSE"

  private var comClosed = false

  @deprecated("Use the overload with an explicit FileMaterializer.", "0.1.2")
  def this(browserProvider: SeleniumBrowser, libs: Seq[ResolvedJSDependency],
      code: VirtualJSFile, keepAlive: Boolean) = {
    this(browserProvider, libs, code, keepAlive, DefaultFileMaterializer)
  }

  protected def envName: String =
    "SeleniumComJSRunner on " + browserProvider.name

  private final val sendScript = {
    "var callback = arguments[arguments.length - 1];" +
    "this.scalajsSeleniumComJSRunnerChannel.recvMessage(arguments[0]);" +
    "callback();"
  }

  private final val receiveScript = {
    "var callback = arguments[arguments.length - 1];" +
    "callback(this.scalajsSeleniumComJSRunnerChannel.popOutMsg());"
  }

  def send(msg: String): Unit = {
    if (comClosed)
      throw new ComJSEnv.ComClosedException
    awaitForBrowser()
    val encodedMsg =
      msg.replace("&", "&&").replace("\u0000", "&0")
    browser.getWebDriver.executeAsyncScript(sendScript, encodedMsg)
    browser.processConsoleLogs(console)
  }

  def receive(timeout: Duration): String = {
    if (comClosed)
      throw new ComJSEnv.ComClosedException
    awaitForBrowser(timeout)
    @tailrec def loop(): String = {
      browser.getWebDriver.executeAsyncScript(receiveScript) match {
        case null =>
          loop()

        case msg: String =>
          browser.processConsoleLogs(console)
          if (msg.startsWith(MESSAGE_TAG)) {
            val taglessMsg = msg.substring(MESSAGE_TAG.length)
            "&[0&]".r.replaceAllIn(taglessMsg, regMatch =>
                if (regMatch.group(0) == "&&") "&" else "\u0000")
          } else if (msg == CLOSE_TAG) {
            throw new ComJSEnv.ComClosedException("Closed from browser.")
          } else {
            BrowserDriver.illFormattedScriptResult(msg)
          }

        case obj =>
          // Here we only try to get the console because it uses the same
          // communication channel that is potentially corrupted.
          Try(browser.processConsoleLogs(console))
          BrowserDriver.illFormattedScriptResult(obj)
      }
    }
    loop()
  }

  def close(): Unit = {
    browser.processConsoleLogs(console)
    comClosed = true
    if (!keepAlive || ignoreKeepAlive)
      browser.close()
  }

  override protected def initFiles(): Seq[VirtualJSFile] =
    super.initFiles() :+ comSetupFile()

  protected def comSetupFile(): VirtualJSFile = {
    val code = {
      s"""
         |(function() {
         |  // Buffers for outgoing messages
         |  var sendMsgBufIn = []; // push to this one
         |  var sendMsgBufOut = []; // pop from this one
         |
         |  // Buffer for incoming messages (used if onReceive not initialized)
         |  var receiveBuf = [];
         |
         |  var onReceive = null;
         |
         |  this.scalajsCom = {
         |    init: function(recvCB) {
         |      onReceive = recvCB;
         |      for (var i in receiveBuf)
         |        onReceive(receiveBuf[i]);
         |      receiveBuf = null;
         |    },
         |    send: function(msg) {
         |      var encodedMsg =
         |        msg.split("&").join("&&").split("\0").join("&0");
         |      sendMsgBufIn.push("$MESSAGE_TAG" + encodedMsg);
         |    },
         |    close: function() {
         |      sendMsgBufIn.push("$CLOSE_TAG");
         |    }
         |  };
         |
         |  this.scalajsSeleniumComJSRunnerChannel = {
         |    popOutMsg: function() {
         |      if (sendMsgBufOut.length == 0) {
         |        sendMsgBufOut = sendMsgBufIn.reverse();
         |        sendMsgBufIn = [];
         |      }
         |      return sendMsgBufOut.pop();
         |    },
         |    recvMessage: function(msg) {
         |      var matcher = function (match) {
         |        if (match == "&&")
         |          return "&"
         |        else
         |          return "\0"
         |      };
         |      var decodedMsg = msg.replace(/(&&)|(&0)/g, matcher);
         |      if (onReceive != null) {
         |        onReceive(decodedMsg);
         |      } else {
         |        receiveBuf.push(decodedMsg);
         |      }
         |    }
         |  };
         |}).call(this);
      """.stripMargin
    }
    new MemVirtualJSFile("comSetup.js").withContent(code)
  }

  protected def awaitForBrowser(timeout: Duration = Duration.Inf): Unit = {
    if (!Await.ready(future, timeout).isCompleted)
      throw new TimeoutException()
  }
}
