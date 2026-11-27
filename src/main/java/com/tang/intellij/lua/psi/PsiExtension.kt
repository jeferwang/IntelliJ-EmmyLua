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

package com.tang.intellij.lua.psi

import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.tang.intellij.lua.comment.LuaCommentUtil
import com.tang.intellij.lua.comment.psi.LuaDocGenericDef
import com.tang.intellij.lua.comment.psi.LuaDocTagClass
import com.tang.intellij.lua.comment.psi.LuaDocTagOverload
import com.tang.intellij.lua.comment.psi.api.LuaComment
import com.tang.intellij.lua.ext.recursionGuard
import com.tang.intellij.lua.lang.type.LuaString
import com.tang.intellij.lua.psi.search.LuaShortNamesManager
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.stubs.LuaFuncBodyOwnerStub
import com.tang.intellij.lua.ty.*

/**
 * 1.
 * ---@type MyClass
 * local a = {}
 *
 * this table should be `MyClass`
 *
 * 2.
 * ---@param callback fun(sender: any, type: string):void
 * local function addListener(type, callback)
 *      ...
 * end
 *
 * addListener(function() end)
 *
 * this closure should be `fun(sender: any, type: string):void`
 *
 * 3.
 * ---@class MyClass
 * ---@field foo Bar
 * local a = {}
 *
 * ---@type MyClass
 * local tbl = {
 *     foo = ?? -- foo should be type of `Bar`
 * }
 */
private fun LuaExpr.shouldBeInternal(context: SearchContext): ITy {
    leadingInlineDocTy()?.let { return it }
    val p1 = parent
    if (p1 is LuaExprList) {
        val p2 = p1.parent
        if (p2 is LuaAssignStat && p1 == p2.valueExprList) {
            val index = p1.exprStubList.indexOf(this)
            if (index < 0) return Ty.UNKNOWN
            if (index == 0) {
                val docTy = p2.comment?.ty
                if (docTy != null && !Ty.isInvalid(docTy)) return docTy
            }
            val receiver = p2.varExprList.getExprAt(index)
            if (receiver != null)
                return infer(receiver, context)
        } else if (p2 is LuaLocalDef) {
            val receiver = p2.nameList?.nameDefList?.getOrNull(p1.exprStubList.indexOf(this))
            if (receiver != null)
                return infer(receiver, context)
        } else if (p2 is LuaReturnStat) {
            val index = p1.exprStubList.indexOf(this)
            if (index < 0) return Ty.UNKNOWN
            if (index == 0) {
                val docTy = p2.comment?.ty
                if (docTy != null && !Ty.isInvalid(docTy)) return docTy
            }
            val owner = PsiTreeUtil.getParentOfType(p2, LuaFuncBodyOwner::class.java) ?: return Ty.UNKNOWN
            return owner.expectedReturnSlot(index, context)
        }
    } else if (p1 is LuaParenExpr) {
        return p1.shouldBe(context)
    } else if (p1 is LuaBinaryExpr) {
        when (p1.stub?.opType ?: p1.binaryOp.node.firstChildNode?.elementType) {
            LuaTypes.AND -> if (this == p1.right) return p1.shouldBe(context)
            LuaTypes.OR -> {
                val left = p1.left
                if (this == left || this == p1.right) {
                    val outer = p1.shouldBe(context)
                    if (this != left && left != null && !hasConcreteExpectedType(outer)) return infer(left, context)
                    return outer
                }
            }
        }
    } else if (p1 is LuaArgs) {
        val p2 = p1.parent
        if (p2 is LuaCallExpr) return p2.expectedArgumentType(p1.getIndexFor(this), context)
    } else if (p1 is LuaTableField) {
        val tbl = p1.parent as? LuaTableExpr ?: return Ty.UNKNOWN
        val key = p1.idExpr
        if (this == key) {
            var keyType: ITy = Ty.UNKNOWN
            tbl.shouldBe(context).each {
                if (it is ITyGeneric && it.isTableGeneric()) keyType = keyType.union(it.getParamTy(0))
            }
            return keyType
        }
        if (this != p1.valueExpr) return Ty.UNKNOWN
        val fieldDocTy = p1.comment?.ty
        if (fieldDocTy != null && !Ty.isInvalid(fieldDocTy)) return fieldDocTy

        val tyTbl = tbl.shouldBe(context)
        val fieldName = p1.name
        var fieldType: ITy = Ty.UNKNOWN
        if (fieldName != null) {
            tyTbl.eachTopClass(Processor { cls ->
                if (!(cls is TyTable && cls.table == tbl)) {
                    cls.findMemberType(fieldName, context)?.let {
                        val solved = it.substitute(TySelfSubstitutor(project, null, cls))
                        fieldType = fieldType.union(solved)
                    }
                }
                true
            })
            tyTbl.each {
                if (it is ITyGeneric && it.isTableGeneric() && acceptsStringKey(it.getParamTy(0), fieldName))
                    fieldType = fieldType.union(it.getParamTy(1))
            }
        } else {
            val keyTy = key?.let { infer(it, context) }
            tyTbl.each {
                if (it is ITyArray && keyTy != Ty.STRING && keyTy !is TyStringLiteral)
                    fieldType = fieldType.union(it.base)
                if (it is ITyGeneric && it.isTableGeneric())
                    fieldType = fieldType.union(it.getParamTy(1))
            }
        }
        return fieldType
    }
    return Ty.UNKNOWN
}

internal enum class StandardTableCall { INSERT, SET_METATABLE }

private val KEY_LOCAL_REASSIGNED = Key.create<CachedValue<Boolean>>("lua.local_reassigned")

private fun isLocalReassigned(local: LuaNameDef, owner: LuaLocalDef): Boolean {
    val file = local.containingFile
    if (file is LuaPsiFile && !file.isContentsLoaded) return true
    val scope: PsiElement = PsiTreeUtil.getStubOrPsiParentOfType(owner, LuaDeclarationScope::class.java) ?: file
    return CachedValuesManager.getCachedValue(local, KEY_LOCAL_REASSIGNED) {
        val name = local.name
        var reassigned = false
        PsiTreeUtil.processElements(scope) { element ->
            if (element is LuaFuncDef && element.name == name && resolveLocal(name, element) == local) {
                reassigned = true
                return@processElements false
            }
            if (element is LuaAssignStat) {
                for (lhs in element.varExprList.exprList) {
                    if (lhs is LuaNameExpr && lhs.name == name && resolveLocal(lhs) == local) {
                        reassigned = true
                        return@processElements false
                    }
                }
            }
            true
        }
        CachedValueProvider.Result.create(reassigned, scope)
    }
}

private val KEY_TABLE_MUTATED = Key.create<CachedValue<Boolean>>("lua.table_insert_mutated")
private val KEY_SETMETATABLE_MUTATED = Key.create<CachedValue<Boolean>>("lua.setmetatable_mutated")

private fun isGlobalBuiltinMutated(file: PsiFile, name: String, memberName: String?): Boolean {
    if (file is LuaPsiFile && !file.isContentsLoaded) return true
    val key = if (memberName == null) KEY_SETMETATABLE_MUTATED else KEY_TABLE_MUTATED
    return CachedValuesManager.getCachedValue(file, key) {
        fun global(expr: LuaExpr?): Boolean =
            expr is LuaNameExpr && expr.name == name && resolveLocal(expr) == null

        val unchanged = PsiTreeUtil.processElements(file) { element ->
            val writes = when (element) {
                is LuaAssignStat -> element.varExprList.exprList.any { lhs ->
                    global(lhs) || (memberName != null && lhs is LuaIndexExpr &&
                            lhs.name == memberName && global(lhs.prefixExpr))
                }
                is LuaFuncDef -> element.name == name && resolveLocal(name, element) == null
                is LuaClassMethodDef -> memberName != null && element.name == memberName &&
                        global(element.classMethodName.expr)
                else -> false
            }
            !writes
        }
        CachedValueProvider.Result.create(!unchanged, file)
    }
}

internal fun LuaCallExpr.standardTableCall(): StandardTableCall? {
    val file = containingFile
    if (file is LuaPsiFile && !file.isContentsLoaded) return null
    if (isMethodColonCall) return null
    var callee = expr
    var alias: LuaNameDef? = null
    var owner: LuaLocalDef? = null
    if (callee is LuaNameExpr) {
        val declaration = resolveInFile(callee.name, callee, null)
        if (declaration != null) {
            alias = declaration as? LuaNameDef ?: return null
            owner = PsiTreeUtil.getStubOrPsiParentOfType(alias, LuaLocalDef::class.java) ?: return null
            callee = owner.getExprFor(alias) ?: return null
            while (callee is LuaParenExpr) callee = callee.expr ?: return null
        }
    }
    val builtin = when (callee) {
        is LuaNameExpr -> {
            if (callee.name != "setmetatable" || resolveInFile(callee.name, callee, null) != null ||
                    isGlobalBuiltinMutated(file, "setmetatable", null)) return null
            StandardTableCall.SET_METATABLE
        }
        is LuaIndexExpr -> {
            if (callee.colon != null || callee.name != "insert") return null
            val prefix = callee.prefixExpr as? LuaNameExpr ?: return null
            if (prefix.name != "table" || resolveInFile(prefix.name, prefix, null) != null ||
                    isGlobalBuiltinMutated(file, "table", "insert")) return null
            StandardTableCall.INSERT
        }
        else -> return null
    }
    return if (alias != null && owner != null && isLocalReassigned(alias, owner)) null else builtin
}

internal fun resolveMetatableIndexType(mt: LuaExpr, context: SearchContext): ITy {
    var result: ITy = Ty.UNKNOWN
    fun collect(member: LuaTypeGuessable?) {
        member?.guessType(context)?.each {
            if (it is ITyClass && !it.isAnonymous && !it.isGlobal) result = result.union(it)
        }
    }
    var cur: LuaExpr? = mt
    while (cur is LuaParenExpr) cur = cur.expr
    if (cur is LuaTableExpr) {
        collect(cur.findField("__index")?.valueExpr)
    } else if (cur != null) {
        cur.guessType(context).eachTopClass { cls ->
            collect(cls.findMember("__index", context))
            true
        }
    }
    return result
}

internal fun LuaCallExpr.selectCandidateSignatures(calleeTy: ITy): List<IFunSignature> {
    val selected = mutableListOf<IFunSignature>()
    var bestTier = Int.MAX_VALUE
    val argCount = argList.size
    val colon = isMethodColonCall
    calleeTy.each { ty ->
        if (ty is ITyFunction) ty.process { sig ->
            val fixed = when {
                colon && !sig.colonCall -> (sig.params.size - 1).coerceAtLeast(0)
                !colon && sig.colonCall -> sig.params.size + 1
                else -> sig.params.size
            }
            val tier = when {
                argCount == fixed || (sig.hasVarargs() && argCount >= fixed) -> 0
                argCount < fixed -> 1
                else -> 2
            }
            if (tier < bestTier) {
                bestTier = tier
                selected.clear()
            }
            if (tier == bestTier) selected.add(sig)
            true
        }
    }
    return selected
}

private fun LuaCallExpr.expectedArgumentType(index: Int, context: SearchContext): ITy {
    val args = argList
    when (standardTableCall()) {
        StandardTableCall.INSERT -> if (args.size in 2..3 && index == args.lastIndex) {
            var element: ITy = Ty.UNKNOWN
            args[0].guessType(context).each {
                if (it is ITyArray) element = element.union(it.base)
                if (it is ITyGeneric && it.isTableGeneric()) element = element.union(it.getParamTy(1))
            }
            if (!Ty.isInvalid(element)) return element
        }
        StandardTableCall.SET_METATABLE -> if (index == 0) {
            val outer = shouldBe(context)
            if (hasConcreteExpectedType(outer)) return outer
            args.getOrNull(1)?.let {
                val indexTy = resolveMetatableIndexType(it, context)
                if (!Ty.isInvalid(indexTy)) return indexTy
            }
        }
        null -> Unit
    }

    val selected = selectCandidateSignatures(expr.guessType(context))
    if (selected.isEmpty()) return Ty.UNKNOWN
    val self = TySelfSubstitutor(project, this)
    val results = ArrayList<ITy>(selected.size)
    for (sig in selected) {
        val param = sig.getParamTyEx(this, index)
        val generic = createSubstitutor(sig, context, excludeArgIndex = index)
        results.add((generic?.let { param.substitute(it) } ?: param).substitute(self))
    }
    return TyUnion.unionAll(results)
}

private fun hasConcreteExpectedType(ty: ITy): Boolean = when (ty) {
    is TyUnion -> ty.getChildTypes().any(::hasConcreteExpectedType)
    is ITyClass -> !ty.isAnonymous && !ty.isGlobal
    is ITyArray, is ITyGeneric, is ITyFunction, is TyStringLiteral -> true
    else -> false
}

internal fun LuaExpr.leadingInlineDocTy(): ITy? {
    var sibling = prevSibling
    val beforeList = sibling == null
    if (beforeList) sibling = (parent as? LuaExprList)?.prevSibling ?: return null
    var breaks = 0
    while (sibling is PsiWhiteSpace) {
        breaks += StringUtil.getLineBreakCount(sibling.text)
        if (breaks > 1) return null
        sibling = sibling.prevSibling
    }
    val comment = sibling as? LuaComment ?: return null
    var previous = PsiTreeUtil.prevLeaf(comment)
    var ownLine = false
    while (previous is PsiWhiteSpace) {
        if (StringUtil.getLineBreakCount(previous.text) > 0) ownLine = true
        previous = PsiTreeUtil.prevLeaf(previous)
    }
    val token = previous?.node?.elementType
    if (beforeList && token != LuaTypes.ASSIGN && token != LuaTypes.RETURN) return null
    if (!ownLine && previous != null && token != LuaTypes.LPAREN && token != LuaTypes.ASSIGN &&
            token != LuaTypes.RETURN && token != LuaTypes.AND && token != LuaTypes.OR) return null
    return comment.ty?.takeUnless { Ty.isInvalid(it) }
}

internal fun extractReturnSlot(ty: ITy, index: Int): ITy = when (ty) {
    is TyUnion -> {
        val slots = ArrayList<ITy>(ty.size)
        ty.each { slots.add(extractReturnSlot(it, index)) }
        TyUnion.unionAll(slots)
    }
    is TyTuple -> ty.list.getOrNull(index) ?: Ty.UNKNOWN
    else -> if (index == 0) ty else Ty.UNKNOWN
}

private fun LuaFuncBodyOwner.expectedReturnSlot(index: Int, context: SearchContext): ITy {
    var declared = (this as? LuaCommentOwner)?.comment?.tagReturn?.type
    if (this is LuaClassMethodDef) {
        if (declared == null) {
            val method = findOverridingMethod(context)
            val stub = (method as? StubBasedPsiElementBase<*>)?.stub as? LuaFuncBodyOwnerStub<*>
            declared = stub?.returnDocTy ?: (method as? LuaCommentOwner)?.comment?.tagReturn?.type
        }
        declared = declared?.substitute(TySelfSubstitutor(project, null, guessClassType(context)))
    }
    if (declared != null) return extractReturnSlot(declared, index)
    if (this !is LuaClosureExpr) return Ty.UNKNOWN

    var result: ITy = Ty.UNKNOWN
    shouldBe(context).each { ty ->
        if (ty is ITyFunction && !(ty is TyPsiFunction && ty.psi == this)) {
            ty.process { signature ->
                result = result.union(extractReturnSlot(signature.returnTy, index))
                true
            }
        }
    }
    return result
}

fun LuaExpr.shouldBe(context: SearchContext): ITy = recursionGuard(this, Computable {
    val ty = shouldBeInternal(context)
    if (this is LuaClosureExpr) {
        // Expanding function aliases evaluates lazy return signatures. Drop the initializer itself first.
        var expected: ITy = Ty.UNKNOWN
        ty.each { if (!(it is TyPsiFunction && it.psi == this)) expected = expected.union(it) }
        TyAliasSubstitutor.substitute(expected, context)
    } else TyAliasSubstitutor.substitute(ty, context)
}) ?: Ty.UNKNOWN

/**
 * 获取所在的位置
 */
fun LuaLocalDef.getIndexFor(psi: LuaNameDef): Int {
    var idx = 0
    val nameList = nameList
    val stub = nameList?.stub
    if (stub != null) {
        idx = stub.childrenStubs.indexOf(psi.stub)
    } else {
        LuaPsiTreeUtilEx.processChildren(nameList, Processor{
            if (it is LuaNameDef) {
                if (it == psi)
                    return@Processor false
                idx++
            }
            return@Processor true
        })
    }
    return idx
}

val LuaNameDef.docTy: ITy? get() {
    val stub = stub
    if (stub != null)
        return stub.docTy

    val localDef = PsiTreeUtil.getParentOfType(this, LuaLocalDef::class.java)
    return localDef?.comment?.ty
}

fun LuaAssignStat.getIndexFor(psi: LuaExpr): Int {
    var idx = 0
    val stub = varExprList.stub
    if (stub != null) {
        val children = stub.childrenStubs
        for (i in 0 until children.size) {
            if (psi == children[i].psi) {
                idx = i
                break
            }
        }
    } else {
        LuaPsiTreeUtilEx.processChildren(this.varExprList, Processor{
            if (it is LuaExpr) {
                if (it == psi)
                    return@Processor false
                idx++
            }
            return@Processor true
        })
    }
    return idx
}

fun LuaAssignStat.getExprAt(index:Int) : LuaExpr? {
    val list = this.varExprList.exprList
    return list.getOrNull(index)
}

fun LuaArgs.getIndexFor(psi: LuaExpr): Int {
    if (this is LuaSingleArg)
        return 0
    var idx = 0
    LuaPsiTreeUtilEx.processChildren(this, Processor {
        if (it is LuaExpr) {
            if (it == psi)
                return@Processor false
            idx++
        }
        return@Processor true
    })
    return idx
}

val LuaExprList.exprStubList: List<LuaExpr> get() {
    return PsiTreeUtil.getStubChildrenOfTypeAsList(this, LuaExpr::class.java)
}

fun LuaExprList.getExprAt(idx: Int): LuaExpr? {
    return exprStubList.getOrNull(idx)
}

internal fun LuaExpr.isMultiReturnExpandable(): Boolean =
    this is LuaCallExpr || (this is LuaLiteralExpr && kind == LuaLiteralKind.Varargs)

internal fun adjustExprList(exprs: List<LuaExpr>, context: SearchContext, slot0Override: ITy? = null): ITy {
    if (exprs.isEmpty()) return slot0Override ?: Ty.UNKNOWN
    if (exprs.size == 1) {
        val only = exprs[0]
        if (slot0Override == null) {
            return if (only.isMultiReturnExpandable()) only.guessType(context)
            else context.withIndex(0) { only.guessType(context) }
        }
        if (!only.isMultiReturnExpandable()) return slot0Override
        val full = context.withIndex(-1) { only.guessType(context) }
        val arms = mutableListOf<ITy>()
        TyUnion.each(full) { alt ->
            if (alt is TyTuple && alt.list.size > 1) {
                val replaced = ArrayList<ITy>(alt.list.size)
                replaced.add(slot0Override)
                for (j in 1 until alt.list.size) replaced.add(alt.list[j])
                arms.add(TyTuple(replaced))
            } else {
                arms.add(slot0Override)
            }
        }
        return if (arms.isEmpty()) slot0Override else TyUnion.unionAll(arms)
    }

    val prefix = ArrayList<ITy>(exprs.size)
    val lastIndex = exprs.lastIndex
    for (i in 0 until lastIndex) {
        if (i == 0 && slot0Override != null) {
            prefix.add(slot0Override)
        } else {
            prefix.add(context.withIndex(0) { exprs[i].guessType(context) })
        }
    }
    val last = exprs[lastIndex]
    if (!last.isMultiReturnExpandable()) {
        prefix.add(context.withIndex(0) { last.guessType(context) })
        return TyTuple(prefix)
    }
    val lastTy = context.withIndex(-1) { last.guessType(context) }
    if (lastTy is TyTuple) {
        val merged = ArrayList<ITy>(prefix.size + lastTy.list.size)
        merged.addAll(prefix)
        merged.addAll(lastTy.list)
        return TyTuple(merged)
    }
    if (lastTy is TyUnion && lastTy.getChildTypes().any { it is TyTuple }) {
        val arms = ArrayList<ITy>(lastTy.size)
        TyUnion.each(lastTy) { alt ->
            if (alt is TyTuple) {
                val merged = ArrayList<ITy>(prefix.size + alt.list.size)
                merged.addAll(prefix)
                merged.addAll(alt.list)
                arms.add(TyTuple(merged))
            } else {
                val merged = ArrayList<ITy>(prefix.size + 1)
                merged.addAll(prefix)
                merged.add(alt)
                arms.add(TyTuple(merged))
            }
        }
        return TyUnion.unionAll(arms)
    }
    prefix.add(lastTy)
    return TyTuple(prefix)
}

fun LuaExprList.guessType(context: SearchContext): ITy = adjustExprList(exprStubList, context)

fun LuaParametersOwner.getIndexFor(paramNameDef: LuaParamNameDef): Int {
    val list = this.paramNameDefList
    list?.indices?.filter { list[it].name == paramNameDef.name }?.forEach { return it }
    return 0
}

fun LuaLocalDef.getExprFor(nameDef: LuaNameDef): LuaExpr? {
    val nameList = this.nameList ?: return null
    val exprList = this.exprList ?: return null

    var next = nameList.firstChild
    var idx = 0
    var found = false
    while (next != null) {
        if (next is LuaNameDef) {
            if (next == nameDef) {
                found = true
                break
            }
            idx++
        }
        next = next.nextSibling
    }
    if (!found) return null
    return exprList.getExprAt(idx)
}

val LuaParamNameDef.owner: LuaParametersOwner
    get() = PsiTreeUtil.getParentOfType(this, LuaParametersOwner::class.java)!!

val LuaFuncBodyOwner.overloads: Array<IFunSignature> get() {
    if (this is StubBasedPsiElementBase<*>) {
        val stub = this.stub
        if (stub is LuaFuncBodyOwnerStub<*>) {
            return stub.overloads
        }
    }

    val list = mutableListOf<IFunSignature>()
    if (this is LuaCommentOwner) {
        val comment = comment
        if (comment != null) {
            val children = PsiTreeUtil.findChildrenOfAnyType(comment, LuaDocTagOverload::class.java)
            val colonCall = this is LuaClassMethodDef && !this.isStatic
            children.forEach {
                val fty = it.functionTy
                if (fty != null)
                    list.add(FunSignature.create(colonCall, fty))
            }
        }
    }
    return list.toTypedArray()
}

val LuaFuncBodyOwner.tyParams: Array<TyParameter> get() {
    if (this is StubBasedPsiElementBase<*>) {
        val stub = this.stub
        if (stub is LuaFuncBodyOwnerStub<*>) {
            return stub.tyParams
        }
    }

    val list = mutableListOf<TyParameter>()
    if (this is LuaCommentOwner) {
        val genericDefList = comment?.findTags(LuaDocGenericDef::class.java)
        genericDefList?.forEach { it.name?.let { name -> list.add(TyParameter(name, it.classNameRef?.text)) } }
    }
    return list.toTypedArray()
}

enum class LuaLiteralKind {
    String,
    Bool,
    Number,
    Nil,
    Varargs,
    Unknown;

    companion object {
        fun toEnum(ID: Byte): LuaLiteralKind {
            return LuaLiteralKind.values().find { it.ordinal == ID.toInt() } ?: Unknown
        }
    }
}

val LuaLiteralExpr.kind: LuaLiteralKind get() {
    val stub = this.stub
    if (stub != null)
        return stub.kind

    return when(node.firstChildNode.elementType) {
        LuaTypes.STRING -> LuaLiteralKind.String
        LuaTypes.TRUE -> LuaLiteralKind.Bool
        LuaTypes.FALSE -> LuaLiteralKind.Bool
        LuaTypes.NIL -> LuaLiteralKind.Nil
        LuaTypes.NUMBER -> LuaLiteralKind.Number
        LuaTypes.ELLIPSIS -> LuaLiteralKind.Varargs
        else -> LuaLiteralKind.Unknown
    }
}

/**
 * too larger to write to stub
 */
val LuaLiteralExpr.tooLargerString: Boolean get() {
    return stub?.tooLargerString ?: (stringValue.length >= 1024 * 10)
}

val LuaLiteralExpr.stringValue: String get() {
    val stub = stub
    if (stub != null && !stub.tooLargerString)
        return stub.string ?: ""
    val content = LuaString.getContent(text)
    return content.value
}

val LuaLiteralExpr.boolValue: Boolean get() = text == "true"

val LuaLiteralExpr.numberValue: Float get() {
    val t = text
    if (t.startsWith("0x", true)) {
        return "${t}p0".toFloat()
    }
    return text.toFloat()
}

val LuaComment.docTy: ITy? get() {
    return this.tagType?.type
}

val LuaComment.ty: ITy? get() {
    val cls = tagClass?.type
    return cls ?: tagType?.type
}

val LuaDocTagClass.aliasName: String? get() {
    val owner = LuaCommentUtil.findOwner(this)
    var expr = when (owner) {
        is LuaAssignStat -> owner.valueExprList?.getExprAt(0)
        is LuaLocalDef -> owner.exprList?.getExprAt(0)
        else -> null
    }
    while (expr is LuaParenExpr) expr = expr.expr
    return (expr as? LuaTableExpr)?.let { getTableTypeName(it) }
}

val LuaIndexExpr.brack: Boolean get() {
    val stub = stub
    return stub?.brack ?: (lbrack != null)
}

val LuaIndexExpr.docTy: ITy? get() {
    val stub = stub
    return if (stub != null)
        stub.docTy
    else
        assignStat?.takeIf { it.getIndexFor(this) == 0 }?.comment?.ty
}

val LuaIndexExpr.prefixExpr: LuaExpr get() {
    return PsiTreeUtil.getStubChildOfType(this, LuaExpr::class.java)!!
    //return firstChild as LuaExpr
}

val LuaExpr.assignStat: LuaAssignStat? get() {
    val p1 = PsiTreeUtil.getStubOrPsiParent(this)
    if (p1 is LuaVarList) {
        val p2 = PsiTreeUtil.getStubOrPsiParent(p1)
        if (p2 is LuaAssignStat)
            return p2
    }
    return null
}

val LuaNameExpr.docTy: ITy? get() {
    val stub = stub
    if (stub != null)
        return stub.docTy
    return assignStat?.takeIf { it.getIndexFor(this) == 0 }?.comment?.ty
}

private val KEY_SHOULD_CREATE_STUB = Key.create<CachedValue<Boolean>>("lua.should_create_stub")

// { field = valueExpr }
// { valueExpr }
// { ["field"] = valueExpr }
val LuaTableField.valueExpr: LuaExpr? get() {
    val list = PsiTreeUtil.getStubChildrenOfTypeAsList(this, LuaExpr::class.java)
    return if (lbrack != null) list.getOrNull(1) else list.firstOrNull()
}

val LuaTableField.shouldCreateStub: Boolean get() =
    CachedValuesManager.getCachedValue(this, KEY_SHOULD_CREATE_STUB) {
        CachedValueProvider.Result.create(innerShouldCreateStub, this)
    }

private val LuaTableField.innerShouldCreateStub: Boolean get() {
    if (id == null && idExpr == null)
        return false
    if (name == null)
        return false

    val tableExpr = PsiTreeUtil.getStubOrPsiParentOfType(this, LuaTableExpr::class.java)
    tableExpr ?: return false
    return tableExpr.shouldCreateStub
}

val LuaTableExpr.shouldCreateStub: Boolean get() =
    CachedValuesManager.getCachedValue(this, KEY_SHOULD_CREATE_STUB) {
        CachedValueProvider.Result.create(innerShouldCreateStub, this)
    }

private val LuaTableExpr.innerShouldCreateStub: Boolean get() {
    val pt = parent
    return when (pt) {
        is LuaTableField -> pt.shouldCreateStub
        is LuaExprList -> {
            val ppt = pt.parent
            when (ppt) {
                is LuaArgs-> false
                else-> true
            }
        }
        else-> true
    }
}

private val KEY_FORWARD = Key.create<CachedValue<PsiElement>>("lua.lua_func_def.forward")

val LuaFuncDef.forwardDeclaration: PsiElement? get() {
    return CachedValuesManager.getCachedValue(this, KEY_FORWARD) {
        val refName = name
        val ret = if (refName == null) null else resolveLocal(refName, this)
        CachedValueProvider.Result.create(ret, this)
    }
}

val LuaCallExpr.prefixExpr: LuaExpr? get() {
    val expr = this.expr
    if (expr is LuaIndexExpr) {
        return expr.prefixExpr
    }
    return null
}

val LuaCallExpr.argList: List<LuaExpr> get() {
    val args = this.args
    return when (args) {
        is LuaSingleArg -> listOf(args.expr)
        is LuaListArgs -> args.exprList
        else -> emptyList()
    }
}

val LuaBinaryExpr.left: LuaExpr? get() {
    return PsiTreeUtil.getStubChildOfType(this, LuaExpr::class.java)
}

val LuaBinaryExpr.right: LuaExpr? get() {
    val list = PsiTreeUtil.getStubChildrenOfTypeAsList(this, LuaExpr::class.java)
    return list.getOrNull(1)
}

fun LuaClassMethod.findOverridingMethod(context: SearchContext): LuaClassMethod? {
    val methodName = name ?: return null
    val type = guessClassType(context) ?: return null
    var superMethod: LuaClassMethod? = null
    TyClass.processSuperClass(type, context) { superType->
        ProgressManager.checkCanceled()
        val superTypeName = superType.className
        superMethod = LuaShortNamesManager.getInstance(context.project).findMethod(superTypeName, methodName, context)
        superMethod == null
    }
    return superMethod
}
