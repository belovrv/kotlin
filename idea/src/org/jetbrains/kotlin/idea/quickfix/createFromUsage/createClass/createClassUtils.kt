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

package org.jetbrains.kotlin.idea.quickfix.createFromUsage.createClass

import com.intellij.codeInsight.daemon.quickFix.CreateClassOrPackageFix
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPackage
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.core.refactoring.canRefactor
import org.jetbrains.kotlin.idea.quickfix.DelegatingIntentionAction
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.TypeInfo
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.containsStarProjections
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.guessTypes
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.noSubstitutions
import org.jetbrains.kotlin.psi.Call
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.scopes.receivers.Qualifier
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.descriptors.ClassKind as ClassDescriptorKind

internal fun String.checkClassName(): Boolean = isNotEmpty() && Character.isUpperCase(first())

private fun String.checkPackageName(): Boolean = isNotEmpty() && Character.isLowerCase(first())

internal fun getTargetParentByQualifier(
        file: KtFile,
        isQualified: Boolean,
        qualifierDescriptor: DeclarationDescriptor?): PsiElement? {
    val project = file.getProject()
    val targetParent: PsiElement = when {
        !isQualified ->
            file
        qualifierDescriptor is ClassDescriptor ->
            DescriptorToSourceUtilsIde.getAnyDeclaration(project, qualifierDescriptor)
        qualifierDescriptor is PackageViewDescriptor ->
            if (qualifierDescriptor.fqName != file.getPackageFqName()) {
                JavaPsiFacade.getInstance(project).findPackage(qualifierDescriptor.fqName.asString())
            }
            else file as PsiElement // KT-9972
        else ->
            null
    } ?: return null
    return if (targetParent.canRefactor()) return targetParent else null
}

internal fun getTargetParentByCall(call: Call, file: KtFile): PsiElement? {
    val receiver = call.getExplicitReceiver()
    return when (receiver) {
        ReceiverValue.NO_RECEIVER -> getTargetParentByQualifier(file, false, null)
        is Qualifier -> getTargetParentByQualifier(file, true, receiver.resultingDescriptor)
        else -> getTargetParentByQualifier(file, true, receiver.getType().getConstructor().getDeclarationDescriptor())
    }
}

internal fun isInnerClassExpected(call: Call): Boolean {
    val receiver = call.getExplicitReceiver()
    return receiver != ReceiverValue.NO_RECEIVER && receiver !is Qualifier
}

internal fun KtExpression.getInheritableTypeInfo(
        context: BindingContext,
        moduleDescriptor: ModuleDescriptor,
        containingDeclaration: PsiElement): Pair<TypeInfo, (ClassKind) -> Boolean> {
    val types = guessTypes(context, moduleDescriptor, coerceUnusedToUnit = false)
    if (types.size() != 1) return TypeInfo.Empty to { classKind -> true }

    val type = types.first()
    val descriptor = type.getConstructor().getDeclarationDescriptor() ?: return TypeInfo.Empty to { classKind -> false }

    val canHaveSubtypes = !(type.constructor.isFinal || type.containsStarProjections())
    val isEnum = DescriptorUtils.isEnumClass(descriptor)

    if (!(canHaveSubtypes || isEnum)
        || descriptor is TypeParameterDescriptor) return TypeInfo.Empty to { classKind -> false }

    return TypeInfo.ByType(type, Variance.OUT_VARIANCE).noSubstitutions() to { classKind ->
        when (classKind) {
            ClassKind.ENUM_ENTRY -> isEnum && containingDeclaration == DescriptorToSourceUtils.descriptorToDeclaration(descriptor)
            ClassKind.INTERFACE -> containingDeclaration !is PsiClass
                                   || (descriptor as? ClassDescriptor)?.getKind() == ClassDescriptorKind.INTERFACE
            else -> canHaveSubtypes
        }
    }
}

internal fun KtSimpleNameExpression.getCreatePackageFixIfApplicable(targetParent: PsiElement): IntentionAction? {
    val name = getReferencedName()
    if (!name.checkPackageName()) return null

    val basePackage: PsiPackage? = when (targetParent) {
                                      is KtFile -> JavaPsiFacade.getInstance(targetParent.getProject()).findPackage(targetParent.getPackageFqName().asString())
                                      is PsiPackage -> targetParent
                                      else -> null
                                  }
    if (basePackage == null) return null

    val baseName = basePackage.getQualifiedName()
    val fullName = if (baseName.isNotEmpty()) "$baseName.$name" else name

    val javaFix = CreateClassOrPackageFix.createFix(fullName, getResolveScope(), this, basePackage, null, null, null) ?: return null

    return object: DelegatingIntentionAction(javaFix) {
        override fun getFamilyName(): String = KotlinBundle.message("create.from.usage.family")

        override fun getText(): String = "Create package '${fullName}'"
    }
}
