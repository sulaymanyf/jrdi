package io.jrdi.core.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers to convert between JVM-internal slashed descriptors and human-readable types.
 * <p>
 * Examples:
 * <ul>
 *   <li>{@code Ljava/util/List;} → {@code java.util.List}</li>
 *   <li>{@code I} → {@code int}</li>
 *   <li>{@code [[Ljava/lang/String;} → {@code java.lang.String[][]}</li>
 * </ul>
 */
public final class Descriptors {

    private static final Pattern L_TYPE = Pattern.compile("^L([^;]+);$");

    private Descriptors() {
    }

    public static String toReadable(String descriptor) {
        int idx = 0;
        int dims = 0;
        while (idx < descriptor.length() && descriptor.charAt(idx) == '[') {
            dims++;
            idx++;
        }
        String element;
        char c = descriptor.charAt(idx);
        if (c == 'L') {
            element = descriptor.substring(idx + 1, descriptor.length() - 1).replace('/', '.');
        } else {
            element = switch (c) {
                case 'V' -> "void";
                case 'Z' -> "boolean";
                case 'B' -> "byte";
                case 'C' -> "char";
                case 'S' -> "short";
                case 'I' -> "int";
                case 'J' -> "long";
                case 'F' -> "float";
                case 'D' -> "double";
                default -> throw new IllegalArgumentException("bad descriptor: " + descriptor);
            };
        }
        StringBuilder out = new StringBuilder(element);
        for (int i = 0; i < dims; i++) {
            out.append("[]");
        }
        return out.toString();
    }

    public static String toReadableReturn(String methodDescriptor) {
        int paren = methodDescriptor.indexOf(')');
        return toReadable(methodDescriptor.substring(paren + 1));
    }

    public static String[] parameterTypes(String methodDescriptor) {
        int paren = methodDescriptor.indexOf(')');
        String args = methodDescriptor.substring(1, paren);
        return splitArgs(args);
    }

    private static String[] splitArgs(String args) {
        if (args.isEmpty()) return new String[0];
        java.util.List<String> out = new java.util.ArrayList<>();
        int idx = 0;
        while (idx < args.length()) {
            int start = idx;
            char c = args.charAt(idx);
            if (c == '[') {
                idx++;
                if (idx < args.length() && args.charAt(idx) == 'L') {
                    int semi = args.indexOf(';', idx);
                    idx = semi + 1;
                } else {
                    idx++;
                }
            } else if (c == 'L') {
                int semi = args.indexOf(';', idx);
                idx = semi + 1;
            } else {
                idx++;
            }
            out.add(args.substring(start, idx));
        }
        return out.toArray(new String[0]);
    }

    /**
     * Replace slashed form with dotted form in an internal name.
     */
    public static String internalToDotted(String internal) {
        Matcher m = L_TYPE.matcher(internal);
        if (m.matches()) {
            return m.group(1).replace('/', '.');
        }
        return internal.replace('/', '.');
    }
}
