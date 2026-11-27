package com.tang.intellij.lua.reference

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.cache.impl.id.IdIndex
import com.intellij.psi.impl.cache.impl.id.IdIndexEntry
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import com.tang.intellij.lua.lang.type.LuaString
import com.tang.intellij.lua.psi.LuaClassMember
import com.tang.intellij.lua.psi.LuaIndexExpr
import com.tang.intellij.lua.psi.LuaLiteralExpr
import com.tang.intellij.lua.psi.LuaLiteralKind
import com.tang.intellij.lua.psi.LuaPsiFile
import com.tang.intellij.lua.psi.LuaTableField
import com.tang.intellij.lua.psi.kind
import com.tang.intellij.lua.usages.ESCAPED_STRING_KEY_PREFIX

class LuaEscapedTableFieldReferencesSearch :
    QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {

    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ) {
        val target = queryParameters.elementToSearch as? LuaClassMember ?: return
        val targetName = target.name?.takeIf { it.isNotEmpty() } ?: return
        val project = target.project
        if (DumbService.isDumb(project)) return

        fun scanElement(root: PsiElement): Boolean {
            val chars: CharSequence = (root as? PsiFile)?.viewProvider?.contents ?: root.text
            if (chars.indexOf('\\') < 0 || chars.indexOf('[') < 0) return true
            return PsiTreeUtil.processElements(root) { element ->
                ProgressManager.checkCanceled()
                if (element is LuaTableField || element is LuaIndexExpr) {
                    val idExpr = if (element is LuaTableField) element.idExpr else (element as LuaIndexExpr).idExpr
                    if (idExpr is LuaLiteralExpr && idExpr.kind == LuaLiteralKind.String) {
                        val rawText = idExpr.text
                        if (rawText.length >= 4 && (rawText[0] == '"' || rawText[0] == '\'') && '\\' in rawText) {
                            val content = LuaString.getContent(rawText)
                            if (content.value != targetName && LuaString.decodeOrNull(rawText, content) == targetName) {
                                val ref = if (element is LuaTableField) element.reference
                                    else element.references.filterIsInstance<LuaIndexBracketReference>().firstOrNull()
                                if (ref != null && ref.isReferenceTo(target) && !consumer.process(ref)) {
                                    return@processElements false
                                }
                            }
                        }
                    }
                }
                true
            }
        }

        when (val scope = queryParameters.effectiveSearchScope) {
            is LocalSearchScope -> {
                for (element in scope.scope) {
                    if (!scanElement(element)) return
                }
            }
            is GlobalSearchScope -> {
                val key = IdIndexEntry("$ESCAPED_STRING_KEY_PREFIX$targetName", true)
                val psiManager = PsiManager.getInstance(project)
                val files = mutableListOf<VirtualFile>()
                FileBasedIndex.getInstance().processFilesContainingAllKeys(
                    IdIndex.NAME,
                    listOf(key),
                    scope,
                    null
                ) { vfile ->
                    ProgressManager.checkCanceled()
                    files.add(vfile)
                }
                for (vfile in files) {
                    ProgressManager.checkCanceled()
                    if (!vfile.isValid) continue
                    val psiFile = psiManager.findFile(vfile) as? LuaPsiFile ?: continue
                    if (!scanElement(psiFile)) return
                }
            }
        }
    }
}
