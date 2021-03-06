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

package org.jetbrains.kotlin.serialization.deserialization

import com.google.protobuf.MessageLite
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.descriptors.annotations.AnnotationWithTarget
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.PropertyGetterDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.PropertySetterDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.resolve.DescriptorFactory
import org.jetbrains.kotlin.serialization.Flags
import org.jetbrains.kotlin.serialization.ProtoBuf
import org.jetbrains.kotlin.serialization.deserialization.descriptors.*
import org.jetbrains.kotlin.utils.toReadOnlyList

public class MemberDeserializer(private val c: DeserializationContext) {
    public fun loadProperty(proto: ProtoBuf.Property): PropertyDescriptor {
        val flags = proto.getFlags()

        val property = DeserializedPropertyDescriptor(
                c.containingDeclaration, null,
                getAnnotations(proto, flags, AnnotatedCallableKind.PROPERTY),
                Deserialization.modality(Flags.MODALITY.get(flags)),
                Deserialization.visibility(Flags.VISIBILITY.get(flags)),
                Flags.IS_VAR.get(flags),
                c.nameResolver.getName(proto.getName()),
                Deserialization.memberKind(Flags.MEMBER_KIND.get(flags)),
                Flags.IS_LATEINIT.get(flags),
                Flags.IS_CONST.get(flags),
                proto,
                c.nameResolver,
                c.typeTable
        )

        val local = c.childContext(property, proto.getTypeParameterList())

        val hasGetter = Flags.HAS_GETTER.get(flags)
        val receiverAnnotations = if (hasGetter && (proto.hasReceiverType() || proto.hasReceiverTypeId()))
            getReceiverParameterAnnotations(proto, AnnotatedCallableKind.PROPERTY_GETTER)
        else
            Annotations.EMPTY

        property.setType(
                local.typeDeserializer.type(proto.returnType(c.typeTable)),
                local.typeDeserializer.ownTypeParameters,
                getDispatchReceiverParameter(),
                proto.receiverType(c.typeTable)?.let { local.typeDeserializer.type(it, receiverAnnotations) }
        )

        val getter = if (hasGetter) {
            val getterFlags = proto.getGetterFlags()
            val isNotDefault = proto.hasGetterFlags() && Flags.IS_NOT_DEFAULT.get(getterFlags)
            val isExternal = proto.hasGetterFlags() && Flags.IS_EXTERNAL_ACCESSOR.get(getterFlags)
            val getter = if (isNotDefault) {
                PropertyGetterDescriptorImpl(
                        property,
                        getAnnotations(proto, getterFlags, AnnotatedCallableKind.PROPERTY_GETTER),
                        Deserialization.modality(Flags.MODALITY.get(getterFlags)),
                        Deserialization.visibility(Flags.VISIBILITY.get(getterFlags)),
                        /* hasBody = */ isNotDefault,
                        /* isDefault = */ !isNotDefault,
                        /* isExternal = */ isExternal,
                        property.getKind(), null, SourceElement.NO_SOURCE
                )
            }
            else {
                DescriptorFactory.createDefaultGetter(property, Annotations.EMPTY)
            }
            getter.initialize(property.getReturnType())
            getter
        }
        else {
            null
        }

        val setter = if (Flags.HAS_SETTER.get(flags)) {
            val setterFlags = proto.getSetterFlags()
            val isNotDefault = proto.hasSetterFlags() && Flags.IS_NOT_DEFAULT.get(setterFlags)
            val isExternal = proto.hasSetterFlags() && Flags.IS_EXTERNAL_ACCESSOR.get(setterFlags)
            if (isNotDefault) {
                val setter = PropertySetterDescriptorImpl(
                        property,
                        getAnnotations(proto, setterFlags, AnnotatedCallableKind.PROPERTY_SETTER),
                        Deserialization.modality(Flags.MODALITY.get(setterFlags)),
                        Deserialization.visibility(Flags.VISIBILITY.get(setterFlags)),
                        /* hasBody = */ isNotDefault,
                        /* isDefault = */ !isNotDefault,
                        /* isExternal = */ isExternal,
                        property.getKind(), null, SourceElement.NO_SOURCE
                )
                val setterLocal = local.childContext(setter, listOf())
                val valueParameters = setterLocal.memberDeserializer.valueParameters(
                        listOf(proto.setterValueParameter), proto, AnnotatedCallableKind.PROPERTY_SETTER
                )
                setter.initialize(valueParameters.single())
                setter
            }
            else {
                DescriptorFactory.createDefaultSetter(property, Annotations.EMPTY)
            }
        }
        else {
            null
        }

        if (Flags.HAS_CONSTANT.get(flags)) {
            property.setCompileTimeInitializer(
                    c.storageManager.createNullableLazyValue {
                        val container = c.containingDeclaration.asProtoContainer()!!
                        c.components.annotationAndConstantLoader.loadPropertyConstant(container, proto, property.returnType)
                    }
            )
        }

        property.initialize(getter, setter)

        return property
    }

    public fun loadFunction(proto: ProtoBuf.Function): FunctionDescriptor {
        val annotations = getAnnotations(proto, proto.flags, AnnotatedCallableKind.FUNCTION)
        val receiverAnnotations = if (proto.hasReceiverType() || proto.hasReceiverTypeId())
            getReceiverParameterAnnotations(proto, AnnotatedCallableKind.FUNCTION)
        else Annotations.EMPTY
        val function = DeserializedSimpleFunctionDescriptor.create(c.containingDeclaration, annotations, proto, c.nameResolver, c.typeTable)
        val local = c.childContext(function, proto.typeParameterList)
        function.initialize(
                proto.receiverType(c.typeTable)?.let { local.typeDeserializer.type(it, receiverAnnotations) },
                getDispatchReceiverParameter(),
                local.typeDeserializer.ownTypeParameters,
                local.memberDeserializer.valueParameters(proto.valueParameterList, proto, AnnotatedCallableKind.FUNCTION),
                local.typeDeserializer.type(proto.returnType(c.typeTable)),
                Deserialization.modality(Flags.MODALITY.get(proto.flags)),
                Deserialization.visibility(Flags.VISIBILITY.get(proto.flags))
        )
        function.isOperator = Flags.IS_OPERATOR.get(proto.flags)
        function.isInfix = Flags.IS_INFIX.get(proto.flags)
        function.isExternal = Flags.IS_EXTERNAL_FUNCTION.get(proto.flags)
        function.isInline = Flags.IS_INLINE.get(proto.flags)
        function.isTailrec = Flags.IS_TAILREC.get(proto.flags)
        return function
    }

    private fun getDispatchReceiverParameter(): ReceiverParameterDescriptor? {
        return (c.containingDeclaration as? ClassDescriptor)?.getThisAsReceiverParameter()
    }

    public fun loadConstructor(proto: ProtoBuf.Constructor, isPrimary: Boolean): ConstructorDescriptor {
        val classDescriptor = c.containingDeclaration as ClassDescriptor
        val descriptor = DeserializedConstructorDescriptor(
                classDescriptor, null, getAnnotations(proto, proto.flags, AnnotatedCallableKind.FUNCTION),
                isPrimary, CallableMemberDescriptor.Kind.DECLARATION, proto, c.nameResolver, c.typeTable
        )
        val local = c.childContext(descriptor, listOf())
        descriptor.initialize(
                local.memberDeserializer.valueParameters(proto.valueParameterList, proto, AnnotatedCallableKind.FUNCTION),
                Deserialization.visibility(Flags.VISIBILITY.get(proto.flags))
        )
        descriptor.returnType = classDescriptor.defaultType
        return descriptor
    }

    private fun getAnnotations(proto: MessageLite, flags: Int, kind: AnnotatedCallableKind): Annotations {
        if (!Flags.HAS_ANNOTATIONS.get(flags)) {
            return Annotations.EMPTY
        }
        return DeserializedAnnotationsWithPossibleTargets(c.storageManager) {
            c.containingDeclaration.asProtoContainer()?.let {
                c.components.annotationAndConstantLoader.loadCallableAnnotations(it, proto, kind)
            }.orEmpty()
        }
    }

    private fun getReceiverParameterAnnotations(
            proto: MessageLite,
            kind: AnnotatedCallableKind,
            receiverTargetedKind: AnnotatedCallableKind = kind
    ): Annotations {
        return DeserializedAnnotationsWithPossibleTargets(c.storageManager) {
            c.containingDeclaration.asProtoContainer()?.let {
                c.components.annotationAndConstantLoader
                        .loadExtensionReceiverParameterAnnotations(it, proto, receiverTargetedKind)
                        .map { AnnotationWithTarget(it, AnnotationUseSiteTarget.RECEIVER) }
            }.orEmpty()
        }
    }

    private fun valueParameters(
            valueParameters: List<ProtoBuf.ValueParameter>,
            callable: MessageLite,
            kind: AnnotatedCallableKind
    ): List<ValueParameterDescriptor> {
        val callableDescriptor = c.containingDeclaration as CallableDescriptor
        val containerOfCallable = callableDescriptor.containingDeclaration.asProtoContainer()

        return valueParameters.mapIndexed { i, proto ->
            val flags = if (proto.hasFlags()) proto.flags else 0
            ValueParameterDescriptorImpl(
                    callableDescriptor, null, i,
                    containerOfCallable?.let { getParameterAnnotations(it, callable, kind, i, proto) } ?: Annotations.EMPTY,
                    c.nameResolver.getName(proto.name),
                    c.typeDeserializer.type(proto.type(c.typeTable)),
                    Flags.DECLARES_DEFAULT_VALUE.get(flags),
                    Flags.IS_CROSSINLINE.get(flags),
                    Flags.IS_NOINLINE.get(flags),
                    proto.varargElementType(c.typeTable)?.let { c.typeDeserializer.type(it) },
                    SourceElement.NO_SOURCE
            )
        }.toReadOnlyList()
    }

    private fun getParameterAnnotations(
            container: ProtoContainer,
            callable: MessageLite,
            kind: AnnotatedCallableKind,
            index: Int,
            valueParameter: ProtoBuf.ValueParameter
    ): Annotations {
        return DeserializedAnnotations(c.storageManager) {
            c.components.annotationAndConstantLoader.loadValueParameterAnnotations(container, callable, kind, index, valueParameter)
        }
    }

    private fun DeclarationDescriptor.asProtoContainer(): ProtoContainer? = when(this) {
        is PackageFragmentDescriptor -> ProtoContainer(null, fqName, c.nameResolver, c.typeTable)
        is DeserializedClassDescriptor -> ProtoContainer(classProto, null, c.nameResolver, c.typeTable)
        else -> null // TODO: support annotations on lambdas and their parameters
    }
}
