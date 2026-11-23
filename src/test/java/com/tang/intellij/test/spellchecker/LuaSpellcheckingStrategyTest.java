package com.tang.intellij.test.spellchecker;

import junit.framework.TestCase;
import kotlin.NotImplementedError;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;

public class LuaSpellcheckingStrategyTest extends TestCase {
    private static final String DICTIONARY =
            "com.jetbrains.rider.spellChecker.layers.BackendEditableDictionaryLayer";

    public void testSuccessfulCheckRunsOnce() throws Throwable {
        int[] calls = {0};
        check(() -> { calls[0]++; return Unit.INSTANCE; });
        assertEquals(1, calls[0]);
    }

    public void testKnownDictionaryErrorIsSkipped() throws Throwable {
        checkError(fromLookup(new NotImplementedError("Not yet implemented")));
    }

    public void testDictionaryErrorFromAnotherClassLoaderIsSkipped() throws Throwable {
        Error error = foreignNotImplementedError();
        assertFalse(error instanceof NotImplementedError);
        assertNotSame(NotImplementedError.class.getClassLoader(), error.getClass().getClassLoader());
        checkError(fromLookup(error));
    }

    public void testUnrelatedNotImplementedErrorsPropagate() throws Throwable {
        assertPropagates(new NotImplementedError("Unrelated TODO"));
        assertPropagates(foreignNotImplementedError());
        Error wrongMethod = new NotImplementedError("Unrelated method");
        wrongMethod.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(DICTIONARY, "getWords", "RiderDictionaryLayersProvider.kt", 50)
        });
        assertPropagates(wrongMethod);
        Error emptyTrace = new NotImplementedError("No origin");
        emptyTrace.setStackTrace(new StackTraceElement[0]);
        assertPropagates(emptyTrace);
    }

    public void testOtherErrorsAndCancellationPropagate() throws Throwable {
        assertPropagates(fromLookup(new AssertionError("Dictionary assertion")));
        assertPropagates(fromLookup(new LinkageError("Linkage failure")));
        assertPropagates(fromLookup(new IllegalStateException("Runtime failure")));
        assertPropagates(fromLookup(new CancellationException("Canceled")));
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

    private static void assertPropagates(Throwable expected) throws Throwable {
        try {
            checkError(expected);
        } catch (Throwable actual) {
            assertSame(expected, actual);
            return;
        }
        fail("Expected the original error to propagate: " + expected);
    }

    private static void checkError(Throwable error) throws Throwable {
        check(() -> {
            if (error instanceof Error) throw (Error) error;
            throw (RuntimeException) error;
        });
    }

    private static void check(Function0<Unit> action) throws Throwable {
        Method fallback = Class.forName("com.tang.intellij.lua.spellchecker.LuaSpellcheckingStrategyKt")
                .getDeclaredMethod("withRiderDictionaryFallback", Function0.class);
        fallback.setAccessible(true);
        try {
            fallback.invoke(null, action);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }
}
