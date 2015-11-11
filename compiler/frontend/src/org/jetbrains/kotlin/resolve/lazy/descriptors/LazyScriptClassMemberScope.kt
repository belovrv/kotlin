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

package org.jetbrains.kotlin.resolve.lazy.descriptors

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.ConstructorDescriptorImpl
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.lazy.ResolveSession
import org.jetbrains.kotlin.resolve.lazy.declarations.ClassMemberDeclarationProvider

public class LazyScriptClassMemberScope(
        private val resolveSession: ResolveSession,
        declarationProvider: ClassMemberDeclarationProvider,
        thisClass: LazyClassDescriptor,
        trace: BindingTrace)
: LazyClassMemberScope(resolveSession, declarationProvider, thisClass, trace) {

    override fun resolvePrimaryConstructor(): ConstructorDescriptor? {
        val constructor = createConstructor(thisDescriptor, emptyList())
        setDeferredReturnType(constructor)
        return constructor
    }

    private fun createConstructor(scriptDescriptor: ClassDescriptor, valueParameters: List<ValueParameterDescriptor>): ConstructorDescriptorImpl {
        return ConstructorDescriptorImpl.create(
                scriptDescriptor,
                Annotations.EMPTY,
                true,
                SourceElement.NO_SOURCE
        ).initialize(
                valueParameters,
                Visibilities.PUBLIC
        )
    }
}
