package com.tang.intellij.lua.reference

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.tang.intellij.lua.psi.LuaClassMember
import com.tang.intellij.lua.psi.LuaTableExpr
import com.tang.intellij.lua.psi.LuaTableField
import com.tang.intellij.lua.psi.shouldBe
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.isAnonymous
import com.tang.intellij.lua.ty.isGlobal

internal class LuaTableFieldReference(
    field: LuaTableField,
    private val fieldName: String,
    rangeInElement: TextRange
) : PsiPolyVariantReferenceBase<LuaTableField>(field, rangeInElement, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        if (fieldName.isEmpty()) return ResolveResult.EMPTY_ARRAY
        val table = element.parent as? LuaTableExpr ?: return ResolveResult.EMPTY_ARRAY
        val context = SearchContext.get(element.project)
        if (context.isDumb) return ResolveResult.EMPTY_ARRAY

        val targets = mutableListOf<PsiElement>()
        val manager = element.manager
        table.shouldBe(context).eachTopClass { type ->
            // An unannotated receiver can infer its type from this initializer itself.
            if (!type.isAnonymous && !type.isGlobal) {
                val member = type.findMember(fieldName, context)?.takeUnless { isSelf(it) }
                if (member != null && targets.none { manager.areElementsEquivalent(it, member) }) {
                    targets.add(member)
                }
            }
            true
        }
        if (targets.isEmpty()) return ResolveResult.EMPTY_ARRAY
        return targets.map(::PsiElementResolveResult).toTypedArray()
    }

    override fun isReferenceTo(element: PsiElement): Boolean {
        if (element !is LuaClassMember || element.name != fieldName) return false
        val resolved = resolve() ?: return false
        return myElement.manager.areElementsEquivalent(resolved, element)
    }

    override fun handleElementRename(newElementName: String): PsiElement = element.setName(newElementName)

    private fun isSelf(candidate: PsiElement): Boolean {
        if (element.manager.areElementsEquivalent(candidate, element)) return true
        val originalFile = element.containingFile.originalFile
        return candidate.containingFile == originalFile && candidate.textRange == element.textRange
    }
}
