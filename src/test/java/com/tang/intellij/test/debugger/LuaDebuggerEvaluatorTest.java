package com.tang.intellij.test.debugger;

import com.intellij.codeInsight.multiverse.CodeInsightContext;
import com.intellij.core.CoreApplicationEnvironment;
import com.intellij.core.CoreProjectEnvironment;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.tang.intellij.lua.debugger.LuaDebuggerEvaluator;
import com.tang.intellij.lua.lang.LuaFileType;
import com.tang.intellij.lua.lang.LuaParserDefinition;
import com.tang.intellij.lua.project.LuaSettings;
import junit.framework.TestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.function.Supplier;

public class LuaDebuggerEvaluatorTest extends TestCase {
    private Disposable appDisposable;
    private Disposable projectDisposable;
    private Project baseProject;
    private PsiFile psiFile;
    private int alphaOffset;
    private TextRange expectedRange;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        appDisposable = Disposer.newDisposable();
        CoreApplicationEnvironment appEnv = new CoreApplicationEnvironment(appDisposable);
        appEnv.registerFileType(LuaFileType.INSTANCE, "lua");
        appEnv.registerParserDefinition(new LuaParserDefinition());
        appEnv.registerApplicationService(LuaSettings.class, new LuaSettings());

        projectDisposable = Disposer.newDisposable();
        CoreProjectEnvironment projEnv = new CoreProjectEnvironment(projectDisposable, appEnv);
        baseProject = projEnv.getProject();
        psiFile = PsiFileFactory.getInstance(baseProject).createFileFromText(
                "Debug.lua", LuaFileType.INSTANCE, "local alpha = 1\nreturn alpha\n"
        );
        alphaOffset = psiFile.getText().indexOf("alpha");
        expectedRange = TextRange.create(alphaOffset, alphaOffset + "alpha".length());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (projectDisposable != null) {
                Disposer.dispose(projectDisposable);
                projectDisposable = null;
            }
            if (appDisposable != null) {
                Disposer.dispose(appDisposable);
                appDisposable = null;
            }
        } finally {
            super.tearDown();
        }
    }

    public void testBackgroundReadLockUsesCommittedPsiWithoutCommitting() {
        runWithLocks(true, false, false, false, true, psiFile.getTextLength(), (range, commitCalls) -> {
            assertEquals(0, commitCalls);
            assertEquals(expectedRange, range);
        });
    }

    public void testBackgroundReadLockSkipsUncommittedDocument() {
        runWithLocks(true, false, false, false, false, psiFile.getTextLength(), (range, commitCalls) -> {
            assertEquals(0, commitCalls);
            assertNull(range);
        });
    }

    public void testEdtPureReadLockSkipsUncommittedDocument() {
        runWithLocks(true, true, false, false, false, psiFile.getTextLength(), (range, commitCalls) -> {
            assertEquals(0, commitCalls);
            assertNull(range);
        });
    }

    public void testEdtWriteIntentCommitsUncommittedDocument() {
        runWithLocks(true, true, true, false, false, psiFile.getTextLength(), (range, commitCalls) -> {
            assertEquals(1, commitCalls);
            assertEquals(expectedRange, range);
        });
    }

    public void testBackgroundWithoutReadLockCommitsDocument() {
        runWithLocks(false, false, false, false, false, psiFile.getTextLength(), (range, commitCalls) -> {
            assertEquals(1, commitCalls);
            assertEquals(expectedRange, range);
        });
    }

    public void testRangeExceedingDocumentLengthIsDiscarded() {
        runWithLocks(true, false, false, false, true, 3, (range, commitCalls) -> {
            assertEquals(0, commitCalls);
            assertNull(range);
        });
    }

    @FunctionalInterface
    private interface Verification {
        void check(@Nullable TextRange range, int commitCalls);
    }

    private void runWithLocks(
            boolean readAllowed,
            boolean dispatchThread,
            boolean writeIntent,
            boolean writeAllowed,
            boolean committed,
            int docLength,
            Verification verification
    ) {
        Document document = fakeDocument(docLength);
        int[] commitCalls = {0};
        PsiDocumentManager docManager = fakeDocManager(document, committed, commitCalls);
        Project project = (Project) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> {
                    if ("getService".equals(method.getName()) && args != null && args.length == 1 && args[0] == PsiDocumentManager.class) {
                        return docManager;
                    }
                    return method.invoke(baseProject, args);
                }
        );

        Application originalApp = ApplicationManager.getApplication();
        Application fakeApp = (Application) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Application.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isReadAccessAllowed" -> readAllowed;
                    case "isDispatchThread" -> dispatchThread;
                    case "isWriteIntentLockAcquired" -> writeIntent;
                    case "isWriteAccessAllowed" -> writeAllowed;
                    default -> method.invoke(originalApp, args);
                }
        );

        LuaDebuggerEvaluator evaluator = new LuaDebuggerEvaluator() {
            @Override
            protected void eval(@NotNull String express, @NotNull XDebuggerEvaluator.XEvaluationCallback callback, @Nullable XSourcePosition position) {
            }
        };

        Disposable overrideDisposable = Disposer.newDisposable();
        ApplicationManager.setApplication(fakeApp, overrideDisposable);
        try {
            TextRange range = evaluator.getExpressionRangeAtOffset(project, document, alphaOffset, false);
            verification.check(range, commitCalls[0]);
        } finally {
            Disposer.dispose(overrideDisposable);
        }
    }

    private Document fakeDocument(int length) {
        return (Document) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Document.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTextLength" -> length;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private PsiDocumentManager fakeDocManager(Document document, boolean committed, int[] commitCalls) {
        return new PsiDocumentManager() {
            @Override public @Nullable PsiFile getPsiFile(@NotNull Document doc) { return psiFile; }
            @Override public @Nullable PsiFile getPsiFile(@NotNull Document doc, @NotNull CodeInsightContext ctx) { return psiFile; }
            @Override public @Nullable PsiFile getCachedPsiFile(@NotNull Document doc) { return psiFile; }
            @Override public @Nullable PsiFile getCachedPsiFile(@NotNull Document doc, @NotNull CodeInsightContext ctx) { return psiFile; }
            @Override public @Nullable Document getDocument(@NotNull PsiFile file) { return document; }
            @Override public @Nullable Document getCachedDocument(@NotNull PsiFile file) { return document; }
            @Override public void commitAllDocuments() { commitCalls[0]++; }
            @Override public boolean commitAllDocumentsUnderProgress() { commitCalls[0]++; return true; }
            @Override public void performForCommittedDocument(@NotNull Document doc, @NotNull Runnable action) { action.run(); }
            @Override public void commitDocument(@NotNull Document doc) { commitCalls[0]++; }
            @Override public @NotNull CharSequence getLastCommittedText(@NotNull Document doc) { return psiFile.getText(); }
            @Override public long getLastCommittedStamp(@NotNull Document doc) { return 0; }
            @Override public @Nullable Document getLastCommittedDocument(@NotNull PsiFile file) { return document; }
            @Override public @NotNull Document[] getUncommittedDocuments() { return committed ? Document.EMPTY_ARRAY : new Document[]{document}; }
            @Override public boolean isDocumentBlockedByPsi(@NotNull Document doc) { return false; }
            @Override public void doPostponedOperationsAndUnblockDocument(@NotNull Document doc) {}
            @Override public boolean isCommitted(@NotNull Document doc) { return committed; }
            @Override public boolean hasUncommitedDocuments() { return !committed; }
            @Override public void commitAndRunReadAction(@NotNull Runnable runnable) { commitCalls[0]++; runnable.run(); }
            @Override public <T> T commitAndRunReadAction(@NotNull Computable<T> computation) { commitCalls[0]++; return computation.compute(); }
            @Override public boolean performWhenAllCommitted(@NotNull Runnable action) { action.run(); return true; }
            @Override public void performLaterWhenAllCommitted(@NotNull Runnable runnable) { runnable.run(); }
            @Override public void performLaterWhenAllCommitted(@NotNull ModalityState modalityState, @NotNull Runnable runnable) { runnable.run(); }
            @Override public void reparseFiles(@NotNull Collection<? extends VirtualFile> files, boolean includeOpenFiles) {}
            @Override public <T> T allowIsolatedCommits(@NotNull Document doc, @NotNull Supplier<? extends T> action) { return action.get(); }
        };
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        return 0;
    }
}
