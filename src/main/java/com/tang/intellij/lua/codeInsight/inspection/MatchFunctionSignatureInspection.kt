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

package com.tang.intellij.lua.codeInsight.inspection

import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.*

class MatchFunctionSignatureInspection : StrictInspection() {
    data class ConcreteTypeInfo(val param: LuaExpr, val ty: ITy)
    override fun buildVisitor(myHolder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor =
            object : LuaVisitor() {
                override fun visitIndexExpr(o: LuaIndexExpr) {
                    super.visitIndexExpr(o)
                    val id = o.id
                    if (id != null) {
                        if (o.parent is LuaCallExpr && o.colon != null) {
                            // Guess parent types
                            val context = SearchContext.get(o.project)
                            o.exprList.forEach { expr ->
                                if (expr.guessType(context) == Ty.NIL) {
                                    // If parent type is nil add error
                                    myHolder.registerProblem(expr, "Trying to index a nil type.")
                                }
                            }
                        }
                    }
                }

                override fun visitCallExpr(o: LuaCallExpr) {
                    super.visitCallExpr(o)

                    val searchContext = SearchContext.get(o.project)
                    val prefixExpr = o.expr
                    val type = prefixExpr.guessType(searchContext)

                    if (type is TyFunction) {
                        val candidates = o.selectCandidateSignatures(type, searchContext)
                        val alternatives = collectConcreteArgAlternatives(o, searchContext)
                        val effectiveCandidates = if (candidates.isNotEmpty()) candidates else listOf(type.findPerfectSignature(o))
                        if (effectiveCandidates.size == 1 && alternatives.size == 1) {
                            annotateCall(o, effectiveCandidates.first(), searchContext, alternatives.first())
                        } else {
                            val failing = alternatives.firstOrNull { alt ->
                                effectiveCandidates.none { matchesSignature(o, it, alt, searchContext) }
                            }
                            if (failing != null) {
                                annotateCall(o, effectiveCandidates.first(), searchContext, failing)
                            }
                        }
                    } else if (prefixExpr is LuaIndexExpr) {
                        // Get parent type
                        val parentType = prefixExpr.guessParentType(searchContext)
                        if (parentType is TyClass) {
                            val fType = prefixExpr.name?.let { parentType.findSuperMember(it, searchContext) }
                            if (fType == null)
                                myHolder.registerProblem(o, "Unknown function '%s'.".format(prefixExpr.lastChild.text))
                        }
                    } else if (type == Ty.NIL) {
                        myHolder.registerProblem(o, "Unknown function '%s'.".format(prefixExpr.lastChild.text))
                    }
                }

                private fun collectConcreteArgAlternatives(call: LuaCallExpr, searchContext: SearchContext): List<List<ConcreteTypeInfo>> {
                    val concreteParams = call.argList
                    if (concreteParams.isEmpty()) return listOf(emptyList())
                    val prefix = ArrayList<ConcreteTypeInfo>(concreteParams.size)
                    for (index in 0 until concreteParams.lastIndex) {
                        val expr = concreteParams[index]
                        prefix.add(ConcreteTypeInfo(expr, searchContext.withIndex(0) { expr.guessType(searchContext) }))
                    }
                    val last = concreteParams.last()
                    if (!last.isMultiReturnExpandable()) {
                        prefix.add(ConcreteTypeInfo(last, searchContext.withIndex(0) { last.guessType(searchContext) }))
                        return listOf(prefix)
                    }
                    val ty = searchContext.withIndex(-1) { last.guessType(searchContext) }
                    if (ty is TyUnion && ty.getChildTypes().any { it is TyTuple }) {
                        val alts = ArrayList<List<ConcreteTypeInfo>>(ty.size)
                        TyUnion.each(ty) { arm ->
                            val list = ArrayList<ConcreteTypeInfo>(prefix.size + (if (arm is TyTuple) arm.list.size else 1))
                            list.addAll(prefix)
                            if (arm is TyTuple) {
                                arm.list.forEach { list.add(ConcreteTypeInfo(last, it)) }
                            } else {
                                list.add(ConcreteTypeInfo(last, arm))
                            }
                            alts.add(list)
                        }
                        return alts
                    }
                    if (ty is TyTuple) {
                        ty.list.forEach { prefix.add(ConcreteTypeInfo(last, it)) }
                    } else {
                        prefix.add(ConcreteTypeInfo(last, ty))
                    }
                    return listOf(prefix)
                }

                private fun matchesSignature(
                    call: LuaCallExpr,
                    signature: IFunSignature,
                    concreteTypes: List<ConcreteTypeInfo>,
                    searchContext: SearchContext
                ): Boolean {
                    var nArgs = 0
                    var ok = true
                    signature.processArgs(call) { i, pi ->
                        nArgs = i + 1
                        val typeInfo = concreteTypes.getOrNull(i)
                        if (typeInfo == null || !typeInfo.ty.subTypeOf(pi.ty, searchContext, false)) {
                            ok = false
                            return@processArgs false
                        }
                        true
                    }
                    return ok && (nArgs >= concreteTypes.size || signature.hasVarargs())
                }

                private fun annotateCall(
                    call: LuaCallExpr,
                    signature: IFunSignature,
                    searchContext: SearchContext,
                    concreteTypes: List<ConcreteTypeInfo>
                ) {
                    var nArgs = 0
                    signature.processArgs(call) { i, pi ->
                        nArgs = i + 1
                        val typeInfo = concreteTypes.getOrNull(i)
                        if (typeInfo == null) {
                            myHolder.registerProblem(call.lastChild.lastChild, "Missing argument: ${pi.name}: ${pi.ty}")
                            return@processArgs true
                        }

                        val type = typeInfo.ty
                        if (!type.subTypeOf(pi.ty, searchContext, false))
                            myHolder.registerProblem(typeInfo.param, "Type mismatch. Required: '${pi.ty}' Found: '$type'")
                        true
                    }
                    if (nArgs < concreteTypes.size && !signature.hasVarargs()) {
                        val reported = HashSet<LuaExpr>()
                        for (i in nArgs until concreteTypes.size) {
                            val expr = concreteTypes[i].param
                            if (reported.add(expr)) {
                                myHolder.registerProblem(expr, "Too many arguments.")
                            }
                        }
                    }
                }
            }
}