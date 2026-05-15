// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.ipc.proxy.interfaces

/**
 * Extension host configuration service interface
 * Corresponds to ExtHostConfiguration in VSCode
 */
interface ExtHostConfigurationProxy {
    /**
     * Initialize configuration
     * @param configModel Configuration model
     */
    fun initializeConfiguration(configModel: Map<String, Any?>)

    /**
     * Accept configuration changed notification from main thread.
     * Keeps the extension host in-memory model in sync after a config write.
     * @param data The full configuration model
     * @param change Change descriptor with keys and overrides
     */
    fun acceptConfigurationChanged(data: Map<String, Any?>, change: Map<String, Any?>)

    /**
     * Update configuration
     * @param configModel Configuration model
     */
    fun updateConfiguration(configModel: Map<String, Any?>)

    /**
     * Get configuration
     * @param key Configuration key
     * @param section Configuration section
     * @param scopeToLanguage Whether to scope to language
     * @return Configuration value
     */
    fun getConfiguration(key: String, section: String?, scopeToLanguage: Boolean): Any?
}