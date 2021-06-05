package nigloo.gallerymanager.model;

import java.nio.file.Path;

import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class Image
{
	long id;
	private Path path;
	
	@Inject
	private transient Gallery gallery;
	
	private Image()
	{
		Injector.init(this);
	}
	
	public Image(Path path)
	{
		this();
		this.id = -1;
		this.path = path;
	}
	
	public boolean isSaved()
	{
		return this.id > 0;
	}

	public long getId()
	{
		return id;
	}
	
	public Path getPath() {
		return path;
	}
}
