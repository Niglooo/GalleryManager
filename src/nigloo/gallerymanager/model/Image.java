package nigloo.gallerymanager.model;

import java.nio.file.Path;

public class Image {

	private Path path;
	
	public Image() {
	}
	
	public Image(Path path) {
		this.path = path;
	}

	public Path getPath() {
		return path;
	}
}
