package java.lang.invoke;

/**
 * Compile-time stub. android.jar ships without LambdaMetafactory.metafactory, but javac needs
 * the symbol to emit invokedynamic for lambdas. D8 desugars the lambdas, so this class is never
 * loaded at runtime. Equivalent to build-tools/core-lambda-stubs.jar.
 */
public final class LambdaMetafactory {
    private LambdaMetafactory() {}

    public static CallSite metafactory(MethodHandles.Lookup caller, String invokedName,
            MethodType invokedType, MethodType samMethodType, MethodHandle implMethod,
            MethodType instantiatedMethodType) throws LambdaConversionException {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static CallSite altMetafactory(MethodHandles.Lookup caller, String invokedName,
            MethodType invokedType, Object... args) throws LambdaConversionException {
        throw new UnsupportedOperationException("compile-time stub");
    }
}
