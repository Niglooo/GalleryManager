package nigloo.gallerymanager.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Gallery {

	private transient Path rootFolder;
	
	private List<Artist> artists = new ArrayList<>();
	private List<Image> images = new ArrayList<>();
	//Tags are free
	
	public Path getRootFolder() {
		return rootFolder;
	}

	public void setRootFolder(Path rootFolder) {
		if (this.rootFolder != null)
			throw new IllegalStateException("rootFolder already set ("+rootFolder+")");
		
		this.rootFolder = rootFolder;
	}
	
	public Path toRelativePath(Path path)
	{
		return path.isAbsolute() ? rootFolder.relativize(path) : path;
	}
	
	public Path toAbsolutePath(Path path)
	{
		return path.isAbsolute() ? path : rootFolder.resolve(path);
	}
	
	public Image findImage(Path path)
	{
		return images.stream().filter(image -> image.getPath().equals(path)).findAny().orElse(null);
	}
	
	public List<Image> getImages()
	{
		return images;
	}
}
