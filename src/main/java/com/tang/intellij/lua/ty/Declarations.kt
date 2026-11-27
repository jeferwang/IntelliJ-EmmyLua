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

import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.openapi.util.Computable
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.util.PsiTreeUtil
import com.tang.intellij.lua.Constants
import com.tang.intellij.lua.comment.LuaCommentUtil
import com.tang.intellij.lua.comment.psi.LuaDocTableField
import com.tang.intellij.lua.comment.psi.LuaDocTagField
import com.tang.intellij.lua.comment.psi.LuaDocTagReturn
import com.tang.intellij.lua.ext.recursionGuard
import com.tang.intellij.lua.ext.stubOrPsiParent
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.search.GuardType
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.stubs.LuaFuncBodyOwnerStub

fun infer(element: LuaTypeGuessable?, context: SearchContext): ITy {
    if (element == null)
        return Ty.UNKNOWN
    return SearchContext.infer(element, context)
}

internal fun inferInner(element: LuaTypeGuessable, context: SearchContext): ITy {
    return when (element) {
        is LuaFuncBodyOwner -> element.infer(context)
        is LuaExpr -> inferExpr(element, context)
        is LuaParamNameDef -> element.infer(context)
        is LuaNameDef -> element.infer(context)
        is LuaDocTagField -> element.infer()
        is LuaDocTableField -> element.stub?.docTy ?: element.ty?.getType() ?: Ty.UNKNOWN
        is LuaTableField -> element.infer(context)
        is LuaPsiFile -> inferFile(element, context)
        else -> Ty.UNKNOWN
    }
}

fun inferReturnTy(owner: LuaFuncBodyOwner, searchContext: SearchContext): ITy {
    if (owner is StubBasedPsiElementBase<*>) {
        val stub = owner.stub
        if (stub is LuaFuncBodyOwnerStub<*>) {
            return stub.guessReturnTy(searchContext)
        }
    }

    return inferReturnTyInner(owner, searchContext)
}

private fun inferReturnTyInner(owner: LuaFuncBodyOwner, searchContext: SearchContext): ITy {
    if (owner is LuaCommentOwner) {
        val comment = LuaCommentUtil.findComment(owner)
        if (comment != null) {
            val returnDef = PsiTreeUtil.findChildOfType(comment, LuaDocTagReturn::class.java)
            if (returnDef != null) {
                //return returnDef.resolveTypeAt(searchContext.index)
                return returnDef.type
            }
        }
    }

    //infer from return stat
    return searchContext.withRecursionGuard(owner, GuardType.RecursionCall) {
        val collected = mutableListOf<ITy>()
        owner.acceptChildren(object : LuaRecursiveVisitor() {
            override fun visitReturnStat(o: LuaReturnStat) {
                val guessReturnType = guessReturnType(o, searchContext.index, searchContext)
                TyUnion.each(guessReturnType) {
                    /**
                     * 注意，不能排除anonymous
                     * local function test()
                     *      local v = xxx()
                     *      v.yyy = zzz
                     *      return v
                     * end
                     *
                     * local r = test()
                     *
                     * type of r is an anonymous ty
                     */
                    collected.add(it)
                }
            }

            override fun visitExprStat(o: LuaExprStat) {}
            override fun visitLabelStat(o: LuaLabelStat) {}
            override fun visitAssignStat(o: LuaAssignStat) {}
            override fun visitGotoStat(o: LuaGotoStat) {}
            override fun visitClassMethodDef(o: LuaClassMethodDef) {}
            override fun visitFuncDef(o: LuaFuncDef) {}
            override fun visitLocalDef(o: LuaLocalDef) {}
            override fun visitLocalFuncDef(o: LuaLocalFuncDef) {}
        })
        if (collected.isEmpty()) Ty.VOID else TyUnion.unionAll(collected)
    }
}

private fun LuaParamNameDef.infer(context: SearchContext): ITy {
    var type = resolveParamType(this, context)
    //anonymous
    if (Ty.isInvalid(type))
        type = TyClass.createAnonymousType(this)
    return type
}

private fun LuaNameDef.infer(context: SearchContext): ITy {
    var type: ITy = Ty.UNKNOWN
    val parent = this.stubOrPsiParent
    if (parent is LuaTableField) {
        val expr = PsiTreeUtil.findChildOfType(parent, LuaExpr::class.java)
        if (expr != null)
            type = infer(expr, context)
    } else {
        val docTy = this.docTy
        if (docTy != null)
            return docTy

        val localDef = PsiTreeUtil.getStubOrPsiParentOfType(this, LuaLocalDef::class.java)
        if (localDef != null) {
            //计算 expr 返回类型
            if (Ty.isInvalid(type) /*&& !context.forStub*/) {
                val nameList = localDef.nameList
                val exprList = localDef.exprList
                if (nameList != null && exprList != null) {
                    type = context.withIndex(localDef.getIndexFor(this)) {
                        exprList.guessTypeAt(context)
                    }
                }
            }

            //anonymous
            if (type !is ITyPrimitive)
                type = type.union(TyClass.createAnonymousType(this))
            else if (type == Ty.TABLE)
                type = type.union(TyClass.createAnonymousType(this))
        }
    }
    return type
}

private fun LuaDocTagField.infer(): ITy {
    val stub = stub
    if (stub != null)
        return stub.type
    return ty?.getType() ?: Ty.UNKNOWN
}

@Suppress("UNUSED_PARAMETER")
private fun LuaFuncBodyOwner.infer(context: SearchContext): ITy {
    if (this is LuaFuncDef)
        return TyPsiFunction(false, this, TyFlags.GLOBAL)
    return if (this is LuaClassMethodDef) {
        TyPsiFunction(!this.isStatic, this, 0)
    } else TyPsiFunction(false, this, 0)
}

private fun LuaTableField.infer(context: SearchContext): ITy {
    val stub = stub
    //from comment
    val docTy = if (stub != null) stub.docTy else comment?.ty
    if (docTy != null)
        return docTy

    //guess from value
    val valueExpr = valueExpr
    return if (valueExpr != null) infer(valueExpr, context) else Ty.UNKNOWN
}

private fun collectFileReturnsFromStub(parent: StubElement<*>, context: SearchContext, acc: MutableList<ITy>) {
    for (child in parent.childrenStubs) {
        when (val psi = child.psi) {
            is LuaReturnStat -> acc.add(guessReturnType(psi, 0, context))
            is LuaDoStat,
            is LuaIfStat,
            is LuaWhileStat,
            is LuaForAStat,
            is LuaForBStat,
            is LuaRepeatStat -> collectFileReturnsFromStub(child, context, acc)
        }
    }
}

private fun inferFile(file: LuaPsiFile, context: SearchContext): ITy {
    return recursionGuard(file, Computable {
        val moduleName = file.moduleName
        if (moduleName != null)
            TyLazyClass(moduleName)
        else {
            val acc = mutableListOf<ITy>()
            val stub = file.stub
            if (stub != null) {
                collectFileReturnsFromStub(stub, context, acc)
            } else {
                file.acceptChildren(object : LuaRecursiveVisitor() {
                    override fun visitReturnStat(o: LuaReturnStat) {
                        acc.add(guessReturnType(o, 0, context))
                    }

                    override fun visitExprStat(o: LuaExprStat) {}
                    override fun visitLabelStat(o: LuaLabelStat) {}
                    override fun visitAssignStat(o: LuaAssignStat) {}
                    override fun visitGotoStat(o: LuaGotoStat) {}
                    override fun visitClassMethodDef(o: LuaClassMethodDef) {}
                    override fun visitFuncDef(o: LuaFuncDef) {}
                    override fun visitLocalDef(o: LuaLocalDef) {}
                    override fun visitLocalFuncDef(o: LuaLocalFuncDef) {}
                    override fun visitClosureExpr(o: LuaClosureExpr) {}
                })
            }
            TyUnion.unionAll(acc)
        }
    }) ?: Ty.UNKNOWN
}

/**
 * 找参数的类型
 * @param paramNameDef param name
 * *
 * @param context SearchContext
 * *
 * @return LuaType
 */
private fun resolveParamType(paramNameDef: LuaParamNameDef, context: SearchContext): ITy {
    val paramName = paramNameDef.name
    val paramOwner = PsiTreeUtil.getParentOfType(paramNameDef, LuaParametersOwner::class.java)

    val stub = paramNameDef.stub
    val docTy:ITy? = if (stub != null) { stub.docTy } else {
        // from comment
        val commentOwner = PsiTreeUtil.getParentOfType(paramNameDef, LuaCommentOwner::class.java)
        if (commentOwner != null) {
            val docTy = commentOwner.comment?.getParamDef(paramName)?.type
            if (docTy != null)
                return docTy
        }
        null
    }
    if (docTy != null)
        return docTy

    // 如果是个类方法，则有可能在父类里
    if (paramOwner is LuaClassMethodDef) {
        val classType = paramOwner.guessClassType(context)
        val methodName = paramOwner.name
        var set: ITy = Ty.UNKNOWN
        if (classType != null && methodName != null) {
            TyClass.processSuperClass(classType, context) { superType ->
                val superMethod = superType.findMember(methodName, context)
                if (superMethod is LuaClassMethod) {
                    val params = superMethod.params//todo : 优化
                    for (param in params) {
                        if (paramName == param.name) {
                            set = param.ty
                            if (set != Ty.UNKNOWN)
                                return@processSuperClass false
                        }
                    }
                }
                true
            }
        }
        if (set !is TyUnknown)
            return set
    }

    // module fun
    // function method(self) end
    if (paramOwner is LuaFuncDef && paramName == Constants.WORD_SELF) {
        val moduleName = paramNameDef.moduleName
        if (moduleName != null) {
            return TyLazyClass(moduleName)
        }
    }

    //for
    if (paramOwner is LuaForBStat) {
        val exprs = paramOwner.exprList?.exprStubList.orEmpty()
        val firstExpr = exprs.firstOrNull()
        if (firstExpr != null) {
            val paramIndex = paramOwner.getIndexFor(paramNameDef)
            val builtinSlot = resolveBuiltinForBParam(firstExpr, exprs, paramIndex, context)
            if (!Ty.isInvalid(builtinSlot)) return builtinSlot

            val iterTy = TyAliasSubstitutor.substitute(context.withIndex(0) { firstExpr.guessType(context) }, context)
            val slots = mutableListOf<ITy>()
            TyUnion.each(iterTy) {
                if (it is ITyFunction) {
                    it.process { sig ->
                        slots.add(extractReturnSlot(sig.returnTy, paramIndex))
                        true
                    }
                }
            }
            val result = TyUnion.unionAll(slots)
            if (!Ty.isInvalid(result)) return result
        }
    }
    // for param = 1, 2 do end
    if (paramOwner is LuaForAStat)
        return Ty.NUMBER
    /**
     * ---@param processor fun(p1:TYPE):void
     * local function test(processor)
     * end
     *
     * test(function(p1)  end)
     *
     * guess type for p1
     */
    if (paramOwner is LuaClosureExpr) {
        var ret: ITy = Ty.UNKNOWN
        val paramIndex = paramOwner.getIndexFor(paramNameDef)
        val shouldBe = paramOwner.shouldBe(context)
        shouldBe.each {
            if (it is ITyFunction) {
                val ownerSelf = (it as? TyPsiFunction)?.psi?.let { fn ->
                    (fn as? LuaClassMethod)?.guessClassType(context)
                }
                it.process { sig ->
                    val paramTy = if (sig.colonCall) {
                        if (paramIndex == 0) {
                            ownerSelf ?: findCallbackReceiverType(paramOwner, context)
                        } else if (paramIndex - 1 < sig.params.size) {
                            sig.getParamTy(paramIndex - 1)
                        } else {
                            sig.varargTy ?: Ty.UNKNOWN
                        }
                    } else {
                        if (paramIndex < sig.params.size) sig.getParamTy(paramIndex)
                        else sig.varargTy ?: Ty.UNKNOWN
                    }
                    ret = ret.union(paramTy)
                    true
                }
            }
        }
        return ret
    }
    return Ty.UNKNOWN
}

private fun findCallbackReceiverType(expr: LuaExpr, context: SearchContext): ITy {
    var cur: LuaExpr = expr
    var containerTy: ITy? = null
    while (containerTy == null) {
        when (val parent = cur.parent) {
            is LuaParenExpr -> cur = parent
            is LuaBinaryExpr -> {
                val op = parent.stub?.opType ?: parent.binaryOp.node.firstChildNode?.elementType
                val propagates = (op == LuaTypes.AND && cur == parent.right) ||
                        (op == LuaTypes.OR && (cur == parent.left || cur == parent.right))
                if (propagates) cur = parent else break
            }
            is LuaTableField -> {
                if (parent.valueExpr == cur) {
                    containerTy = (parent.parent as? LuaTableExpr)?.shouldBe(context)
                }
                break
            }
            is LuaExprList -> {
                val assign = parent.parent as? LuaAssignStat
                if (assign != null && parent == assign.valueExprList) {
                    val idx = parent.exprStubList.indexOf(cur)
                    val lhs = assign.varExprList.getExprAt(idx) as? LuaIndexExpr
                    containerTy = lhs?.guessParentType(context)
                }
                break
            }
            else -> break
        }
    }
    if (containerTy == null) return Ty.UNKNOWN
    val classes = mutableListOf<ITy>()
    containerTy.eachTopClass { c ->
        if (!c.isAnonymous && !c.isGlobal) classes.add(c)
        true
    }
    return TyUnion.unionAll(classes)
}

private fun resolveBuiltinForBParam(firstExpr: LuaExpr, exprs: List<LuaExpr>, paramIndex: Int, context: SearchContext): ITy {
    if (paramIndex !in 0..1) return Ty.UNKNOWN
    var cur: LuaExpr = firstExpr
    while (cur is LuaParenExpr) cur = cur.expr ?: return Ty.UNKNOWN
    val targetExpr: LuaExpr?
    val isIpairs: Boolean
    if (cur is LuaCallExpr && !cur.isMethodColonCall) {
        when (resolveStandardBuiltin(cur.expr)) {
            "ipairs" -> {
                if (paramIndex == 0) return Ty.NUMBER
                targetExpr = cur.argList.getOrNull(0)
                isIpairs = true
            }
            "pairs" -> {
                targetExpr = cur.argList.getOrNull(0)
                isIpairs = false
            }
            else -> return Ty.UNKNOWN
        }
    } else if (resolveStandardBuiltin(cur) == "next") {
        targetExpr = exprs.getOrNull(1)
        isIpairs = false
    } else {
        return Ty.UNKNOWN
    }
    if (targetExpr == null) return Ty.UNKNOWN
    val tableTy = TyAliasSubstitutor.substitute(context.withIndex(0) { targetExpr.guessType(context) }, context)
    val results = mutableListOf<ITy>()
    TyUnion.each(tableTy) { ty ->
        when {
            ty is ITyArray -> results.add(if (paramIndex == 0) Ty.NUMBER else ty.base)
            ty is ITyGeneric && ty.isTableGeneric() -> {
                val keyTy = ty.getParamTy(0)
                if (!isIpairs || acceptsIndexKey(keyTy, Ty.NUMBER, null)) {
                    results.add(if (paramIndex == 0) keyTy else ty.getParamTy(1))
                }
            }
        }
    }
    return TyUnion.unionAll(results)
}
