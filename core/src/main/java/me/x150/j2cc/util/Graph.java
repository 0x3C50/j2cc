package me.x150.j2cc.util;

import lombok.Getter;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Getter
public class Graph<T extends Graph.Node> {
	final
	Set<T> nodes = new HashSet<>();
	final
	Set<Edge<T>> edges = new HashSet<>();

	public void add(T t) {
		nodes.add(t);
	}

	public void addEdge(T a, T b) {
		if (edges.stream().anyMatch(e -> e.a.equals(a) && e.b.equals(b))) return;
		edges.add(new Edge<>(a, b));
	}

	public void remove(T node) {
		nodes.remove(node);
		edges.removeIf(v -> v.a.equals(node) || v.b.equals(node));
	}

	public void writeDotFormat(PrintStream stream) {
		stream.println("digraph {");
		Map<T, Integer> idMap = new HashMap<>();
		int counter = 0;
		for (T node : nodes) {
			int id = counter++;
			idMap.put(node, id);
			stream.append("n").append(String.valueOf(id)).append("[label=\"").append(Util.escapeString(node.getContent().toCharArray())).println("\",shape=box];");
		}
		for (Edge<T> edge : edges) {
			stream.append("n").append(String.valueOf(idMap.get(edge.a))).append(" -> ").append("n").append(String.valueOf(idMap.get(edge.b))).println(";");
		}
		stream.println("}");
	}

	public interface Node {
		String getContent();
	}

	public record Edge<T>(T a, T b) {
	}
}
