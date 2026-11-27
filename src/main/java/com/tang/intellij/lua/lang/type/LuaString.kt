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

package com.tang.intellij.lua.lang.type

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 *
 * Created by tangzx on 2016/12/25.
 */
class LuaString {
    var start: Int = 0
    var end: Int = 0
    var value = ""

    val length: Int
        get() = end - start

    companion object {

        /**
         * 获取 lua 字符串的内容，
         * "value"
         * 'value'
         * [[value]]
         * @param text string element
         * @return value of String
         */
        fun getContent(text: String): LuaString {
            val content = LuaString()
            if (text.startsWith("[")) {
                val pattern = Pattern.compile("\\[(=*)\\[([\\s\\S]*)]\\1]")
                val matcher = pattern.matcher(text)
                if (matcher.find()) {
                    val contentString = matcher.group(2)
                    content.start = matcher.start(2)
                    content.end = matcher.end(2)
                    content.value = contentString
                }
            } else {
                content.start = 1
                content.end = text.length - 1
                if (content.end > content.start)
                    content.value = text.substring(content.start, content.end)
                else
                    content.end = content.start
            }
            return content
        }

        fun decodeOrNull(rawText: String, content: LuaString = getContent(rawText)): String? {
            val raw = content.value
            if (rawText.startsWith("[")) {
                return when {
                    raw.startsWith("\r\n") -> raw.substring(2)
                    raw.startsWith("\n") || raw.startsWith("\r") -> raw.substring(1)
                    else -> raw
                }
            }
            if (rawText.length < 2 || rawText.first() != rawText.last()) return null
            if ('\\' !in raw) return raw
            val result = StringBuilder(raw.length)
            var bytes: ByteArray? = null
            var size = 0
            val decoder by lazy(LazyThreadSafetyMode.NONE) {
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            }
            fun flush(): Boolean {
                if (size == 0) return true
                return try {
                    result.append(decoder.decode(ByteBuffer.wrap(bytes!!, 0, size)))
                    size = 0
                    true
                } catch (_: CharacterCodingException) {
                    false
                }
            }

            var i = 0
            while (i < raw.length) {
                val ch = raw[i++]
                if (ch != '\\') {
                    if (!flush()) return null
                    result.append(ch)
                    continue
                }
                if (i == raw.length) return null
                val esc = raw[i++]
                if (esc == 'x' || esc in '0'..'9') {
                    val value: Int
                    if (esc == 'x') {
                        if (i + 2 > raw.length) return null
                        val hi = hexDigit(raw[i++])
                        val lo = hexDigit(raw[i++])
                        if (hi < 0 || lo < 0) return null
                        value = hi * 16 + lo
                    } else {
                        var decimal = esc - '0'
                        val end = minOf(i + 2, raw.length)
                        while (i < end && raw[i] in '0'..'9') decimal = decimal * 10 + (raw[i++] - '0')
                        if (decimal > 255) return null
                        value = decimal
                    }
                    val buffer = bytes ?: ByteArray(raw.length / 2).also { bytes = it }
                    buffer[size++] = value.toByte()
                    continue
                }
                if (!flush()) return null
                when (esc) {
                    'a' -> result.append('\u0007')
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000c')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'v' -> result.append('\u000b')
                    '\\', '"', '\'' -> result.append(esc)
                    '\r', '\n' -> {
                        if (i < raw.length && raw[i] == (if (esc == '\r') '\n' else '\r')) i++
                        result.append('\n')
                    }
                    'z' -> while (i < raw.length && raw[i] in " \t\n\r\u000b\u000c") i++
                    'u' -> {
                        if (i >= raw.length || raw[i++] != '{') return null
                        val start = i
                        var cp = 0
                        while (i < raw.length && raw[i] != '}') {
                            val digit = hexDigit(raw[i++])
                            if (digit < 0) return null
                            cp = cp * 16 + digit
                            if (cp > Character.MAX_CODE_POINT) return null
                        }
                        if (i == start || i == raw.length || cp in 0xd800..0xdfff) return null
                        i++
                        result.appendCodePoint(cp)
                    }
                    else -> return null
                }
            }
            return if (flush()) result.toString() else null
        }

        private fun hexDigit(ch: Char): Int = when (ch) {
            in '0'..'9' -> ch - '0'
            in 'a'..'f' -> ch - 'a' + 10
            in 'A'..'F' -> ch - 'A' + 10
            else -> -1
        }
    }
}
