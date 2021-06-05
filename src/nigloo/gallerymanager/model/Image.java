package nigloo.gallerymanager.model;

import java.lang.ref.SoftReference;
import java.net.MalformedURLException;
import java.nio.file.Path;

import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class Image
{
	long id;
	private Path path;
	
	transient SoftReference<javafx.scene.image.Image> thumbnailCache = null;
	
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
	
	public javafx.scene.image.Image getThumbnail()
	{
		javafx.scene.image.Image thumbnail = (thumbnailCache == null) ? null : thumbnailCache.get();
		if (thumbnail == null || thumbnail.isError())
		{
			try
			{
				String imageUrl = gallery.toAbsolutePath(path).toUri().toURL().toString();
				thumbnail = new javafx.scene.image.Image(imageUrl, 300, 300, true, true, true);
				thumbnailCache = new SoftReference<javafx.scene.image.Image>(thumbnail);
			}
			catch (MalformedURLException e)
			{
				throw new RuntimeException(e);
			}
		}
		
		return thumbnail;
	}
	
	public void cancelLoadingThumbnail()
	{
		javafx.scene.image.Image thumbnail = (thumbnailCache == null) ? null : thumbnailCache.get();
		if (thumbnail != null)
			thumbnail.cancel();
	}
}
