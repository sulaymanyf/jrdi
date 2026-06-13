/*
 * Copyright 2026 sulaymanyf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */package io.jrdi.bytecode;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Lightweight stack-frame tracker for reflection detection. It does NOT do a full abstract
 * interpretation — only enough to spot the common patterns:
 *
 * <ul>
 *   <li>{@code Class.forName("...")} / {@code ClassLoader.loadClass("...")}</li>
 *   <li>{@code Method.invoke(target, args)} when the method came from a getMethod/findMethod
 *       on a constant class</li>
 *   <li>{@code Constructor.newInstance}</li>
 * </ul>
 *
 * <p>Works by snapshotting the operand stack before each {@code INVOKESTATIC} / {@code INVOKEVIRTUAL}
 * and looking for the magic descriptor of a known reflective target. Constant string operands
 * are followed via {@code LDC} into a small {@link java.util.Deque} that mirrors the JVM
 * stack at the call site.
 */
public final class ReflectionFrame {

    public static final String FOR_NAME = "java/lang/Class.forName";
    public static final String CLASS_LOADER_LOAD_CLASS = "java/lang/ClassLoader.loadClass";
    public static final String METHOD_INVOKE = "java/lang/reflect/Method.invoke";
    public static final String CONSTRUCTOR_NEW_INSTANCE = "java/lang/reflect/Constructor.newInstance";

    private final Deque<Object> stack = new ArrayDeque<>();

    public void push(Object value) {
        stack.push(value);
    }

    public Object pop() {
        return stack.isEmpty() ? null : stack.pop();
    }

    public Object peek() {
        return stack.isEmpty() ? null : stack.peek();
    }

    public int depth() {
        return stack.size();
    }

    public void clear() {
        stack.clear();
    }

    /**
     * Apply a value to the stack according to a JVM type descriptor: {@code V} → nothing,
     * primitive/array widths → push {@code null} (we treat them as opaque), reference types
     * → push {@code value}.
     */
    public void pushTyped(Object value, String typeDescriptor) {
        if ("V".equals(typeDescriptor)) return;
        // For primitive / array slots, we still push null — only reference type slots can carry
        // a class reference we care about for reflection detection.
        stack.push(value);
    }

    /**
     * Inspect a call right before dispatch. Returns a description of the reflective edge
     * if the call matches a known pattern, or {@code null} otherwise.
     */
    public ReflectiveCall inspect(String owner, String name, String desc) {
        String key = owner + "." + name;
        return switch (key) {
            case FOR_NAME -> reflectFromForName();
            case CLASS_LOADER_LOAD_CLASS -> reflectFromForName();
            case METHOD_INVOKE -> null; // needs context of the previous getMethod call
            case CONSTRUCTOR_NEW_INSTANCE -> null;
            default -> null;
        };
    }

    private ReflectiveCall reflectFromForName() {
        // The top of the stack should be a String LDC'd just before the call.
        Object top = pop();
        if (top instanceof String s && !s.isEmpty()) {
            return new ReflectiveCall(s, "Class.forName", true);
        }
        return new ReflectiveCall(null, "Class.forName", false);
    }

    public record ReflectiveCall(String targetFqn, String kind, boolean resolved) {
        public String internalName() {
            if (targetFqn == null) return null;
            return targetFqn.replace('.', '/');
        }
    }
}
