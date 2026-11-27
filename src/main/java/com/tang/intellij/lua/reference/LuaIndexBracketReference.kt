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

package com.tang.intellij.lua.reference

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.tang.intellij.lua.psi.LuaClassMember
import com.tang.intellij.lua.psi.LuaDeclarationTree
import com.tang.intellij.lua.psi.LuaIndexExpr
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.isAnonymous
import com.tang.intellij.lua.ty.isGlobal

class LuaIndexBracketReference internal constructor(element: LuaIndexExpr, private val name: String, range: TextRange)
    : PsiPolyVariantReferenceBase<LuaIndexExpr>(element, range, true), LuaReference {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        targets(SearchContext.get(myElement.project)).map(::PsiElementResolveResult).toTypedArray()

    private fun targets(context: SearchContext): List<PsiElement> {
        if (context.isDumb) return emptyList()
        val manager = myElement.manager
        val namedTargets = mutableListOf<PsiElement>()
        val fallbackTargets = mutableListOf<PsiElement>()
        fun addTo(list: MutableList<PsiElement>, candidate: PsiElement?) {
            if (candidate == null || manager.areElementsEquivalent(candidate, myElement)) return
            if (candidate.containingFile == myElement.containingFile.originalFile && candidate.textRange == myElement.textRange) return
            if (list.none { manager.areElementsEquivalent(it, candidate) }) list.add(candidate)
        }
        myElement.guessParentType(context).eachTopClass { cls ->
            val targetList = if (!cls.isAnonymous && !cls.isGlobal) namedTargets else fallbackTargets
            addTo(targetList, cls.findMember(name, context))
            true
        }
        val targets = if (namedTargets.isNotEmpty()) namedTargets else fallbackTargets
        if (targets.isEmpty()) addTo(targets, LuaDeclarationTree.get(myElement.containingFile).find(myElement)?.psi)
        return targets
    }

    override fun resolve(context: SearchContext): PsiElement? = targets(context).singleOrNull()

    override fun isReferenceTo(element: PsiElement): Boolean =
        element is LuaClassMember && element.name == name && myElement.manager.areElementsEquivalent(resolve(), element)

    override fun handleElementRename(newElementName: String): PsiElement = myElement.setName(newElementName)
}
