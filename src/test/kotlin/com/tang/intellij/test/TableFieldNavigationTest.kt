package com.tang.intellij.test

import com.intellij.psi.PsiPolyVariantReference
import com.tang.intellij.lua.comment.psi.LuaDocTagField
import com.tang.intellij.lua.editor.completion.TableCompletionProvider
import com.tang.intellij.lua.lang.LuaLanguageLevel
import com.tang.intellij.lua.lang.type.LuaString
import com.tang.intellij.lua.project.LuaSettings

class TableFieldNavigationTest : LuaTestBase() {
    fun testStaticKeyDecodingAndBareKeyLevels() {
        fun decode(content: String) = LuaString.decodeOrNull("\"$content\"")
        assertEquals("ÿ", decode("\\xC3\\191"))
        assertEquals("a", decode("\\97"))
        assertEquals("itemId", decode("\\105temId"))
        for (invalid in listOf("\\xFF", "\\256", "\\xF", "\\u{}", "\\u{D800}", "\\u{110000}", "\\")) {
            assertNull(invalid, decode(invalid))
        }
        assertTrue(TableCompletionProvider.isValidLuaBareKey("配置2", LuaLanguageLevel.LUA54))
        assertTrue(TableCompletionProvider.isValidLuaBareKey("goto", LuaLanguageLevel.LUA51))
        assertFalse(TableCompletionProvider.isValidLuaBareKey("goto", LuaLanguageLevel.LUA52))
    }

    fun testEscapedConstructorAndReadRenameTogether() {
        val declarations = myFixture.addFileToProject("KeyType.lua", """
            ---@class KeyType
            ---@field a number
            local KeyType = {}
        """.trimIndent())
        val usage = myFixture.configureByText("KeyUsage.lua", """
            ---@type KeyType
            local obj = { ["\97"] = 1 }
            local id = obj['\x61']
            local message = "\97"
        """.trimIndent())
        val field = declarations.findElementAt(declarations.text.indexOf("a number"))!!.parent
        assertTrue(usage.findReferenceAt(usage.text.indexOf("\\x61"))!!.isReferenceTo(field))
        myFixture.renameElement(field, "renamed")
        assertTrue(usage.text.contains("[\"renamed\"] = 1"))
        assertTrue(usage.text.contains("obj['renamed']"))
        assertTrue(usage.text.contains("message = \"\\97\""))
    }

    private fun checkSlotFields(source: String) {
        val declarations = myFixture.addFileToProject("Slots.lua", """
            ---@class LoadoutSnapshotSlot
            ---@field itemId number
            ---@field count number
            LoadoutSnapshotSlot = {}
        """.trimIndent())
        val file = myFixture.configureByText("Usage.lua", source.trimIndent())
        val fields = Regex("\\b(itemId|count)\\b").findAll(file.text).toList()
        assertTrue(fields.isNotEmpty())
        for (field in fields) {
            val offset = field.range.first
            val target = file.findReferenceAt(offset)?.resolve() as? LuaDocTagField
            assertEquals("field at offset $offset", declarations, target?.containingFile)
            assertEquals(declarations.text.indexOf(field.value), target?.textOffset)
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

    fun testWrapperAndReassignmentFields() = checkSlotFields("""
        ---@type LoadoutSnapshotSlot
        local wrapped = (({ itemId = 1 }))
        ---@type LoadoutSnapshotSlot
        local choice = cond and { itemId = 2 } or { itemId = 3 }
        ---@param existing LoadoutSnapshotSlot
        local function fallback(existing) return existing or { itemId = 4 } end
        local item
        ---@type LoadoutSnapshotSlot
        item = { itemId = 5 }
        local function update(self)
            ---@type LoadoutSnapshotSlot
            self.slot = { itemId = 6 }
        end
        ---@type LoadoutSnapshotSlot
        local second
        ---@type number
        item, second = 0, { itemId = 7 }
    """)

    fun testParenthesizedClassInitializerKeepsItsFields() {
        val file = myFixture.configureByText("Class.lua", """
            ---@class Wrapped
            local C = (({ itemId = 1 }))
            ---@type Wrapped
            local other
            local id = other.itemId
        """.trimIndent())
        assertEquals(file.text.indexOf("itemId"), file.findReferenceAt(file.text.lastIndexOf("itemId"))?.resolve()?.textOffset)
    }

    fun testInlineExpressionAnnotations() = checkSlotFields("""
        consume(
            ---@type LoadoutSnapshotSlot
            { itemId = 1 })
        consume(---@type LoadoutSnapshotSlot
            { itemId = 2 })
        local function make()
            return true,
                ---@type LoadoutSnapshotSlot
                { itemId = 3 }
        end
        local ok, returned = make()
        local id = returned.itemId
        local first, item = true,
            ---@type LoadoutSnapshotSlot
            ({ itemId = 4 })
        id = item.itemId
        local choice = cond and
            ---@type LoadoutSnapshotSlot
            { itemId = 5 }
            or
            ---@type LoadoutSnapshotSlot
            { itemId = 6 }
        local single =
            ---@type LoadoutSnapshotSlot
            { itemId = 7 }
        id = single.itemId
        local function firstSlot()
            return
                ---@type LoadoutSnapshotSlot
                { itemId = 8 }
        end
        id = firstSlot().itemId
    """)

    fun testUntypedBranchesConditionsAndDetachedComments() {
        val file = myFixture.configureByText("Negative.lua", """
            ---@class Slot
            ---@field itemId number
            local Slot = {}
            ---@type Slot
            local typed = { itemId = 0 } and {}
            local untyped = cond and { itemId = 1 } or { itemId = 2 }
            consume(bar, ---@type Slot
                { itemId = 3 })
            consume(
                ---@type Slot

                { itemId = 4 })
            consume(bar ---@type Slot
                , { itemId = 5 })
            ---@type table<"only", Slot>
            local map = {}
            local missing = map["other"].itemId
        """.trimIndent())
        for (value in 0..5) {
            val offset = file.text.indexOf("itemId = $value")
            assertTrue(offset >= 0)
            assertNull(file.findReferenceAt(offset)?.resolve())
        }
        assertNull(file.findReferenceAt(file.text.lastIndexOf("itemId"))?.resolve())
    }

    fun testInsertArgumentFields() = checkSlotFields("""
        ---@type LoadoutSnapshotSlot[]
        local list = {}
        table.insert(list, { itemId = 1 })
        table.insert(list, 1, { itemId = 2 })
        local insert = table.insert
        insert(list, { itemId = 3 })
        ---@type table<number, LoadoutSnapshotSlot>
        local map = {}
        table.insert(map, { itemId = 4 })
    """)

    fun testMetatableArgumentAndResultFields() = checkSlotFields("""
        ---@return LoadoutSnapshotSlot
        local function make(mt) return setmetatable({ itemId = 1 }, mt) end
        ---@type LoadoutSnapshotSlot
        local typed = setmetatable({ itemId = 2 }, {})
        LoadoutSnapshotSlot.__index = LoadoutSnapshotSlot
        local obj = setmetatable({ itemId = 3 }, LoadoutSnapshotSlot)
        local n = obj.count
        local set = setmetatable
        local other = set({ itemId = 4 }, { __index = LoadoutSnapshotSlot })
        n = other.count
    """)

    fun testUnverifiedMetatableAndShadowedBuiltins() {
        val file = myFixture.configureByText("Shadowed.lua", """
            ---@class Meta
            ---@field key number
            local Meta = {}
            local obj = setmetatable({ key = 1 }, Meta)
            local function fake(...) end
            local setmetatable = fake
            local alias = setmetatable
            local other = alias({ key = 2 }, { __index = Meta })
            local table = { insert = fake }
            ---@type Meta[]
            local list = {}
            table.insert(list, { key = 3 })
        """.trimIndent())
        for (i in 1..3) {
            val offset = file.text.indexOf("key = $i")
            assertTrue(offset >= 0)
            assertNull(file.findReferenceAt(offset)?.resolve())
        }
    }

    fun testVarargsAndMethodReceivers() = checkSlotFields("""
        ---@vararg LoadoutSnapshotSlot
        local function take(...) end
        take({ itemId = 1 }, { itemId = 2 })
        ---@param other self
        function LoadoutSnapshotSlot:merge(other) end
        ---@type LoadoutSnapshotSlot
        local slot
        slot:merge({ itemId = 3 })
        function LoadoutSnapshotSlot:copy() end
        LoadoutSnapshotSlot.copy({ itemId = 4 })
    """)

    fun testOverloadAritySelectsTheFieldOwner() {
        val file = myFixture.configureByText("Overloads.lua", """
            ---@class A
            ---@field key number
            local A = {}
            ---@class B
            ---@field key string
            local B = {}
            ---@overload fun(x: A)
            ---@param x B
            ---@param n number
            local function take(x, n) end
            take({ key = 1 })
            take({ key = "two" }, 2)
        """.trimIndent())
        for ((usage, declaration) in listOf("key = 1" to "key number", "key = \"two\"" to "key string")) {
            val target = file.findReferenceAt(file.text.indexOf(usage))?.resolve()
            assertEquals(file.text.indexOf(declaration), target?.textOffset)
        }
    }

    fun testGenericArgumentUsesSiblingEvidence() {
        val settings = LuaSettings.instance
        val previous = settings.enableGeneric
        settings.enableGeneric = true
        try {
            checkSlotFields("""
                ---@generic T
                ---@param list T[]
                ---@param value T
                local function push(list, value) end
                ---@type LoadoutSnapshotSlot[]
                local slots = {}
                push(slots, { itemId = 1 })
                ---@generic T
                ---@vararg T
                local function take(...) end
                take({ itemId = 2 }, slots[1])
            """)
        } finally {
            settings.enableGeneric = previous
        }
    }

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

    fun testBracketStringKeysNavigateAndRenameSafely() {
        val declarations = myFixture.addFileToProject("Slots.lua", """
            ---@class LoadoutSnapshotSlot
            ---@field itemId number
            ---@field count number
            local snapshot = {}
        """.trimIndent())
        val file = myFixture.configureByText("BracketKeys.lua", """
            local dynKey = "itemId"
            ---@type LoadoutSnapshotSlot
            local item = {
                ["itemId"] = 1,
                ['count'] = 2,
                [dynKey] = 3,
            }
        """.trimIndent())
        val field = declarations.findElementAt(declarations.text.indexOf("itemId"))!!.parent
        val countField = declarations.findElementAt(declarations.text.indexOf("count"))!!.parent
        val itemRef = file.findReferenceAt(file.text.indexOf("[\"itemId\"]") + 2)!!
        assertEquals(field, itemRef.resolve())
        assertTrue(itemRef.isReferenceTo(field))
        assertEquals(countField, file.findReferenceAt(file.text.indexOf("'count'") + 1)?.resolve())
        val dynTarget = file.findReferenceAt(file.text.indexOf("[dynKey]") + 1)?.resolve()
        assertEquals(file.text.indexOf("dynKey"), dynTarget?.textOffset)

        myFixture.renameElement(field, "slotItemId")
        assertTrue(file.text.contains("[\"slotItemId\"] = 1"))
    }

    fun testAmbiguousUnionNavigatesToBothAndAvoidsUnsafeRename() {
        val declarations = myFixture.addFileToProject("Unions.lua", """
            ---@class BaseSlot
            ---@field sharedId number
            local BaseSlot = {}

            ---@class SubSlotA : BaseSlot
            ---@field itemId number
            ---@field onlyA string
            local SubSlotA = {}

            ---@class SubSlotB : BaseSlot
            ---@field itemId string
            local SubSlotB = {}
        """.trimIndent())
        val file = myFixture.configureByText("UnionUsage.lua", """
            ---@type SubSlotA|SubSlotB
            local entry = {
                itemId = 1,
                sharedId = 2,
                onlyA = "ok",
            }
        """.trimIndent())
        val itemA = declarations.findElementAt(declarations.text.indexOf("itemId number"))!!.parent
        val itemB = declarations.findElementAt(declarations.text.indexOf("itemId string"))!!.parent
        val shared = declarations.findElementAt(declarations.text.indexOf("sharedId"))!!.parent
        val onlyA = declarations.findElementAt(declarations.text.indexOf("onlyA"))!!.parent

        val ambigRef = file.findReferenceAt(file.text.indexOf("itemId = 1")) as PsiPolyVariantReference
        val results = ambigRef.multiResolve(false).mapNotNull { it.element }
        assertEquals(2, results.size)
        assertTrue(itemA in results && itemB in results)
        assertNull(ambigRef.resolve())
        assertFalse(ambigRef.isReferenceTo(itemA))
        assertFalse(ambigRef.isReferenceTo(itemB))

        val sharedRef = file.findReferenceAt(file.text.indexOf("sharedId = 2"))!!
        assertEquals(shared, sharedRef.resolve())
        assertTrue(sharedRef.isReferenceTo(shared))

        assertEquals(onlyA, file.findReferenceAt(file.text.indexOf("onlyA = \"ok\""))?.resolve())
    }

    fun testTableCallbackClosuresAndTupleAdjustment() {
        val declarations = myFixture.addFileToProject("Callbacks.lua", """
            ---@class LoadoutSnapshotSlot
            ---@field itemId number
            local LoadoutSnapshotSlot = {}

            ---@class Handler
            ---@field tag string
            ---@field clone fun(): self
            ---@field onTick fun(self: self, slot: LoadoutSnapshotSlot)
            Handler = {}

            ---@param slot LoadoutSnapshotSlot
            ---@return LoadoutSnapshotSlot
            function Handler:handle(slot) end
        """.trimIndent())
        val file = myFixture.configureByText("CallbackUsage.lua", """
            ---@type Handler
            local h = {
                clone = function()
                    return { tag = "x" }
                end,
                onTick = function(self, slot)
                    local t = self.tag
                    local id = slot.itemId
                end,
                handle = function(self, slot)
                    local t = self.tag
                    local id = slot.itemId
                    return { itemId = id }
                end,
            }

            ---@param cb fun(vararg LoadoutSnapshotSlot)
            local function forEach(cb) end
            forEach(function(a, b)
                local x = a.itemId
                local y = b.itemId
            end)

            ---@return number, LoadoutSnapshotSlot
            local function pair() end
            local function wrapped() return true, pair() end
            local ok, n, slot = wrapped()
            local fromWrapped = slot.itemId

            local first, extra = { itemId = 1 }
            extra = { itemId = 2 }
        """.trimIndent())
        val itemIdDecl = declarations.findElementAt(declarations.text.indexOf("itemId number"))!!.parent
        val tagDecl = declarations.findElementAt(declarations.text.indexOf("tag string"))!!.parent

        assertEquals(tagDecl, file.findReferenceAt(file.text.indexOf("tag = \"x\""))?.resolve())
        assertEquals(tagDecl, file.findReferenceAt(file.text.indexOf("self.tag") + 5)?.resolve())
        assertEquals(tagDecl, file.findReferenceAt(file.text.lastIndexOf("self.tag") + 5)?.resolve())
        assertEquals(itemIdDecl, file.findReferenceAt(file.text.indexOf("slot.itemId") + 5)?.resolve())
        assertEquals(itemIdDecl, file.findReferenceAt(file.text.indexOf("return { itemId = id }") + "return { ".length)?.resolve())
        assertEquals(itemIdDecl, file.findReferenceAt(file.text.indexOf("a.itemId") + 2)?.resolve())
        assertEquals(itemIdDecl, file.findReferenceAt(file.text.indexOf("b.itemId") + 2)?.resolve())
        assertEquals(itemIdDecl, file.findReferenceAt(file.text.indexOf("fromWrapped = slot.itemId") + "fromWrapped = slot.".length)?.resolve())
        assertNull(file.findReferenceAt(file.text.indexOf("itemId = 2"))?.resolve())
    }

    fun testReassignedAliasesEscapedKeysTuplesAndOverloadReturns() {
        val declarations = myFixture.addFileToProject("ReviewDecls.lua", """
            ---@class LoadoutSnapshotSlot
            ---@field itemId number
            local LoadoutSnapshotSlot = {}

            ---@class BagSlot
            ---@field bagId string
            local BagSlot = {}

            ---@class ResultA
            ---@field onlyA number
            local ResultA = {}

            ---@class ResultB
            ---@field onlyB string
            local ResultB = {}

            ---@class Handler
            ---@field tag string
            Handler = {}

            ---@param slot LoadoutSnapshotSlot
            ---@return LoadoutSnapshotSlot
            function Handler:handle(slot) end
        """.trimIndent())
        val file = myFixture.configureByText("ReviewUsage.lua", """
            ---@type LoadoutSnapshotSlot[]
            local slots = {}

            local put = table.insert
            put = function(a, b) end
            put(slots, { itemId = 1 })

            ---@type LoadoutSnapshotSlot
            local escaped = {
                ["\105temId"] = 2,
                ['\x69temId'] = 3,
                ["\u{69}temId"] = 4,
            }

            ---@return number, LoadoutSnapshotSlot
            local function pairA() end
            ---@return string, BagSlot
            local function pairB() end
            local function eitherPair(flag)
                if flag then return pairA() else return pairB() end
            end
            local function wrappedEither(flag) return true, eitherPair(flag) end
            local ok, first, third = wrappedEither(true)
            local fromA = third.itemId
            local fromB = third.bagId

            local function logicalRet(flag) return flag and pairA() end
            local l1, l2 = logicalRet(true)
            l2 = { itemId = 5 }

            local function annotatedMulti()
                ---@type BagSlot
                return pairA()
            end
            local am1, am2 = annotatedMulti()
            local x1 = am1.bagId
            local x2 = am2.itemId

            ---@type Handler
            local h = {
                handle = (function(self, slot)
                    return self.tag
                end)
            }

            ---@overload fun(b: BagSlot, n: number): ResultB
            ---@param a LoadoutSnapshotSlot
            ---@return ResultA
            local function pick(a) end
            local r1 = pick({ itemId = 6 })
            local a1 = r1.onlyA
            local b1 = r1.onlyB
            local r2 = pick({ bagId = "x" }, 2)
            local b2 = r2.onlyB
            local a2 = r2.onlyA
        """.trimIndent())
        val itemIdDecl = declarations.findElementAt(declarations.text.indexOf("itemId number"))!!.parent
        val bagIdDecl = declarations.findElementAt(declarations.text.indexOf("bagId string"))!!.parent
        val onlyADecl = declarations.findElementAt(declarations.text.indexOf("onlyA number"))!!.parent
        val onlyBDecl = declarations.findElementAt(declarations.text.indexOf("onlyB string"))!!.parent
        val tagDecl = declarations.findElementAt(declarations.text.indexOf("tag string"))!!.parent

        assertNull(file.findReferenceAt(file.text.indexOf("itemId = 1"))?.resolve())
        assertEquals(itemIdDecl, file.findReferenceAt(file.text.indexOf("\\105temId"))?.resolve())
        assertEquals(itemIdDecl, file.findReferenceAt(file.text.indexOf("\\x69temId"))?.resolve())
        assertEquals(itemIdDecl, file.findReferenceAt(file.text.indexOf("\\u{69}temId"))?.resolve())
        assertEquals(itemIdDecl, file.findReferenceAt(file.text.indexOf("third.itemId") + 6)?.resolve())
        assertEquals(bagIdDecl, file.findReferenceAt(file.text.indexOf("third.bagId") + 6)?.resolve())
        assertNull(file.findReferenceAt(file.text.indexOf("itemId = 5"))?.resolve())
        assertEquals(bagIdDecl, file.findReferenceAt(file.text.indexOf("am1.bagId") + 4)?.resolve())
        assertEquals(itemIdDecl, file.findReferenceAt(file.text.indexOf("am2.itemId") + 4)?.resolve())
        assertEquals(tagDecl, file.findReferenceAt(file.text.indexOf("self.tag") + 5)?.resolve())
        assertEquals(onlyADecl, file.findReferenceAt(file.text.indexOf("r1.onlyA") + 3)?.resolve())
        assertNull(file.findReferenceAt(file.text.indexOf("r1.onlyB") + 3)?.resolve())
        assertEquals(onlyBDecl, file.findReferenceAt(file.text.indexOf("r2.onlyB") + 3)?.resolve())
        assertNull(file.findReferenceAt(file.text.indexOf("r2.onlyA") + 3)?.resolve())
    }
}
