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

package org.jetbrains.kotlin.codegen

import org.jetbrains.kotlin.backend.common.bridges.DescriptorBasedFunctionHandle
import org.jetbrains.kotlin.backend.common.bridges.findAllReachableDeclarations
import org.jetbrains.kotlin.backend.common.bridges.findConcreteSuperDeclaration
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.load.java.BuiltinMethodsWithSpecialGenericSignature
import org.jetbrains.kotlin.load.java.BuiltinMethodsWithSpecialGenericSignature.getSpecialSignatureInfo
import org.jetbrains.kotlin.load.java.descriptors.JavaClassDescriptor
import org.jetbrains.kotlin.load.java.getOverriddenBuiltinWithDifferentJvmDescriptor
import org.jetbrains.kotlin.load.java.hasRealKotlinSuperClassWithOverrideOf
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.calls.callUtil.getParentCall
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.utils.singletonOrEmptyList
import java.util.*

class BridgeForBuiltinSpecial<Signature>(
        val from: Signature, val to: Signature,
        val isSpecial: Boolean = false,
        val isDelegateToSuper: Boolean = false
)

object BuiltinSpecialBridgesUtil {
    @JvmStatic
    public fun <Signature> generateBridgesForBuiltinSpecial(
            function: FunctionDescriptor,
            signatureByDescriptor: (FunctionDescriptor) -> Signature
    ): Set<BridgeForBuiltinSpecial<Signature>> {

        val functionHandle = DescriptorBasedFunctionHandle(function)
        val fake = !functionHandle.isDeclaration
        val overriddenBuiltin = function.getOverriddenBuiltinWithDifferentJvmDescriptor()!!

        val reachableDeclarations = findAllReachableDeclarations(function)

        // e.g. `getSize()I`
        val methodItself = signatureByDescriptor(function)
        // e.g. `size()I`
        val overriddenBuiltinSignature = signatureByDescriptor(overriddenBuiltin)

        val needGenerateSpecialBridge = needGenerateSpecialBridge(
                function, reachableDeclarations, overriddenBuiltin, signatureByDescriptor, overriddenBuiltinSignature)
                                            && methodItself != overriddenBuiltinSignature

        val specialBridge = if (needGenerateSpecialBridge)
            BridgeForBuiltinSpecial(overriddenBuiltinSignature, methodItself, isSpecial = true)
        else null

        val bridgesToGenerate = reachableDeclarations.mapTo(LinkedHashSet<Signature>(), signatureByDescriptor)
        bridgesToGenerate.remove(overriddenBuiltinSignature)

        val superImplementationDescriptor = findSuperImplementationForStubDelegation(function, fake)
        if (superImplementationDescriptor != null || !fake) {
            bridgesToGenerate.remove(methodItself)
        }

        if (fake) {
            for (overridden in function.overriddenDescriptors.map { it.original }) {
                if (!DescriptorBasedFunctionHandle(overridden).isAbstract) {
                    bridgesToGenerate.removeAll(findAllReachableDeclarations(overridden).map(signatureByDescriptor))
                }
            }
        }

        val bridges: MutableSet<BridgeForBuiltinSpecial<Signature>> =
                (bridgesToGenerate.map { BridgeForBuiltinSpecial(it, overriddenBuiltinSignature) } + specialBridge.singletonOrEmptyList()).toMutableSet()

        if (superImplementationDescriptor != null) {
            bridges.add(BridgeForBuiltinSpecial(methodItself, signatureByDescriptor(superImplementationDescriptor), isDelegateToSuper = true))
        }

        return bridges
    }
}


private fun findSuperImplementationForStubDelegation(function: FunctionDescriptor, fake: Boolean): FunctionDescriptor? {
    if (function.modality != Modality.OPEN || !fake) return null
    val implementation = findConcreteSuperDeclaration(DescriptorBasedFunctionHandle(function)).descriptor
    if (DescriptorUtils.isInterface(implementation.containingDeclaration)) return null

    return implementation
}

private fun findAllReachableDeclarations(functionDescriptor: FunctionDescriptor): MutableSet<FunctionDescriptor> =
        findAllReachableDeclarations(DescriptorBasedFunctionHandle(functionDescriptor)).map { it.descriptor }.toMutableSet()

private fun <Signature> needGenerateSpecialBridge(
        functionDescriptor: FunctionDescriptor,
        reachableDeclarations: Collection<FunctionDescriptor>,
        specialCallableDescriptor: CallableMemberDescriptor,
        signatureByDescriptor: (FunctionDescriptor) -> Signature,
        overriddenBuiltinSignature: Signature
): Boolean {
    val classDescriptor = functionDescriptor.containingDeclaration as ClassDescriptor
    return !classDescriptor.hasRealKotlinSuperClassWithOverrideOf(specialCallableDescriptor)
            && specialCallableDescriptor.modality != Modality.FINAL
            && reachableDeclarations.none {
                    it.modality == Modality.FINAL
                        && signatureByDescriptor(it) == overriddenBuiltinSignature
            }
}

public fun isValueArgumentForCallToMethodWithTypeCheckBarrier(
        element: KtElement,
        bindingContext: BindingContext
): Boolean {

    val parentCall = element.getParentCall(bindingContext, strict = true) ?: return false
    val argumentExpression = parentCall.valueArguments.singleOrNull()?.getArgumentExpression() ?: return false
    if (KtPsiUtil.deparenthesize(argumentExpression) !== element) return false

    val candidateDescriptor = parentCall.getResolvedCall(bindingContext)?.candidateDescriptor as CallableMemberDescriptor?
                                ?: return false


    if (candidateDescriptor.getSpecialSignatureInfo() == BuiltinMethodsWithSpecialGenericSignature.SpecialSignatureInfo.GENERIC_PARAMETER) {
        return true
    }

    return false
}
