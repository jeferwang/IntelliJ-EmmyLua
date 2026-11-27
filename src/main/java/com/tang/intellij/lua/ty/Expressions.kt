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

import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.tang.intellij.lua.Constants
import com.tang.intellij.lua.ext.recursionGuard
import com.tang.intellij.lua.lang.type.LuaString
import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.psi.impl.LuaNameExprMixin
import com.tang.intellij.lua.psi.search.LuaShortNamesManager
import com.tang.intellij.lua.search.GuardType
import com.tang.intellij.lua.search.SearchContext

fun inferExpr(expr: LuaExpr?, context: SearchContext): ITy {
    if (expr == null)
        return Ty.UNKNOWN
    if (expr is LuaIndexExpr || expr is LuaNameExpr) {
        val tree = LuaDeclarationTree.get(expr.containingFile)
        val declaration = tree.find(expr)?.firstDeclaration?.psi
        if (declaration != expr && declaration is LuaTypeGuessable) {
            return declaration.guessType(context)
        }
    }
    return inferExprInner(expr, context)
}

private fun inferExprInner(expr: LuaExpr, context: SearchContext): ITy {
    if ((expr is LuaTableExpr || expr is LuaParenExpr) &&
            (expr.stub == null || (expr.containingFile as? LuaPsiFile)?.isContentsLoaded == true)) {
        expr.leadingInlineDocTy()?.let { return it }
    }
    return when (expr) {
        is LuaUnaryExpr -> expr.infer(context)
        is LuaBinaryExpr -> expr.infer(context)
        is LuaCallExpr -> expr.infer(context)
        is LuaClosureExpr -> infer(expr, context)
        is LuaTableExpr -> expr.infer()
        is LuaParenExpr -> context.withIndex(0) { infer(expr.expr, context) }
        is LuaNameExpr -> expr.infer(context)
        is LuaLiteralExpr -> expr.infer()
        is LuaIndexExpr -> expr.infer(context)
        else -> Ty.UNKNOWN
    }
}

private fun LuaUnaryExpr.infer(context: SearchContext): ITy {
    val stub = stub
    val operator = if (stub != null) stub.opType else unaryOp.node.firstChildNode.elementType

    return when (operator) {
        LuaTypes.MINUS -> context.withIndex(0) { infer(expr, context) } // Negative something
        LuaTypes.GETN -> Ty.NUMBER // Table length is a number
        else -> Ty.UNKNOWN
    }
}

private fun LuaBinaryExpr.infer(context: SearchContext): ITy {
    val stub = stub
    val operator = if (stub != null) stub.opType else {
        val firstChild = firstChild
        val nextVisibleLeaf = PsiTreeUtil.nextVisibleLeaf(firstChild)
        nextVisibleLeaf?.node?.elementType
    }
    return operator.let {
        when (it) {
        //..
            LuaTypes.CONCAT -> Ty.STRING
        //<=, ==, <, ~=, >=, >
            LuaTypes.LE, LuaTypes.EQ, LuaTypes.LT, LuaTypes.NE, LuaTypes.GE, LuaTypes.GT -> Ty.BOOLEAN
        //and, or
            LuaTypes.AND, LuaTypes.OR -> guessAndOrType(this, operator, context)
        //&, <<, |, >>, ~, ^,    +, -, *, /, //, %
            LuaTypes.BIT_AND, LuaTypes.BIT_LTLT, LuaTypes.BIT_OR, LuaTypes.BIT_RTRT, LuaTypes.BIT_TILDE, LuaTypes.EXP,
            LuaTypes.PLUS, LuaTypes.MINUS, LuaTypes.MULT, LuaTypes.DIV, LuaTypes.DOUBLE_DIV, LuaTypes.MOD -> guessBinaryOpType(this, context)
            else -> Ty.UNKNOWN
        }
    }
}

private fun guessAndOrType(binaryExpr: LuaBinaryExpr, operator: IElementType?, context:SearchContext): ITy {
    return context.withIndex(0) {
        val rhs = binaryExpr.right
        //and
        if (operator == LuaTypes.AND)
            return@withIndex infer(rhs, context)

        //or
        val lhs = binaryExpr.left
        val lty = infer(lhs, context)
        if (rhs != null) lty.union(infer(rhs, context)) else lty
    }
}

private fun guessBinaryOpType(binaryExpr : LuaBinaryExpr, context:SearchContext): ITy {
    val lhs = binaryExpr.left
    // TODO: Search for operator overrides
    return context.withIndex(0) { infer(lhs, context) }
}

fun LuaCallExpr.createSubstitutor(sig: IFunSignature, context: SearchContext, excludeArgIndex: Int = -1): ITySubstitutor? {
    if (sig.isGeneric()) {
        val list = mutableListOf<ITy?>()
        // self type
        if (this.isMethodColonCall) {
            this.prefixExpr?.let { prefix ->
                list.add(prefix.guessType(context))
            }
        }
        argList.forEachIndexed { index, arg ->
            list.add(if (index == excludeArgIndex) null else arg.guessType(context))
        }
        val map = mutableMapOf<String, ITy>()
        var processedIndex = if (sig.colonCall) 0 else -1
        sig.tyParameters.forEach { map[it.name] = Ty.UNKNOWN }
        sig.processArgs { index, param ->
            val arg = list.getOrNull(index)
            if (arg != null) {
                GenericAnalyzer(arg, param.ty).analyze(map)
            }
            processedIndex = index
            true
        }
        // vararg
        val varargTy = sig.varargTy
        if (varargTy != null) {
            for (i in processedIndex + 1 until list.size) {
                val argTy = list[i] ?: continue
                GenericAnalyzer(argTy, varargTy).analyze(map)
            }
        }
        sig.tyParameters.forEach {
            val superCls = it.superClassName
            if (Ty.isInvalid(map[it.name]) && superCls != null) map[it.name] = Ty.create(superCls)
        }
        return object : TySubstitutor() {
            override fun substitute(clazz: ITyClass): ITy {
                return map.getOrElse(clazz.className) { clazz }
            }
        }
    }
    return null
}

private fun LuaCallExpr.getReturnTy(sig: IFunSignature, context: SearchContext): ITy {
    val substitutor = createSubstitutor(sig, context)
    var returnTy = if (substitutor != null) sig.returnTy.substitute(substitutor) else sig.returnTy
    returnTy = returnTy.substitute(TySelfSubstitutor(project, this))
    return if (context.guessTuple()) returnTy else extractReturnSlot(returnTy, context.index)
}

private fun LuaCallExpr.infer(context: SearchContext): ITy {
    val luaCallExpr = this
    // xxx()
    val expr = luaCallExpr.expr
    // 从 require 'xxx' 中获取返回类型
    if (expr is LuaNameExpr && LuaSettings.isRequireLikeFunctionName(expr.name)) {
        var filePath: String? = null
        val string = luaCallExpr.firstStringArg
        if (string is LuaLiteralExpr) {
            filePath = string.stringValue
        }
        var file: LuaPsiFile? = null
        if (filePath != null)
            file = resolveRequireFile(filePath, luaCallExpr.project)
        if (file != null)
            return file.guessType(context)

        return Ty.UNKNOWN
    }

    var ret: ITy = Ty.UNKNOWN
    if (!context.forStub && !isMethodColonCall) {
        val args = argList
        when (val builtin = resolveStandardBuiltin(expr)) {
            "setmetatable" -> if (context.index <= 0 && args.size == 2) {
                ret = context.withIndex(0) {
                    val indexTy = resolveMetatableIndexType(args[1], context)
                    indexTy.union(args[0].guessType(context))
                }
            }
            "rawget" -> if (context.index <= 0 && args.size >= 2) {
                val rawTy = resolveRawTableFieldType(args[0], args[1], context)
                if (!Ty.isInvalid(rawTy)) return rawTy
            }
            "rawset" -> if (context.index <= 0 && args.isNotEmpty()) {
                val tblTy = context.withIndex(0) { args[0].guessType(context) }
                if (!Ty.isInvalid(tblTy)) return tblTy
            }
            "table.unpack", "unpack" -> if (args.isNotEmpty()) {
                val listTy = TyAliasSubstitutor.substitute(context.withIndex(0) { args[0].guessType(context) }, context)
                val elems = mutableListOf<ITy>()
                listTy.each {
                    if (it is ITyArray) elems.add(it.base)
                    if (it is ITyGeneric && it.isTableGeneric() && acceptsIndexKey(it.getParamTy(0), Ty.NUMBER, null)) {
                        elems.add(it.getParamTy(1))
                    }
                }
                val elemTy = TyUnion.unionAll(elems)
                if (!Ty.isInvalid(elemTy)) return elemTy
            }
            "select" -> if (args.isNotEmpty()) {
                var selector: LuaExpr = args[0]
                while (selector is LuaParenExpr) selector = selector.expr ?: break
                if (selector is LuaLiteralExpr) {
                    if (selector.kind == LuaLiteralKind.String && LuaString.decodeOrNull(selector.text) == "#") {
                        return if (context.index <= 0) Ty.NUMBER else Ty.UNKNOWN
                    }
                    if (selector.kind == LuaLiteralKind.Number) {
                        val n = selector.text.toIntOrNull()
                        if (n != null && n >= 1) {
                            val shifted = args.drop(n)
                            if (shifted.isEmpty()) return Ty.UNKNOWN
                            if (context.guessTuple()) return adjustExprList(shifted, context)
                            val idx = context.index
                            if (idx < shifted.size) return context.withIndex(0) { shifted[idx].guessType(context) }
                            val last = shifted.last()
                            return if (last.isMultiReturnExpandable())
                                context.withIndex(idx - shifted.lastIndex) { last.guessType(context) }
                            else Ty.UNKNOWN
                        }
                    }
                }
            }
            "pcall", "xpcall" -> {
                val argOffset = if (builtin == "pcall") 1 else 2
                if (args.size >= argOffset) {
                    if (context.index == 0) return Ty.BOOLEAN
                    val fnExpr = args[0]
                    val fnTy = TyAliasSubstitutor.substitute(context.withIndex(0) { fnExpr.guessType(context) }, context)
                    val sigs = selectSignaturesByArity(fnTy, args.size - argOffset, colon = false)
                    val selfReceiver = (fnExpr as? LuaIndexExpr)?.prefixExpr?.let {
                        context.withIndex(0) { it.guessType(context) }
                    }
                    val selfSub = TySelfSubstitutor(project, null, selfReceiver)
                    val fnRets = mutableListOf<ITy>()
                    for (sig in sigs) {
                        val r = sig.returnTy.substitute(selfSub)
                        if (!Ty.isInvalid(r)) fnRets.add(r)
                    }
                    val fnRetTy = TyUnion.unionAll(fnRets)
                    if (context.index > 0) return extractReturnSlot(fnRetTy, context.index - 1)
                    if (context.guessTuple()) return prependTupleSlot(Ty.BOOLEAN, fnRetTy)
                }
            }
        }
    }
    val ty = TyAliasSubstitutor.substitute(infer(expr, context), context)
    val selected = selectCandidateSignatures(ty)
    if (selected.isNotEmpty()) {
        val retTypes = ArrayList<ITy>(selected.size)
        for (sig in selected) {
            retTypes.add(getReturnTy(sig, context))
        }
        ret = ret.union(TyUnion.unionAll(retTypes))
    }
    TyUnion.each(ty) {
        //constructor : Class table __call
        if (it is ITyClass) ret = ret.union(it)
    }

    // xxx.new()
    if (expr is LuaIndexExpr) {
        val fnName = expr.name
        if (fnName != null && LuaSettings.isConstructorName(fnName)) {
            ret = ret.union(expr.guessParentType(context))
        }
    }

    return ret
}

private fun LuaNameExpr.infer(context: SearchContext): ITy {
    val set = recursionGuard(this, Computable {
        var type:ITy = Ty.UNKNOWN

        context.withRecursionGuard(this, GuardType.GlobalName) {
            val multiResolve = multiResolve(this, context)
            var maxTimes = 10
            for (element in multiResolve) {
                val set = getType(context, element)
                type = type.union(set)
                if (--maxTimes == 0)
                    break
            }
            type
        }

        /**
         * fixme : optimize it.
         * function xx:method()
         *     self.name = '123'
         * end
         *
         * https://github.com/EmmyLua/IntelliJ-EmmyLua/issues/93
         * the type of 'self' should be same of 'xx'
         */
        if (Ty.isInvalid(type)) {
            if (name == Constants.WORD_SELF) {
                val methodDef = PsiTreeUtil.getStubOrPsiParentOfType(this, LuaClassMethodDef::class.java)
                if (methodDef != null && !methodDef.isStatic) {
                    val methodName = methodDef.classMethodName
                    val expr = methodName.expr
                    type = expr.guessType(context)
                }
            }
        }

        if (Ty.isInvalid(type)) {
            type = getType(context, this)
        }

        type
    })
    return set ?: Ty.UNKNOWN
}

private fun getType(context: SearchContext, def: PsiElement): ITy {
    when (def) {
        is LuaNameExpr -> {
            //todo stub.module -> ty
            val stub = def.stub
            stub?.module?.let {
                val memberType = createSerializedClass(it).findMemberType(def.name, context)
                if (memberType != null && !Ty.isInvalid(memberType))
                    return memberType
            }

            var type: ITy = def.docTy ?: Ty.UNKNOWN
            //guess from value expr
            val stat = def.assignStat
            if (stat != null) {
                val exprList = stat.valueExprList
                if (exprList != null) {
                    val defIndex = stat.getIndexFor(def)
                    // foo = { ... }
                    if (Ty.isInvalid(type) || exprList.at(defIndex) is LuaTableExpr) {
                        val valueTy = context.withIndex(defIndex) {
                            exprList.guessTypeAt(context)
                        }
                        type = type.union(valueTy)
                    }
                }
            }

            //Global
            if (isGlobal(def) && type !is ITyPrimitive) {
                //use globalClassTy to store class members, that's very important
                type = type.union(TyClass.createGlobalType(def, context.forStub))
            }
            return type
        }
        is LuaTypeGuessable -> return def.guessType(context)
        else -> return Ty.UNKNOWN
    }
}

private fun isGlobal(nameExpr: LuaNameExpr):Boolean {
    val minx = nameExpr as LuaNameExprMixin
    val gs = minx.greenStub
    return gs?.isGlobal ?: (resolveLocal(nameExpr, null) == null)
}

fun LuaLiteralExpr.infer(): ITy {
    return when (this.kind) {
        LuaLiteralKind.Bool -> Ty.BOOLEAN
        LuaLiteralKind.String -> Ty.STRING
        LuaLiteralKind.Number -> Ty.NUMBER
        LuaLiteralKind.Varargs -> {
            val o = PsiTreeUtil.getParentOfType(this, LuaFuncBodyOwner::class.java)
            o?.varargType ?: Ty.UNKNOWN
        }
        //LuaLiteralKind.Nil -> Ty.NIL
        else -> Ty.UNKNOWN
    }
}

private fun LuaIndexExpr.infer(context: SearchContext): ITy {
    val retTy = recursionGuard(this, Computable {
        val indexExpr = this
        var parentTy: ITy? = null
        // xxx[yyy] as an array or map element?
        if (indexExpr.brack) {
            val tySet = TyAliasSubstitutor.substitute(indexExpr.guessParentType(context), context)
            val keyExpr = indexExpr.exprList.getOrNull(1)
            val isStringLiteral = keyExpr is LuaLiteralExpr && keyExpr.kind == LuaLiteralKind.String
            val literalKey = if (isStringLiteral) indexExpr.name else null
            val keyTy = if (isStringLiteral) null else keyExpr?.let { context.withIndex(0) { infer(it, context) } }

            val matched = mutableListOf<ITy>()
            TyUnion.each(tySet) {
                if (it is ITyArray && !isStringLiteral && acceptsArrayKey(keyTy)) {
                    matched.add(it.base)
                } else if (it is ITyGeneric && it.isTableGeneric() &&
                        (!isStringLiteral || literalKey != null) &&
                        acceptsIndexKey(it.getParamTy(0), keyTy, literalKey)) {
                    matched.add(it.getParamTy(1))
                }
            }
            val ty = TyUnion.unionAll(matched)
            if (!Ty.isInvalid(ty)) return@Computable ty

            parentTy = tySet
        }

        //from @type annotation
        val docTy = indexExpr.docTy
        if (docTy != null)
            return@Computable docTy

        // xxx.yyy = zzz
        //from value
        var result: ITy = Ty.UNKNOWN
        val assignStat = indexExpr.assignStat
        if (assignStat != null) {
            result = context.withIndex(assignStat.getIndexFor(indexExpr)) {
                assignStat.valueExprList?.guessTypeAt(context) ?: Ty.UNKNOWN
            }
        }

        //from other class member
        val propName = indexExpr.name
        if (propName != null) {
            val prefixType = parentTy ?: indexExpr.guessParentType(context)
            prefixType.eachTopClass(Processor { clazz ->
                result = result.union(guessFieldType(propName, clazz, context))
                true
            })
            
            // table<string, K> -> member type is K
            prefixType.each { ty ->
                if (ty is ITyGeneric && ty.isTableGeneric() && acceptsStringKey(ty.getParamTy(0), propName))
                    result = result.union(ty.getParamTy(1))
            }
        }
        result
    })

    return retTy ?: Ty.UNKNOWN
}

private fun guessFieldType(fieldName: String, type: ITyClass, context: SearchContext): ITy {
    // _G.var = {}  <==>  var = {}
    if (type.className == Constants.WORD_G)
        return TyClass.createGlobalType(fieldName)

    var set:ITy = Ty.UNKNOWN

    LuaShortNamesManager.getInstance(context.project).processMembers(type, fieldName, context, Processor {
        set = set.union(it.guessType(context))
        true
    })

    return set
}

fun LuaTableExpr.infer(): ITy {
    val list = this.tableFieldList
    if (list.size == 1) {
        val field = list.first()
        if (field.id != null || field.lbrack != null) return TyTable(this)
        val valueExpr = field.valueExpr
        if (valueExpr is LuaLiteralExpr && valueExpr.kind == LuaLiteralKind.Varargs) {
            val func = PsiTreeUtil.getStubOrPsiParentOfType(this, LuaFuncBodyOwner::class.java)
            val ty = func?.varargType
            if (ty != null)
                return TyArray(ty)
        }
    }
    return TyTable(this)
}
