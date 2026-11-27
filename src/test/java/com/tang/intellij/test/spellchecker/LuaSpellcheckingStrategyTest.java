package com.tang.intellij.test.spellchecker;

import com.intellij.core.CoreApplicationEnvironment;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.spellchecker.inspections.Splitter;
import com.intellij.spellchecker.tokenizer.TokenConsumer;
import com.intellij.spellchecker.tokenizer.Tokenizer;
import com.tang.intellij.lua.lang.LuaLanguage;
import com.tang.intellij.lua.psi.LuaLiteralExpr;
import com.tang.intellij.lua.psi.LuaTypes;
import com.tang.intellij.lua.spellchecker.LuaSpellcheckingStrategy;
import junit.framework.TestCase;
import kotlin.NotImplementedError;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.concurrent.CancellationException;

public class LuaSpellcheckingStrategyTest extends TestCase {
    private static final String DICTIONARY =
            "com.jetbrains.rider.spellChecker.layers.BackendEditableDictionaryLayer";

    private final LuaSpellcheckingStrategy strategy = new LuaSpellcheckingStrategy();
    private Disposable appDisposable;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (ApplicationManager.getApplication() == null) {
            appDisposable = Disposer.newDisposable();
            new CoreApplicationEnvironment(appDisposable);
        }
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (appDisposable != null) {
                Disposer.dispose(appDisposable);
                appDisposable = null;
            }
        } finally {
            super.tearDown();
        }
    }

    public void testSuccessfulCheckRunsOnce() throws Throwable {
        for (PsiElement element : sampleElements()) {
            int[] calls = {0};
            tokenize(element, () -> calls[0]++);
            assertEquals(1, calls[0]);
        }
    }

    public void testKnownDictionaryErrorIsSkippedAcrossAllTokenizers() throws Throwable {
        for (PsiElement element : sampleElements()) {
            tokenizeWithError(element, fromLookup(new NotImplementedError("Not yet implemented")));
        }
    }

    public void testDictionaryErrorFromAnotherClassLoaderIsSkippedAcrossAllTokenizers() throws Throwable {
        for (PsiElement element : sampleElements()) {
            Error error = foreignNotImplementedError();
            assertFalse(error instanceof NotImplementedError);
            assertNotSame(NotImplementedError.class.getClassLoader(), error.getClass().getClassLoader());
            tokenizeWithError(element, fromLookup(error));
        }
    }

    public void testUnrelatedNotImplementedErrorsPropagate() throws Throwable {
        for (PsiElement element : sampleElements()) {
            assertPropagates(element, new NotImplementedError("Unrelated TODO"));
            assertPropagates(element, foreignNotImplementedError());
            Error wrongMethod = new NotImplementedError("Unrelated method");
            wrongMethod.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement(DICTIONARY, "getWords", "RiderDictionaryLayersProvider.kt", 50)
            });
            assertPropagates(element, wrongMethod);
            Error emptyTrace = new NotImplementedError("No origin");
            emptyTrace.setStackTrace(new StackTraceElement[0]);
            assertPropagates(element, emptyTrace);
        }
    }

    public void testOtherErrorsAndCancellationPropagate() throws Throwable {
        for (PsiElement element : sampleElements()) {
            assertPropagates(element, fromLookup(new AssertionError("Dictionary assertion")));
            assertPropagates(element, fromLookup(new LinkageError("Linkage failure")));
            assertPropagates(element, fromLookup(new IllegalStateException("Runtime failure")));
            assertPropagates(element, fromLookup(new CancellationException("Canceled")));
        }
    }

    private static PsiElement[] sampleElements() {
        return new PsiElement[]{
                fakeComment(),
                fakeIdentifierOwner(),
                fakeStringLiteral()
        };
    }

    private static PsiComment fakeComment() {
        return (PsiComment) Proxy.newProxyInstance(
                LuaSpellcheckingStrategyTest.class.getClassLoader(),
                new Class<?>[]{PsiComment.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLanguage" -> LuaLanguage.INSTANCE;
                    case "getText" -> "-- lua comment";
                    case "getTextOffset" -> 1;
                    case "getTextRange" -> TextRange.create(1, 15);
                    case "getParent" -> null;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static PsiNameIdentifierOwner fakeIdentifierOwner() {
        PsiElement id = (PsiElement) Proxy.newProxyInstance(
                LuaSpellcheckingStrategyTest.class.getClassLoader(),
                new Class<?>[]{PsiElement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getText" -> "sampleName";
                    case "getTextRange" -> TextRange.create(6, 16);
                    default -> defaultValue(method.getReturnType());
                }
        );
        return (PsiNameIdentifierOwner) Proxy.newProxyInstance(
                LuaSpellcheckingStrategyTest.class.getClassLoader(),
                new Class<?>[]{PsiNameIdentifierOwner.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getNameIdentifier" -> id;
                    case "getText" -> "local sampleName";
                    case "getTextRange" -> TextRange.create(0, 16);
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static LuaLiteralExpr fakeStringLiteral() {
        ASTNode stringLeaf = (ASTNode) Proxy.newProxyInstance(
                LuaSpellcheckingStrategyTest.class.getClassLoader(),
                new Class<?>[]{ASTNode.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getElementType" -> LuaTypes.STRING;
                    default -> defaultValue(method.getReturnType());
                }
        );
        ASTNode literalNode = (ASTNode) Proxy.newProxyInstance(
                LuaSpellcheckingStrategyTest.class.getClassLoader(),
                new Class<?>[]{ASTNode.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getFirstChildNode" -> stringLeaf;
                    default -> defaultValue(method.getReturnType());
                }
        );
        return (LuaLiteralExpr) Proxy.newProxyInstance(
                LuaSpellcheckingStrategyTest.class.getClassLoader(),
                new Class<?>[]{LuaLiteralExpr.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getStub" -> null;
                    case "getNode" -> literalNode;
                    case "getText" -> "\"sample\"";
                    case "getTextRange" -> TextRange.create(0, 8);
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        return 0;
    }

    private static <T extends Throwable> T fromLookup(T error) {
        error.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(DICTIONARY, "lookup", "RiderDictionaryLayersProvider.kt", 45)
        });
        return error;
    }

    private static Error foreignNotImplementedError() throws Exception {
        // Define only this error again, as happens with the plugin's bundled stdlib.
        ClassLoader loader = new ClassLoader(NotImplementedError.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals("kotlin.NotImplementedError")) {
                    return super.loadClass(name, resolve);
                }
                try (InputStream stream = getParent().getResourceAsStream("kotlin/NotImplementedError.class")) {
                    if (stream == null) throw new ClassNotFoundException(name);
                    byte[] bytes = stream.readAllBytes();
                    Class<?> type = defineClass(name, bytes, 0, bytes.length);
                    if (resolve) resolveClass(type);
                    return type;
                } catch (java.io.IOException error) {
                    throw new ClassNotFoundException(name, error);
                }
            }
        };
        return (Error) loader.loadClass("kotlin.NotImplementedError")
                .getConstructor(String.class).newInstance("Not yet implemented");
    }

    private void assertPropagates(PsiElement element, Throwable expected) {
        try {
            tokenizeWithError(element, expected);
        } catch (Throwable actual) {
            assertSame(expected, actual);
            return;
        }
        fail("Expected the original error to propagate: " + expected);
    }

    private void tokenizeWithError(PsiElement element, Throwable error) {
        tokenize(element, () -> {
            if (error instanceof Error) throw (Error) error;
            throw (RuntimeException) error;
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void tokenize(PsiElement element, Runnable onConsume) {
        Tokenizer tokenizer = strategy.getTokenizer(element);
        tokenizer.tokenize(element, new TokenConsumer() {
            @Override
            public void consumeToken(PsiElement target, String text, boolean useRename, int offset, TextRange rangeToCheck, Splitter splitter) {
                onConsume.run();
            }
        });
    }
}
