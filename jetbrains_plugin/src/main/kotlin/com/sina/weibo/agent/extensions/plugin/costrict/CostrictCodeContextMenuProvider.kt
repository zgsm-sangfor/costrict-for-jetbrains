// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.extensions.plugin.costrict

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sina.weibo.agent.comments.CommentManager
import com.sina.weibo.agent.extensions.ui.contextmenu.ExtensionContextMenuProvider
import com.sina.weibo.agent.extensions.ui.contextmenu.ContextMenuConfiguration
import com.sina.weibo.agent.extensions.ui.contextmenu.ContextMenuActionType
import com.sina.weibo.agent.webview.WebViewManager
import com.sina.weibo.agent.actions.executeCommand

/**
 * Costrict extension context menu provider.
 * Provides context menu actions specific to Costrict extension.
 * This includes all the advanced functionality evolved from roo.
 */
class CostrictCodeContextMenuProvider : ExtensionContextMenuProvider {
    
    override fun getExtensionId(): String = "costrict"
    
    override fun getDisplayName(): String = "Costrict"
    
    override fun getDescription(): String = "AI-powered code assistant with advanced capabilities and full context menu"
    
    override fun isAvailable(project: Project): Boolean {
        // Check if costrict extension is available
        return true
    }
    
    override fun getContextMenuActions(project: Project): List<AnAction> {
        return listOf(
            ExplainCodeAction(),
            FixCodeAction(),
            FixLogicAction(),
            ImproveCodeAction(),
            AddToContextAction(),
            CodeReviewAction(),
        )
    }
    
    override fun getContextMenuConfiguration(): ContextMenuConfiguration {
        return CostrictCodeContextMenuConfiguration()
    }
    
    /**
     * Costrict context menu configuration - shows all actions (full-featured).
     */
    private class CostrictCodeContextMenuConfiguration : ContextMenuConfiguration {
        override fun isActionVisible(actionType: ContextMenuActionType): Boolean {
            return true // All actions are visible for Costrict
        }
        
        override fun getVisibleActions(): List<ContextMenuActionType> {
            return ContextMenuActionType.values().toList()
        }
    }

    /**
     * Action to explain selected code.
     * Creates a new task with the explanation request.
     */
    class ExplainCodeAction : AnAction("Explain Code") {
        private val logger: Logger = Logger.getInstance(ExplainCodeAction::class.java)

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            val file = e.dataContext.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

            val effectiveRange = CostrictCodeContextMenuProvider.getEffectiveRange(editor)
            if (effectiveRange == null) return

            val args = mutableMapOf<String, Any?>()
            args["filePath"] = file.path
            args["selectedText"] = effectiveRange.text
            args["startLine"] = effectiveRange.startLine + 1
            args["endLine"] = effectiveRange.endLine + 1

            CostrictCodeContextMenuProvider.handleCodeAction("costrict.explainCode.InCurrentTask", "EXPLAIN", args, project)
        }
    }

    /**
     * Action to fix code issues.
     * Creates a new task with the fix request.
     */
    class FixCodeAction : AnAction("Fix Code") {
        private val logger: Logger = Logger.getInstance(FixCodeAction::class.java)

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            val file = e.dataContext.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

            val effectiveRange = CostrictCodeContextMenuProvider.getEffectiveRange(editor)
            if (effectiveRange == null) return

            val args = mutableMapOf<String, Any?>()
            args["filePath"] = file.path
            args["selectedText"] = effectiveRange.text
            args["startLine"] = effectiveRange.startLine + 1
            args["endLine"] = effectiveRange.endLine + 1

            CostrictCodeContextMenuProvider.handleCodeAction("costrict.fixCode.InCurrentTask", "FIX", args, project)
        }
    }

    /**
     * Action to fix logical issues in code.
     * Creates a new task with the logic fix request.
     */
    class FixLogicAction : AnAction("Fix Logic") {
        private val logger: Logger = Logger.getInstance(FixLogicAction::class.java)

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            val file = e.dataContext.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

            val effectiveRange = CostrictCodeContextMenuProvider.getEffectiveRange(editor)
            if (effectiveRange == null) return

            val args = mutableMapOf<String, Any?>()
            args["filePath"] = file.path
            args["selectedText"] = effectiveRange.text
            args["startLine"] = effectiveRange.startLine + 1
            args["endLine"] = effectiveRange.endLine + 1

            CostrictCodeContextMenuProvider.handleCodeAction("costrict.fixCode.InCurrentTask", "FIX", args, project)
        }
    }

    /**
     * Action to improve code quality.
     * Creates a new task with the improvement request.
     */
    class ImproveCodeAction : AnAction("Improve Code") {
        private val logger: Logger = Logger.getInstance(ImproveCodeAction::class.java)

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            val file = e.dataContext.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

            val effectiveRange = CostrictCodeContextMenuProvider.getEffectiveRange(editor)
            if (effectiveRange == null) return

            val args = mutableMapOf<String, Any?>()
            args["filePath"] = file.path
            args["selectedText"] = effectiveRange.text
            args["startLine"] = effectiveRange.startLine + 1
            args["endLine"] = effectiveRange.endLine + 1

            CostrictCodeContextMenuProvider.handleCodeAction("costrict.improveCode.InCurrentTask", "IMPROVE", args, project)
        }
    }

    /**
     * Action to add selected code to context.
     * Adds the code to the current chat context.
     */
    class AddToContextAction : AnAction("Add to Context") {
        private val logger: Logger = Logger.getInstance(AddToContextAction::class.java)

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            val file = e.dataContext.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

            val effectiveRange = CostrictCodeContextMenuProvider.getEffectiveRange(editor)
            if (effectiveRange == null) return

            val args = mutableMapOf<String, Any?>()
            args["filePath"] = file.path
            args["selectedText"] = effectiveRange.text
            args["startLine"] = effectiveRange.startLine + 1
            args["endLine"] = effectiveRange.endLine + 1

            CostrictCodeContextMenuProvider.handleCodeAction("costrict.addToContext", "ADD_TO_CONTEXT", args, project)
        }
    }

    /**
     * Action to create a new task.
     * Opens a new task with the selected code.
     */
    class NewTaskAction : AnAction("New Task") {
        private val logger: Logger = Logger.getInstance(NewTaskAction::class.java)
        
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            val file = e.dataContext.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
            
            val effectiveRange = CostrictCodeContextMenuProvider.getEffectiveRange(editor)
            if (effectiveRange == null) return
            
            val args = mutableMapOf<String, Any?>()
            args["filePath"] = file.path
            args["selectedText"] = effectiveRange.text
            args["startLine"] = effectiveRange.startLine + 1
            args["endLine"] = effectiveRange.endLine + 1
            
            CostrictCodeContextMenuProvider.handleCodeAction("costrict.newTask", "NEW_TASK", args, project)
        }
    }

    /**
     * Action to perform code review.
     * Triggers code review command with the selected code.
     */
    class CodeReviewAction : AnAction("Code Review") {
        private val logger: Logger = Logger.getInstance(CodeReviewAction::class.java)

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            val file = e.dataContext.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

            val effectiveRange = CostrictCodeContextMenuProvider.getEffectiveRange(editor)
            if (effectiveRange == null) return

            val args = mutableMapOf<String, Any?>()
            args["filePath"] = file.path
            args["selectedText"] = effectiveRange.text
            args["startLine"] = effectiveRange.startLine + 1
            args["endLine"] = effectiveRange.endLine + 1

            project.getService(CommentManager::class.java)?.clearAllThreads()

            val webViewManager = project.getService(WebViewManager::class.java)
            val isCloud = webViewManager.getUiMode() == "cloud"

            if (isCloud) {
                // Cloud mode: the registered costrict.codeReviewJetbrains command
                // only routes to the classic controller unless the extension is
                // rebuilt with the cloud branch. Deliver the review prompt directly
                // via the assistantUIContext channel (matching cloudReviewController),
                // so the action works without an extension rebuild.
                logger.info("🔍 Triggering cloud code review via assistantUIContext")
                val promptText = CostrictCodeSupportPrompt.create("ADD_TO_CONTEXT", args)
                val message = mapOf<String, Any?>(
                    "type" to "assistantUIContext",
                    "text" to "/review $promptText",
                    "focus" to true,
                    "newThread" to true,
                    "autoSend" to true
                )
                val messageJson = com.google.gson.Gson().toJson(message)
                webViewManager.getLatestWebView()?.postMessageToWebView(messageJson)
            } else {
                logger.info("🔍 Triggering code review with command: costrict.codeReviewJetbrains")
                executeCommand("costrict.codeReviewJetbrains", project, args)
            }
        }
    }

    /**
     * Data class representing an effective range of selected text.
     * Contains the selected text and its start/end line numbers.
     *
     * @property text The selected text content
     * @property startLine The starting line number (0-based)
     * @property endLine The ending line number (0-based)
     */
    data class EffectiveRange(
        val text: String,
        val startLine: Int,
        val endLine: Int
    )

    companion object {
        /**
         * Gets the effective range and text from the current editor selection.
         *
         * @param editor The current editor instance
         * @return EffectiveRange object containing selected text and line numbers, or null if no selection
         */
        fun getEffectiveRange(editor: com.intellij.openapi.editor.Editor): EffectiveRange? {
            val document = editor.document
            val selectionModel = editor.selectionModel

            return if (selectionModel.hasSelection()) {
                val selectedText = selectionModel.selectedText ?: ""
                val startLine = document.getLineNumber(selectionModel.selectionStart)
                val endLine = document.getLineNumber(selectionModel.selectionEnd)
                EffectiveRange(selectedText, startLine, endLine)
            } else {
                null
            }
        }

        /**
         * Core logic for handling code actions.
         * Processes different types of commands and sends appropriate messages to the webview.
         *
         * @param command The command identifier
         * @param promptType The type of prompt to use
         * @param params Parameters for the action (can be Map or List)
         * @param project The current project
         */
        fun handleCodeAction(command: String, promptType: String, params: Any, project: Project?) {
            val webViewManager = project?.getService(WebViewManager::class.java)
            val latestWebView = webViewManager?.getLatestWebView()
            if (latestWebView == null) {
                return
            }

            // Resolve the normalized prompt params (filePath/selectedText/startLine/endLine)
            // and the base prompt type regardless of how the action was invoked.
            val promptParams: Map<String, Any?> = when (params) {
                is Map<*, *> -> params as Map<String, Any?>
                is List<*> -> {
                    val argsList = params as List<Any>
                    if (argsList.size >= 4) {
                        mapOf(
                            "filePath" to argsList[0],
                            "selectedText" to argsList[1],
                            "startLine" to argsList[2],
                            "endLine" to argsList[3]
                        )
                    } else {
                        emptyMap()
                    }
                }
                else -> emptyMap()
            }
            val basePromptType = when {
                command.contains("addToContext") -> "ADD_TO_CONTEXT"
                command.contains("explain") -> "EXPLAIN"
                command.contains("fix") -> "FIX"
                command.contains("improve") -> "IMPROVE"
                else -> promptType
            }
            val promptText = CostrictCodeSupportPrompt.create(basePromptType, promptParams)

            // Cloud UI is a separate frontend (iframe) that does not understand the
            // classic "invoke" protocol. In cloud mode, deliver the prompt via the
            // "assistantUIContext" message that the cloud webview consumes:
            //   - addToContext: fill the input box without sending (focus only)
            //   - explain/fix/improve: auto-send in a new thread
            val isCloud = webViewManager.getUiMode() == "cloud"
            val messageContent: Map<String, Any?> = if (isCloud) {
                if (command.contains("addToContext")) {
                    mapOf(
                        "type" to "assistantUIContext",
                        "text" to promptText,
                        "focus" to true
                    )
                } else {
                    mapOf(
                        "type" to "assistantUIContext",
                        "text" to promptText,
                        "focus" to true,
                        "newThread" to true,
                        "autoSend" to true
                    )
                }
            } else {
                // Classic mode: keep the original invoke-protocol behavior.
                when {
                    command.contains("addToContext") -> mapOf(
                        "type" to "invoke",
                        "invoke" to "setChatBoxMessage",
                        "text" to promptText
                    )
                    command.endsWith("InCurrentTask") -> mapOf(
                        "type" to "invoke",
                        "invoke" to "sendMessage",
                        "text" to promptText
                    )
                    else -> mapOf(
                        "type" to "invoke",
                        "invoke" to "initClineWithTask",
                        "text" to promptText
                    )
                }
            }

            // Convert to JSON and send
            val messageJson = com.google.gson.Gson().toJson(messageContent)
            latestWebView.postMessageToWebView(messageJson)
        }

        /**
         * Creates a prompt by replacing placeholders in a template with actual values.
         *
         * @param promptType The type of prompt to create
         * @param params Parameters to substitute into the template
         * @return The final prompt with all placeholders replaced
         */
        fun createPrompt(promptType: String, params: Map<String, Any?>): String {
            val template = getPromptTemplate(promptType)
            return replacePlaceholders(template, params)
        }

        /**
         * Gets the template for a specific prompt type.
         *
         * @param type The type of prompt to retrieve
         * @return The template string for the specified prompt type
         */
        fun getPromptTemplate(type: String): String {
            return when (type) {
                "EXPLAIN" -> """Explain the following code from file path ${'$'}{filePath}:${'$'}{startLine}-${'$'}{endLine}

```
${'$'}{selectedText}
```

Please provide a clear and concise explanation of what this code does, including:
1. The purpose and functionality
2. Key components and their interactions
3. Important patterns or techniques used"""
                "FIX" -> """Fix any issues in the following code from file path ${'$'}{filePath}:${'$'}{startLine}-${'$'}{endLine}

```
${'$'}{selectedText}
```

Please:
1. Address all detected problems listed above (if any)
2. Identify any other potential bugs or issues
3. Provide corrected code
4. Explain what was fixed and why"""
                "IMPROVE" -> """Improve the following code from file path ${'$'}{filePath}:${'$'}{startLine}-${'$'}{endLine}

```
${'$'}{selectedText}
```

Please suggest improvements for:
1. Code readability and maintainability
2. Performance optimization
3. Best practices and patterns
4. Error handling and edge cases

Provide the improved code along with explanations for each enhancement."""
                "ADD_TO_CONTEXT" -> """${'$'}{filePath}:${'$'}{startLine}-${'$'}{endLine}
```
${'$'}{selectedText}
```"""
                "NEW_TASK" -> """${'$'}{selectedText}"""
                else -> ""
            }
        }

        /**
         * Replaces placeholders in a template with actual values.
         *
         * @param template The prompt template with placeholders
         * @param params Map of parameter values to replace placeholders
         * @return The processed prompt with placeholders replaced by actual values
         */
        fun replacePlaceholders(template: String, params: Map<String, Any?>): String {
            val pattern = Regex("""\$\{(.*?)}""")
            return pattern.replace(template) { matchResult ->
                val key = matchResult.groupValues[1]
                params[key]?.toString() ?: ""
            }
        }
    }
}
