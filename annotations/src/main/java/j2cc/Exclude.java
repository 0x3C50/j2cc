package j2cc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.TYPE, ElementType.PACKAGE})
public @interface Exclude {
	From[] value();

	enum From {
		RENAMING, OBFUSCATION, COMPILATION
	}
}
