/**
 *  Copyright (c) 2010, Aemon Cannon
 *  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *      * Redistributions of source code must retain the above copyright
 *        notice, this list of conditions and the following disclaimer.
 *      * Redistributions in binary form must reproduce the above copyright
 *        notice, this list of conditions and the following disclaimer in the
 *        documentation and/or other materials provided with the distribution.
 *      * Neither the name of ENSIME nor the
 *        names of its contributors may be used to endorse or promote products
 *        derived from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 *  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  DISCLAIMED. IN NO EVENT SHALL Aemon Cannon BE LIABLE FOR ANY
 *  DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.ensime.server
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.StepRequest
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import org.ensime.debug.ProjectDebugInfo
import org.ensime.config.ProjectConfig
import org.ensime.model._
import org.ensime.protocol.ProtocolConversions
import org.ensime.protocol.ProtocolConst._
import org.ensime.util._
import scala.actors._
import scala.actors.Actor._
import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import scala.collection.mutable.ListBuffer
import scala.collection.{ Iterable, Map }
import com.sun.jdi._
import com.sun.jdi.event._

case class DebuggerShutdownEvent()

case class DebugStartVMReq(commandLine: String)
case class DebugStopVMReq()
case class DebugRunReq()
case class DebugContinueReq(threadId: Long)
case class DebugNextReq(threadId: Long)
case class DebugStepReq(threadId: Long)
case class DebugStepOutReq(threadId: Long)
case class DebugValueForNameReq(threadId: Long, name: String)
case class DebugActiveVMReq()
case class DebugBreakReq(file: String, line: Int)
case class DebugClearBreakReq(file: String, line: Int)
case class DebugClearAllBreaksReq()
case class DebugListBreaksReq()

abstract class DebugEvent
case class DebugStepEvent(threadId: Long, pos: SourcePosition) extends DebugEvent
case class DebugBreakEvent(threadId: Long, pos: SourcePosition) extends DebugEvent
case class DebugVMDeathEvent() extends DebugEvent
case class DebugVMStartEvent() extends DebugEvent
case class DebugVMDisconnectEvent() extends DebugEvent
case class DebugExceptionEvent(e: String, threadId: Long) extends DebugEvent
case class DebugThreadStartEvent(threadId: Long) extends DebugEvent
case class DebugThreadDeathEvent(threadId: Long) extends DebugEvent

class DebugManager(project: Project, protocol: ProtocolConversions, config: ProjectConfig) extends Actor {

  import protocol._

  def locToPos(loc: Location): Option[SourcePosition] = {
    (for (set <- sourceMap.get(loc.sourceName())) yield {
      if (set.size > 1) {
        System.err.println("Warning, ambiguous source name: " +
          loc.sourceName())
      }
      set.headOption.map(f => SourcePosition(f, loc.lineNumber))
    }).getOrElse(None)
  }

  private val sourceMap = HashMap[String, HashSet[CanonFile]]()
  def rebuildSourceMap() {
    sourceMap.clear()
    for (f <- config.sources) {
      val set = sourceMap.getOrElse(f.getName, HashSet())
      set.add(f)
      sourceMap(f.getName) = set
    }
  }
  rebuildSourceMap()

  def tryPendingBreaksForSourcename(sourcename: String) {
    for (breaks <- pendingBreaksBySourceName.get(sourcename)) {
      val toTry = HashSet() ++ breaks
      for (bp <- toTry) {
        setBreakpoint(bp.pos.file, bp.pos.line)
      }
    }
  }

  def setBreakpoint(file: CanonFile, line: Int): Boolean = {
    if ((for (vm <- maybeVM) yield {
      vm.setBreakpoint(file, line)
    }).getOrElse { false }) {
      activeBreakpoints.add(Breakpoint(SourcePosition(file, line)))
      true
    } else {
      addPendingBreakpoint(Breakpoint(SourcePosition(file, line)))
      false
    }
  }

  def clearBreakpoint(file: CanonFile, line: Int) {
    val clearBp = Breakpoint(SourcePosition(file, line))
    for (bps <- pendingBreaksBySourceName.get(file.getName)) {
      bps.retain { _ != clearBp }
    }
    val toRemove = activeBreakpoints.filter { _ == clearBp }
    for (vm <- maybeVM) {
      vm.clearBreakpoints(toRemove)
    }
    activeBreakpoints --= toRemove
  }

  def clearAllBreakpoints() {
    pendingBreaksBySourceName.clear()
    activeBreakpoints.clear()
    for (vm <- maybeVM) {
      vm.clearAllBreakpoints()
    }
  }

  def moveActiveBreaksToPending() {
    for (bp <- activeBreakpoints) {
      addPendingBreakpoint(bp)
    }
    activeBreakpoints.clear()
  }

  def addPendingBreakpoint(bp: Breakpoint) {
    val file = bp.pos.file
    val breaks = pendingBreaksBySourceName.get(file.getName).getOrElse(HashSet())
    breaks.add(bp)
    pendingBreaksBySourceName(file.getName) = breaks
  }

  private val activeBreakpoints = HashSet[Breakpoint]()
  private val pendingBreaksBySourceName = new HashMap[String, HashSet[Breakpoint]] {
    override def default(key: String) = new HashSet()
  }

  def pendingBreakpoints: List[Breakpoint] = {
    pendingBreaksBySourceName.values.flatten.toList
  }

  def vmOptions(): List[String] = {
    List("-classpath", config.debugClasspath)
  }

  private var maybeVM: Option[VM] = None

  private def handleWithVM(action: (VM => Unit)) = {
    (for (vm <- maybeVM) yield {
    }).getOrElse {
      System.err.println("No VM under debug!")
    }
  }
  private def handleRPCWithVM(callId: Int)(action: (VM => Unit)) = {
    (for (vm <- maybeVM) yield {
      action(vm)
    }).getOrElse {
      project ! RPCResultEvent(toWF(false), callId)
      System.err.println("No VM under debug!")
    }
  }
  private def handleRPCWithVMAndThread(callId: Int, threadId: Long)(action: ((VM, ThreadReference) => Unit)) = {
    (for (vm <- maybeVM) yield {
      (for (thread <- vm.threadById(threadId)) yield {
        action(vm, thread)
      }).getOrElse {
        System.err.println("Couldn't find thread: " + threadId)
        project ! RPCResultEvent(toWF(false), callId)
      }
    }).getOrElse {
      System.err.println("No VM under debug!")
      project ! RPCResultEvent(toWF(false), callId)
    }
  }

  def act() {
    loop {
      try {
        receive {
          case DebuggerShutdownEvent => {
            for (vm <- maybeVM) {
              vm.dispose()
            }
            exit('stop)
          }
          case evt: com.sun.jdi.event.Event => {
            evt match {
              case e: VMStartEvent => {
                for (vm <- maybeVM) {
                  vm.initLocationMap()
                }
                project ! AsyncEvent(toWF(DebugVMStartEvent()))
              }
              case e: VMDeathEvent => {
                maybeVM = None
                moveActiveBreaksToPending()
                project ! AsyncEvent(toWF(DebugVMDeathEvent()))
              }
              case e: VMDisconnectEvent => {
                maybeVM = None
                moveActiveBreaksToPending()
                project ! AsyncEvent(toWF(DebugVMDisconnectEvent()))
              }
              case e: StepEvent => {
                (for (pos <- locToPos(e.location())) yield {
                  project ! AsyncEvent(toWF(DebugStepEvent(
                    e.thread().uniqueID(), pos)))
                }) getOrElse {
                  System.err.println("Step position not found: " +
                    e.location().sourceName() + " : " + e.location().lineNumber())
                }
              }
              case e: BreakpointEvent => {
                (for (pos <- locToPos(e.location())) yield {
                  project ! AsyncEvent(toWF(DebugBreakEvent(
                    e.thread().uniqueID(), pos)))
                }) getOrElse {
                  System.err.println("Break position not found: " +
                    e.location().sourceName() + " : " + e.location().lineNumber())
                }
              }
              case e: ExceptionEvent => {
                project ! AsyncEvent(toWF(DebugExceptionEvent(
                  e.toString,
                  e.thread().uniqueID())))
              }
              case e: ThreadDeathEvent => {
                project ! AsyncEvent(toWF(DebugThreadDeathEvent(
                  e.thread().uniqueID())))
              }
              case e: ThreadStartEvent => {
                project ! AsyncEvent(toWF(DebugThreadStartEvent(
                  e.thread().uniqueID())))
              }
              case e: AccessWatchpointEvent => {}
              case e: ClassPrepareEvent => {
                for (vm <- maybeVM) {
                  vm.typeAdded(e.referenceType())
                }
              }
              case e: ClassUnloadEvent => {}
              case e: MethodEntryEvent => {}
              case e: MethodExitEvent => {}
              case _ => {}
            }
          }
          case RPCRequestEvent(req: Any, callId: Int) => {
            try {
              req match {
                case DebugStartVMReq(commandLine: String) => {
                  for (vm <- maybeVM) {
                    vm.dispose()
                  }
                  maybeVM = None
                  val connector = Bootstrap.virtualMachineManager().defaultConnector
                  val arguments = connector.defaultArguments()
                  //arguments.get("home").setValue(jreHome);
                  val opts = arguments.get("options").value
                  val allVMOpts = (List(opts) ++ vmOptions).mkString(" ")
                  arguments.get("options").setValue(allVMOpts)
                  arguments.get("main").setValue(commandLine)
                  arguments.get("suspend").setValue("false")
                  //arguments.get("quote").setValue("\"");
                  //arguments.get("vmexec").setValue("java");
                  println("Using Connector: " + connector.name +
                    " : " + connector.description())
                  println("Connector class: " + connector.getClass.getName())
                  println("Debugger VM args: " + allVMOpts)
                  println("Debugger program args: " + commandLine)
                  val vm = connector.launch(arguments)

                  maybeVM = Some(new VM(vm))
                  project ! RPCResultEvent(toWF(true), callId)
                }

                case DebugActiveVMReq() => {
                  handleRPCWithVM(callId) { vm =>
                    project ! RPCResultEvent(toWF(true), callId)
                  }
                }

                case DebugStopVMReq() => {
                  handleRPCWithVM(callId) { vm =>
                    vm.dispose()
                    project ! RPCResultEvent(toWF(true), callId)
                  }
                }

                case DebugRunReq() => {
                  handleRPCWithVM(callId) { vm =>
                    vm.resume()
                    project ! RPCResultEvent(toWF(true), callId)
                  }
                }

                case DebugContinueReq(threadId) => {
                  handleRPCWithVMAndThread(callId, threadId) {
                    (vm, thread) =>
                      vm.resume()
                      project ! RPCResultEvent(toWF(true), callId)
                  }
                }

                case DebugBreakReq(filepath: String, line: Int) => {
                  val file = CanonFile(filepath)
                  if (!setBreakpoint(file, line)) {
                    project.bgMessage("Location not loaded. Set pending breakpoint.")
                  }
                  project ! RPCResultEvent(toWF(true), callId)
                }

                case DebugClearBreakReq(filepath: String, line: Int) => {
                  val file = CanonFile(filepath)
                  clearBreakpoint(file, line)
                  project ! RPCResultEvent(toWF(true), callId)
                }

                case DebugClearAllBreaksReq() => {
                  clearAllBreakpoints()
                  project ! RPCResultEvent(toWF(true), callId)
                }

                case DebugListBreaksReq() => {
                  import scala.collection.JavaConversions._
                  val breaks = BreakpointList(
                    activeBreakpoints.toList,
                    pendingBreakpoints)
                  project ! RPCResultEvent(toWF(breaks), callId)
                }

                case DebugNextReq(threadId: Long) => {
                  handleRPCWithVMAndThread(callId, threadId) {
                    (vm, thread) =>
                      vm.newStepRequest(thread,
                        StepRequest.STEP_LINE,
                        StepRequest.STEP_OVER)
                      project ! RPCResultEvent(toWF(true), callId)
                  }
                }

                case DebugStepReq(threadId: Long) => {
                  handleRPCWithVMAndThread(callId, threadId) {
                    (vm, thread) =>
                      vm.newStepRequest(thread,
                        StepRequest.STEP_LINE,
                        StepRequest.STEP_INTO)
                      project ! RPCResultEvent(toWF(true), callId)
                  }
                }

                case DebugStepOutReq(threadId: Long) => {
                  handleRPCWithVMAndThread(callId, threadId) {
                    (vm, thread) =>
                      vm.newStepRequest(thread,
                        StepRequest.STEP_LINE,
                        StepRequest.STEP_OUT)
                      project ! RPCResultEvent(toWF(true), callId)
                  }
                }

                case DebugValueForNameReq(threadId: Long, name: String) => {
                  handleRPCWithVMAndThread(callId, threadId) {
                    (vm, thread) =>
                      vm.valueForName(thread, name) match {
                        case Some(value) =>
                          project ! RPCResultEvent(toWF(value), callId)
                        case None =>
                          project ! RPCResultEvent(toWF(false), callId)
                      }
                  }
                }
              }
            } catch {
              case e: VMDisconnectedException =>
                {
                  System.err.println("Error handling RPC:")
                  e.printStackTrace()
		  moveActiveBreaksToPending()
                  project ! AsyncEvent(toWF(DebugVMDisconnectEvent()))
                  project.sendRPCError(ErrExceptionInDebugger,
                    Some("VM is disconnected!"), callId)
                  maybeVM = None
                }
              case e: Exception =>
                {
                  System.err.println("Error handling RPC:")
                  e.printStackTrace()
                  project.sendRPCError(ErrExceptionInDebugger,
                    Some("Error occurred in Debug Manager. Check the server log."),
                    callId)
                }
            }
          }
          case other =>
            {
              println("Debug Manager: WTF, what's " + other)
            }
        }

      } catch {
        case e: Exception =>
          {
            System.err.println("Error at Debug Manager message loop:")
            e.printStackTrace()
          }
      }
    }
  }

  override def finalize() {
    System.out.println("Finalizing debug manager actor.")
  }

  private class VM(val vm: VirtualMachine) {
    import scala.collection.JavaConversions._

    //    vm.setDebugTraceMode(VirtualMachine.TRACE_EVENTS)
    val evtQ = new VMEventManager(vm.eventQueue())
    evtQ.start()
    val erm = vm.eventRequestManager();
    {
      val req = erm.createClassPrepareRequest()
      req.setSuspendPolicy(EventRequest.SUSPEND_NONE)
      req.enable()
    }
    {
      val req = erm.createThreadStartRequest()
      req.setSuspendPolicy(EventRequest.SUSPEND_NONE)
      req.enable()
    }
    {
      val req = erm.createThreadDeathRequest()
      req.setSuspendPolicy(EventRequest.SUSPEND_NONE)
      req.enable()
    }
    {
      val req = erm.createExceptionRequest(null, false, true)
      req.setSuspendPolicy(EventRequest.SUSPEND_ALL)
      req.enable()
    }
    private val fileToUnits = HashMap[String, HashSet[ReferenceType]]()
    private val process = vm.process();
    private val outputMon = new MonitorOutput(process.getErrorStream());
    outputMon.start
    private val inputMon = new MonitorOutput(process.getInputStream());
    inputMon.start

    def resume() {
      vm.resume()
    }

    def newStepRequest(thread: ThreadReference, stride: Int, depth: Int) {
      erm.deleteEventRequests(erm.stepRequests)
      val request = erm.createStepRequest(
        thread,
        stride,
        depth)
      request.addCountFilter(1)
      request.enable()
      vm.resume()
    }

    def setBreakpoint(file: CanonFile, line: Int): Boolean = {
      (for (loc <- location(file, line)) yield {
        val request = erm.createBreakpointRequest(loc)
        request.setSuspendPolicy(EventRequest.SUSPEND_ALL)
        request.enable();
        project.bgMessage("Set breakpoint at: " + file + " : " + line)
        true
      }).getOrElse { false }
    }

    def clearAllBreakpoints() {
      erm.deleteAllBreakpoints()
    }

    def clearBreakpoints(bps: Iterable[Breakpoint]) {
      for (bp <- bps) {
        for (
          req <- erm.breakpointRequests();
          pos <- locToPos(req.location())
        ) {
          if (pos == bp.pos) {
            req.disable()
          }
        }
      }
    }

    def typeAdded(t: ReferenceType) {
      try {
        val key = t.sourceName
        val types = fileToUnits.get(key).getOrElse(HashSet[ReferenceType]())
        types += t
        fileToUnits(key) = types
        tryPendingBreaksForSourcename(key)
      } catch {
        case e: AbsentInformationException =>
          println("No location information available for: " + t.name())
      }
    }

    def initLocationMap() = {
      for (t <- vm.allClasses) {
        typeAdded(t)
      }
    }

    def location(file: CanonFile, line: Int): Option[Location] = {
      val buf = ListBuffer[Location]()
      val key = file.getName
      for (types <- fileToUnits.get(key)) {
        for (t <- types) {
          for (m <- t.methods()) {
            try { buf ++= m.locationsOfLine(line) } catch {
              case _ =>
                print("no debug info for: " + m)
            }
          }
          try { buf ++= t.locationsOfLine(line) } catch {
            case _ =>
              print("no debug info for: " + t)
          }
        }
      }
      println("Found locations: " + buf)
      buf.headOption
    }

    def threadById(id: Long): Option[ThreadReference] = {
      vm.allThreads().find(t => t.uniqueID == id)
    }

    // Helper as Value.toString doesn't give
    // us what we want...
    def valueToString(value: Value): String = {
      value match {
        case v: BooleanValue => v.value().toString()
        case v: ByteValue => v.value().toString()
        case v: CharValue => "'" + v.value().toString() + "'"
        case v: DoubleValue => v.value().toString() + "d"
        case v: FloatValue => v.value().toString() + "f"
        case v: IntegerValue => v.value().toString()
        case v: LongValue => v.value().toString() + "l"
        case v: ShortValue => v.value().toString()
        case v: VoidValue => "void"
        case v: StringReference => "\"" + v.value().toString() + "\""
        case v: ArrayReference => {
          "Array[" + v.getValues().take(3).map(valueToString).mkString(", ") + "]"
        }
        case v: ObjectReference => "instance of " + v.referenceType().name()
        case _ => "NA"
      }
    }


    def makeDebugArr(value: ArrayReference, thread: ThreadReference): 
    DebugArrayReference = {
      DebugArrayReference(
        value.length,
        value.referenceType().name,
        value.referenceType().asInstanceOf[ArrayType].componentTypeName(),
        thread.uniqueID,
        value.uniqueID)
    }

    def makeDebugObj(value: ObjectReference, thread: ThreadReference): 
    DebugObjectReference = {
          DebugObjectReference(
            {
              var i = -1
              value.referenceType().fields().map { f =>
		i += 1
                DebugObjectField(i, f.name(), None, value.uniqueID)
              }.toList
            },
            value.referenceType().name(),
            value.uniqueID(),
            thread.uniqueID())
    }

    def makeDebugPrim(value: PrimitiveValue, thread: ThreadReference): 
    DebugPrimitiveValue = DebugPrimitiveValue(
      valueToString(value),
      value.`type`().name(),
      thread.uniqueID())

    def makeDebugStr(value: StringReference, thread: ThreadReference): 
    DebugStringReference = {
          DebugStringReference(
	    value.value().toString().take(50),
            {
              var i = -1
              value.referenceType().fields().map { f =>
		i += 1
                DebugObjectField(i, f.name(), None, value.uniqueID)
              }.toList
            },
            value.referenceType().name(),
            value.uniqueID(),
            thread.uniqueID())
    }

    def makeDebugValue(value: Value, thread: ThreadReference): DebugValue = {
      value match {
        case value: ArrayReference => makeDebugArr(value, thread)
        case value: StringReference => makeDebugStr(value, thread)
        case value: ObjectReference => makeDebugObj(value, thread)
        case value: PrimitiveValue => makeDebugPrim(value, thread)
      }
    }

    def valueForName(thread: ThreadReference, name: String): Option[DebugValue] = {
      stackValueNamed(thread, name).orElse(objectFieldValueNamed(thread, name))
    }

    def stackValueNamed(thread: ThreadReference, name: String): Option[DebugValue] = {
      var result: Option[DebugValue] = None
      var i = 0
      while (result.isEmpty && i < thread.frameCount) {
        val stackFrame = thread.frame(i)
        val visVars = stackFrame.visibleVariables()
        visVars.find(_.name() == name) match {
          case Some(visibleVar) => {
            result = Some(makeDebugValue(stackFrame.getValue(visibleVar), thread))
          }
          case _ =>
        }
        i += 1
      }
      result
    }

    def objectFieldValueNamed(thread: ThreadReference, name: String): Option[DebugValue] = {
      val stackFrame = thread.frame(0)
      val objRef = stackFrame.thisObject();
      val refType = objRef.referenceType();
      val objFields = refType.allFields();
      objFields.find(_.name() == name) match {
        case Some(field) => Some(makeDebugValue(objRef.getValue(field), thread))
        case None => None
      }
    }

    def dispose() = try {
      evtQ.finished = true
      vm.dispose()
    } catch {
      case e: VMDisconnectedException => {}
    }

  }

  private class VMEventManager(val eventQueue: EventQueue) extends Thread {
    var finished = false
    override def run() {
      try {
        do {
          val eventSet = eventQueue.remove();
          val it = eventSet.eventIterator()
          while (it.hasNext()) {
            val evt = it.nextEvent();
            println("VM Event:" + evt.toString)
            evt match {
              case e: VMDisconnectEvent => {
                finished = true
              }
              case _ => {}
            }
            actor { DebugManager.this ! evt }
          }
        } while (!finished);
      } catch {
        case t: Throwable => t.printStackTrace();
      }
    }
  }

  private class MonitorOutput(val inStream: InputStream) extends Thread {
    val in = new InputStreamReader(inStream)
    val out = new OutputStreamWriter(System.out);
    override def run() {
      try {
        var i = 0;
        val buf = new Array[Char](256);
        i = in.read(buf, 0, buf.length)
        while (i >= 0) {
          out.write(buf, 0, i);
          i = in.read(buf, 0, buf.length)
        }
      } catch {
        case t: Throwable => {
          t.printStackTrace();
        }
      }
    }
  }
}

