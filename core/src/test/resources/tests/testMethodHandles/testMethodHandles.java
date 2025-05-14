import j2cc.Nativeify;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

public class Test {
	String someField = "Cringe";

	@Nativeify
	public static void main(String[] args) throws Throwable {
		MethodHandle format = MethodHandles.lookup().findStatic(String.class, "format", MethodType.methodType(String.class, String.class, Object[].class));
		System.out.println(format.invoke("Hello %s! I am %d years old", "World", 1234));
		Object[] o = new Object[]{"Hello %s! I am %d years old", "World", 1234};
		System.out.println(format.invokeWithArguments(o));
		System.out.println(format.asFixedArity().invoke("Hello %s! I am %d years old", new Object[]{"World", 1234}));
		System.out.println(((String) format.invokeWithArguments("Hello %s", o)).split("@")[0]);

		Test inst = new Test();
		System.out.println(inst.someField);
		VarHandle varHandle = MethodHandles.lookup().findVarHandle(Test.class, "someField", String.class);
		System.out.println(varHandle.get(inst));
		varHandle.set(inst, "Yep");
		System.out.println(inst.someField);
		System.out.println(varHandle.get(inst));

	}
}