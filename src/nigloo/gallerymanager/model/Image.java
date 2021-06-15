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
	transient SoftReference<javafx.scene.image.Image> fxImageCache = null;
	
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
		assert !path.isAbsolute();
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
	
	public Path getAbsolutePath()
	{
		return gallery.toAbsolutePath(path);
	}
	
	public javafx.scene.image.Image getThumbnail(boolean async)
	{
		javafx.scene.image.Image thumbnail = (thumbnailCache == null) ? null : thumbnailCache.get();
		if (thumbnail == null || thumbnail.isError())
		{
			try
			{
				String imageUrl = gallery.toAbsolutePath(path).toUri().toURL().toString();
				thumbnail = new javafx.scene.image.Image(imageUrl, 300, 300, true, true, async);
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
	
	public javafx.scene.image.Image getFXImage(boolean async)
	{
		javafx.scene.image.Image fxImage = (fxImageCache == null) ? null : fxImageCache.get();
		if (fxImage == null || fxImage.isError())
		{
			try
			{
				String imageUrl = gallery.toAbsolutePath(path).toUri().toURL().toString();
				fxImage = new javafx.scene.image.Image(imageUrl, async);
				fxImageCache = new SoftReference<javafx.scene.image.Image>(fxImage);
			}
			catch (MalformedURLException e)
			{
				throw new RuntimeException(e);
			}
		}
		
		return fxImage;
	}
	
	public void cancelLoadingFXImage()
	{
		javafx.scene.image.Image fxImage = (fxImageCache == null) ? null : fxImageCache.get();
		if (fxImage != null)
			fxImage.cancel();
	}
	
	@Override
	public String toString()
	{
		javafx.scene.image.Image img;
		
		StringBuilder sb = new StringBuilder();
		sb.append("{id=");
		sb.append(id);
		sb.append(", path=");
		sb.append(path);
		if (thumbnailCache != null && (img = thumbnailCache.get()) != null)
		{
			sb.append(", thumbnail(");
			sb.append((int) (img.getProgress() * 100));
			sb.append("%)");
		}
		if (fxImageCache != null && (img = fxImageCache.get()) != null)
		{
			sb.append(", fxImage(");
			sb.append((int) (img.getProgress() * 100));
			sb.append("%)");
		}
		sb.append("}");
		
		return sb.toString();
	}
	
	public static boolean isImage(Path file)
	{
		String filename = file.getFileName().toString();
		int posExt = filename.lastIndexOf('.');
		if (posExt == -1)
			return false;
		
		String extention = filename.substring(posExt + 1).toLowerCase();
		return extention.equals("jpg") || extention.equals("jpeg") || extention.equals("jfif")
		        || extention.equals("png") || extention.equals("gif");
	}
}
