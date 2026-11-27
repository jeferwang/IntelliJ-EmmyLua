package com.tang.intellij.test

import com.tang.intellij.lua.comment.psi.LuaDocTagField

class TableFieldNavigationTest : LuaTestBase() {
    private fun checkSlotFields(source: String) {
        val declarations = myFixture.addFileToProject("Slots.lua", """
            ---@class LoadoutSnapshotSlot
            ---@field itemId number
            LoadoutSnapshotSlot = {}
        """.trimIndent())
        val file = myFixture.configureByText("Usage.lua", source.trimIndent())
        val offsets = Regex("\\bitemId\\b").findAll(file.text).map { it.range.first }.toList()
        assertTrue(offsets.isNotEmpty())
        for (offset in offsets) {
            val target = file.findReferenceAt(offset)?.resolve() as? LuaDocTagField
            assertEquals("field at offset $offset", declarations, target?.containingFile)
            assertEquals(declarations.text.indexOf("itemId"), target?.textOffset)
        }
    }

    fun testStatementReturnAnnotationsAndCaller() = checkSlotFields("""
        local function make()
            ---@type LoadoutSnapshotSlot
            return { itemId = 1 }
        end
        local r = make()
        local id = r.itemId
        ---@type LoadoutSnapshotSlot
        return { itemId = 1 }
    """)

    fun testDeclaredReturnSlotsAndSelf() = checkSlotFields("""
        ---@return LoadoutSnapshotSlot
        local function make() return { itemId = 1 } end
        ---@return boolean, LoadoutSnapshotSlot
        local function pair() return true, { itemId = 1 } end
        local ok, slot = pair()
        local id = slot.itemId
        ---@return self
        function LoadoutSnapshotSlot:copy() return { itemId = 1 } end
    """)

    fun testContextualClosureReturns() = checkSlotFields("""
        ---@type fun(): LoadoutSnapshotSlot
        local make = function() return { itemId = 1 } end
        ---@param cb fun(): LoadoutSnapshotSlot
        local function consume(cb) end
        consume(function() return { itemId = 1 } end)
    """)

    fun testArrayAndNestedContainerFields() = checkSlotFields("""
        ---@type LoadoutSnapshotSlot[]
        local list = { { itemId = 1 }, { itemId = 2 } }
        ---@return LoadoutSnapshotSlot[]
        local function make() return { { itemId = 3 } } end
        ---@class Container
        ---@field slots LoadoutSnapshotSlot[]
        local Container = {}
        ---@type Container
        local box = { slots = { { itemId = 4 } } }
    """)

    fun testMapFieldsAndAssignments() = checkSlotFields("""
        local k = "dynamic"
        ---@type table<string, LoadoutSnapshotSlot>
        local map = { primary = { itemId = 1 }, ["secondary"] = { itemId = 2 }, [k] = { itemId = 3 } }
        map.primary = { itemId = 4 }
        ---@type table<any, LoadoutSnapshotSlot>
        local anyKey = {}
        anyKey.primary = { itemId = 5 }
        ---@type table<"a"|"b", LoadoutSnapshotSlot>
        local limited = {}
        local id = limited.a.itemId
    """)

    fun testFieldAnnotationsAndDocTableFields() = checkSlotFields("""
        local arr = { ---@type LoadoutSnapshotSlot
            { itemId = 1 } }
        local cfg = { ---@type LoadoutSnapshotSlot
            slot = { itemId = 2 } }
        local indexed = { ---@type LoadoutSnapshotSlot
            [1] = { itemId = 3 } }
        ---@type { inner: LoadoutSnapshotSlot }
        local box = { inner = { itemId = 4 } }
    """)

    fun testAliasedFunctionAndArrayElements() = checkSlotFields("""
        ---@alias SlotAlias LoadoutSnapshotSlot
        ---@alias SlotList SlotAlias[]
        ---@alias SlotFn fun(a: SlotAlias, b: SlotAlias): SlotAlias
        ---@type SlotFn
        local fn
        local result = fn({ itemId = 1 }, { itemId = 2 })
        local id = result.itemId
        ---@type SlotList
        local list = {}
        list[1] = { itemId = 3 }
    """)

    fun testUnannotatedClosureReturnKeyRemainsADeclaration() {
        val file = myFixture.configureByText("Closure.lua", """
            local f = function() return { itemId = 1 } end
            local r = f()
            local id = r.itemId
        """.trimIndent())
        val key = file.text.indexOf("itemId")
        assertNull(file.findReferenceAt(key)?.resolve())
        assertEquals(key, file.findReferenceAt(file.text.lastIndexOf("itemId"))?.resolve()?.textOffset)
    }

    fun testConstructorKeysResolveToFieldsOfTheAnnotatedClass() {
        val declarations = myFixture.addFileToProject("Slots.lua", """
            ---@class LoadoutSnapshotSlot
            ---@field itemId number
            local snapshot = {}

            ---@class BagSlot
            ---@field itemId string
            local bag = {}
        """.trimIndent())
        val file = myFixture.configureByText("Model.lua", """
            ---@return LoadoutSnapshotSlot
            local function createSlot() end

            ---@param slot BagSlot
            local function snapshot(slot)
                ---@type LoadoutSnapshotSlot
                local item = { itemId = slot.itemId }
                ---@type LoadoutSnapshotSlot[]
                local list = {}
                list[#list + 1] = { itemId = slot.itemId }
                ---@type BagSlot
                local bag = {}
                bag, item = { itemId = slot.itemId }, { itemId = slot.itemId }
                local first, second = createSlot(), { itemId = 1 }
                item = { missingField = 1 }
            end
        """.trimIndent())

        val constructors = mapOf(
            "local item = { " to "itemId number",
            "list[#list + 1] = { " to "itemId number",
            "bag, item = { " to "itemId string",
            "bag, item = { itemId = slot.itemId }, { " to "itemId number"
        )
        for ((prefix, declaration) in constructors) {
            val keyOffset = file.text.indexOf(prefix) + prefix.length
            val keyTarget = file.findReferenceAt(keyOffset)?.resolve() as? LuaDocTagField
            assertEquals(declarations, keyTarget?.containingFile)
            assertEquals(declarations.text.indexOf(declaration), keyTarget?.textOffset)

            val valueOffset = file.text.indexOf("slot.itemId", keyOffset) + "slot.".length
            val valueTarget = file.findReferenceAt(valueOffset)?.resolve() as? LuaDocTagField
            assertEquals(declarations, valueTarget?.containingFile)
            assertEquals(declarations.text.indexOf("itemId string"), valueTarget?.textOffset)
        }
        val untypedPrefix = "local first, second = createSlot(), { "
        val untypedKeyOffset = file.text.indexOf(untypedPrefix) + untypedPrefix.length
        assertNull(file.findReferenceAt(untypedKeyOffset)?.resolve())
        assertNull(file.findReferenceAt(file.text.indexOf("missingField"))?.resolve())
    }

    fun testUntypedConstructorKeyRemainsADeclaration() {
        val file = myFixture.configureByText("Untyped.lua", """
            local item = { itemId = 1 }
            local id = item.itemId
        """.trimIndent())
        val keyOffset = file.text.indexOf("itemId")
        assertNull(file.findReferenceAt(keyOffset)?.resolve())
        val readTarget = file.findReferenceAt(file.text.lastIndexOf("itemId"))?.resolve()
        assertEquals(keyOffset, readTarget?.textOffset)
    }

    fun testRenameAnnotatedFieldUpdatesTableConstructorKeys() {
        val declarations = myFixture.addFileToProject("Slots.lua", """
            ---@class LoadoutSnapshotSlot
            ---@field itemId number
            local snapshot = {}
        """.trimIndent())
        val file = myFixture.configureByText("Model.lua", """
            ---@type LoadoutSnapshotSlot
            local item = { itemId = 1 }
            local id = item.itemId
        """.trimIndent())
        val field = declarations.findElementAt(declarations.text.indexOf("itemId"))!!.parent
        val keyRef = file.findReferenceAt(file.text.indexOf("itemId"))!!
        assertTrue(keyRef.isReferenceTo(field))
        assertFalse(keyRef.isReferenceTo(file))
        myFixture.renameElement(field, "slotItemId")
        assertTrue(file.text.contains("local item = { slotItemId = 1 }"))
        assertTrue(file.text.contains("local id = item.slotItemId"))
    }
}
