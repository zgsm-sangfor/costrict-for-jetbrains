// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.webview

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.sina.weibo.agent.core.PluginContext
import com.sina.weibo.agent.core.ServiceProxyRegistry
import com.sina.weibo.agent.events.WebviewHtmlUpdateData
import com.sina.weibo.agent.events.WebviewViewProviderData
import com.sina.weibo.agent.ipc.proxy.SerializableObjectWithBuffers
import com.sina.weibo.agent.theme.ThemeChangeListener
import com.sina.weibo.agent.theme.ThemeManager
import com.sina.weibo.agent.util.ConfigFileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.*
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.pathString

/**
 * WebView creation callback interface
 */
interface WebViewCreationCallback {
    /**
     * Called when WebView is created
     * @param instance Created WebView instance
     */
    fun onWebViewCreated(instance: WebViewInstance)

    /**
     * Called to remove a WebViewInstance's browser component from the UI panel.
     * Implementations that manage a tool window panel should override this to
     * remove the browser component. The default implementation is a no-op.
     */
    fun removeWebViewComponent(instance: WebViewInstance) {}
}

/**
 * WebView manager, responsible for managing all WebView instances created during the plugin lifecycle
 */
@Service(Service.Level.PROJECT)
class WebViewManager(var project: Project) : Disposable, ThemeChangeListener {
    private val logger = Logger.getInstance(WebViewManager::class.java)

    private companion object {
        /** Cloud UI (Assistant UI) sidebar viewType. */
        const val CLOUD_VIEW_TYPE = "costrict.AssistantUISidebarProvider"
    }

    // Latest created WebView instance
    @Volatile
    private var latestWebView: WebViewInstance? = null

    // Handle-based routing: the VSCode protocol uses viewId (UUID) as the
    // webview handle. When $setHtml arrives, we look up the correct WebView
    // by that handle instead of always using latestWebView.
    private val handleToWebview = ConcurrentHashMap<String, WebViewInstance>()

    // viewType-based lookup for APIs like getWebViewByViewType and theme dispatch
    private val viewTypeToWebview = ConcurrentHashMap<String, WebViewInstance>()
    
    // Store WebView creation callbacks
    private val creationCallbacks = mutableListOf<WebViewCreationCallback>()

    // Resource root directory path
    @Volatile
    private var resourceRootDir: Path? = null
    
    // Current theme configuration
    private var currentThemeConfig: JsonObject? = null
    
    // Current theme type
    private var isDarkTheme: Boolean = true
    
    // Prevent repeated dispose
    private var isDisposed = false
    private var themeInitialized = false

    // Current UI mode ("classic" or "cloud"), synced from setContext("costrict.uiMode", ...)
    @Volatile
    private var uiMode: String? = null

    /**
     * Initialize theme manager
     * @param resourceRoot Resource root directory
     */
    fun initializeThemeManager(resourceRoot: String) {
        if (isDisposed or themeInitialized) return
        
        logger.debug("Initialize theme manager")
        val themeManager = ThemeManager.getInstance()
        themeManager.initialize(resourceRoot)
        themeManager.addThemeChangeListener(this)
        themeInitialized = true
    }
    
    /**
     * Implement ThemeChangeListener interface, handle theme change events
     */
    override fun onThemeChanged(themeConfig: JsonObject, isDarkTheme: Boolean) {
        logger.debug("Received theme change event, isDarkTheme: $isDarkTheme, config: ${themeConfig.size()}")
        this.currentThemeConfig = themeConfig
        this.isDarkTheme = isDarkTheme
        
        // Send theme config to all WebView instances
        sendThemeConfigToWebViews(themeConfig)
    }
    
    /**
     * Send theme config to all WebView instances
     */
    private fun sendThemeConfigToWebViews(themeConfig: JsonObject) {
        logger.debug("Send theme config to WebViews")

        for (webView in viewTypeToWebview.values) {
            try {
                webView.sendThemeConfigToWebView(themeConfig)
            } catch (e: Exception) {
                logger.error("Failed to send theme config to WebView", e)
            }
        }
    }
    
    /**
     * Save HTML content to resource directory
     * @param html HTML content
     * @param filename File name
     * @return Saved file path
     */
    private fun saveHtmlToResourceDir(html: String, filename: String): Path? {
        if( resourceRootDir == null || !resourceRootDir!!.exists() ) {
            logger.warn("Resource root directory does not exist, cannot save HTML content")
            throw IOException("Resource root directory does not exist")
        }
        
        // Clean up stale index-*.html files from previous updates (keep only last 4)
        try {
            val rootDir = resourceRootDir!!
            val oldFiles = java.nio.file.Files.list(rootDir)
                .filter { p -> p.fileName.toString().startsWith("index-") && p.fileName.toString().endsWith(".html") }
                .sorted(Comparator.comparingLong<Path> { java.nio.file.Files.getLastModifiedTime(it).toMillis() }.reversed())
                .skip(4)
                .toList()
            oldFiles.forEach { oldFile -> java.nio.file.Files.deleteIfExists(oldFile) }
        } catch (e: Exception) {
            logger.warn("Failed to clean up old HTML files", e)
        }
        
        val filePath = resourceRootDir?.resolve(filename)
        
        try {
            if (filePath != null) {
                Files.write(filePath, html.toByteArray(StandardCharsets.UTF_8))
                return filePath
            }
            return null
        } catch (e: Exception) {
            logger.error("Failed to save HTML content: $filePath", e)
            throw e
        }
    }
    
    /**
     * Inject environment variables into HTML content
     * Replace undefined or empty environment variable values with actual environment variables
     * from idea-shell-env.json or system environment
     * @param html HTML content
     * @return HTML content with environment variables injected
     */
    private fun injectEnvironmentVariables(html: String): String {
        var modifiedHtml = html
        
        // Try to load from idea-shell-env.json first, then fall back to system env
        val envMap = loadIdeaShellEnv()
        
        val anthropicModel = envMap["ANTHROPIC_MODEL"] ?: System.getenv("ANTHROPIC_MODEL") ?: ""
        val anthropicBaseUrl = envMap["ANTHROPIC_BASE_URL"] ?: System.getenv("ANTHROPIC_BASE_URL") ?: ""
        
        logger.debug("Injecting environment variables: ANTHROPIC_MODEL='$anthropicModel', ANTHROPIC_BASE_URL='$anthropicBaseUrl'")
        
        // Replace patterns like "ANTHROPIC_MODEL": "undefined" or "ANTHROPIC_MODEL": ""
        // with actual values from environment variables
        if (anthropicModel.isNotEmpty()) {
            modifiedHtml = modifiedHtml.replace(
                Regex(""""ANTHROPIC_MODEL":\s*"(?:undefined|)""""),
                """"ANTHROPIC_MODEL": "$anthropicModel""""
            )
        }
        
        if (anthropicBaseUrl.isNotEmpty()) {
            modifiedHtml = modifiedHtml.replace(
                Regex(""""ANTHROPIC_BASE_URL":\s*"(?:undefined|)""""),
                """"ANTHROPIC_BASE_URL": "$anthropicBaseUrl""""
            )
        }
        
        return modifiedHtml
    }
    
    /**
     * Seed classic WebViews with the current VS Code theme before JCEF's first paint.
     *
     * Runtime theme injection happens after the main frame loads. Until then the bundled
     * classic HTML leaves html/body/#root transparent, so Chromium shows its default white
     * viewport even though controls using VS Code variables can already render dark.
     */
    private fun injectInitialClassicThemeStyles(html: String, webView: WebViewInstance): String {
        if (webView.viewType == CLOUD_VIEW_TYPE) {
            return html
        }

        val cssContent = currentThemeConfig
            ?.takeIf { it.has("cssContent") }
            ?.get("cssContent")
            ?.asString
            ?: return html

        val cssVariables = cssContent.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("--") }
            .joinToString("\n") { "    $it" }

        if (cssVariables.isBlank()) {
            return html
        }

        val initialThemeStyle = """
            <style id="_initialWebviewTheme">
            :root {
            $cssVariables
            }

            html,
            body {
                width: 100%;
                min-height: 100%;
                margin: 0;
                background-color: var(--vscode-editor-background);
                color: var(--vscode-editor-foreground);
            }

            #root {
                min-height: 100%;
                background-color: var(--vscode-editor-background);
            }
            </style>
        """.trimIndent()

        val headCloseIndex = html.indexOf("</head>", ignoreCase = true)
        return if (headCloseIndex >= 0) {
            html.substring(0, headCloseIndex) + initialThemeStyle + "\n" + html.substring(headCloseIndex)
        } else {
            initialThemeStyle + "\n" + html
        }
    }
    
    /**
     * Load environment variables from idea-shell-env.json
     * @return Map of environment variables
     */
    private fun loadIdeaShellEnv(): Map<String, String> {
        try {
            val envFilePath = resolveIdeaShellEnvPath()
            if (envFilePath == null || !envFilePath.exists()) {
                logger.debug("idea-shell-env.json not found at: $envFilePath")
                return emptyMap()
            }
            
            logger.debug("Loading environment variables from: $envFilePath")
            val jsonContent = Files.readString(envFilePath, StandardCharsets.UTF_8)
            val gson = Gson()
            val envMap = gson.fromJson(jsonContent, Map::class.java) as? Map<String, String> ?: emptyMap()
            
            logger.debug("Loaded ${envMap.size} environment variables from idea-shell-env.json")
            return envMap
        } catch (e: Exception) {
            logger.warn("Failed to load idea-shell-env.json", e)
            return emptyMap()
        }
    }
    
    /**
     * Resolve the path to idea-shell-env.json based on platform
     * @return Path to idea-shell-env.json or null if cannot be determined
     */
    private fun resolveIdeaShellEnvPath(): Path? {
        val filename = "idea-shell-env.json"
        val osName = System.getProperty("os.name").lowercase()
        
        return when {
            osName.contains("win") -> {
                // Windows: %LOCALAPPDATA%/idea-shell-env.json
                val localAppData = System.getenv("LOCALAPPDATA")
                if (localAppData != null) Paths.get(localAppData, filename) else null
            }
            osName.contains("mac") || osName.contains("darwin") -> {
                // macOS: ~/Library/Caches/idea-shell-env.json
                val home = System.getProperty("user.home")
                Paths.get(home, "Library", "Caches", filename)
            }
            else -> {
                // Linux: ~/.cache/idea-shell-env.json
                val home = System.getProperty("user.home")
                Paths.get(home, ".cache", filename)
            }
        }
    }
    
    /**
     * Register WebView creation callback
     * @param callback Callback object
     * @param disposable Associated Disposable object, used for automatic callback removal
     */
    fun addCreationCallback(callback: WebViewCreationCallback, disposable: Disposable? = null) {
        synchronized(creationCallbacks) {
            creationCallbacks.add(callback)
            
            // If Disposable is provided, automatically remove callback when disposed
            if (disposable != null) {
                Disposer.register(disposable, Disposable {
                    removeCreationCallback(callback)
                })
            }
        }
        
        // If there is already a latest created WebView, notify immediately
        latestWebView?.let { webview ->
            ApplicationManager.getApplication().invokeLater {
                callback.onWebViewCreated(webview)
            }
        }
    }
    
    /**
     * Remove WebView creation callback
     * @param callback Callback object to remove
     */
    fun removeCreationCallback(callback: WebViewCreationCallback) {
        synchronized(creationCallbacks) {
            creationCallbacks.remove(callback)
        }
    }
    
    /**
     * Notify all callbacks that WebView has been created
     * @param instance Created WebView instance
     */
    private fun notifyWebViewCreated(instance: WebViewInstance) {
        val callbacks = synchronized(creationCallbacks) {
            creationCallbacks.toList() // Create a copy to avoid concurrent modification
        }
        
        // Safely call callbacks in UI thread
        ApplicationManager.getApplication().invokeLater {
            callbacks.forEach { callback ->
                try {
                    callback.onWebViewCreated(instance)
                } catch (e: Exception) {
                    logger.error("Exception occurred when calling WebView creation callback", e)
                }
            }
        }
    }
    
    /**
     * Register WebView provider and create WebView instance.
     *
     * If a WebViewInstance for a *different* viewType already exists, the old one
     * is removed from the panel and disposed before the new one is created.
     * This prevents multiple JCEF browser components from stacking in the tool window
     * when both classic and cloud providers are registered (e.g. in IDEA plugin).
     */
    fun registerProvider(data: WebviewViewProviderData) {
        logger.debug("Register WebView provider and create WebView instance: ${data.viewType}")

        // ── Clean up stale WebViewInstances left behind by extension-host reloads ──
        // When the VSCode extension host restarts (e.g. after switching UI mode) the
        // old providers are killed without a proper unregister, leaving disposed
        // WebViewInstances in our maps.  Re-using them causes HTML updates to be
        // silently ignored because loadUrl/loadHtml check isDisposed.
        val staleViewTypes = viewTypeToWebview.filter { it.value.isDisposed() }.keys.toList()
        for (staleViewType in staleViewTypes) {
            viewTypeToWebview.remove(staleViewType)
        }
        val staleHandles = handleToWebview.filter { it.value.isDisposed() }.keys.toList()
        for (staleHandle in staleHandles) {
            handleToWebview.remove(staleHandle)
        }
        if (latestWebView?.isDisposed() == true) {
            latestWebView = null
        }

        // ── Dispose existing WebViewInstance of a different viewType ──
        // Only dispose if the new viewType matches the current uiMode, so that a
        // mis-matched provider registration (e.g. classic provider registering while
        // the mode is "cloud") doesn't tear down the active WebView.
        val existingDifferentViewType = viewTypeToWebview.entries.firstOrNull { it.key != data.viewType }
        if (existingDifferentViewType != null) {
            val oldViewType = existingDifferentViewType.key
            val oldInstance = existingDifferentViewType.value

            // If the new viewType matches the current mode, dispose the old one
            // and show the new one.  Otherwise keep the old one visible.
            val shouldSwitch = when (uiMode) {
                "cloud" -> data.viewType == "costrict.AssistantUISidebarProvider"
                "classic" -> data.viewType == "costrict.SidebarProvider"
                else -> true // no mode set yet — switch unconditionally
            }

            if (shouldSwitch) {
                logger.info("registerProvider: disposing old WebViewInstance for different viewType=$oldViewType (new=$data.viewType, uiMode=$uiMode)")

                // Remove old browser component from the panel
                removeBrowserComponentFromPanel(oldInstance)

                // Remove from routing maps
                viewTypeToWebview.remove(oldViewType)
                handleToWebview.entries.removeAll { it.value == oldInstance }
                if (latestWebView == oldInstance) {
                    latestWebView = null
                }

                // Dispose JCEF browser resources
                Disposer.dispose(oldInstance)
            } else {
                // The new viewType doesn't match current mode — register it in
                // the background, but keep the active WebView visible and routable.
                logger.info("registerProvider: registering viewType=${data.viewType} in background (uiMode=$uiMode, keeping $oldViewType visible)")
            }
        }

        // ── If the same viewType already exists, re-resolve it ──
        val existing = viewTypeToWebview[data.viewType]
        if (existing != null) {
            logger.debug("registerProvider: WebViewInstance already exists for viewType=${data.viewType}, re-resolving")
            val proxy = project.getService(PluginContext::class.java).getRPCProtocol()
                ?.getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostWebviewViews)
            if (proxy != null) {
                proxy.resolveWebviewView(
                    existing.viewId, data.viewType,
                    data.options["title"] as? String ?: data.viewType,
                    data.options["state"] as? Map<String, Any?> ?: emptyMap<String, Any?>(), null
                )
            }
            latestWebView = existing
            return
        }

        // ── Set resource root directory from extension ──
        val extension = data.extension
        try {
            @Suppress("UNCHECKED_CAST")
            val location = extension?.get("location") as? Map<String, Any?>
            val fsPath = location?.get("fsPath") as? String
            if (fsPath != null) {
                val path = Paths.get(fsPath)
                logger.debug("Get resource directory path from extension: $path")
                if (!path.exists()) {
                    path.createDirectories()
                }
                resourceRootDir = path
                initializeThemeManager(fsPath)
            }
        } catch (e: Exception) {
            logger.error("Failed to get resource directory from extension", e)
        }

        // ── Create new WebViewInstance ──
        val protocol = project.getService(PluginContext::class.java).getRPCProtocol()
        if (protocol == null) {
            logger.error("Cannot get RPC protocol instance, cannot register WebView provider: ${data.viewType}")
            return
        }

        val viewId = UUID.randomUUID().toString()
        val title = data.options["title"] as? String ?: data.viewType
        val state = data.options["state"] as? Map<String, Any?> ?: emptyMap()
        val webview = WebViewInstance(data.viewType, viewId, title, state, project, data.extension)

        val proxy = protocol.getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostWebviewViews)
        proxy.resolveWebviewView(viewId, data.viewType, title, state, null)

        latestWebView = webview
        handleToWebview[viewId] = webview
        viewTypeToWebview[data.viewType] = webview

        // Only add the WebView to the tool window panel if it matches the
        // current uiMode.  Mismatched providers are kept for routing but
        // remain hidden until a mode switch brings them to the front.
        val shouldShow = when (uiMode) {
            "cloud" -> data.viewType == "costrict.AssistantUISidebarProvider"
            "classic" -> data.viewType == "costrict.SidebarProvider"
            else -> true // no mode set yet — show unconditionally
        }

        if (shouldShow) {
            logger.debug("Create WebView instance (visible): viewType=${data.viewType}, viewId=$viewId, uiMode=$uiMode")
            notifyWebViewCreated(webview)
        } else {
            logger.debug("Create WebView instance (hidden): viewType=${data.viewType}, viewId=$viewId, uiMode=$uiMode")
        }
    }

    /**
     * Find and remove a WebViewInstance's browser component from the tool window panel.
     * Searches via the registered creation callbacks (which include RunVSAgentToolWindowContent).
     */
    private fun removeBrowserComponentFromPanel(instance: WebViewInstance) {
        val callbacks = synchronized(creationCallbacks) { creationCallbacks.toList() }
        ApplicationManager.getApplication().invokeLater {
            for (callback in callbacks) {
                callback.removeWebViewComponent(instance)
            }
        }
    }
    
    /**
         * Get the latest created WebView instance
         */
    fun getLatestWebView(): WebViewInstance? {
        return latestWebView
    }

    /**
         * Get a WebView by viewType, for handle-aware routing.
         * Falls back to latestWebView when the map doesn't contain the key.
         */
    fun getWebViewByViewType(viewType: String): WebViewInstance? {
        return viewTypeToWebview[viewType] ?: latestWebView
    }

    /**
     * Look up a WebView by its full handle (the UUID minted at registerProvider,
     * echoed back by the ext host in $setHtml / $postMessage). This is the precise
     * routing key for response messages: unlike getWebViewByViewType it does NOT
     * fall back to latestWebView, so it is safe in multi-project scenarios where
     * latestWebView may belong to a different webview.
     */
    fun getWebViewByHandle(handle: String): WebViewInstance? {
        return handleToWebview[handle]
    }

    /**
     * Set the current UI mode and switch the visible WebView accordingly.
     * Called from MainThreadCommands when setContext("costrict.uiMode", ...) is
     * executed by the extension.
     *
     * @param mode "classic" to show costrict.SidebarProvider, "cloud" to show costrict.AssistantUISidebarProvider
     */
    fun setUiMode(mode: String?) {
        uiMode = mode
        logger.info("setUiMode: $mode")
        switchToCurrentMode()
    }

    /**
     * Get the current UI mode.
     */
    fun getUiMode(): String? = uiMode

    /**
     * Reload the cloud UI webview so it reconnects to the cs-cloud daemon.
     *
     * Called when the extension-host socket dies: all cloud UI API calls are
     * proxied through the extension host, so a socket loss leaves the UI in a
     * state where every request hangs forever and user retries do nothing.
     * Reloading the page re-runs the bootstrap script (which re-installs the
     * fetch override) and lets the UI talk to cs-cloud again, because cs-cloud
     * runs as a detached daemon independent of the extension host socket.
     *
     * Safe to call from any thread; the actual JCEF load is scheduled on EDT.
     */
    fun reloadCloudWebView() {
        val cloudWebView = getWebViewByViewType(CLOUD_VIEW_TYPE)
        if (cloudWebView == null || cloudWebView.isDisposed()) {
            logger.info("reloadCloudWebView: cloud webview not available (null or disposed), skipping reload")
            return
        }
        logger.info("reloadCloudWebView: scheduling reload of cloud webview ${cloudWebView.viewType}/${cloudWebView.viewId}")
        ApplicationManager.getApplication().invokeLater {
            try {
                cloudWebView.reloadLastUrl()
            } catch (e: Exception) {
                logger.error("reloadCloudWebView: failed to reload cloud webview", e)
            }
        }
    }

    /**
     * Switch the tool window to show the WebView that matches the current mode.
     * ViewType mapping: classic → "costrict.SidebarProvider", cloud → "costrict.AssistantUISidebarProvider"
     */
    private fun switchToCurrentMode() {
        val targetViewType = when (uiMode) {
            "cloud" -> "costrict.AssistantUISidebarProvider"
            "classic" -> "costrict.SidebarProvider"
            else -> return
        }
        val targetWebView = viewTypeToWebview[targetViewType] ?: return

        logger.info("switchToCurrentMode: switching to viewType=$targetViewType (uiMode=$uiMode)")

        // Notify creation callbacks to show this WebView, which replaces the current one
        val callbacks = synchronized(creationCallbacks) { creationCallbacks.toList() }
        ApplicationManager.getApplication().invokeLater {
            // Remove any previously visible WebView from the panel first
            if (latestWebView != null) {
                for (callback in callbacks) {
                    callback.removeWebViewComponent(latestWebView!!)
                }
            }
            // Show the target WebView
            for (callback in callbacks) {
                callback.onWebViewCreated(targetWebView)
            }
        }

        // Remove any stale WebView whose viewType doesn't match the current mode
        for ((existingViewType, instance) in viewTypeToWebview.entries) {
            if (existingViewType != targetViewType) {
                removeBrowserComponentFromPanel(instance)
                handleToWebview.entries.removeAll { it.value == instance }
                viewTypeToWebview.remove(existingViewType)
                if (!instance.isDisposed()) {
                    Disposer.dispose(instance)
                }
                logger.info("switchToCurrentMode: removed stale WebView viewType=$existingViewType")
            }
        }

        latestWebView = targetWebView
    }

    /**
         * Extract viewType from a TS-side webview handle.
         * Handles follow the format "webview-<viewType>-<timestamp>".
         */
    private fun extractViewTypeFromHandle(handle: String): String? {
        val withoutPrefix = handle.removePrefix("webview-")
        val lastDash = withoutPrefix.lastIndexOf('-')
        return if (lastDash >= 0) withoutPrefix.substring(0, lastDash) else withoutPrefix
    }

    /** Random alphanumeric token, matching the length/format the extension uses for its nonces. */
    private fun generateNonce(): String {
        val possible = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.security.SecureRandom()
        val sb = StringBuilder(32)
        repeat(32) { sb.append(possible[random.nextInt(possible.length)]) }
        return sb.toString()
    }

    /**
     * Patch the CSP of [html] so that an inline `<script nonce="[nonce]">` is allowed to run.
     *
     * Some webview HTML (notably the cs-cloud loading/error page) ships a strict CSP of
     * `default-src 'none'; style-src 'unsafe-inline'` with no `script-src` directive, which
     * falls back to `default-src 'none'` and blocks ALL scripts — including the VSCode API
     * mock this plugin injects. Without this rewrite the loading page stays stuck forever.
     *
     * Per the CSP spec, an explicit `script-src` takes precedence over `default-src` for
     * scripts, so we only need to add `script-src 'nonce-[nonce]'` — we never have to touch
     * `default-src` (preserving the page's other restrictions).
     *
     * Behavior:
     *  - If a CSP `<meta>` already exists:
     *      • if `script-src` is present and already lists `'nonce-[nonce]'` → unchanged
     *      • if `script-src` is present but lacks the nonce → append `'nonce-[nonce]'`
     *      • if `script-src` is absent → prepend `script-src 'nonce-[nonce]'`
     *  - If no CSP `<meta>` exists → inject `<meta http-equiv="Content-Security-Policy"
     *    content="script-src 'nonce-[nonce]'">` right after `<head>`.
     *
     * The original CSP source expressions are preserved so existing legitimate scripts
     * (bundled app scripts, etc.) keep working.
     */
    private fun allowNonceInCsp(html: String, nonce: String): String {
        val nonceDirective = "'nonce-$nonce'"
        // Match: <meta http-equiv="Content-Security-Policy" content="...">
        // Tolerate single/double quotes and attribute order. The captured content value may
        // contain HTML-entity-escaped quotes (e.g. &#39;) but never a raw " or ', so the
        // ["'][^"']*]["'] char-class still captures the whole value correctly.
        val cspMetaRegex = Regex(
            """<meta\b[^>]*\bhttp-equiv\s*=\s*["']?\s*Content-Security-Policy["']?\s+[^>]*?\bcontent\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE,
        )
        val match = cspMetaRegex.find(html) ?: run {
            // No CSP meta at all — inject a minimal one that allows our nonce'd script.
            val injectedCsp = htmlEncodeForDoubleQuotedAttr("script-src $nonceDirective")
            val injected = "<meta http-equiv=\"Content-Security-Policy\" " +
                "content=\"$injectedCsp\" />"
            val headOpenMatch = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE).find(html)
            if (headOpenMatch == null) {
                return html + injected // no <head> either; append at end as a last resort
            }
            val insertAt = headOpenMatch.range.last + 1
            return html.substring(0, insertAt) + injected + html.substring(insertAt)
        }

        val cspBodyRaw = match.groupValues[1]
        // The captured value is HTML-attribute text and may contain entity-escaped
        // characters — most importantly &#39; for the ' quotes CSP keywords use, and
        // &#39; itself contains a literal ';' which would corrupt the directive split
        // below if we left it encoded. Decode entities, work on real CSP text, then
        // re-encode when writing back.
        val cspBody = htmlDecode(cspBodyRaw)
        val directives = cspBody.split(";").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

        val scriptSrcIdx = directives.indexOfFirst { it.startsWith("script-src", ignoreCase = true) }
        if (scriptSrcIdx >= 0) {
            val existing = directives[scriptSrcIdx]
            if (existing.contains(nonceDirective, ignoreCase = true)) {
                return html // already allows this nonce
            }
            directives[scriptSrcIdx] = "$existing $nonceDirective"
        } else {
            // No script-src directive → add one. Per CSP spec script-src overrides
            // default-src for scripts, so default-src 'none' no longer blocks our script.
            directives.add(0, "script-src $nonceDirective")
        }

        val newCspBody = htmlEncodeForDoubleQuotedAttr(directives.joinToString("; "))
        // Replace only the captured content="..." value inside the matched <meta>. We splice
        // by the captured value's range (group 1) rather than using a regex replacement
        // string, so any characters in newCspBody are taken literally.
        val contentValueRange = match.groups[1]!!.range
        val sb = StringBuilder(html.length + 16)
        sb.append(html, 0, contentValueRange.first)
        sb.append(newCspBody)
        sb.append(html, contentValueRange.last + 1, html.length)
        return sb.toString()
    }

    /** Decode the subset of HTML entities the CSP attribute may legally contain. */
    private fun htmlDecode(s: String): String =
        s.replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#x22;", "\"")
            .replace("&amp;", "&")

    /**
     * Re-encode [s] for safe inclusion inside a double-quoted HTML attribute value, i.e.
     * escape `"` and `&` (and `'` for readability/compat). Matches the form the cs-cloud
     * extension itself emits (`&#39;`).
     */
    private fun htmlEncodeForDoubleQuotedAttr(s: String): String =
        s.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /**
         * Update the HTML content of the WebView
         * @param data HTML update data
         */
    fun updateWebViewHtml(data: WebviewHtmlUpdateData) {
        // Resolve the correct WebView for the incoming handle.
        // Strategy (in order):
        //  1. Direct handle lookup (handle == viewId generated by registerProvider)
        //  2. viewType extraction from "webview-<viewType>-<timestamp>" handles
        //  3. Fall back to latestWebView
        val targetWebView = handleToWebview[data.handle]
            ?: viewTypeToWebview[extractViewTypeFromHandle(data.handle) ?: ""]
            ?: latestWebView

        if (targetWebView == null) {
            logger.warn("updateWebViewHtml: no WebView found for handle=${data.handle}")
            return
        }

        logger.warn("updateWebViewHtml: routing handle=${data.handle} -> viewType=${targetWebView.viewType}")

        val encodedState = targetWebView.state.toString().replace("\"", "\\\"")
        val htmlLengthBefore = data.htmlContent.length

        // The VSCode API mock block that must be injected into the page.
        val vscodeApiMock = """
                        // First define the function to send messages
                        window.sendMessageToPlugin = function(message) {
                            // Convert JS object to JSON string
                            // console.log("sendMessageToPlugin: ", message);
                            const msgStr = JSON.stringify(message);
                            ${targetWebView.jsQuery?.inject("msgStr")}
                        };
                        
                        // Inject VSCode API mock
                        globalThis.acquireVsCodeApi = (function() {
                            let acquired = false;
                        
                            let state = JSON.parse('${encodedState}');
                        
                            if (typeof window !== "undefined" && !window.receiveMessageFromPlugin) {
                                console.log("VSCodeAPIWrapper: Setting up receiveMessageFromPlugin for IDEA plugin compatibility");
                                window.receiveMessageFromPlugin = (message) => {
                                    // console.log("receiveMessageFromPlugin received message:", JSON.stringify(message));
                                    // Create a new MessageEvent and dispatch it to maintain compatibility with existing code
                                    const event = new MessageEvent("message", {
                                        data: message,
                                    });
                                    window.dispatchEvent(event);
                                };
                            }
                        
                            return () => {
                                if (acquired) {
                                    throw new Error('An instance of the VS Code API has already been acquired');
                                }
                                acquired = true;
                                return Object.freeze({
                                    postMessage: function(message, transfer) {
                                        // console.log("postMessage: ", message);
                                        window.sendMessageToPlugin(message);
                                    },
                                    setState: function(newState) {
                                        state = newState;
                                        window.sendMessageToPlugin(newState);
                                        return newState;
                                    },
                                    getState: function() {
                                        return state;
                                    }
                                });
                            };
                        })();
                        
                        console.log("VSCode API mock injected");
                        """

        // --- Strategy 1: find the first <script … nonce="…" …> opening tag and ---
        // --- prepend our mock right after it.                                   ---
        // The regex is intentionally loose: after the nonce attribute there may be
        // additional attributes (src, type, defer, etc.) before the closing ">".
        val nonceScriptRegex = """<script\b[^>]*\bnonce="([A-Za-z0-9]{32,64})"[^>]*>""".toRegex()
        val nonceMatch = nonceScriptRegex.find(data.htmlContent)

        if (nonceMatch != null) {
            val openingTag = nonceMatch.value
            data.htmlContent = data.htmlContent.replaceFirst(openingTag, "$openingTag$vscodeApiMock")
            logger.warn("updateWebViewHtml: injected mock via nonce script tag: '${openingTag.take(80)}...'")
        } else {
            // --- Strategy 2 (fallback): no <script nonce="…"> tag in the HTML.   ---
            // --- This happens for the cs-cloud loading/error HTML, whose CSP is  ---
            // --- `default-src 'none'; style-src 'unsafe-inline'` with no          ---
            // --- script-src directive — every inline script is blocked by CSP,   ---
            // --- so a bare <script>…</script> would be silently refused and the   ---
            // --- webview would stay stuck on the loading screen.                  ---
            // --- Fix: generate a fresh nonce, rewrite the CSP <meta> to allow     ---
            // --- scripts carrying that nonce, then inject the mock with it.       ---
            val fallbackNonce = generateNonce()

            // Rewrite the CSP meta so the injected script is permitted.
            val cspBefore = data.htmlContent
            data.htmlContent = allowNonceInCsp(data.htmlContent, fallbackNonce)
            val cspRewritten = data.htmlContent != cspBefore
            logger.warn("updateWebViewHtml: fallback nonce=$fallbackNonce, cspRewritten=$cspRewritten")

            val fallbackScript = "<script nonce=\"$fallbackNonce\">$vscodeApiMock</script>"
            val headCloseIdx = data.htmlContent.indexOf("</head>")
            if (headCloseIdx >= 0) {
                data.htmlContent = data.htmlContent.substring(0, headCloseIdx) +
                        fallbackScript +
                        data.htmlContent.substring(headCloseIdx)
                logger.warn("updateWebViewHtml: injected mock via </head> fallback")
            } else {
                // Last resort: prepend
                data.htmlContent = fallbackScript + data.htmlContent
                logger.warn("updateWebViewHtml: injected mock via prepend fallback")
            }
            // Diagnostic: log the first few script tags for debugging.
            val allScriptTags = """<script\b[^>]*>""".toRegex().findAll(data.htmlContent).take(5).map { it.value }.toList()
            logger.warn("updateWebViewHtml: nonce regex DID NOT MATCH (expected for loading/error HTML). First 5 script tags: $allScriptTags, contains 'nonce=': ${data.htmlContent.contains("nonce=")}")
        }



        // Inject environment variables into HTML content
        // Replace placeholders like "ANTHROPIC_MODEL": "undefined" with actual values
        logger.warn("updateWebViewHtml: after mock injection htmlLength=${data.htmlContent.length} (delta=${data.htmlContent.length - htmlLengthBefore})")

        data.htmlContent = injectEnvironmentVariables(data.htmlContent)
        data.htmlContent = injectInitialClassicThemeStyles(data.htmlContent, targetWebView)
        logger.warn("updateWebViewHtml: after env injection htmlLength=${data.htmlContent.length}, handle=${data.handle}")
        
        val webView = targetWebView
        
        if (webView != null) {
            try {
                // If HTTP server is running
                if ( resourceRootDir != null) {
                    // Use a unique filename per update (handle hash + timestamp) so that
                    // JCEF treats every loadUrl as a brand-new resource.  When the loading
                    // HTML is later replaced by the static HTML both calls used to write to
                    // the same filename — JCEF cached the first URL and silently ignored the
                    // second loadUrl with the identical address.
                    val ts = System.currentTimeMillis()
                    val filename = "index-${data.handle.hashCode().toString().replace("-", "n")}-$ts.html"

                    // Save HTML content to file
                    saveHtmlToResourceDir(data.htmlContent, filename)

                    // Use HTTP URL to load WebView content
                    val url = "http://localhost:12345/$filename"
                    logger.debug("Load WebView HTML content via HTTP: $url")

                    webView.loadUrl(url)
                } else {
                    // Fallback to direct HTML loading
                    logger.warn("HTTP server not running or resource directory not set, loading HTML content directly")
                    webView.loadHtml(data.htmlContent)
                }

                    logger.debug("WebView HTML content updated: handle=${data.handle}")

                // If there is already a theme config, send it after content is loaded
                if (currentThemeConfig != null) {
                    // Delay sending theme config to ensure HTML is loaded
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            webView.sendThemeConfigToWebView(currentThemeConfig!!)
                        } catch (e: Exception) {
                            logger.error("Failed to send theme config to WebView", e)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to update WebView HTML content", e)
                // Fallback to direct HTML loading
                webView.loadHtml(data.htmlContent)
            }
        } else {
            logger.warn("WebView instance not found: handle=${data.handle}")
        }
    }

    
    override fun dispose() {
        if (isDisposed) {
            logger.debug("WebViewManager has already been disposed, ignoring repeated call")
            return
        }
        isDisposed = true
        
        logger.debug("Releasing WebViewManager resources...")

        // Remove listener from theme manager
        try {
            ThemeManager.getInstance().removeThemeChangeListener(this)
        } catch (e: Exception) {
            logger.error("Failed to remove listener from theme manager", e)
        }
        
        // Clean up resource directory
        try {
            // Only delete index.html file, keep other files
            resourceRootDir?.let {
                val indexFile = it.resolve("index.html").toFile()
                if (indexFile.exists() && indexFile.isFile) {
                    val deleted = indexFile.delete()
                    if (deleted) {
                        logger.debug("index.html file deleted")
                    } else {
                        logger.warn("Failed to delete index.html file")
                    }
                } else {
                    logger.debug("index.html file does not exist, no need to clean up")
                }
            }
            resourceRootDir = null
        } catch (e: Exception) {
            logger.error("Failed to clean up index.html file", e)
        }

        try {
            // Dispose all webviews in both maps, then clear them
            handleToWebview.values.forEach { webview ->
                try { webview.dispose() } catch (e: Exception) { logger.error("Failed to release WebView resources: ${webview.viewType}", e) }
            }
            handleToWebview.clear()
            viewTypeToWebview.clear()
            latestWebView?.dispose()
        } catch (e: Exception) {
            logger.error("Failed to release WebView resources", e)
        }
        
        // Reset theme data
        currentThemeConfig = null
        
        // Clear callback list
        synchronized(creationCallbacks) {
            creationCallbacks.clear()
        }
        
        logger.debug("WebViewManager released")
    }


}

/**
 * WebView instance class, encapsulates JCEF browser
 */
class WebViewInstance(
    val viewType: String,
    val viewId: String,
    val title: String,
    val state: Map<String, Any?>,
    val project: Project,
    val extension: Map<String, Any?>
) : Disposable {
    private val logger = Logger.getInstance(WebViewInstance::class.java)

    // JCEF browser instance
    val browser = JBCefBrowser.createBuilder()
        .setOffScreenRendering(ConfigFileUtils.isWebViewOffscreenRenderingEnabled())
        .build()
    
    // WebView state
    private var isDisposed = false

    /** @return true if this WebViewInstance has already been disposed. */
    fun isDisposed(): Boolean = isDisposed

    // JavaScript query handler for communication with webview
    var jsQuery: JBCefJSQuery? = null

    // JSON serialization
    private val gson = Gson()

    // Coroutine scope
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var isPageLoaded = false

    // Last URL successfully passed to loadUrl(). Used by reloadLastUrl() to
    // re-load the current page (e.g. after the extension-host socket dies and
    // the cloud UI must reconnect to the still-running cs-cloud daemon without
    // going through the TS message bridge).
    @Volatile
    private var lastLoadedUrl: String? = null

    private var currentThemeConfig: JsonObject? = null
    
    // Callback for page load completion
    private var pageLoadCallback: ((success: Boolean, errorInfo: String?) -> Unit)? = null
    
    init {
        setupJSBridge()
        // Enable resource loading interception
        enableResourceInterception(extension)
    }

    /**
     * Send theme config to the specified WebView instance
     */
    fun sendThemeConfigToWebView(themeConfig: JsonObject) {
        currentThemeConfig = themeConfig
        if(isDisposed or !isPageLoaded) {
            logger.warn("WebView has been disposed or not loaded, cannot send theme config:${isDisposed},${isPageLoaded}")
            return
        }
        injectTheme()
    }

    /**
     * Check if page is loaded
     * @return true if page is loaded, false otherwise
     */
    fun isPageLoaded(): Boolean {
        return isPageLoaded
    }
    
    /**
     * Set callback for page load completion
     * @param callback Callback function to be called when page is loaded
     */
    fun setPageLoadCallback(callback: ((success: Boolean, errorInfo: String?) -> Unit)?) {
        pageLoadCallback = callback
    }
    
    private fun injectTheme() {
        if(currentThemeConfig == null) {
            return
        }
        try {
            var cssContent: String? = null

            // Get cssContent from themeConfig and save, then remove from object
            if (currentThemeConfig!!.has("cssContent")) {
                cssContent = currentThemeConfig!!.get("cssContent").asString
                // Create a copy of themeConfig to modify without affecting the original object
                val themeConfigCopy = currentThemeConfig!!.deepCopy()
                // Remove cssContent property from the copy
                themeConfigCopy.remove("cssContent")

                val assistantUITheme = if (ThemeManager.getInstance().isDarkThemeForce()) "dark" else "light"
                val isCloudUi = viewType == "costrict.AssistantUISidebarProvider"
                // Inject CSS variables into WebView
                if (cssContent != null) {
                    val injectThemeScript = """
                        (function() {
                            const assistantUITheme = "$assistantUITheme";
                            const isCloudUi = $isCloudUi;
                            console.log("Ready to inject CSS variables into WebView")
                            function injectCSSVariables() {
                                if(document.documentElement) {
                                    if (isCloudUi) {
                                        document.documentElement.classList.toggle('dark', assistantUITheme === 'dark');
                                    }
                                    if (document.body && !isCloudUi) {
                                        const themeKind = assistantUITheme === 'light' ? 'vscode-light' : 'vscode-dark';
                                        document.body.classList.toggle('vscode-light', themeKind === 'vscode-light');
                                        document.body.classList.toggle('vscode-dark', themeKind === 'vscode-dark');
                                        document.body.dataset.vscodeThemeKind = themeKind;
                                    }
                                    // Convert cssContent to style attribute of html tag
                                    try {
                                        // Extract CSS variables (format: --name:value;)
                                        const cssLines = `$cssContent`.split('\n');
                                        const cssVariables = [];
                                        
                                        // Process each line, extract CSS variable declarations
                                        for (const line of cssLines) {
                                            const trimmedLine = line.trim();
                                            // Skip comments and empty lines
                                            if (trimmedLine.startsWith('/*') || trimmedLine.startsWith('*') || trimmedLine.startsWith('*/') || trimmedLine === '') {
                                                continue;
                                            }
                                            // Extract CSS variable part
                                            if (trimmedLine.startsWith('--')) {
                                                cssVariables.push(trimmedLine);
                                            }
                                        }
                                        
                                        // Merge extracted CSS variables into style attribute string
                                        const styleAttrValue = cssVariables.join(' ');
                                        
                                        // Set as style attribute of html tag
                                        document.documentElement.setAttribute('style', styleAttrValue);
                                        console.log("CSS variables set as style attribute of HTML tag");
                                    } catch (error) {
                                        console.error("Error processing CSS variables:", error);
                                    }
                                    
                                    // Keep original default style injection logic
                                    if(document.head) {
                                        // Inject default theme style into head, use id="_defaultStyles"
                                        let defaultStylesElement = document.getElementById('_defaultStyles');
                                        if (!defaultStylesElement) {
                                            defaultStylesElement = document.createElement('style');
                                            defaultStylesElement.id = '_defaultStyles';
                                            document.head.appendChild(defaultStylesElement);
                                        }

                                        // Add default_themes.css content.
                                        // Cloud UI (AssistantUISidebarProvider) is a complete web app with its own
                                        // light/dark theme classes. Apply that class above and only inject shared
                                        // VS Code variables/scrollbar styles plus the theme message.
                                        defaultStylesElement.textContent = isCloudUi ? `
                                            html {
                                                scrollbar-color: var(--vscode-scrollbarSlider-background) var(--vscode-editor-background);
                                            }

                                            ::-webkit-scrollbar {
                                                width: 10px;
                                                height: 10px;
                                            }

                                            ::-webkit-scrollbar-corner {
                                                background-color: var(--vscode-editor-background);
                                            }

                                            ::-webkit-scrollbar-thumb {
                                                background-color: var(--vscode-scrollbarSlider-background);
                                            }
                                            ::-webkit-scrollbar-thumb:hover {
                                                background-color: var(--vscode-scrollbarSlider-hoverBackground);
                                            }
                                            ::-webkit-scrollbar-thumb:active {
                                                background-color: var(--vscode-scrollbarSlider-activeBackground);
                                            }
                                            ::highlight(find-highlight) {
                                                background-color: var(--vscode-editor-findMatchHighlightBackground);
                                            }
                                            ::highlight(current-find-highlight) {
                                                background-color: var(--vscode-editor-findMatchBackground);
                                            }
                                        ` : `
                                            html {
                                                scrollbar-color: var(--vscode-scrollbarSlider-background) var(--vscode-editor-background);
                                            }

                                            body {
                                                overscroll-behavior-x: none;
                                                background-color: var(--vscode-editor-background);
                                                color: var(--vscode-editor-foreground);
                                                font-family: var(--vscode-font-family);
                                                font-weight: var(--vscode-font-weight);
                                                font-size: var(--vscode-font-size);
                                                margin: 0;
                                                padding: 0 20px;
                                                overflow-x: hidden;   /* prevent horizontal scrollbar */
                                                overflow-y: auto;     /* allow vertical scrolling only */
                                            }

                                            img, video {
                                                max-width: 100%;
                                                height: auto;        /* keep aspect ratio and avoid vertical overflow */
                                                display: block;      /* remove inline baseline gaps that can trigger overflow */
                                            }

                                            a, a code {
                                                color: var(--vscode-textLink-foreground);
                                            }

                                            p > a {
                                                text-decoration: var(--text-link-decoration);
                                            }

                                            a:hover {
                                                color: var(--vscode-textLink-activeForeground);
                                            }

                                            a:focus,
                                            input:focus,
                                            select:focus,
                                            textarea:focus {
                                                outline: 1px solid -webkit-focus-ring-color;
                                                outline-offset: -1px;
                                            }

                                            code {
                                                font-family: var(--monaco-monospace-font);
                                                color: var(--vscode-textPreformat-foreground);
                                                background-color: var(--vscode-textPreformat-background);
                                                padding: 1px 3px;
                                                border-radius: 4px;
                                            }

                                            pre code {
                                                padding: 0;
                                            }

                                            blockquote {
                                                background: var(--vscode-textBlockQuote-background);
                                                border-color: var(--vscode-textBlockQuote-border);
                                            }

                                            kbd {
                                                background-color: var(--vscode-keybindingLabel-background);
                                                color: var(--vscode-keybindingLabel-foreground);
                                                border-style: solid;
                                                border-width: 1px;
                                                border-radius: 3px;
                                                border-color: var(--vscode-keybindingLabel-border);
                                                border-bottom-color: var(--vscode-keybindingLabel-bottomBorder);
                                                box-shadow: inset 0 -1px 0 var(--vscode-widget-shadow);
                                                vertical-align: middle;
                                                padding: 1px 3px;
                                            }

                                            ::-webkit-scrollbar {
                                                width: 10px;
                                                height: 10px;
                                            }

                                            ::-webkit-scrollbar-corner {
                                                background-color: var(--vscode-editor-background);
                                            }

                                            *, *::before, *::after { box-sizing: border-box; }
                                            html, body { width: 100%; height: 100%; background-color: var(--vscode-editor-background); }
                                            #root { min-height: 100%; background-color: var(--vscode-editor-background); }

                                            ::-webkit-scrollbar-thumb {
                                                background-color: var(--vscode-scrollbarSlider-background);
                                            }
                                            ::-webkit-scrollbar-thumb:hover {
                                                background-color: var(--vscode-scrollbarSlider-hoverBackground);
                                            }
                                            ::-webkit-scrollbar-thumb:active {
                                                background-color: var(--vscode-scrollbarSlider-activeBackground);
                                            }
                                            ::highlight(find-highlight) {
                                                background-color: var(--vscode-editor-findMatchHighlightBackground);
                                            }
                                            ::highlight(current-find-highlight) {
                                                background-color: var(--vscode-editor-findMatchBackground);
                                            }
                                        `;
                                        console.log("Default style injected to id=_defaultStyles");
                                    }
                                } else {
                                    // If html tag does not exist yet, wait for DOM to load and try again
                                    setTimeout(injectCSSVariables, 10);
                                }
                            }
                            // If document is already loaded
                            if (document.readyState === 'complete' || document.readyState === 'interactive') {
                                console.log("Document loaded, inject CSS variables immediately");
                                injectCSSVariables();
                            } else {
                                // Otherwise wait for DOMContentLoaded event
                                console.log("Document not loaded, waiting for DOMContentLoaded event");
                                document.addEventListener('DOMContentLoaded', injectCSSVariables);
                            }
                        })()
                    """.trimIndent()

                    logger.debug("Injecting theme style into WebView(${viewId}), size: ${cssContent.length} bytes")
                    executeJavaScript(injectThemeScript)
                }

                // Pass the theme config without cssContent via message
                val themeConfigJson = gson.toJson(themeConfigCopy)
                val message = """
                    {
                        "type": "theme",
                        "theme": "$assistantUITheme",
                        "text": "${themeConfigJson.replace("\"", "\\\"")}"
                    }
                """.trimIndent()

                postMessageToWebView(message)
                logger.debug("Theme config without cssContent has been sent to WebView")
            } else {
                // If there is no cssContent, send the original config directly
                val themeConfigJson = gson.toJson(currentThemeConfig)
                val message = """
                    {
                        "type": "theme",
                        "text": "${themeConfigJson.replace("\"", "\\\"")}"
                    }
                """.trimIndent()

                postMessageToWebView(message)
                logger.debug("Theme config has been sent to WebView")
            }
        } catch (e: Exception) {
            logger.error("Failed to send theme config to WebView", e)
        }
    }

    private fun setupJSBridge() {
        // Create JS query object to handle messages from webview
        jsQuery = JBCefJSQuery.create(browser)

        // Set callback for receiving messages from webview
        jsQuery?.addHandler { message ->
            coroutineScope.launch {
                // Handle message
                val protocol = project.getService(PluginContext::class.java).getRPCProtocol()
                if (protocol != null) {
                    // Send message to plugin host
                    val serializeParam = SerializableObjectWithBuffers(emptyList<ByteArray>())
                    protocol.getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostWebviews).onMessage(viewId, message, serializeParam)
                } else {
                    logger.error("Cannot get RPC protocol instance, cannot handle message: $message")
                }
            }
            null // No return value needed
        }
    }

    /**
         * Send message to WebView
         * @param message Message to send (JSON string)
         */
    fun postMessageToWebView(message: String) {
        if (!isDisposed) {
            // Send message to WebView via JavaScript function
            val script = """
                if (window.receiveMessageFromPlugin) {
                    window.receiveMessageFromPlugin($message);
                } else {
                    console.warn("receiveMessageFromPlugin not available");
                }
            """.trimIndent()
            executeJavaScript(script)
        }
    }

    /**
         * Enable resource request interception
         */
    fun enableResourceInterception(extension: Map<String, Any?>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val location = extension?.get("location") as? Map<String, Any?>
            val fsPath = location?.get("fsPath") as? String

            // Get JCEF client
            val client = browser.jbCefClient

            // Register console message handler
            client.addDisplayHandler(object: CefDisplayHandlerAdapter() {
                override fun onConsoleMessage(
                    browser: CefBrowser?,
                    level: CefSettings.LogSeverity?,
                    message: String?,
                    source: String?,
                    line: Int
                ): Boolean {
                    val logMessage = "WebView console message: [$level] $message (line: $line, source: $source, viewType=$viewType, viewId=$viewId)"
                    when (level?.name) {
                        "LOGSEVERITY_ERROR", "LOGSEVERITY_WARNING" -> logger.warn(logMessage)
                        else -> logger.info(logMessage)
                    }
                    return false
                }
            }, browser.cefBrowser)
            
            // Register load handler
            client.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadingStateChange(
                    browser: CefBrowser?,
                    isLoading: Boolean,
                    canGoBack: Boolean,
                    canGoForward: Boolean
                ) {
                    logger.debug("WebView loading state changed: isLoading=$isLoading, canGoBack=$canGoBack, canGoForward=$canGoForward")
                }
                
                override fun onLoadStart(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    transitionType: CefRequest.TransitionType?
                ) {
                    logger.info("WebView started loading: url=${frame?.url}, transitionType=$transitionType, viewType=$viewType, viewId=$viewId")
                    if (frame?.isMain == true) {
                        isPageLoaded = false
                    }
                }

                override fun onLoadEnd(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    httpStatusCode: Int
                ) {
                    val url = frame?.url
                    val isMainFrame = frame?.isMain == true
                    logger.info("WebView finished loading: url=$url, statusCode=$httpStatusCode, isMainFrame=$isMainFrame, viewType=$viewType, viewId=$viewId")

                    if (!isMainFrame) {
                        return
                    }

                    val success = httpStatusCode in 200..399 || httpStatusCode == 0
                    if (success) {
                        isPageLoaded = true
                        injectTheme()
                        pageLoadCallback?.invoke(true, null)
                    } else {
                        isPageLoaded = false
                        val errorInfo = "HTTP $httpStatusCode while loading $url"
                        logger.warn("WebView main frame load completed with error: $errorInfo, viewType=$viewType, viewId=$viewId")
                        pageLoadCallback?.invoke(false, errorInfo)
                    }
                }

                override fun onLoadError(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    errorCode: CefLoadHandler.ErrorCode?,
                    errorText: String?,
                    failedUrl: String?
                ) {
                    val frameUrl = frame?.url
                    val isMainFrame = frame?.isMain == true
                    val errorInfo = "CEF load error $errorCode: $errorText, failedUrl=$failedUrl, frameUrl=$frameUrl"
                    logger.warn("WebView load error: $errorInfo, isMainFrame=$isMainFrame, viewType=$viewType, viewId=$viewId")
                    if (isMainFrame) {
                        isPageLoaded = false
                        pageLoadCallback?.invoke(false, errorInfo)
                    }
                }
            }, browser.cefBrowser)
            client.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                    user_gesture: Boolean,
                    is_redirect: Boolean
                ): Boolean {
                    logger.debug("onBeforeBrowse,url:${request?.url}")
                    if(request?.url?.startsWith("http://localhost") == false){
                        BrowserUtil.browse(request.url)
                        return true
                    }
                    return false
                }

                override fun getResourceRequestHandler(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                    isNavigation: Boolean,
                    isDownload: Boolean,
                    requestInitiator: String?,
                    disableDefaultHandling: BoolRef?
                ): CefResourceRequestHandler? {
                    logger.debug("getResourceRequestHandler,fsPath:${fsPath}")
                    if (fsPath != null && request?.url?.contains("localhost")==true) {
                        // Set resource root directory
                        val path = Paths.get(fsPath)
                        return LocalResHandler(path.pathString,request)
                    }else{
                        return null
                    }

                }
            }, browser.cefBrowser)
            logger.debug("WebView resource interception enabled: $viewType/$viewId")
        } catch (e: Exception) {
            logger.error("Failed to enable WebView resource interception", e)
        }
    }
    
    /**
         * Load URL
         */
    fun loadUrl(url: String) {
        if (!isDisposed) {
            logger.debug("WebView loading URL: $url")
            lastLoadedUrl = url
            browser.loadURL(url)
        }
    }

    /**
     * Reload the last URL passed to loadUrl(), if any. Used to recover the
     * cloud UI after the extension-host socket dies: re-running the page
     * bootstrap re-executes the fetch override so the UI reconnects to the
     * cs-cloud daemon (which runs as an independent process and survives the
     * socket loss). No-op if nothing was loaded yet or the instance is disposed.
     */
    fun reloadLastUrl() {
        val url = lastLoadedUrl
        if (url == null) {
            logger.info("reloadLastUrl: no previous URL to reload for $viewType/$viewId")
            return
        }
        if (!isDisposed) {
            logger.info("reloadLastUrl: reloading $viewType/$viewId from $url")
            isPageLoaded = false
            browser.loadURL(url)
        }
    }
    
    /**
         * Load HTML content
         */
    fun loadHtml(html: String, baseUrl: String? = null) {
        if (!isDisposed) {
            logger.debug("WebView loading HTML content, length: ${html.length}, baseUrl: $baseUrl")
            if(baseUrl != null) {
                browser.loadHTML(html, baseUrl)
            }else {
                browser.loadHTML(html)
            }
        }
    }
    
    /**
         * Execute JavaScript
         */
    fun executeJavaScript(script: String) {
        if (!isDisposed) {
            logger.debug("WebView executing JavaScript, script length: ${script.length}")
            browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        }
    }
    
    /**
         * Open developer tools
         */
    fun openDevTools() {
        if (!isDisposed) {
            browser.openDevtools()
        }
    }
    
    override fun dispose() {
        if (!isDisposed) {
            browser.dispose()
            isDisposed = true
            logger.debug("WebView instance released: $viewType/$viewId")
        }
    }
}