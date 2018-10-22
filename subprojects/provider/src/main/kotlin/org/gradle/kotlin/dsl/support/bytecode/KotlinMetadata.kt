/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.kotlin.dsl.support.bytecode

import kotlinx.metadata.Flag
import kotlinx.metadata.Flags
import kotlinx.metadata.KmFunctionVisitor
import kotlinx.metadata.KmPackageExtensionVisitor
import kotlinx.metadata.KmTypeVisitor
import kotlinx.metadata.KmVariance
import kotlinx.metadata.flagsOf
import kotlinx.metadata.jvm.JvmFunctionExtensionVisitor
import kotlinx.metadata.jvm.JvmMethodSignature
import kotlinx.metadata.jvm.JvmPackageExtensionVisitor
import kotlinx.metadata.jvm.JvmPropertyExtensionVisitor
import kotlinx.metadata.jvm.KotlinClassHeader
import kotlinx.metadata.jvm.KotlinClassMetadata
import kotlinx.metadata.jvm.KotlinModuleMetadata

import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.MethodVisitor

import java.io.File


internal
fun publicKotlinClass(
    internalClassName: InternalName,
    header: KotlinClassHeader,
    classBody: ClassWriter.() -> Unit
): ByteArray = publicClass(internalClassName) {
    visitKotlinMetadataAnnotation(header)
    classBody()
}


internal
fun writeFileFacadeClassHeader(fileFacadeWriter: KotlinClassMetadata.FileFacade.Writer.() -> Unit) =
    beginFileFacadeClassHeader().run {
        fileFacadeWriter()
        closeHeader()
    }


internal
fun beginFileFacadeClassHeader() = KotlinClassMetadata.FileFacade.Writer()


internal
fun KotlinClassMetadata.FileFacade.Writer.closeHeader(): KotlinClassHeader {
    (visitExtensions(JvmPackageExtensionVisitor.TYPE) as KmPackageExtensionVisitor).run {
        visitEnd()
    }
    visitEnd()
    return write().header
}


internal
fun moduleMetadataBytesFor(fileFacades: List<InternalName>): ByteArray =
    KotlinModuleMetadata.Writer().run {
        visitPackageParts("org.gradle.kotlin.dsl", fileFacades.map { it.value }, emptyMap())
        visitEnd()
        write().bytes
    }


internal
fun moduleFileFor(baseDir: File, moduleName: String = baseDir.name) =
    baseDir.resolve("META-INF").resolve("$moduleName.kotlin_module")


internal
fun ClassVisitor.publicStaticMethod(
    jvmMethodSignature: JvmMethodSignature,
    signature: String? = null,
    exceptions: Array<String>? = null,
    methodBody: MethodVisitor.() -> Unit
) = jvmMethodSignature.run {
    publicStaticMethod(name, desc, signature, exceptions, methodBody)
}


/**
 * Writes the given [header] to the class file as a [kotlin.Metadata] annotation.
 **/
internal
fun ClassWriter.visitKotlinMetadataAnnotation(header: KotlinClassHeader) {
    visitAnnotation("Lkotlin/Metadata;", true).run {
        visit("mv", header.metadataVersion)
        visit("bv", header.bytecodeVersion)
        visit("k", header.kind)
        visitArray("d1").run {
            header.data1.forEach { visit(null, it) }
            visitEnd()
        }
        visitArray("d2").run {
            header.data2.forEach { visit(null, it) }
            visitEnd()
        }
        visitEnd()
    }
}


internal
inline fun KotlinClassMetadata.FileFacade.Writer.writeFunctionOf(
    receiverType: KmTypeBuilder,
    nullableReturnType: KmTypeBuilder,
    name: String,
    parameterName: String,
    parameterType: KmTypeBuilder,
    signature: JvmMethodSignature,
    functionFlags: Flags = inlineFunctionFlags
) {
    writeFunctionOf(
        receiverType,
        nullableReturnType,
        name,
        signature = signature,
        parameters = { visitParameter(parameterName, parameterType) },
        returnTypeFlags = flagsOf(Flag.Type.IS_NULLABLE),
        functionFlags = functionFlags
    )
}


internal
fun KmFunctionVisitor.visitOptionalParameter(parameterName: String, parameterType: KmTypeBuilder) {
    visitParameter(
        parameterName,
        parameterType,
        parameterFlags = flagsOf(Flag.ValueParameter.DECLARES_DEFAULT_VALUE),
        parameterTypeFlags = flagsOf(Flag.Type.IS_NULLABLE)
    )
}


internal
inline fun KmFunctionVisitor.visitParameter(
    parameterName: String,
    parameterType: KmTypeBuilder,
    parameterFlags: Flags = 0,
    parameterTypeFlags: Flags = 0
) {
    visitValueParameter(parameterFlags, parameterName)!!.run {
        visitType(parameterTypeFlags).with(parameterType)
        visitEnd()
    }
}


internal
inline fun KotlinClassMetadata.FileFacade.Writer.writeFunctionOf(
    receiverType: KmTypeBuilder,
    returnType: KmTypeBuilder,
    name: String,
    parameters: KmFunctionVisitor.() -> Unit,
    signature: JvmMethodSignature,
    returnTypeFlags: Flags = 0,
    functionFlags: Flags = inlineFunctionFlags
) {
    visitFunction(functionFlags, name)!!.run {
        visitReceiverParameterType(0).with(receiverType)
        parameters()
        visitReturnType(returnTypeFlags).with(returnType)
        visitSignature(signature)
        visitEnd()
    }
}


internal
fun KmFunctionVisitor.visitSignature(genericOverload: JvmMethodSignature) {
    (visitExtensions(JvmFunctionExtensionVisitor.TYPE) as JvmFunctionExtensionVisitor).run {
        visit(genericOverload)
        visitEnd()
    }
}


internal
fun KotlinClassMetadata.FileFacade.Writer.writePropertyOf(
    receiverType: KmTypeBuilder,
    returnType: KmTypeBuilder,
    propertyName: String,
    getterSignature: JvmMethodSignature,
    getterFlags: Flags = inlineGetterFlags
) {
    visitProperty(readOnlyPropertyFlags, propertyName, getterFlags, 6)!!.run {
        visitReceiverParameterType(0).with(receiverType)
        visitReturnType(0).with(returnType)
        (visitExtensions(JvmPropertyExtensionVisitor.TYPE) as JvmPropertyExtensionVisitor).run {
            visit(null, getterSignature, null)
            visitSyntheticMethodForAnnotations(null)
            visitEnd()
        }
        visitEnd()
    }
}


internal
inline fun KmTypeVisitor?.with(builder: KmTypeBuilder) {
    this!!.run {
        builder()
        visitEnd()
    }
}


internal
fun actionTypeOf(parameterType: KmTypeBuilder): KmTypeBuilder = {
    visitClass("org/gradle/api/Action")
    visitArgument(0, KmVariance.INVARIANT).with(parameterType)
}


internal
fun functionTypeOf(parameterType: KmTypeBuilder, returnType: KmTypeBuilder): KmTypeBuilder = {
    visitClass("kotlin/Function1")
    visitArgument(0, KmVariance.INVARIANT).with(parameterType)
    visitArgument(0, KmVariance.INVARIANT).with(returnType)
}


internal
typealias KmTypeBuilder = KmTypeVisitor.() -> Unit


internal
fun jvmGetterSignatureFor(propertyName: String, desc: String): JvmMethodSignature =
// TODO:accessors Honor JavaBeans convention?
    JvmMethodSignature("get${propertyName.capitalize()}", desc)


internal
val readOnlyPropertyFlags = flagsOf(
    Flag.IS_PUBLIC,
    Flag.Property.HAS_GETTER,
    Flag.Property.IS_DECLARATION
)


internal
val inlineGetterFlags = flagsOf(
    Flag.IS_PUBLIC,
    Flag.PropertyAccessor.IS_NOT_DEFAULT,
    Flag.PropertyAccessor.IS_INLINE
)


internal
val inlineFunctionFlags = flagsOf(
    Flag.IS_PUBLIC,
    Flag.Function.IS_INLINE
)


internal
val nonInlineFunctionFlags = flagsOf(Flag.IS_PUBLIC)