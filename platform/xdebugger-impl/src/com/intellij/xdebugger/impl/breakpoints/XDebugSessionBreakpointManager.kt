// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.xdebugger.impl.breakpoints

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Comparing
import com.intellij.openapi.util.Disposer
import com.intellij.util.progress.withLockMaybeCancellable
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.XDebuggerManagerImpl
import com.intellij.xdebugger.impl.XDebuggerUtilImpl
import com.intellij.xdebugger.impl.breakpoints.BreakpointsUsageCollector.reportBreakpointVerified
import com.intellij.xdebugger.impl.settings.XDebuggerSettingManagerImpl
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections
import java.util.concurrent.locks.ReentrantLock
import javax.swing.Icon

/**
 * Owns breakpoint registration, dependencies, and presentations for one debug session.
 */
internal class XDebugSessionBreakpointManager(
  private val session: XDebugSessionImpl,
  private val debuggerManager: XDebuggerManagerImpl,
) {
  private val myLock = ReentrantLock()

  // protected with myRegisteredBreakpoints
  private val myRegisteredBreakpoints: MutableMap<XBreakpoint<*>, CustomizedBreakpointPresentation?> = HashMap()
  private val myInactiveSlaveBreakpoints: MutableSet<XBreakpoint<*>> = Collections.synchronizedSet(HashSet())

  // protected with myLock
  private var myBreakpointsDisabled = false

  // protected with myLock
  private var myBreakpointListenerDisposable: Disposable? = null

  // protected with myLock
  private var breakpointsInitialized = false

  fun reset() {
    myLock.withLockMaybeCancellable {
      breakpointsInitialized = false
    }
    removeBreakpointListeners()
  }

  fun initBreakpoints() {
    myLock.withLockMaybeCancellable {
      LOG.assertTrue(!breakpointsInitialized)
      breakpointsInitialized = true
      var breakpointListenerDisposable = myBreakpointListenerDisposable
      if (breakpointListenerDisposable == null) {
        breakpointListenerDisposable = Disposer.newDisposable()
        myBreakpointListenerDisposable = breakpointListenerDisposable
        Disposer.register(session.project, breakpointListenerDisposable)
        val busConnection = session.project.getMessageBus().connect(breakpointListenerDisposable)
        busConnection.subscribe(XBreakpointListener.TOPIC, MyBreakpointListener())
        busConnection.subscribe(XDependentBreakpointListener.TOPIC, MyDependentBreakpointListener())
      }
    }

    disableSlaveBreakpoints()
    processAllBreakpoints(true, false)
  }

  private fun disableSlaveBreakpoints() {
    val slaveBreakpoints = debuggerManager.breakpointManager.dependentBreakpointManager.allSlaveBreakpoints
    if (slaveBreakpoints.isEmpty()) {
      return
    }

    val breakpointTypes: MutableSet<XBreakpointType<*, *>?> = HashSet()
    for (handler in session.debugProcess.breakpointHandlers) {
      breakpointTypes.add(getBreakpointTypeClass(handler))
    }
    for (slaveBreakpoint in slaveBreakpoints) {
      if (breakpointTypes.contains(slaveBreakpoint.getType())) {
        myInactiveSlaveBreakpoints.add(slaveBreakpoint)
      }
    }
  }

  private fun <B : XBreakpoint<*>> processBreakpoints(
    handler: XBreakpointHandler<*>,
    register: Boolean,
    temporary: Boolean,
  ) {
    @Suppress("UNCHECKED_CAST")
    handler as XBreakpointHandler<B>
    val breakpoints = debuggerManager.breakpointManager.getBreakpoints<B>(handler.getBreakpointTypeClass())
    for (b in breakpoints) {
      handleBreakpoint(handler, b, register, temporary)
    }
  }

  private fun <B : XBreakpoint<*>> handleBreakpoint(
    handler: XBreakpointHandler<B>, b: B, register: Boolean,
    temporary: Boolean,
  ) {
    if (register) {
      val active = isBreakpointActive(b)
      if (active) {
        synchronized(myRegisteredBreakpoints) {
          myRegisteredBreakpoints[b] = CustomizedBreakpointPresentation()
          if (b is XLineBreakpoint<*>) {
            updateBreakpointPresentation(b, b.getType().pendingIcon, null)
          }
        }
        handler.registerBreakpoint(b)
      }
    }
    else {
      val removed: Boolean
      synchronized(myRegisteredBreakpoints) {
        removed = myRegisteredBreakpoints.remove(b) != null
      }
      if (removed) {
        handler.unregisterBreakpoint(b, temporary)
      }
    }
  }

  fun getBreakpointPresentation(breakpoint: XBreakpoint<*>): CustomizedBreakpointPresentation? {
    synchronized(myRegisteredBreakpoints) {
      return myRegisteredBreakpoints[breakpoint]
    }
  }

  private fun processAllHandlers(breakpoint: XBreakpoint<*>, register: Boolean) {
    for (handler in session.debugProcess.breakpointHandlers) {
      processBreakpoint(breakpoint, handler, register)
    }
  }

  private fun <B : XBreakpoint<*>> processBreakpoint(
    breakpoint: B,
    handler: XBreakpointHandler<*>,
    register: Boolean,
  ) {
    val type = breakpoint.getType()
    if (handler.getBreakpointTypeClass() == type.javaClass) {
      @Suppress("UNCHECKED_CAST")
      handleBreakpoint(handler as XBreakpointHandler<B>, breakpoint, register, false)
    }
  }

  fun isBreakpointActive(b: XBreakpoint<*>): Boolean {
    return !areBreakpointsMuted() && b.isEnabled() && !isInactiveSlaveBreakpoint(b) && !(b as XBreakpointBase<*, *, *>).isDisposed
  }

  fun areBreakpointsMuted(): Boolean {
    return session.sessionData.isBreakpointsMuted
  }

  fun getBreakpointsMutedFlow(): StateFlow<Boolean> {
    return session.sessionData.breakpointsMutedFlow
  }

  private fun areBreakpointDisabled() = myLock.withLockMaybeCancellable { myBreakpointsDisabled }

  fun setBreakpointMuted(muted: Boolean): Boolean {
    if (areBreakpointsMuted() == muted) return false
    session.sessionData.isBreakpointsMuted = muted
    if (!areBreakpointDisabled()) {
      processAllBreakpoints(!muted, muted)
    }
    return true
  }

  private fun processAllBreakpoints(register: Boolean, temporary: Boolean) {
    for (handler in session.debugProcess.breakpointHandlers) {
      processBreakpoints<XBreakpoint<*>>(handler, register, temporary)
    }
  }

  fun setBreakpointsDisabledTemporarily(disabled: Boolean) {
    val changed = myLock.withLockMaybeCancellable {
      if (myBreakpointsDisabled == disabled) return@withLockMaybeCancellable false
      myBreakpointsDisabled = disabled
      true
    }
    if (changed && !areBreakpointsMuted()) {
      processAllBreakpoints(!disabled, disabled)
    }
  }

  fun updateBreakpointPresentation(
    breakpoint: XLineBreakpoint<*>,
    icon: Icon?,
    errorMessage: String?,
  ) {
    val presentation: CustomizedBreakpointPresentation?
    synchronized(myRegisteredBreakpoints) {
      presentation = myRegisteredBreakpoints[breakpoint]
      if (presentation == null ||
          (Comparing.equal<Icon?>(presentation.icon, icon) && Comparing.strEqual(presentation.errorMessage, errorMessage))
      ) {
        return
      }

      presentation.errorMessage = errorMessage
      presentation.icon = icon

      val timestamp = presentation.timestamp
      if (timestamp != 0L && XDebuggerUtilImpl.getVerifiedIcon(breakpoint) == icon) {
        val delay = System.currentTimeMillis() - timestamp
        presentation.timestamp = 0
        reportBreakpointVerified(breakpoint, delay)
      }
    }
    if (breakpoint is XLineBreakpointImpl<*>) {
      // for useFeProxy we call update directly since visual presentation is disabled on the backend
      breakpoint.fireBreakpointPresentationUpdated(session)
    }
  }

  fun handleTemporaryBreakpointHit(breakpoint: XBreakpoint<*>?) {
    session.addSessionListener(object : XDebugSessionListener {
      fun removeBreakpoint() {
        XDebuggerUtil.getInstance().removeBreakpoint(session.project, breakpoint)
        session.removeSessionListener(this)
      }

      override fun sessionResumed() {
        removeBreakpoint()
      }

      override fun sessionStopped() {
        removeBreakpoint()
      }
    })
  }

  fun processDependencies(breakpoint: XBreakpoint<*>) {
    val dependentBreakpointManager = debuggerManager.breakpointManager.dependentBreakpointManager
    if (!dependentBreakpointManager.isMasterOrSlave(breakpoint)) return

    val breakpoints = dependentBreakpointManager.getSlaveBreakpoints(breakpoint)
    breakpoints.forEach { o -> myInactiveSlaveBreakpoints.remove(o) }
    for (slaveBreakpoint in breakpoints) {
      processAllHandlers(slaveBreakpoint, true)
    }

    if (dependentBreakpointManager.getMasterBreakpoint(breakpoint) != null && !dependentBreakpointManager.isLeaveEnabled(breakpoint)) {
      val added = myInactiveSlaveBreakpoints.add(breakpoint)
      if (added) {
        processAllHandlers(breakpoint, false)
      }
    }
  }

  fun unmuteOnStop() {
    if (XDebuggerSettingManagerImpl.getInstanceImpl().generalSettings.isUnmuteOnStop) {
      session.sessionData.isBreakpointsMuted = false
    }
  }

  fun clearRegisteredBreakpoints() {
    synchronized(myRegisteredBreakpoints) {
      myRegisteredBreakpoints.clear()
    }
  }

  fun removeBreakpointListeners() {
    val breakpointListenerDisposable = myLock.withLockMaybeCancellable {
      val current = myBreakpointListenerDisposable
      myBreakpointListenerDisposable = null
      current
    }
    if (breakpointListenerDisposable != null) {
      Disposer.dispose(breakpointListenerDisposable)
    }
  }

  fun isInactiveSlaveBreakpoint(breakpoint: XBreakpoint<*>): Boolean {
    return myInactiveSlaveBreakpoints.contains(breakpoint)
  }

  private inner class MyBreakpointListener : XBreakpointListener<XBreakpoint<*>> {
    override fun breakpointAdded(breakpoint: XBreakpoint<*>) {
      if (processAdd(breakpoint)) {
        val presentation = getBreakpointPresentation(breakpoint)
        if (presentation != null) {
          if (XDebuggerUtilImpl.getVerifiedIcon(breakpoint) == presentation.icon) {
            reportBreakpointVerified(breakpoint, 0)
          }
          else {
            presentation.timestamp = System.currentTimeMillis()
          }
        }
      }
    }

    override fun breakpointRemoved(breakpoint: XBreakpoint<*>) {
      session.checkActiveNonLineBreakpointOnRemoval(breakpoint)
      processRemove(breakpoint)
    }

    fun processRemove(breakpoint: XBreakpoint<*>) {
      processAllHandlers(breakpoint, false)
    }

    fun processAdd(breakpoint: XBreakpoint<*>): Boolean {
      if (!areBreakpointDisabled()) {
        processAllHandlers(breakpoint, true)
        return true
      }
      return false
    }

    override fun breakpointChanged(breakpoint: XBreakpoint<*>) {
      processRemove(breakpoint)
      processAdd(breakpoint)
    }
  }

  private inner class MyDependentBreakpointListener : XDependentBreakpointListener {
    override fun dependencySet(slave: XBreakpoint<*>, master: XBreakpoint<*>) {
      val added = myInactiveSlaveBreakpoints.add(slave)
      if (added) {
        processAllHandlers(slave, false)
      }
    }

    override fun dependencyCleared(breakpoint: XBreakpoint<*>) {
      val removed = myInactiveSlaveBreakpoints.remove(breakpoint)
      if (removed) {
        processAllHandlers(breakpoint, true)
      }
    }
  }

  companion object {
    private val LOG = Logger.getInstance(XDebugSessionImpl::class.java)

    //need to compile under 1.8, please do not remove before checking
    private fun getBreakpointTypeClass(handler: XBreakpointHandler<*>): XBreakpointType<*, *>? {
      return XDebuggerUtil.getInstance().findBreakpointType(handler.getBreakpointTypeClass())
    }
  }
}
