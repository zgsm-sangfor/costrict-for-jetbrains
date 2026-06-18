// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sina.weibo.agent.webview.WebViewManager
import java.net.ServerSocket
import java.net.Socket
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Extension process Socket server
 * Used to establish communication with extension process
 */
interface ISocketServer : Disposable {
    fun start(projectPath: String = ""): Any?
    fun stop()
    fun isRunning(): Boolean
}

class ExtensionSocketServer() : ISocketServer {
    private val logger = Logger.getInstance(ExtensionSocketServer::class.java)
    
    // Server socket
    private var serverSocket: ServerSocket? = null
    
    // Connected client managers
    private val clientManagers = ConcurrentHashMap<Socket, ExtensionHostManager>()
    
    // Server thread
    private var serverThread: Thread? = null
    
    // Current project path
    private var projectPath: String = ""
    
    // Whether running
    @Volatile
    private var isRunning = false

    lateinit var project: Project

    /**
     * Start Socket server
     * @param projectPath Current project path
     * @return Server port, -1 if failed
     */
    override fun start(projectPath: String): Int {
        if (isRunning) {
            logger.info("Socket server is already running")
            return serverSocket?.localPort ?: -1
        }
        
        this.projectPath = projectPath
        
        try {
            // Use 0 to indicate random port assignment
            serverSocket = ServerSocket(0)
            val port = serverSocket?.localPort ?: -1
            
            if (port <= 0) {
                logger.error("Failed to get valid port for socket server")
                return -1
            }
            
            isRunning = true
            logger.info("Starting socket server on port: $port")
            
            // Start the thread to accept connections
            serverThread = thread(start = true, name = "ExtensionSocketServer") {
                acceptConnections()
            }
            
            return port
        } catch (e: Exception) {
            logger.error("Failed to start socket server", e)
            stop()
            return -1
        }
    }
    
    /**
     * Stop Socket server
     */
    override fun stop() {
        if (!isRunning) {
            return
        }
        
        isRunning = false
        logger.info("Stopping socket server")
        
        // Close all client managers
        clientManagers.forEach { (_, manager) ->
            try {
                manager.dispose()
            } catch (e: Exception) {
                logger.warn("Failed to dispose client manager", e)
            }
        }
        clientManagers.clear()
        
        // Close the server
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            logger.warn("Failed to close server socket", e)
        }
        
        // Interrupt the server thread
        serverThread?.interrupt()
        serverThread = null
        serverSocket = null
        
        logger.info("Socket server stopped")
    }
    
    /**
     * Thread function for accepting connections
     */
    private fun acceptConnections() {
        val server = serverSocket ?: return
        
        logger.info("Socket server started, waiting for connections..., tid: ${Thread.currentThread().id}")
        
        while (isRunning && !Thread.currentThread().isInterrupted) {
            try {
                val clientSocket = server.accept()
                logger.info("New client connected from: ${clientSocket.inetAddress.hostAddress}")
                
                clientSocket.tcpNoDelay = true // Set no delay
                
                // Create extension host manager
                val manager = ExtensionHostManager(clientSocket, projectPath,project)
                clientManagers[clientSocket] = manager

                thread(start = true, name = "ClientHandler-${clientSocket.port}") {
                    handleClient(clientSocket, manager)
                }
            } catch (e: IOException) {
                if (isRunning) {
                    logger.error("Error accepting client connection", e)
                } else {
                    // IOException is thrown when ServerSocket is closed, this is normal
                    logger.info("Socket server closed")
                    break
                }
            } catch (e: InterruptedException) {
                // Thread interrupted, this is normal
                logger.info("Socket server thread interrupted")
                break
            } catch (e: Exception) {
                logger.error("Unexpected error in accept loop", e)
                if (isRunning) {
                    try {
                        // Retry after short delay
                        Thread.sleep(1000)
                    } catch (ie: InterruptedException) {
                        // Thread interrupted, server is shutting down
                        logger.info("Socket server thread interrupted during sleep")
                        break
                    }
                }
            }
        }
        
        logger.info("Socket accept loop terminated")
    }

    /**
     * Handle client connection
     */
    private fun handleClient(clientSocket: Socket, manager: ExtensionHostManager) {
        // True only when we detected an unhealthy connection mid-flight (i.e. the
        // extension host died unexpectedly). Distinguishes this from a normal
        // server shutdown (isRunning == false) so we only trigger cloud webview
        // recovery on genuine connection loss.
        var connectionLost = false
        try {
            // Start extension host manager
            manager.start()

            // Health check loop using polling (DO NOT read from socket - NodeSocket handles that)
            // Reading from clientSocket.getInputStream() would compete with NodeSocket's receive
            // thread and steal protocol messages, causing initialization to hang.
            while (clientSocket.isConnected && !clientSocket.isClosed && isRunning) {
                try {
                    Thread.sleep(5000)
                } catch (e: InterruptedException) {
                    logger.info("Client handler thread interrupted during health check sleep")
                    break
                }

                // Check socket health
                if (!isSocketHealthy(clientSocket)) {
                    logger.error("Detected unhealthy Socket connection, closing connection")
                    connectionLost = true
                    break
                }

                // Check RPC response state
                val responsiveState = manager.getResponsiveState()
                if (responsiveState != null) {
                    logger.debug("Current RPC response state: $responsiveState")
                }
            }
        } catch (e: Exception) {
            // Filter out InterruptedException, it means normal interruption
            if (e !is InterruptedException) {
                logger.error("Error handling client socket: ${e.message}", e)
                // An unexpected exception while the server is still running also
                // indicates an abnormal connection loss.
                if (isRunning) connectionLost = true
            } else {
                logger.info("Client handler thread interrupted during processing")
            }
        } finally {
            // Clean up resources
            manager.dispose()
            clientManagers.remove(clientSocket)

            if (!clientSocket.isClosed) {
                try {
                    clientSocket.close()
                } catch (e: IOException) {
                    logger.warn("Failed to close client socket", e)
                }
            }

            logger.info("Client socket closed and removed")

            // If the extension host connection died unexpectedly (not a normal
            // server shutdown), the cloud UI webview is now stranded: all its
            // API calls are proxied through the (now dead) extension host, so
            // every request hangs and user retries do nothing. Reload the cloud
            // webview so its bootstrap re-runs and reconnects to the cs-cloud
            // daemon, which runs as an independent process and survives the
            // socket loss. connectionLost is only set on an in-flight failure
            // while the server was still running, so normal shutdown is excluded.
            if (connectionLost) {
                triggerCloudWebViewRecovery()
            }
        }
    }

    /**
     * Ask the WebViewManager to reload the cloud UI webview after an
     * extension-host connection loss. Best-effort: failures here must not
     * interfere with connection cleanup.
     */
    private fun triggerCloudWebViewRecovery() {
        try {
            val webviewManager = project.getService(WebViewManager::class.java)
            logger.info("Extension host connection lost; triggering cloud webview recovery reload")
            webviewManager.reloadCloudWebView()
        } catch (e: Exception) {
            logger.warn("Failed to trigger cloud webview recovery after connection loss", e)
        }
    }
    
    /**
     * Check socket connection health
     */
    private fun isSocketHealthy(socket: Socket): Boolean {
        val isHealthy = socket.isConnected && 
                        !socket.isClosed && 
                        !socket.isInputShutdown && 
                        !socket.isOutputShutdown
        
        if (!isHealthy) {
            logger.warn("Socket health check failed: isConnected=${socket.isConnected}, " +
                       "isClosed=${socket.isClosed}, " +
                       "isInputShutdown=${socket.isInputShutdown}, " +
                       "isOutputShutdown=${socket.isOutputShutdown}")
        }
        
        return isHealthy
    }
    
    /**
     * Get current port
     */
    fun getPort(): Int {
        return serverSocket?.localPort ?: -1
    }
    
    /**
     * Whether running
     */
    override fun isRunning(): Boolean {
        return isRunning
    }
    
    /**
     * Resource cleanup
     */
    override fun dispose() {
        stop()
    }
    
    /**
     * Connect to debug host
     * @param host Debug host address
     * @param port Debug host port
     * @return Whether connection is successful
     */
    fun connectToDebugHost(host: String, port: Int): Boolean {
        if (isRunning) {
            logger.info("Socket server is already running, stopping first")
            stop()
        }
        
        try {
            logger.info("Connecting to debug host at $host:$port")
            
            // Directly connect to the specified address and port
            val clientSocket = Socket(host, port)
            clientSocket.tcpNoDelay = true // Set no delay
            
            isRunning = true
            
            // Create extension host manager
            val manager = ExtensionHostManager(clientSocket, projectPath,project)
            clientManagers[clientSocket] = manager
            
            // Start connection handling in background thread
            thread(start = true, name = "DebugHostHandler") {
                handleClient(clientSocket, manager)
            }

            logger.info("Successfully connected to debug host at $host:$port")
            return true
        } catch (e: Exception) {
            logger.error("Failed to connect to debug host at $host:$port", e)
            stop()
            return false
        }
    }
} 
