package com.tang.intellij.lua.comment.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.impl.source.tree.LeafElement
import com.tang.intellij.lua.psi.LuaTypeDef
import com.tang.intellij.lua.psi.search.LuaShortNamesManager
import com.tang.intellij.lua.search.SearchContext

abstract class LuaDocCommentStringMixin(node: ASTNode) : ASTWrapperPsiElement(node), LuaDocCommentString {
    override fun getReference(): PsiReference? {
        if (parent !is LuaDocTagParam && parent !is LuaDocTagField) return null
        if (firstChild?.node?.elementType != LuaDocTypes.STRING_BEGIN) return null
        val description = string ?: return null
        if (description.startOffsetInParent != 1) return null
        val name = typeName.find(description.text)?.value ?: return null
        return DescriptionTypeReference(this, name)
    }

    private companion object {
        val typeName = Regex("""^[A-Za-z_][A-Za-z0-9_]*+(?:\.[A-Za-z_][A-Za-z0-9_]*+)*+""")
    }
}

private class DescriptionTypeReference(element: LuaDocCommentString, private val name: String) :
    PsiReferenceBase<LuaDocCommentString>(element, TextRange.from(1, name.length), true) {
    override fun resolve(): PsiElement? {
        val project = element.project
        if (DumbService.isDumb(project)) return null
        return LuaShortNamesManager.getInstance(project).findTypeDef(name, SearchContext.get(project))
    }

    override fun isReferenceTo(element: PsiElement): Boolean {
        return element is LuaTypeDef && element.name == name && super.isReferenceTo(element)
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        val description = element.string?.node as? LeafElement ?: return element
        if (!description.text.startsWith(name)) return element
        description.replaceWithText(newElementName + description.text.substring(name.length))
        return element
    }
}
