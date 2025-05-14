package me.x150.j2cc.conf.javaconf.annots;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigValue {
	String value();

	String description() default "";

	String exampleContent() default "";

	boolean required() default false;
}
