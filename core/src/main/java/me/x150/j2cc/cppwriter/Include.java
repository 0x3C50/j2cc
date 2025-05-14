package me.x150.j2cc.cppwriter;

public class Include implements Printable {

	private final String headerName;
	private final boolean local;

	public Include(String headerName, boolean local) {
		this.headerName = headerName;
		this.local = local;
	}

	@Override
	public String stringify() {
		return "#include " + (local ? "\"" : "<") + headerName + (local ? "\"" : ">");
	}
}
