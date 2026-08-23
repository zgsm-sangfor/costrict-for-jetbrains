// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.util

object PluginConstants {
    const val PLUGIN_ID = "CoStrict"
    const val NODE_MODULES_PATH = "node_modules"
    const val EXTENSION_ENTRY_FILE = "extension.js"
    const val RUNTIME_DIR = "runtime"

    /**
     * Environment variable name for specifying a custom Node.js executable path.
     * When set, this path takes the highest priority in Node.js detection.
     */
    const val ENV_NODE_PATH = "COSTRICT_IDEA_NODE_PATH"

    /**
     * Configuration file constants
     */
    object ConfigFiles {
        /**
         * Main configuration file name
         */
        const val MAIN_CONFIG_FILE = ".vscode-agent"
        
        /**
         * Extension-specific configuration file prefix
         */
        const val EXTENSION_CONFIG_PREFIX = ".vscode-agent."
        
        /**
         * Extension type configuration key
         */
        const val EXTENSION_TYPE_KEY = "extension.type"
        
        /**
         * Debug mode configuration key
         */
        const val DEBUG_MODE_KEY = "debug.mode"
        
        /**
         * Debug resource configuration key
         */
        const val DEBUG_RESOURCE_KEY = "debug.resource"

        /**
         * Toggle JCEF offscreen rendering for WebView instances.
         * Defaults to true to preserve existing rendering behavior.
         */
        const val WEBVIEW_OFFSCREEN_RENDERING_KEY = "webview.offscreen.rendering"

        /**
         * Master switch for the periodic WebView render-refresh watchdog.
         * When enabled, a low-frequency "fake resize" is sent to the JCEF
         * browser so stale / frozen UI frames get re-composited without the
         * user having to drag the tool window. Defaults to true.
         */
        const val WEBVIEW_REFRESH_ENABLED_KEY = "webview.refresh.enabled"

        /**
         * Interval (ms) between periodic render-refresh nudges.
         * Defaults to 2000. Values are clamped to [200, 60000].
         *
         * Shorter = fresher JS-driven updates (streaming tokens) but more
         * frequent page reflows; longer = cheaper but laggier streaming.
         * Plugin-bridge content updates are flushed ~150ms after they arrive
         * regardless of this value.
         */
        const val WEBVIEW_REFRESH_INTERVAL_KEY = "webview.refresh.interval"

        /**
         * Enable the "dirty pixel" mode: instead of only notifying CEF with the
         * same size, the refresh performs a 1px resize-and-restore cycle so the
         * native window really changes size and CEF is forced through its full
         * resize -> re-layout -> re-composite path (exactly what a manual tool
         * window drag does). Defaults to true.
         */
        const val WEBVIEW_REFRESH_DIRTY_PIXEL_KEY = "webview.refresh.dirtyPixel"
        
        /**
         * Get user home directory for configuration storage
         */
        fun getUserConfigDir(): String {
            return System.getProperty("user.home") + "/.costrict-jetbrains"
        }
        
        /**
         * Get main configuration file path in user home directory
         */
        fun getMainConfigPath(): String {
            return getUserConfigDir() + "/" + MAIN_CONFIG_FILE
        }
        
        /**
         * Get extension configuration file path in user home directory
         */
        fun getExtensionConfigPath(extensionId: String): String {
            return getUserConfigDir() + "/" + EXTENSION_CONFIG_PREFIX + extensionId
        }
        
        /**
         * Get extension ID from extension config filename
         */
        fun getExtensionIdFromFilename(filename: String): String? {
            return if (filename.startsWith(EXTENSION_CONFIG_PREFIX)) {
                filename.substring(EXTENSION_CONFIG_PREFIX.length)
            } else null
        }
        
        /**
         * Check if filename is an extension config file
         */
        fun isExtensionConfigFile(filename: String): Boolean {
            return filename.startsWith(EXTENSION_CONFIG_PREFIX) && filename != MAIN_CONFIG_FILE
        }
    }
}
