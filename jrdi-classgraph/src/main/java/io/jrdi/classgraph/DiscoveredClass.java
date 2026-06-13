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
 */package io.jrdi.classgraph;

import java.util.List;

/**
 * One JVM class discovered by {@link ClasspathScanner}. The {@code fqn} is the internal
 * slashed form (e.g. {@code com/acme/Foo}); the {@code accessFlags} bitmask mirrors
 * ASM's {@code Opcodes} (e.g. {@code ACC_PUBLIC=1}, {@code ACC_INTERFACE=0x0200}).
 */
public record DiscoveredClass(
        String fqn,
        int accessFlags,
        String superclassFqn,
        boolean isInterface,
        boolean isAnnotation,
        boolean isEnum,
        boolean isRecord,
        boolean isSealed
) {
    public boolean isPublic() {
        return (accessFlags & 0x0001) != 0;
    }

    public String slashedFqn() {
        return fqn.replace('.', '/');
    }
}
