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
        if (p2 is LuaCallExpr) {
            val idx = p1.getIndexFor(this)
            val fTy = TyAliasSubstitutor.substitute(infer(p2.expr, context), context)
            var ret: ITy = Ty.UNKNOWN
            fTy.each {
                if (it is ITyFunction) {
                    it.process { signature ->
                        var solvedSignature = signature
                        val substitutor = p2.createSubstitutor(solvedSignature, context)
                        if (substitutor != null) solvedSignature = solvedSignature.substitute(substitutor)

                        ret = ret.union(solvedSignature.getParamTyEx(p2, idx))
                        true
                    }
                }
            }
            return ret
        }
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
                    cls.findMemberType(fieldName, context)?.let { fieldType = fieldType.union(it) }
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
        var result: ITy = Ty.UNKNOWN
        ty.each { result = result.union(extractReturnSlot(it, index)) }
        result
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

fun LuaExprList.guessType(context: SearchContext):ITy {
    val exprList = exprStubList
    return if (exprList.size == 1)
        exprList.first().guessType(context)
    else {
        val list = mutableListOf<ITy>()
        exprList.forEach { list.add(it.guessType(context)) }
        TyTuple(list)
    }
}

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
