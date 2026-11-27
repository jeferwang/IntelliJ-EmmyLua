/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
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

package com.tang.intellij.lua.ty

import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.util.io.StringRef
import com.tang.intellij.lua.comment.psi.LuaDocGenericTy
import com.tang.intellij.lua.comment.psi.LuaDocTy
import com.tang.intellij.lua.psi.LuaClassMember
import com.tang.intellij.lua.search.SearchContext

class TyParameter(val name:String, base: String? = null) : TySerializedClass(name, name, base) {

    override val kind: TyKind
        get() = TyKind.GenericParam

    override fun processMembers(context: SearchContext, processor: (ITyClass, LuaClassMember) -> Unit, deep: Boolean) {
        val superType = getSuperClass(context) as? ITyClass ?: return
        superType.processMembers(context, processor, deep)
    }

    override fun doLazyInit(searchContext: SearchContext) {}
}

object TyGenericParamSerializer : TySerializer<TyParameter>() {
    override fun deserializeTy(flags: Int, stream: StubInputStream): TyParameter {
        val name = StringRef.toString(stream.readName())
        val base = StringRef.toString(stream.readName())
        return TyParameter(name, base)
    }

    override fun serializeTy(ty: TyParameter, stream: StubOutputStream) {
        stream.writeName(ty.name)
        stream.writeName(ty.superClassName)
    }
}

interface ITyGeneric : ITy {
    val params: Array<ITy>
    val base: ITy

    fun getParamTy(index: Int): ITy {
        return params.getOrElse(index) { Ty.UNKNOWN }
    }
}

internal fun ITyGeneric.isTableGeneric(): Boolean = base == Ty.TABLE && params.size >= 2

internal fun acceptsStringKey(keyTy: ITy, keyName: String?): Boolean = when (keyTy) {
    is TyUnknown -> true
    is TyStringLiteral -> keyName == null || keyTy.content == keyName
    is TyUnion -> keyTy.getChildTypes().any { acceptsStringKey(it, keyName) }
    else -> keyTy == Ty.STRING
}

private fun isUnconstrainedAtom(ty: ITy): Boolean =
    Ty.isInvalid(ty) || ty == Ty.NIL || (ty is ITyClass && (ty.isAnonymous || ty.isGlobal) && ty !is TyTable)

private fun isUnconstrainedKeyType(ty: ITy?): Boolean = when {
    ty == null -> true
    ty is TyUnion -> ty.getChildTypes().all(::isUnconstrainedAtom)
    else -> isUnconstrainedAtom(ty)
}

internal fun acceptsArrayKey(actualKeyTy: ITy?): Boolean {
    if (isUnconstrainedKeyType(actualKeyTy)) return true
    if (actualKeyTy is TyUnion) return actualKeyTy.getChildTypes().any { !isUnconstrainedAtom(it) && acceptsArrayKey(it) }
    return actualKeyTy == Ty.NUMBER
}

internal fun acceptsIndexKey(expectedKeyTy: ITy, actualKeyTy: ITy?, literalStringName: String?): Boolean {
    if (literalStringName != null) return acceptsStringKey(expectedKeyTy, literalStringName)
    if (expectedKeyTy is TyUnknown || isUnconstrainedKeyType(actualKeyTy)) return true
    if (expectedKeyTy is TyUnion) return expectedKeyTy.getChildTypes().any { acceptsIndexKey(it, actualKeyTy, null) }
    if (actualKeyTy is TyUnion) return actualKeyTy.getChildTypes().any { !isUnconstrainedAtom(it) && acceptsIndexKey(expectedKeyTy, it, null) }
    return when (expectedKeyTy) {
        Ty.NUMBER -> actualKeyTy == Ty.NUMBER
        Ty.STRING -> actualKeyTy == Ty.STRING || actualKeyTy is TyStringLiteral
        is TyStringLiteral -> actualKeyTy == Ty.STRING || (actualKeyTy is TyStringLiteral && actualKeyTy.content == expectedKeyTy.content)
        Ty.BOOLEAN -> actualKeyTy == Ty.BOOLEAN
        Ty.TABLE -> actualKeyTy == Ty.TABLE || actualKeyTy is ITyClass || actualKeyTy is ITyArray || actualKeyTy is ITyGeneric
        is ITyClass -> actualKeyTy == Ty.TABLE || actualKeyTy is ITyClass
        else -> expectedKeyTy == actualKeyTy
    }
}

abstract class TyGeneric : Ty(TyKind.Generic), ITyGeneric {

    override fun equals(other: Any?): Boolean {
        return other is ITyGeneric && other.base == base && other.displayName == displayName
    }

    override fun hashCode(): Int {
        return displayName.hashCode()
    }

    override fun subTypeOf(other: ITy, context: SearchContext, strict: Boolean): Boolean {
        if (super.subTypeOf(other, context, strict)) return true
        if (other !is TyGeneric && base.subTypeOf(other, context, strict)) return true
        return other is TyGeneric
                && base.subTypeOf(other.base, context, strict) // Base should be subtype of other base
                && params.size == other.params.size // Equal amount of params
                && params.indices.all { i -> params[i].subTypeOf(other.params[i], context, strict) } // Params need to be subtypes

    }

    override fun accept(visitor: ITyVisitor) {
        visitor.visitGeneric(this)
    }

    override fun substitute(substitutor: ITySubstitutor): ITy {
        return TySerializedGeneric(
                params.map { it.substitute(substitutor) }.toTypedArray(),
                base.substitute(substitutor)
        )
    }
}

class TyDocGeneric(luaDocGenericTy: LuaDocGenericTy) : TyGeneric() {

    private fun initBaseTy(luaDocGenericTy: LuaDocGenericTy): ITy {
        val firstTyPsi = luaDocGenericTy.firstChild as LuaDocTy
        return firstTyPsi.getType()
    }

    private val _baseTy:ITy = initBaseTy(luaDocGenericTy)

    private fun initParams(luaDocGenericTy: LuaDocGenericTy): Array<ITy> {
        val tyList = luaDocGenericTy.tyList
        val tbl = mutableListOf<ITy>()
        tyList.forEach { tbl.add(it.getType()) }
        //第一个是 base
        tbl.removeAt(0)
        return tbl.toTypedArray()
    }

    private val _params: Array<ITy> = initParams(luaDocGenericTy)

    override val params: Array<ITy>
        get() = _params
    override val base: ITy
        get() = _baseTy

}

class TySerializedGeneric(override val params: Array<ITy>, override val base: ITy) : TyGeneric()

object TyGenericSerializer : TySerializer<ITyGeneric>() {
    override fun deserializeTy(flags: Int, stream: StubInputStream): ITyGeneric {
        val base = Ty.deserialize(stream)
        val size = stream.readByte()
        val params = mutableListOf<ITy>()
        for (i in 0 until size) {
            params.add(Ty.deserialize(stream))
        }
        return TySerializedGeneric(params.toTypedArray(), base)
    }

    override fun serializeTy(ty: ITyGeneric, stream: StubOutputStream) {
        Ty.serialize(ty.base, stream)
        stream.writeByte(ty.params.size)
        ty.params.forEach { Ty.serialize(it, stream) }
    }
}
