package org.springframework.boot.autoconfigure.condition;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Test-only stub. We define this in the real Spring Boot package so the
// descriptor matches what SpringConditionalPass looks for via ASM
// ("Lorg/springframework/boot/autoconfigure/condition/ConditionalOnClass;").
// We don't pull in Spring Boot as a test dep (it would bloat test classpath
// by 50+ MB); instead we use these stubs to produce realistic bytecode.
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ConditionalOnClass {
    Class<?>[] value() default {};
    String name() default "";
}
