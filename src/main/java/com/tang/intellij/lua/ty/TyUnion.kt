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
import com.tang.intellij.lua.search.SearchContext

class TyUnion : Ty(TyKind.Union) {
    private val childSet = mutableSetOf<ITy>()

    fun getChildTypes() = childSet

    val size:Int
        get() = childSet.size

    fun append(ty: ITy): TyUnion {
        if (ty is TyUnion) {
            ty.childSet.forEach { addChild(it) }
        }
        else addChild(ty)
        return this
    }

    private fun addChild(ty: ITy): Boolean {
        return childSet.add(ty)
    }

    override fun subTypeOf(other: ITy, context: SearchContext, strict: Boolean): Boolean {
        return super.subTypeOf(other, context, strict) || childSet.any { type -> type.subTypeOf(other, context, strict) }
    }

    override fun substitute(substitutor: ITySubstitutor): ITy {
        val u = TyUnion()
        childSet.forEach { u.append(it.substitute(substitutor)) }
        return u
    }

    override fun accept(visitor: ITyVisitor) {
        visitor.visitUnion(this)
    }

    override fun acceptChildren(visitor: ITyVisitor) {
        childSet.forEach { it.accept(visitor) }
    }

    override fun equals(other: Any?): Boolean {
        return other is TyUnion && childSet == other.childSet
    }

    override fun hashCode(): Int {
        return childSet.hashCode()
    }

    companion object {
        fun <T : ITy> find(ty: ITy, clazz: Class<T>): T? {
            if (clazz.isInstance(ty))
                return clazz.cast(ty)
            var ret: T? = null
            process(ty) {
                if (clazz.isInstance(it)) {
                    ret = clazz.cast(it)
                    return@process false
                }
                true
            }
            return ret
        }

        fun process(ty: ITy, process: (ITy) -> Boolean) {
            if (ty is TyUnion) {
                // why nullable ???
                val arr: Array<ITy?> = ty.childSet.toTypedArray()
                for (child in arr) {
                    if (child != null && !process(child))
                        break
                }
            } else process(ty)
        }

        fun each(ty: ITy, fn: (ITy) -> Unit) {
            process(ty) {
                fn(it)
                true
            }
        }

        // used by ver.2017
        @Suppress("unused")
        fun eachPerfect(ty: ITy, process: (ITy) -> Boolean) {
            if (ty is TyUnion) {
                val list = ty.childSet.sorted()
                for (iTy in list) {
                    if (!process(iTy))
                        break
                }
            } else process(ty)
        }

        fun union(t1: ITy, t2: ITy): ITy {
            if (isInvalid(t1)) return t2
            if (isInvalid(t2)) return t1
            if (t1 == t2) return t1
            if (t1 is TyUnion && t2 !is TyUnion && t2 in t1.childSet) return t1
            if (t2 is TyUnion && t1 !is TyUnion && t1 in t2.childSet) return t2
            val u = TyUnion()
            u.append(t1)
            u.append(t2)
            return if (u.childSet.size == 1) u.childSet.first() else u
        }

        fun unionAll(types: Collection<ITy>): ITy {
            var single: ITy? = null
            var union: TyUnion? = null
            for (ty in types) {
                if (isInvalid(ty)) continue
                if (union != null) {
                    union.append(ty)
                } else if (single == null) {
                    single = ty
                } else if (single != ty) {
                    if (single is TyUnion && ty !is TyUnion && ty in single.childSet) continue
                    union = TyUnion().append(single).append(ty)
                    single = null
                }
            }
            return when {
                union != null -> if (union.size == 1) union.childSet.first() else union
                single != null -> single
                else -> UNKNOWN
            }
        }

        fun getPerfectClass(ty: ITy): ITyClass? {
            var clazz: ITyClass? = null
            var anonymous: ITyClass? = null
            var global: ITyClass? = null
            process(ty) {
                if (it is ITyClass) {
                    when {
                        it.isAnonymous -> anonymous = it
                        it.isGlobal -> global = it
                        else -> clazz = it
                    }
                }
                clazz == null
            }
            return clazz ?: global ?: anonymous
        }
    }
}

object TyUnionSerializer : TySerializer<TyUnion>() {
    override fun serializeTy(ty: TyUnion, stream: StubOutputStream) {
        stream.writeInt(ty.size)
        TyUnion.each(ty) { Ty.serialize(it, stream) }
    }

    override fun deserializeTy(flags: Int, stream: StubInputStream): TyUnion {
        val union = TyUnion()
        val size = stream.readInt()
        for (i in 0 until size) {
            union.append(Ty.deserialize(stream))
        }
        return union
    }
}