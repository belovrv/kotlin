/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.rmi.service

import org.jetbrains.kotlin.cli.common.CLICompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.load.kotlin.incremental.components.IncrementalCompilationComponents
import org.jetbrains.kotlin.progress.CompilationCanceledStatus
import org.jetbrains.kotlin.rmi.*
import java.io.BufferedOutputStream
import java.io.File
import java.io.PrintStream
import java.rmi.NoSuchObjectException
import java.rmi.registry.Registry
import java.rmi.server.UnicastRemoteObject
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.read
import kotlin.concurrent.schedule
import kotlin.concurrent.write

fun nowSeconds() = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime())

interface CompilerSelector {
    operator fun get(targetPlatform: CompileService.TargetPlatform): CLICompiler<*>
}

class CompileServiceImpl(
        val registry: Registry,
        val compiler: CompilerSelector,
        val compilerId: CompilerId,
        val daemonOptions: DaemonOptions,
        val daemonJVMOptions: DaemonJVMOptions,
        val port: Int,
        val timer: Timer,
        val onShutdown: () -> Unit
) : CompileService {

    // wrapped in a class to encapsulate alive check logic
    private class ClientOrSessionProxy(val aliveFlagPath: String?) {
        val registered = nowSeconds()
        val secondsSinceRegistered: Long get() = nowSeconds() - registered
        val isAlive: Boolean get() = aliveFlagPath?.let { File(it).exists() } ?: true // assuming that if no file was given, the client is alive
    }

    private val sessionsIdCounter = AtomicInteger(0)
    private val compilationsCounter = AtomicInteger(0)
    private val internalRng = Random()

    private val classpathWatcher = LazyClasspathWatcher(compilerId.compilerClasspath)

    enum class Aliveness(val value: Int) {
        // ordering of values is used in state comparison
        Alive(2), LastSession(1), Dying(0)
    }

    // TODO: encapsulate operations on state here
    private val state = object {

        val clientProxies: MutableSet<ClientOrSessionProxy> = hashSetOf()
        val sessions: MutableMap<Int, ClientOrSessionProxy> = hashMapOf()

        val delayedShutdownQueued = AtomicBoolean(false)

        var alive = AtomicInteger(Aliveness.Alive.value)
    }

    @Volatile private var _lastUsedSeconds = nowSeconds()
    public val lastUsedSeconds: Long get() = if (rwlock.isWriteLocked || rwlock.readLockCount - rwlock.readHoldCount > 0) nowSeconds() else _lastUsedSeconds

    val log by lazy { Logger.getLogger("compiler") }

    private val rwlock = ReentrantReadWriteLock()

    private var runFile: File

    init {
        val runFileDir = File(daemonOptions.runFilesPathOrDefault)
        runFileDir.mkdirs()
        runFile = File(runFileDir,
                           makeRunFilenameString(timestamp = "%tFT%<tH-%<tM-%<tS.%<tLZ".format(Calendar.getInstance(TimeZone.getTimeZone("Z"))),
                                                 digest = compilerId.compilerClasspath.map { File(it).absolutePath }.distinctStringsDigest().toHexString(),
                                                 port = port.toString()))
        try {
            if (!runFile.createNewFile()) throw Exception("createNewFile returned false")
        } catch (e: Exception) {
            throw IllegalStateException("Unable to create run file '${runFile.absolutePath}'", e)
        }
        runFile.deleteOnExit()
    }

    // RMI-exposed API

    override fun getDaemonOptions(): CompileService.CallResult<DaemonOptions> = ifAlive { daemonOptions }

    override fun getDaemonJVMOptions(): CompileService.CallResult<DaemonJVMOptions> = ifAlive { daemonJVMOptions }

    override fun registerClient(aliveFlagPath: String?): CompileService.CallResult<Nothing> = ifAlive_Nothing {
        synchronized(state.clientProxies) {
            state.clientProxies.add(ClientOrSessionProxy(aliveFlagPath))
        }
    }

    override fun getClients(): CompileService.CallResult<List<String>> = ifAlive {
        synchronized(state.clientProxies) {
            state.clientProxies.map { it.aliveFlagPath }.filterNotNull()
        }
    }

    // TODO: consider tying a session to a client and use this info to cleanup
    override fun leaseCompileSession(aliveFlagPath: String?): CompileService.CallResult<Int> = ifAlive(minAliveness = Aliveness.Alive) {
        // fighting hypothetical integer wrapping
        var newId = sessionsIdCounter.incrementAndGet()
        val session = ClientOrSessionProxy(aliveFlagPath)
        for (attempt in 1..100) {
            if (newId != CompileService.NO_SESSION) {
                synchronized(state.sessions) {
                    if (!state.sessions.containsKey(newId)) {
                        state.sessions.put(newId, session)
                        return@ifAlive newId
                    }
                }
            }
            // assuming wrap, jumping to random number to reduce probability of further clashes
            newId = sessionsIdCounter.addAndGet(internalRng.nextInt())
        }
        throw Exception("Invalid state or algorithm error")
    }

    override fun releaseCompileSession(sessionId: Int) = ifAlive_Nothing(minAliveness = Aliveness.LastSession) {
        synchronized(state.sessions) {
            state.sessions.remove(sessionId)
            // TODO: some cleanup goes here
            if (state.sessions.isEmpty()) {
                // TODO: and some goes here
            }
        }
        timer.schedule(0) {
            periodicAndAfterSessionCheck()
        }
    }

    override fun checkCompilerId(expectedCompilerId: CompilerId): Boolean =
            (compilerId.compilerVersion.isEmpty() || compilerId.compilerVersion == expectedCompilerId.compilerVersion) &&
            (compilerId.compilerClasspath.all { expectedCompilerId.compilerClasspath.contains(it) }) &&
            !classpathWatcher.isChanged

    override fun getUsedMemory(): CompileService.CallResult<Long> = ifAlive { usedMemory(withGC = true) }

    override fun shutdown(): CompileService.CallResult<Nothing>  = ifAliveExclusive_Nothing(minAliveness = Aliveness.LastSession, ignoreCompilerChanged = true) {
        shutdownImpl()
    }

    override fun scheduleShutdown(graceful: Boolean): CompileService.CallResult<Boolean> = ifAlive(minAliveness = Aliveness.Alive) {
        if (!graceful || state.alive.compareAndSet(Aliveness.Alive.value, Aliveness.LastSession.value)) {
            timer.schedule(0) {
                ifAliveExclusive(minAliveness = Aliveness.LastSession, ignoreCompilerChanged = true) {
                    if (!graceful || state.sessions.isEmpty()) {
                        shutdownImpl()
                    }
                }
            }
            true
        }
        else false
    }

    override fun remoteCompile(sessionId: Int,
                               targetPlatform: CompileService.TargetPlatform,
                               args: Array<out String>,
                               servicesFacade: CompilerCallbackServicesFacade,
                               compilerOutputStream: RemoteOutputStream,
                               outputFormat: CompileService.OutputFormat,
                               serviceOutputStream: RemoteOutputStream,
                               operationsTracer: RemoteOperationsTracer?
    ): CompileService.CallResult<Int> =
            doCompile(sessionId, args, compilerOutputStream, serviceOutputStream, operationsTracer) { printStream, profiler ->
                when (outputFormat) {
                    CompileService.OutputFormat.PLAIN -> compiler[targetPlatform].exec(printStream, *args)
                    CompileService.OutputFormat.XML -> compiler[targetPlatform].execAndOutputXml(printStream, createCompileServices(servicesFacade, profiler), *args)
                }
            }

    override fun remoteIncrementalCompile(sessionId: Int,
                                          targetPlatform: CompileService.TargetPlatform,
                                          args: Array<out String>,
                                          servicesFacade: CompilerCallbackServicesFacade,
                                          compilerOutputStream: RemoteOutputStream,
                                          compilerOutputFormat: CompileService.OutputFormat,
                                          serviceOutputStream: RemoteOutputStream,
                                          operationsTracer: RemoteOperationsTracer?
    ): CompileService.CallResult<Int> =
            doCompile(sessionId, args, compilerOutputStream, serviceOutputStream, operationsTracer) { printStream, profiler ->
                when (compilerOutputFormat) {
                    CompileService.OutputFormat.PLAIN -> throw NotImplementedError("Only XML output is supported in remote incremental compilation")
                    CompileService.OutputFormat.XML -> compiler[targetPlatform].execAndOutputXml(printStream, createCompileServices(servicesFacade, profiler), *args)
                }
            }

    // internal implementation stuff

    // TODO: consider matching compilerId coming from outside with actual one
    //    private val selfCompilerId by lazy {
    //        CompilerId(
    //                compilerClasspath = System.getProperty("java.class.path")
    //                                            ?.split(File.pathSeparator)
    //                                            ?.map { File(it) }
    //                                            ?.filter { it.exists() }
    //                                            ?.map { it.absolutePath }
    //                                    ?: listOf(),
    //                compilerVersion = loadKotlinVersionFromResource()
    //        )
    //    }

    init {
        // assuming logically synchronized
        try {
            // cleanup for the case of incorrect restart and many other situations
            UnicastRemoteObject.unexportObject(this, false)
        }
        catch (e: NoSuchObjectException) {
            // ignoring if object already exported
        }

        val stub = UnicastRemoteObject.exportObject(this, port, LoopbackNetworkInterface.clientLoopbackSocketFactory, LoopbackNetworkInterface.serverLoopbackSocketFactory) as CompileService
        registry.rebind (COMPILER_SERVICE_RMI_NAME, stub);

        timer.schedule(0) {
            initiateElections()
        }
        timer.schedule(delay = DAEMON_PERIODIC_CHECK_INTERVAL_MS, period = DAEMON_PERIODIC_CHECK_INTERVAL_MS) {
            try {
                periodicAndAfterSessionCheck()
            }
            catch (e: Exception) {
                System.err.println("Exception in timer thread: " + e.message)
                e.printStackTrace(System.err)
                log.log(Level.SEVERE, "Exception in timer thread", e)
            }
        }
    }


    private fun periodicAndAfterSessionCheck() {

        ifAlive_Nothing(minAliveness = Aliveness.LastSession) {

            // 1. check if unused for a timeout - shutdown
            if (shutdownCondition({ daemonOptions.autoshutdownUnusedSeconds != COMPILE_DAEMON_TIMEOUT_INFINITE_S && compilationsCounter.get() == 0 && nowSeconds() - lastUsedSeconds > daemonOptions.autoshutdownUnusedSeconds },
                                  "Unused timeout exceeded ${daemonOptions.autoshutdownUnusedSeconds}s, shutting down")) {
                shutdown()
            }
            else {
                synchronized(state.sessions) {
                    // 2. check if any session hanged - clean
                    // making copy of the list before calling release
                    state.sessions.filterValues { !it.isAlive }.keys.toArrayList()
                }.forEach { releaseCompileSession(it) }

                // 3. check if in graceful shutdown state and all sessions are closed
                if (shutdownCondition({state.alive.get() == Aliveness.LastSession.value && state.sessions.none()}, "All sessions finished, shutting down")) {
                    shutdown()
                }

                // 4. clean dead clients, then check if any left - conditional shutdown (with small delay)
                    synchronized(state.clientProxies) { state.clientProxies.removeAll(state.clientProxies.filter { !it.isAlive }) }
                if (state.clientProxies.none() && compilationsCounter.get() > 0 && !state.delayedShutdownQueued.get()) {
                    log.info("No more clients left, delayed shutdown in ${daemonOptions.shutdownDelayMilliseconds}ms")
                    shutdownWithDelay()
                }
                // 5. check idle timeout - shutdown
                if (shutdownCondition({ daemonOptions.autoshutdownIdleSeconds != COMPILE_DAEMON_TIMEOUT_INFINITE_S && nowSeconds() - lastUsedSeconds > daemonOptions.autoshutdownIdleSeconds },
                                      "Idle timeout exceeded ${daemonOptions.autoshutdownIdleSeconds}s, shutting down") ||
                    // 6. discovery file removed - shutdown
                    shutdownCondition({ !runFile.exists() }, "Run file removed, shutting down") ||
                    // 7. compiler changed (seldom check) - shutdown
                    // TODO: could be too expensive anyway, consider removing this check
                    shutdownCondition({ classpathWatcher.isChanged }, "Compiler changed")) {
                    shutdown()
                }
            }
        }
    }


    private fun initiateElections() {

        ifAlive_Nothing {

            val aliveWithOpts = walkDaemons(File(daemonOptions.runFilesPathOrDefault), compilerId, filter = { f, p -> p != port }, report = { lvl, msg -> log.info(msg) })
                    .map { Pair(it, it.getDaemonJVMOptions()) }
                    .filter { it.second.isGood }
                    .sortedWith(compareBy(DaemonJVMOptionsMemoryComparator().reversed(), { it.second.get() }))
            if (aliveWithOpts.any()) {
                val fattestOpts = aliveWithOpts.first().second.get()
                if (fattestOpts memorywiseFitsInto daemonJVMOptions && !(daemonJVMOptions memorywiseFitsInto fattestOpts)) {
                    // all others are smaller that me, take overs' clients and shut them down
                    aliveWithOpts.forEach {
                        it.first.getClients().ifOrNull { it.isGood }?.let {
                            it.get().forEach { registerClient(it) }
                        }
                        it.first.scheduleShutdown(true)
                    }
                }
                else {
                    // there is ate least one bigger, handover my clients and shutdown
                    aliveWithOpts.first().first.let { fattest ->
                        getClients().ifOrNull { it.isGood }?.let {
                            it.get().forEach { fattest.registerClient(it) }
                        }
                    }
                    scheduleShutdown(true)
                }
            }
        }
    }

    private fun shutdownImpl() {
        log.info("Shutdown started")
        state.alive.set(Aliveness.Dying.value)
        UnicastRemoteObject.unexportObject(this, true)
        log.info("Shutdown complete")
        onShutdown()
    }

    private fun shutdownWithDelay() {
        state.delayedShutdownQueued.set(true)
        val currentCompilationsCount = compilationsCounter.get()
        timer.schedule(daemonOptions.shutdownDelayMilliseconds) {
            state.delayedShutdownQueued.set(false)
            if (currentCompilationsCount == compilationsCounter.get()) {
                log.fine("Execute delayed shutdown")
                shutdown()
            }
            else {
                log.info("Cancel delayed shutdown due to new client")
            }
        }
    }

    private fun shutdownCondition(check: () -> Boolean, message: String): Boolean {
        val res = check()
        if (res) {
            log.info(message)
        }
        return res
    }

    private fun doCompile(sessionId: Int,
                          args: Array<out String>,
                          compilerMessagesStreamProxy: RemoteOutputStream,
                          serviceOutputStreamProxy: RemoteOutputStream,
                          operationsTracer: RemoteOperationsTracer?,
                          body: (PrintStream, Profiler) -> ExitCode): CompileService.CallResult<Int> =
            ifAlive {

                operationsTracer?.before("compile")
                compilationsCounter.incrementAndGet()
                val rpcProfiler = if (daemonOptions.reportPerf) WallAndThreadTotalProfiler() else DummyProfiler()
                val compilerMessagesStream = PrintStream(BufferedOutputStream(RemoteOutputStreamClient(compilerMessagesStreamProxy, rpcProfiler), 4096))
                val serviceOutputStream = PrintStream(BufferedOutputStream(RemoteOutputStreamClient(serviceOutputStreamProxy, rpcProfiler), 4096))
                try {
                    checkedCompile(args, serviceOutputStream, rpcProfiler) {
                        val res = body(compilerMessagesStream, rpcProfiler).code
                        _lastUsedSeconds = nowSeconds()
                        res
                    }
                }
                finally {
                    serviceOutputStream.flush()
                    compilerMessagesStream.flush()
                    operationsTracer?.after("compile")
                }
            }

    private fun createCompileServices(facade: CompilerCallbackServicesFacade, rpcProfiler: Profiler): Services {
        val builder = Services.Builder()
        if (facade.hasIncrementalCaches() || facade.hasLookupTracker()) {
            builder.register(IncrementalCompilationComponents::class.java, RemoteIncrementalCompilationComponentsClient(facade, rpcProfiler))
        }
        if (facade.hasCompilationCanceledStatus()) {
            builder.register(CompilationCanceledStatus::class.java, RemoteCompilationCanceledStatusClient(facade, rpcProfiler))
        }
        return builder.build()
    }


    private fun<R> checkedCompile(args: Array<out String>, serviceOut: PrintStream, rpcProfiler: Profiler, body: () -> R): R {
        try {
            if (args.none())
                throw IllegalArgumentException("Error: empty arguments list.")
            log.info("Starting compilation with args: " + args.joinToString(" "))

            val profiler = if (daemonOptions.reportPerf) WallAndThreadAndMemoryTotalProfiler(withGC = false) else DummyProfiler()

            val res = profiler.withMeasure(null, body)

            val endMem = if (daemonOptions.reportPerf) usedMemory(withGC = false) else 0L

            log.info("Done with result " + res.toString())

            if (daemonOptions.reportPerf) {
                fun Long.ms() = TimeUnit.NANOSECONDS.toMillis(this)
                fun Long.kb() = this / 1024
                val pc = profiler.getTotalCounters()
                val rpc = rpcProfiler.getTotalCounters()

                "PERF: Compile on daemon: ${pc.time.ms()} ms; thread: user ${pc.threadUserTime.ms()} ms, sys ${(pc.threadTime - pc.threadUserTime).ms()} ms; rpc: ${rpc.count} calls, ${rpc.time.ms()} ms, thread ${rpc.threadTime.ms()} ms; memory: ${endMem.kb()} kb (${"%+d".format(pc.memory.kb())} kb)".let {
                    serviceOut.println(it)
                    log.info(it)
                }

                // this will only be reported if if appropriate (e.g. ByClass) profiler is used
                for ((obj, counters) in rpcProfiler.getCounters()) {
                    "PERF: rpc by $obj: ${counters.count} calls, ${counters.time.ms()} ms, thread ${counters.threadTime.ms()} ms".let {
                        serviceOut.println(it)
                        log.info(it)
                    }
                }
            }
            return res
        }
        // TODO: consider possibilities to handle OutOfMemory
        catch (e: Exception) {
            log.info("Error: $e")
            throw e
        }
    }

    private fun<R> ifAlive(minAliveness: Aliveness = Aliveness.Alive, ignoreCompilerChanged: Boolean = false, body: () -> R): CompileService.CallResult<R> = rwlock.read {
        ifAliveChecksImpl(minAliveness, ignoreCompilerChanged) { CompileService.CallResult.Good(body()) }
    }

    // TODO: find how to implement it without using unique name for this variant; making name deliberately ugly meanwhile
    private fun ifAlive_Nothing(minAliveness: Aliveness = Aliveness.Alive, ignoreCompilerChanged: Boolean = false, body: () -> Unit): CompileService.CallResult<Nothing> = rwlock.read {
        ifAliveChecksImpl(minAliveness, ignoreCompilerChanged) {
            body()
            CompileService.CallResult.Ok()
        }
    }

    private fun<R> ifAliveExclusive(minAliveness: Aliveness = Aliveness.Alive, ignoreCompilerChanged: Boolean = false, body: () -> R): CompileService.CallResult<R> = rwlock.write {
        ifAliveChecksImpl(minAliveness, ignoreCompilerChanged) { CompileService.CallResult.Good(body()) }
    }

    // see comment to ifAliveNothing
    private fun<R> ifAliveExclusive_Nothing(minAliveness: Aliveness = Aliveness.Alive, ignoreCompilerChanged: Boolean = false, body: () -> Unit): CompileService.CallResult<R> = rwlock.write {
        ifAliveChecksImpl(minAliveness, ignoreCompilerChanged) {
            body()
            CompileService.CallResult.Ok()

        }
    }

    inline private fun<R> ifAliveChecksImpl(minAliveness: Aliveness = Aliveness.Alive, ignoreCompilerChanged: Boolean = false, body: () -> CompileService.CallResult<R>): CompileService.CallResult<R> =
        when {
            state.alive.get() < minAliveness.value -> CompileService.CallResult.Dying()
            !ignoreCompilerChanged && classpathWatcher.isChanged -> {
                log.info("Compiler changed, scheduling shutdown")
                timer.schedule(0) { shutdown() }
                CompileService.CallResult.Dying()
            }
            else -> {
                try {
                    body()
                }
                catch (e: Exception) {
                    log.log(Level.SEVERE, "Exception", e)
                    CompileService.CallResult.Error(e.message ?: "unknown")
                }
            }
        }

}

internal inline fun<T> T.ifOrNull(pred: (T) -> Boolean): T? = if (pred(this)) this else null

