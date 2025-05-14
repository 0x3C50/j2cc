package me.x150.j2cc.util;

import lombok.*;

@AllArgsConstructor
@Getter
@Setter
@EqualsAndHashCode
@ToString
public class Pair<A, B> {
	A a;
	B b;
}
