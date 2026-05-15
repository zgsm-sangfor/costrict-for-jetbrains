// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.actors

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.sina.weibo.agent.commands.CommandRegistry
import com.sina.weibo.agent.commands.ICommand
import com.sina.weibo.agent.diff.DiffViewRegistrar
import com.sina.weibo.agent.editor.registerOpenEditorAPICommands
import com.sina.weibo.agent.terminal.registerTerminalAPICommands
import com.sina.weibo.agent.util.doInvokeMethod
import kotlin.coroutines.Continuation

/**
 * Main thread commands interface.
 * Corresponds to the MainThreadCommandsShape interface in VSCode.
 */
interface MainThreadCommandsShape : Disposable {
    /**
     * Registers a command.
     * @param id The command identifier
     */
    fun registerCommand(id: String)
    
    /**
     * Unregisters a command.
     * @param id The command identifier
     */
    fun unregisterCommand(id: String)
    
    /**
     * Fires a command activation event.
     * @param id The command identifier
     */
    fun fireCommandActivationEvent(id: String)
    
    /**
     * Executes a command.
     * @param id The command identifier
     * @param args List of arguments for the command
     * @return The execution result
     */
    suspend fun executeCommand(id: String, args: List<Any?>): Any?
    
    /**
     * Gets all registered commands.
     * @return List of command identifiers
     */
    fun getCommands(): List<String>
}

/**
 * Implementation of MainThreadCommandsShape that handles command registration and execution.
 * Manages a registry of commands and provides methods to interact with them.
 *
 * @property project The current project context
 */
class MainThreadCommands(val project: Project) : MainThreadCommandsShape {
    private val registry = CommandRegistry(project)
    private val logger = Logger.getInstance(MainThreadCommandsShape::class.java)

    /** VSCode-compatible context key store, used by extensions to track UI state. */
    private val contextKeys = mutableMapOf<String, Any?>()
    
    /**
     * Initializes the command registry with default commands.
     */
    init {
        registerOpenEditorAPICommands(project,registry);
        registerTerminalAPICommands(project,registry);
        DiffViewRegistrar.registerDiffCommands(project, registry);
        //TODO other commands
    }
    /**
     * Registers a command with the given identifier.
     *
     * @param id The command identifier
     */
    override fun registerCommand(id: String) {
        logger.info("Registering command: $id")
    }

    /**
     * Unregisters a command with the given identifier.
     *
     * @param id The command identifier
     */
    override fun unregisterCommand(id: String) {
        logger.info("Unregistering command: $id")
    }

    /**
     * Fires an activation event for the specified command.
     *
     * @param id The command identifier
     */
    override fun fireCommandActivationEvent(id: String) {
        logger.info("Firing command activation event: $id")
    }

    /**
     * Executes a command with the given identifier and arguments.
     *
     * @param id The command identifier
     * @param args List of arguments for the command
     * @return The execution result
     */
    override suspend fun executeCommand(id: String, args: List<Any?>): Any? {
        logger.info("Executing command: $id ")

        // Handle VSCode built-in commands that don't have JetBrains equivalents
        when {
            // _setContext / setContext – store context key for parity with VSCode "when" clauses
            id == "_setContext" || id == "setContext" -> {
                if (args.size >= 2) {
                    val key = args[0]?.toString() ?: ""
                    contextKeys[key] = args[1]
                    logger.info("setContext: $key = ${args[1]}")
                }
                return Unit
            }
            // _getContext / getContext – retrieve a stored context key
            id == "_getContext" || id == "getContext" -> {
                if (args.isNotEmpty()) {
                    val key = args[0]?.toString() ?: ""
                    return contextKeys[key]
                }
                return null
            }
            // Costrict sidebar focus commands – activate the CoStrict tool window
            id == "costrict.AssistantUISidebarProvider.focus" ||
            id == "costrict.SidebarProvider.focus" -> {
                ApplicationManager.getApplication().invokeLater {
                    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CoStrict")
                    toolWindow?.show { }
                }
                logger.info("Focused CoStrict tool window for command: $id")
                return Unit
            }
            // workbench.action.reloadWindow – restart the IDE
            id == "workbench.action.reloadWindow" -> {
                logger.info("Reloading IDE window...")
                ApplicationManager.getApplication().restart()
                return Unit
            }
        }

        // 添加命令映射逻辑，处理 VSCode 与 JetBrains 命令命名差异
        val commandId = when (id) {
            "_workbench.changes" -> "vscode.changes"  // 映射 _workbench.changes 到 vscode.changes
            else -> id
        }

        registry.getCommand(commandId)?.let { cmd->
            runCmd(cmd,args)
        }?: run {
            if (id != commandId) {
                logger.warn("Command not found: $id (mapped to: $commandId)")
            } else {
                logger.warn("Command not found: $id")
            }
        }
        return Unit
    }

    /**
     * Gets all registered command identifiers.
     *
     * @return List of command identifiers
     */
    override fun getCommands(): List<String> {
        logger.info("Getting all commands")
        return registry.getCommands().keys.toList()
    }

    /**
     * Releases resources used by this command handler.
     */
    override fun dispose() {
        logger.info("Releasing resources: MainThreadCommands")
    }

    /**
     * Runs a command with the given arguments.
     * Finds the appropriate method on the command handler and invokes it.
     * Uses Java reflection to avoid issues with shadowJar relocate not updating Kotlin metadata.
     *
     * @param cmd The command to run
     * @param args List of arguments for the command
     */
    private suspend fun runCmd(cmd: ICommand, args: List<Any?>) {
        val handler = cmd.handler()
        val methodName = cmd.getMethod()

        // Use Java reflection to find the method
        val candidateMethods = handler.javaClass.methods.filter { it.name == methodName }
        if (candidateMethods.isEmpty()) {
            logger.error("Command method not found: $methodName")
            return
        }

        // Find the best matching method based on argument count
        val method = candidateMethods.find { m ->
            val paramTypes = m.parameterTypes
            val isSuspend = paramTypes.isNotEmpty() &&
                    Continuation::class.java.isAssignableFrom(paramTypes.last())
            val actualParamCount = if (isSuspend) paramTypes.size - 1 else paramTypes.size
            actualParamCount == args.size
        } ?: candidateMethods.first()

        doInvokeMethod(method, args, handler)
    }

}