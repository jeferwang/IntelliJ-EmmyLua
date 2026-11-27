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

package com.tang.intellij.lua.editor.completion

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.tang.intellij.lua.lang.LuaIcons
import com.tang.intellij.lua.psi.LuaClassField
import com.tang.intellij.lua.psi.LuaClassMember
import com.tang.intellij.lua.psi.LuaNameExpr
import com.tang.intellij.lua.psi.LuaTableExpr
import com.tang.intellij.lua.psi.LuaTableField
import com.tang.intellij.lua.psi.LuaTypes
import com.tang.intellij.lua.psi.shouldBe
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.ty.ITy
import com.tang.intellij.lua.ty.isAnonymous
import com.tang.intellij.lua.ty.isGlobal

class TableCompletionProvider : ClassMemberCompletionProvider() {

    companion object {
        private val metaMethodNames = mapOf(
                "__add" to "a + b",
                "__sub" to "a - b",
                "__mul" to "a * b",
                "__div" to "a / b",
                "__mod" to "a % b",
                "__pow" to "a ^ b",
                "__unm" to "-a",
                "__concat" to "a .. b",
                "__len" to "#a",
                "__eq" to "a == a",
                "__lt" to "a < b",
                "__le" to "a <= b",
                "__index" to "Meta method",
                "__newindex" to "Meta method",
                "__call" to "Meta method",
                "__tostring" to "Meta method",
                "__metatable" to "Meta method"
        )

        private val luaKeywords = setOf(
                "and", "break", "do", "else", "elseif", "end", "false", "for", "function",
                "goto", "if", "in", "local", "nil", "not", "or", "repeat", "return",
                "then", "true", "until", "while"
        )

        internal fun isValidLuaBareKey(name: String): Boolean {
            if (name.isEmpty() || name in luaKeywords) return false
            val first = name[0]
            if (first !in 'a'..'z' && first !in 'A'..'Z' && first != '_') return false
            for (i in 1 until name.length) {
                val ch = name[i]
                if (ch !in 'a'..'z' && ch !in 'A'..'Z' && ch !in '0'..'9' && ch != '_') return false
            }
            return true
        }
    }
    override fun addCompletions(session: CompletionSession) {
        val completionParameters = session.parameters
        val completionResultSet = session.resultSet
        val position = completionParameters.position
        val tableField = PsiTreeUtil.getParentOfType(position, LuaTableField::class.java)
        if (position.parent is LuaNameExpr && (tableField == null || tableField.id != null || tableField.lbrack != null)) {
            return
        }
        val hasAssign = tableField?.node?.findChildByType(LuaTypes.ASSIGN) != null

        metaMethodNames.forEach {
            val b = LookupElementBuilder.create(it.key)
                    .withTypeText(it.value)
                    .withIcon(LuaIcons.META_METHOD)
            completionResultSet.addElement(b)
        }

        val table = PsiTreeUtil.getParentOfType(position, LuaTableExpr::class.java)
        if (table != null) {
            val existingKeys = HashSet<String>()
            for (field in table.tableFieldList) {
                if (field != tableField) {
                    field.name?.let { existingKeys.add(it) }
                }
            }
            val addedKeys = HashSet<String>()
            val project = table.project
            val context = SearchContext.get(project)
            val prefixMatcher = completionResultSet.prefixMatcher
            val ty = table.shouldBe(context)
            ty.eachTopClass(Processor { luaType ->
                if (!luaType.isAnonymous && !luaType.isGlobal) {
                    luaType.lazyInit(context)
                    luaType.processMembers(context) { curType, member ->
                        member.name?.let { name ->
                            if (member is LuaClassField &&
                                name !in existingKeys &&
                                isValidLuaBareKey(name) &&
                                prefixMatcher.prefixMatches(name) &&
                                addedKeys.add(name)
                            ) {
                                val className = curType.displayName
                                addField(completionResultSet, curType === luaType, className, member, null, object : HandlerProcessor() {
                                    override fun process(element: LuaLookupElement, member: LuaClassMember, memberTy: ITy?): LookupElement {
                                        if (!hasAssign) {
                                            element.itemText = element.itemText + " = "
                                            element.lookupString = element.lookupString + " = "
                                        }
                                        return PrioritizedLookupElement.withPriority(element, 10.0)
                                    }
                                })
                            }
                        }
                    }
                }
                true
            })
        }
    }
}