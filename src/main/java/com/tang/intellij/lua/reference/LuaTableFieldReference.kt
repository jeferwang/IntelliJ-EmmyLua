package com.tang.intellij.lua.reference

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.tang.intellij.lua.psi.LuaTableExpr
import com.tang.intellij.lua.psi.LuaTableField
import com.tang.intellij.lua.psi.shouldBe
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.isAnonymous
import com.tang.intellij.lua.ty.isGlobal

internal class LuaTableFieldReference(field: LuaTableField, id: PsiElement) :
    PsiReferenceBase<LuaTableField>(field, TextRange.from(id.startOffsetInParent, id.textLength), true) {

    override fun resolve(): PsiElement? {
        val table = element.parent as? LuaTableExpr ?: return null
        val name = element.name ?: return null
        val context = SearchContext.get(element.project)
        if (context.isDumb) return null

        var target: PsiElement? = null
        table.shouldBe(context).eachTopClass { type ->
            // An unannotated receiver can infer its type from this initializer itself.
            if (!type.isAnonymous && !type.isGlobal) {
                target = type.findMember(name, context)?.takeUnless { it == element }
            }
            target == null
        }
        return target
    }

    override fun handleElementRename(newElementName: String): PsiElement = element.setName(newElementName)
}
