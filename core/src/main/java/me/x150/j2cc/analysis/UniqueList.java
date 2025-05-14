package me.x150.j2cc.analysis;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class UniqueList<T> implements List<T> {
	private final transient ArrayList<T> backingList = new ArrayList<>();

	@Override
	public int size() {
		return backingList.size();
	}

	@Override
	public boolean isEmpty() {
		return backingList.isEmpty();
	}

	@Override
	public boolean contains(Object o) {
		return backingList.contains(o);
	}

	@Override
	@NotNull
	public Iterator<T> iterator() {
		return backingList.iterator();
	}

	@Override
	public Object @NotNull [] toArray() {
		return backingList.toArray();
	}

	@Override
	public <T1> T1 @NotNull [] toArray(T1 @NotNull [] a) {
		return backingList.toArray(a);
	}

	@Override
	public boolean add(T t) {
		if (backingList.contains(t)) //noinspection Contract
			return false;
		return backingList.add(t);
	}

	@Override
	public boolean remove(Object o) {
		return backingList.remove(o);
	}

	@Override
	public boolean containsAll(@NotNull Collection<?> c) {
		return backingList.containsAll(c);
	}

	@Override
	public boolean addAll(Collection<? extends T> c) {
		boolean any = false;
		for (T t : c) {
			if (backingList.contains(t)) continue;
			backingList.add(t);
			any = true;
		}
		return any;
	}

	@Override
	public boolean addAll(int index, @NotNull Collection<? extends T> c) {
		return false;
	}

	@Override
	public boolean removeAll(@NotNull Collection<?> c) {
		return backingList.removeAll(c);
	}

	@Override
	public boolean retainAll(@NotNull Collection<?> c) {
		return backingList.retainAll(c);
	}

	@Override
	public void clear() {
		backingList.clear();
	}

	@Override
	public T get(int index) {
		return backingList.get(index);
	}

	@Override
	public T set(int index, T element) {
		if (backingList.contains(element)) throw new IllegalArgumentException("Element already present");
		return backingList.set(index, element);
	}

	@Override
	public void add(int index, T element) {
		if (backingList.contains(element)) throw new IllegalArgumentException("Element already present");
		backingList.add(index, element);
	}

	@Override
	public T remove(int index) {
		return backingList.remove(index);
	}

	@Override
	public int indexOf(Object o) {
		return backingList.indexOf(o);
	}

	@Override
	public int lastIndexOf(Object o) {
		return backingList.lastIndexOf(o);
	}

	@Override
	public @NotNull ListIterator<T> listIterator() {
		return backingList.listIterator();
	}

	@Override
	public @NotNull ListIterator<T> listIterator(int index) {
		return backingList.listIterator(index);
	}

	@Override
	@Contract("_, _ -> fail")
	public @NotNull List<T> subList(int fromIndex, int toIndex) {
		throw new UnsupportedOperationException();
	}
}
