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

package com.tang.intellij.lua.spellchecker

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.spellchecker.inspections.PlainTextSplitter
import com.intellij.spellchecker.tokenizer.*
import com.tang.intellij.lua.lang.LuaLanguage
import com.tang.intellij.lua.psi.LuaLiteralExpr
import com.tang.intellij.lua.psi.LuaLiteralKind
import com.tang.intellij.lua.psi.kind

class LuaSpellcheckingStrategy : SpellcheckingStrategy() {
    private val safeCommentTokenizer: Tokenizer<*> = RiderFallbackTokenizer(myCommentTokenizer)

    override fun isMyContext(element: PsiElement): Boolean {
        return element.language is LuaLanguage
    }

    override fun getTokenizer(element: PsiElement): Tokenizer<*> {
        return when (element) {
            is LuaLiteralExpr -> StringLiteralTokenizer
            is PsiNameIdentifierOwner -> IdentifierOwnerTokenizer
            else -> wrapWithRiderDictionaryFallback(super.getTokenizer(element))
        }
    }

    private fun <T : PsiElement> wrapWithRiderDictionaryFallback(tokenizer: Tokenizer<T>): Tokenizer<*> {
        return when (tokenizer) {
            EMPTY_TOKENIZER -> tokenizer
            myCommentTokenizer -> safeCommentTokenizer
            TEXT_TOKENIZER -> SafeTextTokenizer
            else -> RiderFallbackTokenizer(tokenizer)
        }
    }
}

private object IdentifierOwnerTokenizer : PsiIdentifierOwnerTokenizer() {
    override fun tokenize(element: PsiNameIdentifierOwner, consumer: TokenConsumer) {
        val id = element.nameIdentifier
        if (id !is LuaLiteralExpr) {
            withRiderDictionaryFallback { super.tokenize(element, consumer) }
        }
    }
}

private object StringLiteralTokenizer : EscapeSequenceTokenizer<LuaLiteralExpr>() {

    override fun tokenize(element: LuaLiteralExpr, consumer: TokenConsumer) {
        if (element.kind == LuaLiteralKind.String) {
            withRiderDictionaryFallback {
                consumer.consumeToken(element, PlainTextSplitter.getInstance())
            }
        }
    }
}

private val SafeTextTokenizer: Tokenizer<PsiElement> = RiderFallbackTokenizer(SpellcheckingStrategy.TEXT_TOKENIZER)

private class RiderFallbackTokenizer<T : PsiElement>(private val delegate: Tokenizer<T>) : Tokenizer<T>() {
    override fun tokenize(element: T, consumer: TokenConsumer) {
        withRiderDictionaryFallback { delegate.tokenize(element, consumer) }
    }

    override fun getHighlightingRange(element: PsiElement, offset: Int, range: TextRange): TextRange {
        return delegate.getHighlightingRange(element, offset, range)
    }
}

private inline fun withRiderDictionaryFallback(checkToken: () -> Unit) {
    try {
        checkToken()
    } catch (error: Error) {
        // Rider and the plugin can load separate copies of Kotlin's NotImplementedError.
        if (error.javaClass.name != "kotlin.NotImplementedError") {
            throw error
        }
        // Some Rider builds leave this lookup unimplemented. Skip the affected token.
        val origin = error.stackTrace.firstOrNull()
        if (origin?.className != "com.jetbrains.rider.spellChecker.layers.BackendEditableDictionaryLayer" ||
            origin.methodName != "lookup") {
            throw error
        }
    }
}
