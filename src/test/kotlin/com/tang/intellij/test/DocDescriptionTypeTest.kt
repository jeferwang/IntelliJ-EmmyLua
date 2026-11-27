package com.tang.intellij.test

import com.intellij.psi.PsiNamedElement
import com.tang.intellij.lua.highlighting.LuaHighlightingData

class DocDescriptionTypeTest : LuaTestBase() {
    fun testDescriptionLinksAndHighlighting() {
        val declarations = myFixture.addFileToProject("Enums.lua", """
            ---@class LoadoutSlotName
            local slots = {}
            ---@alias CategoryId number
        """.trimIndent())
        val file = myFixture.configureByText("Model.lua", """
            ---@class Loadout
            ---@field category number|nil @CategoryId；允许为空
            local loadout = {}

            ---@param slot number @LoadoutSlotName，预购用
            ---@param altSlot number @LoadoutSlotName预购用
            ---@param count number @剩余数量
            ---@return number removed @卸下前的数量
            function loadout.remove(slot, altSlot, count) end
        """.trimIndent())
        val highlights = myFixture.doHighlighting().filter {
            it.forcedTextAttributesKey == LuaHighlightingData.CLASS_REFERENCE
        }
        for ((marker, name) in listOf(
            "@CategoryId；" to "CategoryId",
            "@LoadoutSlotName，" to "LoadoutSlotName",
            "@LoadoutSlotName预购用" to "LoadoutSlotName"
        )) {
            val start = file.text.indexOf(marker) + 1
            val reference = file.findReferenceAt(start)!!
            val target = reference.resolve() as PsiNamedElement
            assertEquals(name, target.name)
            assertEquals(declarations, target.containingFile)
            assertEquals(name, reference.canonicalText)
            assertTrue(reference.isReferenceTo(target))
            assertFalse(reference.isReferenceTo(file))
            assertTrue(highlights.any { it.startOffset == start && it.endOffset == start + name.length })
        }
        val prose = file.text.indexOf("剩余数量")
        assertNull(file.findReferenceAt(prose))
        assertFalse(highlights.any { prose in it.startOffset until it.endOffset })
    }

    fun testRenameUpdatesDescriptionTypeReference() {
        val declarations = myFixture.addFileToProject("Enums.lua", """
            ---@alias CategoryId number
        """.trimIndent())
        val file = myFixture.configureByText("Model.lua", """
            ---@class Loadout
            ---@field category number|nil @CategoryId；允许为空
            local loadout = {}
        """.trimIndent())
        val alias = declarations.findElementAt(declarations.text.indexOf("CategoryId"))!!.parent
        myFixture.renameElement(alias, "ItemCategoryId")
        assertTrue(file.text.contains("@ItemCategoryId；允许为空"))
    }
}
