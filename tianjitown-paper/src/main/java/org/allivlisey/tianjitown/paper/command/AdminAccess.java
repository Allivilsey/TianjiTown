package org.allivlisey.tianjitown.paper.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Lamp permission rule: full administrators or the specified scoped permission. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AdminAccess {
    /** Empty means any administrator permission (help and confirmation commands). */
    String value() default "";

    boolean playerOnly() default false;
}
