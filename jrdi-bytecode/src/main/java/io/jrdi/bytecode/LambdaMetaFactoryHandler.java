package io.jrdi.bytecode;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;

/**
 * Detects {@code invokedynamic} call sites whose bootstrap method is
 * {@code java.lang.invoke.LambdaMetafactory}, then returns the synthetic
 * implementation method handle. Everything else is reported as
 * {@link #isLambdaSite(Handle) isLambdaSite} == false.
 */
public final class LambdaMetaFactoryHandler {

    private LambdaMetaFactoryHandler() {
    }

    public static boolean isLambdaSite(Handle bsm) {
        if (bsm == null) return false;
        return "java/lang/invoke/LambdaMetafactory".equals(bsm.getOwner())
                && (bsm.getName().equals("metafactory") || bsm.getName().equals("altMetafactory"));
    }

    /**
     * Build the synthetic method FQN of the form {@code owner.lambda$N}, useful for indexing.
     */
    public static String syntheticFqn(Handle implMethod) {
        if (implMethod == null) return null;
        return implMethod.getOwner() + "." + implMethod.getName();
    }

    /**
     * Pretty-print a {@link Handle} into a stable string for storage.
     */
    public static String describeHandle(Handle h) {
        if (h == null) return null;
        return h.getOwner() + "#" + h.getName() + h.getDesc() + ":" + tagOf(h.getTag());
    }

    private static String tagOf(int tag) {
        return switch (tag) {
            case Opcodes.H_GETFIELD -> "getField";
            case Opcodes.H_GETSTATIC -> "getStatic";
            case Opcodes.H_PUTFIELD -> "putField";
            case Opcodes.H_PUTSTATIC -> "putStatic";
            case Opcodes.H_INVOKEVIRTUAL -> "invokeVirtual";
            case Opcodes.H_INVOKESTATIC -> "invokeStatic";
            case Opcodes.H_INVOKESPECIAL -> "invokeSpecial";
            case Opcodes.H_NEWINVOKESPECIAL -> "newInvokeSpecial";
            case Opcodes.H_INVOKEINTERFACE -> "invokeInterface";
            default -> "tag=" + tag;
        };
    }
}
