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

package com.tang.intellij.lua.usages

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordOccurrence
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.tree.TokenSet
import com.intellij.util.Processor
import com.tang.intellij.lua.comment.psi.LuaDocTagClass
import com.tang.intellij.lua.comment.psi.LuaDocTagField
import com.tang.intellij.lua.lang.LuaParserDefinition
import com.tang.intellij.lua.lang.type.LuaString
import com.tang.intellij.lua.lexer.LuaLexerAdapter
import com.tang.intellij.lua.psi.*

internal const val ESCAPED_STRING_KEY_PREFIX = "\\k:"

internal class LuaWordsScanner : DefaultWordsScanner(
        LuaLexerAdapter(),
        TokenSet.create(),
        LuaParserDefinition.COMMENTS,
        LuaParserDefinition.STRINGS
) {
    override fun getVersion(): Int = super.getVersion() + 2

    override fun processWords(fileText: CharSequence, processor: Processor<in WordOccurrence>) {
        super.processWords(fileText, processor)
        if (fileText.indexOf('\\') < 0 || fileText.indexOf('[') < 0) return

        val lexer = LuaLexerAdapter()
        lexer.start(fileText)
        var afterBracket = false
        while (lexer.tokenType != null) {
            val token = lexer.tokenType
            if (afterBracket && token == LuaTypes.STRING) {
                val start = lexer.tokenStart
                val end = lexer.tokenEnd
                if (end - start >= 4 && (fileText[start] == '"' || fileText[start] == '\'')) {
                    var hasEscape = false
                    for (i in start + 1 until end - 1) {
                        if (fileText[i] == '\\') {
                            hasEscape = true
                            break
                        }
                    }
                    if (hasEscape) {
                        val rawText = fileText.subSequence(start, end).toString()
                        val content = LuaString.getContent(rawText)
                        val decoded = LuaString.decodeOrNull(rawText, content)
                        if (!decoded.isNullOrEmpty() && decoded != content.value) {
                            val key = "$ESCAPED_STRING_KEY_PREFIX$decoded"
                            if (!processor.process(WordOccurrence(key, 0, key.length, WordOccurrence.Kind.LITERALS))) {
                                return
                            }
                        }
                    }
                }
            }
            if (token !in LuaParserDefinition.WHITE_SPACES && token !in LuaParserDefinition.COMMENTS)
                afterBracket = token == LuaTypes.LBRACK
            lexer.advance()
        }
    }
}

/**
 *
 * Created by TangZX on 2016/11/24.
 */
class LuaFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner? {
        return LuaWordsScanner()
    }

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean {
        return psiElement is PsiNamedElement
    }

    override fun getHelpId(psiElement: PsiElement): String? {
        return null
    }

    override fun getType(psiElement: PsiElement): String {
        if (psiElement is LuaDocTagClass)
            return "Class"
        if (psiElement is LuaDocTagField)
            return "Class Field"
        if (psiElement is LuaFuncDef)
            return "Global Function"
        if (psiElement is LuaLocalFuncDef)
            return "Local Function"
        if (psiElement is LuaClassMethodDef)
            return "Class Function"
        if (psiElement is LuaParamNameDef)
            return "Param"
        return if (psiElement is LuaTableField) "Table Field" else "Name"
    }

    override fun getDescriptiveName(psiElement: PsiElement): String {
        if (psiElement is PsiNamedElement) {
            val name = psiElement.name
            if (name != null)
                return name
        }
        return ""
    }

    override fun getNodeText(psiElement: PsiElement, b: Boolean): String {
        return getDescriptiveName(psiElement)
    }
}
