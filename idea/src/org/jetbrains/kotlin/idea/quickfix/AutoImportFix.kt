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

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.HintAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.packageDependencies.DependencyValidationManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithVisibility
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.actions.KotlinAddImportAction
import org.jetbrains.kotlin.idea.actions.createGroupedImportsAction
import org.jetbrains.kotlin.idea.actions.createSingleImportAction
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.caches.resolve.getResolveScope
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.core.KotlinIndicesHelper
import org.jetbrains.kotlin.idea.core.isVisible
import org.jetbrains.kotlin.idea.project.ProjectStructureUtil
import org.jetbrains.kotlin.idea.util.CallTypeAndReceiver
import org.jetbrains.kotlin.idea.util.getResolutionScope
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.isImportDirectiveExpression
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.expressions.OperatorConventions
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.kotlin.utils.CachedValueProperty
import org.jetbrains.kotlin.utils.addToStdlib.singletonList
import org.jetbrains.kotlin.utils.addToStdlib.singletonOrEmptyList
import java.util.*

/**
 * Check possibility and perform fix for unresolved references.
 */
internal abstract class AutoImportFixBase<T: KtExpression>(expression: T) :
        KotlinQuickFixAction<T>(expression), HighPriorityAction, HintAction {

    private val modificationCountOnCreate = PsiModificationTracker.SERVICE.getInstance(element.getProject()).getModificationCount()

    private val suggestionCount: Int by CachedValueProperty(
            calculator = { computeSuggestions().size },
            timestampCalculator = { PsiModificationTracker.SERVICE.getInstance(element.project).modificationCount }
    )

    protected abstract val importNames: Collection<Name>
    protected abstract fun getSupportedErrors(): Collection<DiagnosticFactory<*>>
    protected abstract fun getCallTypeAndReceiver(): CallTypeAndReceiver<*, *>

    override fun showHint(editor: Editor): Boolean {
        if (!element.isValid() || isOutdated()) return false

        if (ApplicationManager.getApplication().isUnitTestMode() && HintManager.getInstance().hasShownHintsThatWillHideByOtherHint(true)) return false

        if (suggestionCount == 0) return false

        return createAction(element.project, editor).showHint()
    }

    override fun getText() = KotlinBundle.message("import.fix")

    override fun getFamilyName() = KotlinBundle.message("import.fix")

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile)
            = super.isAvailable(project, editor, file) && suggestionCount > 0

    override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        CommandProcessor.getInstance().runUndoTransparentAction {
            createAction(project, editor!!).execute()
        }
    }

    override fun startInWriteAction() = true

    private fun isOutdated() = modificationCountOnCreate != PsiModificationTracker.SERVICE.getInstance(element.getProject()).getModificationCount()

    protected open fun createAction(project: Project, editor: Editor): KotlinAddImportAction {
        return createSingleImportAction(project, editor, element, computeSuggestions())
    }

    fun computeSuggestions(): Collection<DeclarationDescriptor> {
        if (!element.isValid()) return listOf()
        if (element.getContainingFile() !is KtFile) return emptyList()

        val callTypeAndReceiver = getCallTypeAndReceiver()

        if (callTypeAndReceiver is CallTypeAndReceiver.UNKNOWN) return emptyList()

        if (importNames.isEmpty()) return emptyList()

        return importNames.flatMapTo(LinkedHashSet()) {
            computeSuggestionsForName(it, callTypeAndReceiver)
        }
    }

    fun computeSuggestionsForName(name: Name, callTypeAndReceiver: CallTypeAndReceiver<*, *>):
            Collection<DeclarationDescriptor> {
        val nameStr = name.asString()
        if (nameStr.isEmpty()) return emptyList()

        val file = element.getContainingFile() as KtFile

        fun filterByCallType(descriptor: DeclarationDescriptor) = callTypeAndReceiver.callType.descriptorKindFilter.accepts(descriptor)

        val searchScope = getResolveScope(file)

        val bindingContext = element.analyze(BodyResolveMode.PARTIAL)

        val diagnostics = bindingContext.getDiagnostics().forElement(element)

        if (!diagnostics.any { it.getFactory() in getSupportedErrors() }) return emptyList()

        val resolutionScope = element.getResolutionScope(bindingContext, file.getResolutionFacade())
        val containingDescriptor = resolutionScope.ownerDescriptor

        fun isVisible(descriptor: DeclarationDescriptor): Boolean {
            if (descriptor is DeclarationDescriptorWithVisibility) {
                return descriptor.isVisible(containingDescriptor, bindingContext, element as? KtSimpleNameExpression)
            }

            return true
        }

        val result = ArrayList<DeclarationDescriptor>()

        val indicesHelper = KotlinIndicesHelper(element.getResolutionFacade(), searchScope, ::isVisible)

        val expression = element
        if (expression is KtSimpleNameExpression) {
            if (!expression.isImportDirectiveExpression() && !KtPsiUtil.isSelectorInQualified(expression)) {
                if (ProjectStructureUtil.isJsKotlinModule(file)) {
                    indicesHelper.getKotlinClasses({ it == nameStr }, { true }).filterTo(result, ::filterByCallType)
                }
                else {
                    indicesHelper.getJvmClassesByName(nameStr).filterTo(result, ::filterByCallType)
                }

                indicesHelper.getTopLevelCallablesByName(nameStr).filterTo(result, ::filterByCallType)
            }
        }

        result.addAll(indicesHelper.getCallableTopLevelExtensions(callTypeAndReceiver, element, bindingContext) { it == nameStr })

        return if (result.size > 1)
            reduceCandidatesBasedOnDependencyRuleViolation(result, file)
        else
            result
    }

    private fun reduceCandidatesBasedOnDependencyRuleViolation(
            candidates: Collection<DeclarationDescriptor>, file: PsiFile): Collection<DeclarationDescriptor> {
        val project = file.project
        val validationManager = DependencyValidationManager.getInstance(project)
        return candidates.filter {
            val targetFile = DescriptorToSourceUtilsIde.getAnyDeclaration(project, it)?.containingFile ?: return@filter true
            validationManager.getViolatorDependencyRules(file, targetFile).isEmpty()
        }
    }
}

internal class AutoImportFix(expression: KtSimpleNameExpression) : AutoImportFixBase<KtSimpleNameExpression>(expression) {
    override fun getCallTypeAndReceiver() = CallTypeAndReceiver.detect(element)

    override val importNames: Collection<Name> = run {
        if (element.getIdentifier() == null) {
            val conventionName = KtPsiUtil.getConventionName(element)
            if (conventionName != null) {
                if (element is KtOperationReferenceExpression) {
                    val elementType = element.firstChild.node.elementType
                    if (OperatorConventions.ASSIGNMENT_OPERATIONS.containsKeyRaw(elementType)) {
                        val counterpart = OperatorConventions.ASSIGNMENT_OPERATION_COUNTERPARTS.getRaw(elementType)
                        val counterpartName = OperatorConventions.BINARY_OPERATION_NAMES.get(counterpart)
                        if (counterpartName != null) {
                            return@run listOf(conventionName, counterpartName)
                        }
                    }
                }

                return@run conventionName.singletonOrEmptyList()
            }
        }
        else if (Name.isValidIdentifier(element.getReferencedName())) {
            return@run Name.identifier(element.getReferencedName()).singletonOrEmptyList()
        }

        emptyList<Name>()
    }

    override fun getSupportedErrors() = ERRORS

    companion object : KotlinSingleIntentionActionFactory() {
        override fun createAction(diagnostic: Diagnostic) =
                (diagnostic.getPsiElement() as? KtSimpleNameExpression)?.let { AutoImportFix(it) }

        override fun isApplicableForCodeFragment() = true

        private val ERRORS: Collection<DiagnosticFactory<*>> by lazy(LazyThreadSafetyMode.PUBLICATION) { QuickFixes.getInstance().getDiagnostics(this) }
    }
}

internal class MissingInvokeAutoImportFix(expression: KtExpression) : AutoImportFixBase<KtExpression>(expression) {
    override val importNames = OperatorNameConventions.INVOKE.singletonList()

    override fun getCallTypeAndReceiver() = CallTypeAndReceiver.OPERATOR(element)

    override fun getSupportedErrors() = ERRORS

    companion object : KotlinSingleIntentionActionFactory() {
        override fun createAction(diagnostic: Diagnostic) =
                (diagnostic.psiElement as? KtExpression)?.let { MissingInvokeAutoImportFix(it) }

        private val ERRORS by lazy(LazyThreadSafetyMode.PUBLICATION) { QuickFixes.getInstance().getDiagnostics(this) }
    }
}

internal open class MissingArrayAccessorAutoImportFix(element: KtArrayAccessExpression, override val importNames: Collection<Name>, private val showHint: Boolean) :
        AutoImportFixBase<KtArrayAccessExpression>(element) {
    override fun getCallTypeAndReceiver() =
            CallTypeAndReceiver.OPERATOR(element.arrayExpression!!)

    override fun getSupportedErrors() = ERRORS

    override fun showHint(editor: Editor) = showHint && super.showHint(editor)

    companion object : KotlinSingleIntentionActionFactory() {
        private fun importName(diagnostic: Diagnostic): Name {
            return when (diagnostic.factory) {
                Errors.NO_GET_METHOD -> OperatorNameConventions.GET
                Errors.NO_SET_METHOD -> OperatorNameConventions.SET
                else -> throw IllegalStateException("Shouldn't be called for other diagnostics")
            }
        }

        override fun createAction(diagnostic: Diagnostic): KotlinQuickFixAction<KtArrayAccessExpression>? {
            val factory = diagnostic.factory
            assert(factory == Errors.NO_GET_METHOD || factory == Errors.NO_SET_METHOD)

            val element = diagnostic.psiElement
            if (element is KtArrayAccessExpression && element.arrayExpression != null) {
                return MissingArrayAccessorAutoImportFix(element, importName(diagnostic).singletonList(), true)
            }

            return null
        }

        private val ERRORS by lazy(LazyThreadSafetyMode.PUBLICATION) { QuickFixes.getInstance().getDiagnostics(this) }
    }
}

internal class MissingDelegateAccessorsAutoImportFix(
        element: KtExpression, override val importNames: Collection<Name>, private val solveSeveralProblems: Boolean) :
        AutoImportFixBase<KtExpression>(element) {
    override fun getCallTypeAndReceiver() = CallTypeAndReceiver.DELEGATE(element)

    override fun createAction(project: Project, editor: Editor): KotlinAddImportAction {
        if (solveSeveralProblems) {
            return createGroupedImportsAction(project, editor, element, "Delegate accessors", computeSuggestions())
        }

        return super.createAction(project, editor)
    }

    override fun getSupportedErrors() = ERRORS

    companion object : KotlinSingleIntentionActionFactory() {
        private fun importNames(diagnostics: Collection<Diagnostic>): Collection<Name> {
            return diagnostics.map {
                val missingMethodSignature = Errors.DELEGATE_SPECIAL_FUNCTION_MISSING.cast(it).a
                if (missingMethodSignature.startsWith(OperatorNameConventions.GET_VALUE.identifier))
                    OperatorNameConventions.GET_VALUE
                else
                    OperatorNameConventions.SET_VALUE
            }.distinct()
        }

        override fun createAction(diagnostic: Diagnostic): KotlinQuickFixAction<KtExpression>? {
            return (diagnostic.psiElement as? KtExpression)?.let {
                MissingDelegateAccessorsAutoImportFix(it, importNames(diagnostic.singletonList()), false)
            }
        }

        override fun doCreateActionsForAllProblems(sameTypeDiagnostics: Collection<Diagnostic>): List<IntentionAction> {
            val element = sameTypeDiagnostics.first().psiElement
            val names = importNames(sameTypeDiagnostics)
            return (element as? KtExpression)?.let { MissingDelegateAccessorsAutoImportFix(it, names, true) }.singletonOrEmptyList()
        }

        private val ERRORS by lazy(LazyThreadSafetyMode.PUBLICATION) { QuickFixes.getInstance().getDiagnostics(this) }
    }
}

internal class MissingComponentsAutoImportFix(element: KtExpression, override val importNames: Collection<Name>, private val solveSeveralProblems: Boolean) :
        AutoImportFixBase<KtExpression>(element) {
    override fun getCallTypeAndReceiver() = CallTypeAndReceiver.OPERATOR(element)

    override fun createAction(project: Project, editor: Editor): KotlinAddImportAction {
        if (solveSeveralProblems) {
            return createGroupedImportsAction(project, editor, element, "Component functions", computeSuggestions())
        }

        return super.createAction(project, editor)
    }

    override fun getSupportedErrors() = ERRORS

    companion object : KotlinSingleIntentionActionFactory() {
        private fun importNames(diagnostics: Collection<Diagnostic>) =
                diagnostics.map { Name.identifier(Errors.COMPONENT_FUNCTION_MISSING.cast(it).a.identifier) }

        override fun createAction(diagnostic: Diagnostic): KotlinQuickFixAction<KtExpression>? {
            return (diagnostic.psiElement as? KtExpression)?.let {
                MissingComponentsAutoImportFix(it, importNames(diagnostic.singletonList()), false)
            }
        }

        override fun doCreateActionsForAllProblems(sameTypeDiagnostics: Collection<Diagnostic>): List<IntentionAction> {
            val element = sameTypeDiagnostics.first().psiElement
            val names = importNames(sameTypeDiagnostics)
            val solveSeveralProblems = sameTypeDiagnostics.size > 1
            return (element as? KtExpression)?.let { MissingComponentsAutoImportFix(it, names, solveSeveralProblems) }.singletonOrEmptyList()
        }

        private val ERRORS by lazy(LazyThreadSafetyMode.PUBLICATION) { QuickFixes.getInstance().getDiagnostics(this) }
    }
}

object AutoImportForMissingOperatorFactory : KotlinSingleIntentionActionFactory() {
    override fun createAction(diagnostic: Diagnostic): IntentionAction? {
        val element = diagnostic.psiElement as? KtExpression ?: return null
        val operatorDescriptor = Errors.OPERATOR_MODIFIER_REQUIRED.cast(diagnostic).a
        val name = operatorDescriptor.name
        when (name) {
            OperatorNameConventions.GET, OperatorNameConventions.SET -> {
                if (element is KtArrayAccessExpression) {
                    return object: MissingArrayAccessorAutoImportFix(element, name.singletonList(), false) {
                        override fun getSupportedErrors() = Errors.OPERATOR_MODIFIER_REQUIRED.singletonList()
                    }
                }
            }
        }

        return null
    }
}