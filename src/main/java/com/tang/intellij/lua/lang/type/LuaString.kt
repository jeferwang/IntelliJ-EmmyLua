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

        fun decode(rawText: String, content: LuaString = getContent(rawText)): String {
            val raw = content.value
            if (raw.isEmpty()) return raw
            if (rawText.startsWith("[")) {
                return when {
                    raw.startsWith("\r\n") -> raw.substring(2)
                    raw.startsWith("\n") || raw.startsWith("\r") -> raw.substring(1)
                    else -> raw
                }
            }
            if ('\\' !in raw) return raw
            val sb = StringBuilder(raw.length)
            var i = 0
            val len = raw.length
            while (i < len) {
                val ch = raw[i]
                if (ch != '\\' || i + 1 >= len) {
                    sb.append(ch)
                    i++
                    continue
                }
                i++
                when (val esc = raw[i++]) {
                    'a' -> sb.append('\u0007')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'v' -> sb.append('\u000B')
                    '\\', '"', '\'' -> sb.append(esc)
                    '\n' -> sb.append('\n')
                    '\r' -> {
                        if (i < len && raw[i] == '\n') i++
                        sb.append('\n')
                    }
                    'z' -> {
                        while (i < len && raw[i] in " \t\n\r\u000B\u000C") i++
                    }
                    'x' -> {
                        if (i + 1 < len) {
                            val h1 = Character.digit(raw[i], 16)
                            val h2 = Character.digit(raw[i + 1], 16)
                            if (h1 >= 0 && h2 >= 0) {
                                sb.append(((h1 shl 4) or h2).toChar())
                                i += 2
                            } else {
                                sb.append('x')
                            }
                        } else {
                            sb.append('x')
                        }
                    }
                    'u' -> {
                        if (i < len && raw[i] == '{') {
                            val close = raw.indexOf('}', i + 1)
                            if (close > i + 1) {
                                val cp = raw.substring(i + 1, close).toIntOrNull(16)
                                if (cp != null && Character.isValidCodePoint(cp)) {
                                    sb.appendCodePoint(cp)
                                    i = close + 1
                                    continue
                                }
                            }
                        }
                        sb.append('u')
                    }
                    in '0'..'9' -> {
                        var value = esc - '0'
                        var digits = 1
                        while (digits < 3 && i < len && raw[i] in '0'..'9') {
                            value = value * 10 + (raw[i] - '0')
                            i++
                            digits++
                        }
                        if (value in 0..255) {
                            sb.append(value.toChar())
                        }
                    }
                    else -> sb.append(esc)
                }
            }
            return sb.toString()
        }
    }
}
