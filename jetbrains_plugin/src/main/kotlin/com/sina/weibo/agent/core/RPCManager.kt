// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.core

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sina.weibo.agent.actors.*
import com.sina.weibo.agent.ipc.IMessagePassingProtocol
import com.sina.weibo.agent.ipc.proxy.IRPCProtocol
import com.sina.weibo.agent.ipc.proxy.RPCProtocol
import com.sina.weibo.agent.ipc.proxy.logger.FileRPCProtocolLogger
import com.sina.weibo.agent.ipc.proxy.uri.IURITransformer
import com.sina.weibo.agent.theme.ThemeManager
import com.sina.weibo.agent.util.ProxyConfigUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Responsible for managing RPC protocols, service registration and implementation, plugin lifecycle management
 * This class is based on VSCode's rpcManager.js implementation
 */
class RPCManager(
    private val protocol: IMessagePassingProtocol,
    private val extensionManager: ExtensionManager,
    private val uriTransformer: IURITransformer? = null,
    private val project: Project
) {
    private val logger = Logger.getInstance(RPCManager::class.java)
    private val rpcProtocol: IRPCProtocol = RPCProtocol(protocol, FileRPCProtocolLogger(), uriTransformer)

    init {
        setupDefaultProtocols()
        setupExtensionRequiredProtocols()
        setupWeCodeRequiredProtocols()
        setupCostrictFuncitonProtocols()
        setupRooCodeFuncitonProtocols()
        setupKiloCodeFunctionProtocols()
        setupWebviewProtocols()
    }


    /**
     * Start initializing plugin environment
     * Send configuration and workspace information to extension process
     */
    suspend fun startInitialize() {
        // Debug: log immediately when startInitialize is called
        try {
            java.io.File("/tmp/cos-cli-debug.log").appendText(
                "[INIT] startInitialize() CALLED\n"
            )
        } catch (_: Exception) {}
        try {
            logger.info("Starting to initialize plugin environment")
            withContext(Dispatchers.IO) {
                // Get ExtHostConfiguration proxy
                val extHostConfiguration =
                    rpcProtocol.getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostConfiguration)

                // Send empty configuration model
                logger.info("Sending configuration information to extension process")
                val themeName =
                    if (ThemeManager.getInstance().isDarkThemeForce()) "Visual Studio 2017 Dark - C++" else "Visual Studio 2017 Light - C++"

                // Get proxy configuration
                val httpProxyConfig = ProxyConfigUtil.getHttpProxyConfigForInitialization()

                // Build configuration contents
                val contentsBuilder = mutableMapOf<String, Any>(
                    "workbench.colorTheme" to themeName
                )

                // Add proxy configuration if available
                httpProxyConfig?.let {
                    contentsBuilder["http"] = it
                    logger.info("Using proxy configuration for initialization: $it")
                }

                // Create empty configuration model sections
                val emptyMap = mapOf(
                    "contents" to emptyMap<String, Any>(),
                    "keys" to emptyList<String>(),
                    "overrides" to emptyList<String>()
                )

                // Load persisted configuration from ~/.costrict-jetbrains/config.json
                // so that settings like costrict.uiMode survive extension host restarts.
                val persistedConfig = loadPersistedConfig()
                val userLocalModel = if (persistedConfig.isNotEmpty()) {
                    val nested = buildNestedContents(persistedConfig)
                    // Debug: log what we are sending
                    try {
                        java.io.File("/tmp/cos-cli-debug.log").appendText(
                            "[INIT] userLocal contents: ${com.google.gson.Gson().toJson(nested)}\n"
                        )
                    } catch (_: Exception) {}
                    mapOf(
                        "contents" to nested,
                        "keys" to persistedConfig.keys.toList(),
                        "overrides" to emptyList<String>()
                    )
                } else {
                    emptyMap
                }

                val emptyConfigModel = mapOf(
                    "defaults" to mapOf(
                        "contents" to contentsBuilder,
                        "keys" to emptyList<String>(),
                        "overrides" to emptyList<String>()
                    ),
                    "policy" to emptyMap,
                    "application" to emptyMap,
                    "userLocal" to userLocalModel,
                    "userRemote" to emptyMap,
                    "workspace" to emptyMap,
                    "folders" to emptyList<Any>(),
                    "configurationScopes" to emptyList<Any>()
                )
                // Directly call the interface method and wait for the RPC response.
                // Guard each await with a timeout: if the extension host hangs during
                // initialization the plugin would otherwise stay in "loading" forever.
                val initConfigResult = extHostConfiguration.initializeConfiguration(emptyConfigModel)
                if (initConfigResult is CompletableDeferred<*>) {
                    withTimeout(INIT_RPC_TIMEOUT_MS) { initConfigResult.await() }
                    logger.info("Configuration initialization RPC completed")
                }

                // Get ExtHostWorkspace proxy
                val extHostWorkspace = rpcProtocol.getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostWorkspace)

                // Get current workspace data
                logger.info("Getting current workspace data")
                val workspaceData = project.getService(WorkspaceManager::class.java).getCurrentWorkspaceData()

                // If workspace data is obtained, send it to extension process, otherwise send null
                // Wait for the workspace initialization RPC response as well
                val initWorkspaceResult = if (workspaceData != null) {
                    logger.info("Sending workspace data to extension process: ${workspaceData.name}, folders: ${workspaceData.folders.size}")
                    extHostWorkspace.initializeWorkspace(workspaceData, true)
                } else {
                    logger.info("No available workspace data, sending null to extension process")
                    extHostWorkspace.initializeWorkspace(null, true)
                }
                if (initWorkspaceResult is CompletableDeferred<*>) {
                    withTimeout(INIT_RPC_TIMEOUT_MS) { initWorkspaceResult.await() }
                    logger.info("Workspace initialization RPC completed")
                }

                // Initialize workspace
                logger.info("Workspace initialization completed")
            }
        } catch (e: TimeoutCancellationException) {
            // Initialization RPC did not respond in time. Surface the failure to the
            // tool window UI via lastInitializationFailure so the user sees an error
            // state instead of an indefinite "Initializing..." screen. Do not auto-retry.
            logger.error("Plugin initialization timed out after ${INIT_RPC_TIMEOUT_MS}ms waiting for extension host RPC", e)
            try {
                com.sina.weibo.agent.plugin.WecoderPlugin.getInstance(project).recordInitializationFailure(
                    com.sina.weibo.agent.core.ExtensionProcessManager.StartFailureReason.PROCESS_START_EXCEPTION,
                    "Initialization timed out waiting for extension host RPC (${INIT_RPC_TIMEOUT_MS}ms). The extension host may be unresponsive."
                )
            } catch (reportErr: Exception) {
                logger.warn("Failed to record initialization timeout failure to UI", reportErr)
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize plugin environment: ${e.message}", e)
        }
    }

    /**
     * Set up default protocol handlers
     * These protocols are required for extHost process startup and initialization
     */
    private fun setupDefaultProtocols() {
        logger.info("Setting up default protocol handlers")
        PluginContext.getInstance(project).setRPCProtocol(rpcProtocol)

        // MainThreadErrors
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadErrors, MainThreadErrors())

        // MainThreadConsole
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadConsole, MainThreadConsole())

        // MainThreadLogger
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadLogger, MainThreadLogger())

        // MainThreadCommands
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadCommands, MainThreadCommands(project))

        // MainThreadDebugService
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadDebugService, MainThreadDebugService())

        // MainThreadConfiguration
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadConfiguration, MainThreadConfiguration(project))

        // MainThreadWorkspace
        val workspaceManager = project.getService(WorkspaceManager::class.java)
        if (workspaceManager != null) {
            rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadWorkspace, workspaceManager)
            logger.info("成功注册 MainThreadWorkspace 服务: ${workspaceManager::class.java.simpleName}")
        } else {
            logger.error("无法获取 WorkspaceManager 服务，MainThreadWorkspace 注册失败")
        }

    }

    /**
     * Set up protocol handlers required for plugin package general loading process
     */
    private fun setupExtensionRequiredProtocols() {
        logger.info("Setting up required protocol handlers for plugins")

        // MainThreadExtensionService
        val mainThreadExtensionService = MainThreadExtensionService(extensionManager, rpcProtocol)
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadExtensionService, mainThreadExtensionService)

        // MainThreadTelemetry
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadTelemetry, MainThreadTelemetry())

        // MainThreadTerminalShellIntegration - use new architecture, pass project parameter
        rpcProtocol.set(
            ServiceProxyRegistry.MainContext.MainThreadTerminalShellIntegration,
            MainThreadTerminalShellIntegration(project)
        )

        // MainThreadTerminalService - use new architecture, pass project parameter
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadTerminalService, MainThreadTerminalService(project))

        // MainThreadTask
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadTask, MainThreadTask())

        // MainThreadSearch
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadSearch, MainThreadSearch())

        // MainThreadWindow
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadWindow, MainThreadWindow(project))

        // MainThreadDiaglogs
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadDialogs, MainThreadDiaglogs())

        // MainThreadLanguageModelTools
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadLanguageModelTools, MainThreadLanguageModelTools())

        // MainThreadClipboard
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadClipboard, MainThreadClipboard())

        //MainThreadBulkEdits
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadBulkEdits, MainThreadBulkEdits(project))

        //MainThreadEditorTabs
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadEditorTabs, MainThreadEditorTabs(project))

        // MainThreadDocuments
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadDocuments, MainThreadDocuments(project))

        // MainThreadProgress
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadProgress, MainThreadProgress())

        }

    /**
     * Set up protocol handlers required for WeCode plugin
     */
    private fun setupWeCodeRequiredProtocols() {
        logger.info("Setting up required protocol handlers for WeCode")

        // MainThreadTextEditors
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadTextEditors, MainThreadTextEditors(project))

        // MainThreadStorage
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadStorage, MainThreadStorage())

        // MainThreadOutputService
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadOutputService, MainThreadOutputService())

        // MainThreadWebviewViews
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadWebviewViews, MainThreadWebviewViews(project))

        // MainThreadDocumentContentProviders
        rpcProtocol.set(
            ServiceProxyRegistry.MainContext.MainThreadDocumentContentProviders,
            MainThreadDocumentContentProviders()
        )

        // MainThreadUrls
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadUrls, MainThreadUrls())

        // MainThreadLanguageFeatures
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadLanguageFeatures, MainThreadLanguageFeatures())

        // MainThreadFileSystem
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadFileSystem, MainThreadFileSystem())

        //MainThreadMessageServiceShape
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadMessageService, MainThreadMessageService())
    }

    private fun setupCostrictFuncitonProtocols() {
        logger.info("Setting up protocol handlers required for Costrict specific functionality")

        val mainThreadComments = MainThreadComments(project)
        Disposer.register(project, mainThreadComments)
        rpcProtocol.set(
            ServiceProxyRegistry.MainContext.MainThreadComments,
            mainThreadComments
        )
    }

    private fun setupRooCodeFuncitonProtocols() {
        logger.info("Setting up protocol handlers required for RooCode specific functionality")

        // MainThreadFileSystemEventService
        rpcProtocol.set(
            ServiceProxyRegistry.MainContext.MainThreadFileSystemEventService,
            MainThreadFileSystemEventService()
        )

        // MainThreadSecretState
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadSecretState, MainThreadSecretState())
    }
    private fun setupKiloCodeFunctionProtocols() {
        // MainThreadStatusBar
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadStatusBar, MainThreadStatusBar())
    }

    private fun setupWebviewProtocols() {
        logger.info("Setting up protocol handlers required for Webview")
        // MainThreadWebviews
        rpcProtocol.set(ServiceProxyRegistry.MainContext.MainThreadWebviews, MainThreadWebviews(project))
    }

    /**
     * Get RPC protocol instance
     */
    fun getRPCProtocol(): IRPCProtocol {
        return rpcProtocol
    }

    companion object {
        private const val CONFIG_DIR_NAME = ".costrict-jetbrains"
        private const val CONFIG_FILE_NAME = "config.json"

        /**
         * Per-RPC-call timeout for initialization awaits (config + workspace).
         * 15s is generous for a local extension host; if exceeded we treat the
         * extension host as hung and surface a failure instead of hanging forever.
         */
        private const val INIT_RPC_TIMEOUT_MS = 15_000L

        /**
         * Load persisted configuration from ~/.costrict-jetbrains/config.json.
         * Used to restore settings like uiMode on extension host restart.
         */
        fun loadPersistedConfig(): Map<String, Any?> {
            val configFile = File(System.getProperty("user.home"), "$CONFIG_DIR_NAME/$CONFIG_FILE_NAME")
            if (!configFile.exists()) return emptyMap()
            return try {
                val json = configFile.readText()
                @Suppress("UNCHECKED_CAST")
                (com.google.gson.Gson().fromJson(json, Map::class.java) as? Map<String, Any?>) ?: emptyMap()
            } catch (e: Exception) {
                Logger.getInstance(RPCManager::class.java).warn("Failed to load config.json: ${e.message}")
                emptyMap()
            }
        }

        /**
         * Convert a flat config map with dot-separated keys into a nested map structure.
         * E.g. {"costrict.uiMode": "cloud"} -> {"costrict": {"uiMode": "cloud"}}.
         * This matches the VSCode ConfigurationModel contents format.
         */
        fun buildNestedContents(flatConfig: Map<String, Any?>): Map<String, Any?> {
            val result = mutableMapOf<String, Any?>()
            for ((key, value) in flatConfig) {
                val parts = key.split(".")
                var current = result
                for (i in parts.indices) {
                    val part = parts[i]
                    if (i == parts.lastIndex) {
                        current[part] = value
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val next = current.getOrPut(part) { mutableMapOf<String, Any?>() } as MutableMap<String, Any?>
                        current = next
                    }
                }
            }
            return result
        }
    }
}
