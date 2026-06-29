// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.ipc.proxy

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.sina.weibo.agent.ipc.IMessagePassingProtocol
import com.sina.weibo.agent.ipc.proxy.uri.IURITransformer
import com.sina.weibo.agent.ipc.proxy.uri.UriReplacer
import com.sina.weibo.agent.util.doInvokeMethod
import kotlinx.coroutines.*
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext

/**
 * Request initiator
 */
enum class RequestInitiator {
    /**
     * Initiated locally
     */
    LocalSide,
    
    /**
     * Initiated by the other side
     */
    OtherSide
}

/**
 * Responsive state
 */
enum class ResponsiveState {
    /**
     * Responsive
     */
    Responsive,
    
    /**
     * Unresponsive
     */
    Unresponsive
}

/**
 * RPC protocol logger interface
 */
interface IRPCProtocolLogger {
    /**
     * Log incoming message
     */
    fun logIncoming(msgLength: Int, req: Int, initiator: RequestInitiator, str: String, data: Any? = null)
    
    /**
     * Log outgoing message
     */
    fun logOutgoing(msgLength: Int, req: Int, initiator: RequestInitiator, str: String, data: Any? = null)
}

/**
 * RPC protocol implementation
 * Corresponds to RPCProtocol in VSCode
 */
class RPCProtocol(
    private val protocol: IMessagePassingProtocol,
    private val logger: IRPCProtocolLogger? = null,
    private val uriTransformer: IURITransformer? = null
) : IRPCProtocol, Disposable {
    
    companion object {
        private val LOG = Logger.getInstance(RPCProtocol::class.java)
        
        /**
         * Unresponsive time threshold (milliseconds)
         */
        private const val UNRESPONSIVE_TIME = 3 * 1000 // 3s, same as TS implementation
        
        /**
         * RPC protocol symbol (used to identify objects implementing this interface)
         */
        private val RPC_PROTOCOL_SYMBOL = "rpcProtocol"
        
        /**
         * RPC proxy symbol (used to identify proxy objects)
         */
        private val RPC_PROXY_SYMBOL = "rpcProxy"
        
        /**
         * Dollar sign character code
         */
        private const val DOLLAR_SIGN_CHAR_CODE = 36 // '$'
        
        /**
         * No operation
         */
        private val noop: () -> Unit = {}
    }
    
    /**
     * Coroutine scope
     */
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    /**
     * URI replacer
     */
    private val uriReplacer: ((String, Any?) -> Any?)? = if (uriTransformer != null) UriReplacer(uriTransformer) else null
    
    /**
     * Whether disposed
     */
    private var isDisposed = false
    
    /**
     * Local object list
     */
    private val locals = arrayOfNulls<Any?>(ProxyIdentifier.count + 1)
    
    /**
     * Proxy object list
     */
    private val proxies = arrayOfNulls<Any?>(ProxyIdentifier.count + 1)
    
    /**
     * Last message ID
     */
    private var lastMessageId = 0
    
    /**
     * Cancelled handlers
     */
    private val cancelInvokedHandlers = ConcurrentHashMap<String, () -> Unit>()
    
    /**
     * Pending RPC replies
     */
    private val pendingRPCReplies = ConcurrentHashMap<String, PendingRPCReply>()
    
    /**
     * Responsive state
     */
    override var responsiveState = ResponsiveState.Responsive
        private set
    
    /**
     * Unacknowledged count
     */
    private var unacknowledgedCount = 0
    
    /**
     * Unresponsive time
     */
    private var unresponsiveTime = 0L
    
    /**
     * Asynchronous unresponsive check job
     */
    private var asyncCheckUnresponsiveJob: Job? = null
    
    /**
     * Responsive state change event listeners
     */
    private val onDidChangeResponsiveStateListeners = mutableListOf<(ResponsiveState) -> Unit>()
    
    init {
        protocol.onMessage { data -> receiveOneMessage(data) }
    }
    
    /**
     * Add responsive state change event listener
     */
    fun onDidChangeResponsiveState(listener: (ResponsiveState) -> Unit): Disposable {
        onDidChangeResponsiveStateListeners.add(listener)
        return Disposable {
            onDidChangeResponsiveStateListeners.remove(listener)
        }
    }
    
    override fun dispose() {
        isDisposed = true
        
       // Cancel all coroutines
       coroutineScope.cancel()
      
       // Release all pending replies with cancel error
       pendingRPCReplies.keys.forEach { msgId ->
           val pending = pendingRPCReplies[msgId]
           pendingRPCReplies.remove(msgId)
           pending?.resolveErr(CanceledException())
       }
    }
    
    override suspend fun drain(): Unit {
        protocol.drain()
    }
    
    /**
     * Triggered before sending a request
     */
    private fun onWillSendRequest(req: Int) {
        if (unacknowledgedCount == 0) {
           // This is the first request we've sent in a while
           // Mark this moment as the start of the unresponsive countdown
           unresponsiveTime = System.currentTimeMillis() + UNRESPONSIVE_TIME
           LOG.debug("Set initial unresponsive check time, request ID: $req, unresponsive time: ${unresponsiveTime}ms")
        }
        unacknowledgedCount++

       // Check every 2 seconds for unresponsiveness
       if (asyncCheckUnresponsiveJob == null || asyncCheckUnresponsiveJob?.isActive == false) {
           LOG.debug("Start unresponsive check task")
           asyncCheckUnresponsiveJob = coroutineScope.launch {
               while (isActive) {
                   checkUnresponsive()
                   delay(2000)
               }
           }
       }
    }
    
    /**
     * Triggered when an acknowledge response is received
     */
    private fun onDidReceiveAcknowledge(req: Int) {
       // The next possible unresponsive time is now + increment
       unresponsiveTime = System.currentTimeMillis() + UNRESPONSIVE_TIME
       unacknowledgedCount--
//        LOG.debug("Received acknowledge, request ID: $req, unacknowledged count decreased: $unacknowledgedCount, updated unresponsive time: ${unresponsiveTime}ms")
      
       if (unacknowledgedCount == 0) {
           // No longer need to check for unresponsiveness
           LOG.debug("No unacknowledged requests, cancel unresponsive check task")
           asyncCheckUnresponsiveJob?.cancel()
           asyncCheckUnresponsiveJob = null
       }
      
       // The other side is responsive!
       setResponsiveState(ResponsiveState.Responsive)
    }
    
    /**
     * Check for unresponsiveness
     */
    private fun checkUnresponsive() {
        if (unacknowledgedCount == 0) {
            // Not waiting for anything => cannot determine responsiveness
            return
        }
        
        val currentTime = System.currentTimeMillis()
        if (currentTime > unresponsiveTime) {
            // Unresponsive!!
            LOG.warn("Detected unresponsive state: current time ${currentTime}ms > unresponsive threshold ${unresponsiveTime}ms, unacknowledged requests: $unacknowledgedCount")
            setResponsiveState(ResponsiveState.Unresponsive)
        } else {
            // Not yet unresponsive, log time info
            if (LOG.isDebugEnabled) {
                val remainingTime = unresponsiveTime - currentTime
                LOG.debug("Connection responsive, time left before unresponsive threshold: ${remainingTime}ms, unacknowledged requests: $unacknowledgedCount")
            }
        }
    }
    
    /**
     * Set responsive state
     */
    private fun setResponsiveState(newResponsiveState: ResponsiveState) {
        if (responsiveState == newResponsiveState) {
            // No change
            return
        }
        
        LOG.info("Responsive state changed from $responsiveState to $newResponsiveState")
        responsiveState = newResponsiveState
        
       // Notify listeners
       onDidChangeResponsiveStateListeners.forEach { it(responsiveState) }
    }
    
    /**
     * Transform incoming URIs
     */
    fun <T> transformIncomingURIs(obj: T): T {
        if (uriTransformer == null) {
            return obj
        }
        
        @Suppress("UNCHECKED_CAST")
        return when (obj) {
           // If the object is a URI, convert directly
           is java.net.URI -> uriTransformer.transformIncoming(obj) as T
          
           // If the object is a string and looks like a URI
           is String -> {
               try {
                   val uri = java.net.URI(obj)
                   uriTransformer.transformIncoming(uri).toString() as T
               } catch (e: Exception) {
                   obj
               }
           }
          
           // If the object is a list, recursively convert each element
           is List<*> -> {
               obj.map { item -> transformIncomingURIs(item) } as T
           }
          
           // If the object is a map, recursively convert each value, especially for URI-related keys
           is Map<*, *> -> {
               val result = mutableMapOf<Any?, Any?>()
               obj.forEach { (key, value) ->
                   val transformedValue = if (key is String && (
                       key == "uri" ||
                       key == "documentUri" ||
                       key == "targetUri" ||
                       key == "sourceUri" ||
                       key.endsWith("Uri"))
                   ) {
                       transformIncomingURIs(value)
                   } else {
                       transformIncomingURIs(value)
                   }
                   result[key] = transformedValue
               }
               result as T
           }
          
           // Other objects, if custom class, may need further handling
           else -> obj
        }
    }
    
    override fun <T> getProxy(identifier: ProxyIdentifier<T>): T {
        val rpcId = identifier.nid
        val sid = identifier.sid
        
        if (proxies[rpcId] == null) {
            proxies[rpcId] = createProxy(rpcId, sid)
        }
        
        @Suppress("UNCHECKED_CAST")
        return proxies[rpcId] as T
    }
    
    /**
     * Create proxy object
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> createProxy(rpcId: Int, debugName: String): T {
       // Try to get T's Class object
       val interfaces = mutableListOf<Class<*>>()
      
//        // Add default Any interface
//        interfaces.add(Any::class.java)
      
       // Try to get interface info from generic parameter
       try {
           val classLoader = javaClass.classLoader
           val proxyClass = classLoader.loadClass(debugName)
           if (proxyClass.isInterface) {
               interfaces.add(proxyClass)
           }
       } catch (e: Exception) {
           LOG.warn("Failed to load interface class $debugName: ${e.message}")
       }
      
       // Use Java dynamic proxy to create proxy object
       return Proxy.newProxyInstance(
           javaClass.classLoader,
           interfaces.toTypedArray()
       ) { _, method, args ->
           val name = method.name
      
           // Handle special methods
           if (name == "toString") {
               return@newProxyInstance "Proxy($debugName)"
           }
      
           // Handle special symbols
           if (name == RPC_PROXY_SYMBOL) {
               return@newProxyInstance debugName
           }
      
           // Call remote method
           if (name.isNotEmpty()) {
               return@newProxyInstance remoteCall(rpcId, "\$$name", args ?: emptyArray())
           }
      
           null
       } as T
    }
    
    override fun <T, R : T> set(identifier: ProxyIdentifier<T>, instance: R): R {
        locals[identifier.nid] = instance
        return instance
    }
    
    override fun assertRegistered(identifiers: List<ProxyIdentifier<*>>) {
        for (identifier in identifiers) {
            if (locals[identifier.nid] == null) {
                throw IllegalStateException("Missing proxy instance ${identifier.sid}")
            }
        }
    }

    /**
     * Remote call
     */
    private fun remoteCall(rpcId: Int, methodName: String, args: Array<out Any?>): Any {
        if (isDisposed) {
            throw CanceledException()
        }
        LOG.debug("remoteCall: $rpcId.$methodName.${lastMessageId+1}")

        // Check if the last argument is a cancellation token
        var cancellationToken: Any? = null
        val effectiveArgs = if (args.isNotEmpty()) {
           // There should be more complex logic for detecting cancellation token
           val lastArg = args.last()
           if (lastArg != null && lastArg::class.java.simpleName == "CancellationToken") {
               cancellationToken = lastArg
               args.dropLast(1).toTypedArray()
           } else {
               args
           }
        } else {
            args
        }

        val serializedRequestArguments = MessageIO.serializeRequestArguments(args.toList(), uriReplacer)
        
        val req = ++lastMessageId
        val callId = req.toString()
        val result = LazyPromise()

       // Use LazyPromise to implement Promise functionality
       val deferred = LazyPromise()
      
       // Create Disposable object for cleanup on cancel
       val disposable = Disposable {
           if (!deferred.isCompleted) {
               deferred.cancel()
           }
       }
        
        pendingRPCReplies[callId] = PendingRPCReply(result, disposable)
        onWillSendRequest(req)

        val usesCancellationToken = cancellationToken != null
        val msg = MessageIO.serializeRequest(req, rpcId, methodName, serializedRequestArguments, usesCancellationToken)
        
        logger?.logOutgoing(
            msg.size,
            req,
            RequestInitiator.LocalSide,
            "request: ${getStringIdentifierForProxy(rpcId)}.$methodName(",
            args
        )
        
        protocol.send(msg)

        // Directly return Promise, do not block current thread
        return result
    }
    
    /**
     * Receive a message
     */
    private fun receiveOneMessage(rawmsg: ByteArray) {
        if (isDisposed) {
            return
        }

        val msgLength = rawmsg.size
        val buff = MessageBuffer.read(rawmsg, 0)
        val messageType = MessageType.fromValue(buff.readUInt8()) ?: return
        val req = buff.readUInt32()

        LOG.debug("receiveOneMessage: $messageType, req: $req, length: $msgLength")
        when (messageType) {
            MessageType.RequestJSONArgs, MessageType.RequestJSONArgsWithCancellation -> {
                val (rpcId, method, args) = MessageIO.deserializeRequestJSONArgs(buff)
                // Transform URI
                val transformedArgs = transformIncomingURIs(args)
                receiveRequest(
                    msgLength,
                    req,
                    rpcId,
                    method,
                    transformedArgs,
                    messageType == MessageType.RequestJSONArgsWithCancellation
                )
            }
            MessageType.RequestMixedArgs, MessageType.RequestMixedArgsWithCancellation -> {
                val (rpcId, method, args) = MessageIO.deserializeRequestMixedArgs(buff)
                // Transform URI
                val transformedArgs = transformIncomingURIs(args)
                receiveRequest(
                    msgLength,
                    req,
                    rpcId,
                    method,
                    transformedArgs,
                    messageType == MessageType.RequestMixedArgsWithCancellation
                )
            }
            MessageType.Acknowledged -> {
                logger?.logIncoming(msgLength, req, RequestInitiator.LocalSide, "ack")
                onDidReceiveAcknowledge(req)
            }
            MessageType.Cancel -> {
                receiveCancel(msgLength, req)
            }
            MessageType.ReplyOKEmpty -> {
                receiveReply(msgLength, req, null)
            }
            MessageType.ReplyOKJSON -> {
                val value = MessageIO.deserializeReplyOKJSON(buff)
                // Transform URI
                val transformedValue = transformIncomingURIs(value)
                receiveReply(msgLength, req, transformedValue)
            }
            MessageType.ReplyOKJSONWithBuffers -> {
                val value = MessageIO.deserializeReplyOKJSONWithBuffers(buff, uriReplacer)
                receiveReply(msgLength, req, value)
            }
            MessageType.ReplyOKVSBuffer -> {
                val value = MessageIO.deserializeReplyOKVSBuffer(buff)
                receiveReply(msgLength, req, value)
            }
            MessageType.ReplyErrError -> {
                val err = MessageIO.deserializeReplyErrError(buff)
                // Transform URI
                val transformedErr = transformIncomingURIs(err)
                receiveReplyErr(msgLength, req, transformedErr)
            }
            MessageType.ReplyErrEmpty -> {
                receiveReplyErr(msgLength, req, null)
            }
        }
    }
    
    /**
     * Receive request
     */
    private fun receiveRequest(
        msgLength: Int,
        req: Int,
        rpcId: Int,
        method: String,
        args: List<Any?>,
        usesCancellationToken: Boolean
    ) {
        LOG.debug("receiveRequest:$req.$rpcId.$method()")
        logger?.logIncoming(
            msgLength,
            req,
            RequestInitiator.OtherSide,
            "receiveRequest ${getStringIdentifierForProxy(rpcId)}.$method(",
            args
        )
        
        val callId = req.toString()
        
        val promise: Deferred<Any?>
        val cancel: () -> Unit
        
       // Use coroutine to handle request
       if (usesCancellationToken) {
           // Create coroutine job, can be cancelled
           val job = Job()
          
           // Create coroutine context
           val context: CoroutineContext = job + Dispatchers.Default
      
           // Start coroutine
           promise = coroutineScope.async(context) {
               // Add cancellation token
               val argsList = args.toMutableList()
               // Note: should add a CancellationToken object here
               // But in Kotlin, we can use coroutine's cancel mechanism
               invokeHandler(rpcId, method, argsList)
           }
          
           cancel = { job.cancel() }
       } else {
           // Cannot be cancelled
           promise = coroutineScope.async {
               invokeHandler(rpcId, method, args)
           }
           cancel = noop
       }
        
        cancelInvokedHandlers[callId] = cancel
        
       // Acknowledge request
       val msg = MessageIO.serializeAcknowledged(req)
       logger?.logOutgoing(msg.size, req, RequestInitiator.OtherSide, "ack")
       protocol.send(msg)
      
       // Handle request result
       coroutineScope.launch {
           try {
               val result = promise.await()
//                LOG.info("response: $req.$rpcId.$method")
               cancelInvokedHandlers.remove(callId)
               val msg = MessageIO.serializeReplyOK(req, result, uriReplacer)
               logger?.logOutgoing(msg.size, req, RequestInitiator.OtherSide, "reply:", result)
               protocol.send(msg)
           } catch (err: Throwable) {
               cancelInvokedHandlers.remove(callId)
               val msg = MessageIO.serializeReplyErr(req, err)
               logger?.logOutgoing(msg.size, req, RequestInitiator.OtherSide, "replyErr:", err)
               protocol.send(msg)
           }
       }
    }
    
    /**
     * Receive cancel
     */
    private fun receiveCancel(msgLength: Int, req: Int) {
        logger?.logIncoming(msgLength, req, RequestInitiator.OtherSide, "receiveCancel")
        val callId = req.toString()
        cancelInvokedHandlers[callId]?.invoke()
    }
    
    /**
     * Receive reply
     */
    private fun receiveReply(msgLength: Int, req: Int, value: Any?) {
        logger?.logIncoming(msgLength, req, RequestInitiator.LocalSide, "receiveReply:", value)
        val callId = req.toString()
        if (!pendingRPCReplies.containsKey(callId)) {
            return
        }
        
        val pendingReply = pendingRPCReplies[callId] ?: return
        pendingRPCReplies.remove(callId)
        
        pendingReply.resolveOk(value)
    }
    
    /**
     * Receive error reply
     */
    private fun receiveReplyErr(msgLength: Int, req: Int, value: Throwable?) {
        logger?.logIncoming(msgLength, req, RequestInitiator.LocalSide, "receiveReplyErr:", value)
        
        val callId = req.toString()
        if (!pendingRPCReplies.containsKey(callId)) {
            return
        }
        
        val pendingReply = pendingRPCReplies[callId] ?: return
        pendingRPCReplies.remove(callId)
        
        val err = value ?: Exception("Unknown error")
        pendingReply.resolveErr(err)
    }
    
    /**
     * Invoke handler
     */
    private suspend fun invokeHandler(rpcId: Int, methodName: String, args: List<Any?>): Any? {
        return try {
            doInvokeHandler(rpcId, methodName, args)
        } catch (err: Throwable) {
//            throw err
            LOG.error("Error invoking handler: $methodName(${args.joinToString(", ")})", err)
            null
        }
    }
    
    /**
     * Execute handler invocation
     */
    private suspend fun doInvokeHandler(rpcId: Int, methodName: String, args: List<Any?>): Any? {
        // Trace configuration-related RPC calls (methodName already has $ stripped by MessageIO)
        if (methodName == "updateConfigurationOption" || methodName == "removeConfigurationOption" ||
            methodName == "initializeConfiguration") {
            try {
                java.io.File("/tmp/cos-cli-debug.log").appendText(
                    "[RPC] doInvokeHandler received method=$methodName, rpcId=$rpcId, argsCount=${args.size}\n"
                )
            } catch (_: Exception) {}
        }
        val actor = locals[rpcId]
        if(actor == null) {
            val actorName = getStringIdentifierForProxy(rpcId)
            LOG.error("Unknown actor $actorName (rpcId: $rpcId)")
            
            // 特殊处理 MainThreadWorkspace，尝试延迟获取
            if (actorName == "MainThreadWorkspace") {
                LOG.warn("尝试延迟获取 MainThreadWorkspace 实例")
                try {
                    // 尝试获取当前项目
                    val projectManager = com.intellij.openapi.project.ProjectManager.getInstance()
                    val openProjects = projectManager.openProjects
                    if (openProjects.isNotEmpty()) {
                        val project = openProjects[0] // 取第一个打开的项目
                        val workspaceManager = project.getService(com.sina.weibo.agent.core.WorkspaceManager::class.java)
                        if (workspaceManager != null) {
                            // 注册到 locals 数组中
                            locals[rpcId] = workspaceManager
                            LOG.info("成功延迟注册 MainThreadWorkspace 实例")
                            // 使用反射调用方法
                            val method = workspaceManager::class.java.methods.find {
                                it.name == methodName && it.parameterCount == args.size
                            }
                            if (method != null) {
                                return method.invoke(workspaceManager, *args.toTypedArray())
                            } else {
                                LOG.error("找不到匹配的方法: $methodName")
                            }
                        }
                    }
                } catch (e: Exception) {
                    LOG.error("延迟获取 MainThreadWorkspace 失败", e)
                }
            }
            
            return null
        }
       // Use reflection to get method with parameter type matching
       val method = try {
           findBestMatchingMethod(actor, methodName, args)
       } catch (e: Exception) {
           throw IllegalStateException("Unknown method $methodName on actor ${getStringIdentifierForProxy(rpcId)}")
       }

        return doInvokeMethod(method, args, actor)
    }

    /**
     * Find the best matching method based on method name and argument types.
     * Uses Java reflection instead of Kotlin reflection to avoid issues with
     * shadowJar relocate not updating Kotlin metadata.
     */
    private fun findBestMatchingMethod(actor: Any, methodName: String, args: List<Any?>): Method {
        val candidateMethods = actor.javaClass.methods.filter { it.name == methodName }

        if (candidateMethods.isEmpty()) {
            throw NoSuchMethodException("No method named '$methodName' found")
        }

        if (candidateMethods.size == 1) {
            return candidateMethods.first()
        }

        // For suspend functions, the continuation parameter is added by the compiler
        // So we need to account for that when counting parameters
        val methodsWithMatchingParamCount = candidateMethods.filter { method ->
            val paramTypes = method.parameterTypes
            val isSuspend = paramTypes.isNotEmpty() &&
                    Continuation::class.java.isAssignableFrom(paramTypes.last())
            val actualParamCount = if (isSuspend) paramTypes.size - 1 else paramTypes.size
            actualParamCount == args.size
        }

        if (methodsWithMatchingParamCount.isEmpty()) {
            // If no exact parameter count match, try to find a method that can accept the arguments
            val compatibleMethods = candidateMethods.filter { method ->
                val paramTypes = method.parameterTypes
                val isSuspend = paramTypes.isNotEmpty() &&
                        Continuation::class.java.isAssignableFrom(paramTypes.last())
                val actualParamCount = if (isSuspend) paramTypes.size - 1 else paramTypes.size
                actualParamCount >= args.size // Method can accept fewer arguments (with defaults)
            }
            if (compatibleMethods.isNotEmpty()) {
                return compatibleMethods.first()
            }
            throw NoSuchMethodException("No method '$methodName' with ${args.size} parameters found")
        }

        if (methodsWithMatchingParamCount.size == 1) {
            return methodsWithMatchingParamCount.first()
        }

        // Multiple methods with same parameter count, try to match by type
        for (method in methodsWithMatchingParamCount) {
            if (isMethodCompatible(method, args)) {
                return method
            }
        }

        // If no perfect match, return the first one with matching parameter count
        return methodsWithMatchingParamCount.first()
    }

    /**
     * Check if a method is compatible with the given arguments.
     * Uses Java reflection instead of Kotlin reflection.
     */
    private fun isMethodCompatible(method: Method, args: List<Any?>): Boolean {
        val paramTypes = method.parameterTypes
        val isSuspend = paramTypes.isNotEmpty() &&
                Continuation::class.java.isAssignableFrom(paramTypes.last())
        val actualParamCount = if (isSuspend) paramTypes.size - 1 else paramTypes.size

        if (actualParamCount != args.size) {
            return false
        }

        for (i in 0 until actualParamCount) {
            val paramClass = paramTypes[i]
            val arg = args[i]

            if (arg == null) {
                // Null argument is compatible with non-primitive parameters
                if (paramClass.isPrimitive) {
                    return false
                }
            } else {
                // Check type compatibility
                val argClass = arg::class.java

                // Handle primitive type conversions (similar to doInvokeMethod)
                val isCompatible = when {
                    paramClass.isAssignableFrom(argClass) -> true
                    // Handle boxed types
                    isBoxedAssignable(paramClass, argClass) -> true
                    // Handle Double to numeric type conversions
                    arg is Double && isNumericOrBoolean(paramClass) -> true
                    // Handle String compatibility
                    arg is String && paramClass == String::class.java -> true
                    // Handle Map to String conversion for ExtensionIdentifier
                    arg is Map<*, *> && paramClass == String::class.java -> true
                    else -> false
                }

                if (!isCompatible) {
                    return false
                }
            }
        }

        return true
    }

    /**
     * Check if the argument class can be assigned to the parameter class,
     * considering boxed/primitive type conversions.
     */
    private fun isBoxedAssignable(paramClass: Class<*>, argClass: Class<*>): Boolean {
        return when (paramClass) {
            Int::class.java, Integer::class.java -> argClass == Int::class.java || argClass == Integer::class.java
            Long::class.java, java.lang.Long::class.java -> argClass == Long::class.java || argClass == java.lang.Long::class.java
            Double::class.java, java.lang.Double::class.java -> argClass == Double::class.java || argClass == java.lang.Double::class.java
            Float::class.java, java.lang.Float::class.java -> argClass == Float::class.java || argClass == java.lang.Float::class.java
            Boolean::class.java, java.lang.Boolean::class.java -> argClass == Boolean::class.java || argClass == java.lang.Boolean::class.java
            Short::class.java, java.lang.Short::class.java -> argClass == Short::class.java || argClass == java.lang.Short::class.java
            Byte::class.java, java.lang.Byte::class.java -> argClass == Byte::class.java || argClass == java.lang.Byte::class.java
            Char::class.java, java.lang.Character::class.java -> argClass == Char::class.java || argClass == java.lang.Character::class.java
            else -> false
        }
    }

    /**
     * Check if the class is a numeric type or boolean.
     */
    private fun isNumericOrBoolean(clazz: Class<*>): Boolean {
        return clazz == Int::class.java || clazz == Integer::class.java ||
                clazz == Long::class.java || clazz == java.lang.Long::class.java ||
                clazz == Float::class.java || clazz == java.lang.Float::class.java ||
                clazz == Short::class.java || clazz == java.lang.Short::class.java ||
                clazz == Byte::class.java || clazz == java.lang.Byte::class.java ||
                clazz == Boolean::class.java || clazz == java.lang.Boolean::class.java
    }


}